// Command lookee-cli drives the lookee engine from a terminal so the Go core can be
// developed and verified on a laptop, without an Android device.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"text/tabwriter"

	"github.com/lucasbittar/lookee/core/db"
	"github.com/lucasbittar/lookee/core/engine"
)

const usage = `lookee-cli — drive the lookee Go core from the terminal

Usage:
  lookee-cli [--data DIR] <command> [args]

Library:
  roots                               list library folders
  add <dir> <category>                add a folder (MOVIES, TV_SHOWS, CONCERTS, TUTORIALS)
  remove <root-id>                    remove a folder and its index
  scan [dir --category CAT]           sync all folders (optionally add one first) and match on TMDB
  rematch                             retry titles that found no match
  list [category]                     list titles
  show <title-id>                     show a title with cast, episodes and files
  fix <title-id> [query]              list TMDB candidates for a title
  fix <title-id> --tmdb <id> [--kind movie|tv]   lock a title to a TMDB entry
  browse <dir>                        list a folder like the Tutorials browser

Settings:
  settings                            show settings (keys are masked)
  settings get <key>                  print a setting (secrets masked; --reveal to show)
  settings set <key> <value>          keys: tmdb_api_key, tmdb_language, opensubtitles_api_key, subtitle_languages

Global flags:
  --data DIR   where the database and cache live (default ~/.lookee)
  --json       print the JSON DTOs the TV app receives
`

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run() error {
	home, _ := os.UserHomeDir()
	fs := flag.NewFlagSet("lookee-cli", flag.ExitOnError)
	dataDir := fs.String("data", filepath.Join(home, ".lookee"), "data directory")
	asJSON := fs.Bool("json", false, "print JSON")
	fs.Usage = func() { fmt.Fprint(os.Stderr, usage) }
	fs.Parse(os.Args[1:])
	args := fs.Args()
	if len(args) == 0 {
		fs.Usage()
		return nil
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	e, err := engine.Open(engine.Options{
		DBPath:   filepath.Join(*dataDir, "lookee.db"),
		CacheDir: filepath.Join(*dataDir, "cache"),
	})
	if err != nil {
		return err
	}
	defer func() {
		if cerr := e.Close(); cerr != nil {
			fmt.Fprintln(os.Stderr, "warning: closing database:", cerr)
		}
	}()

	c := &cli{e: e, ctx: ctx, json: *asJSON}
	cmd, rest := args[0], args[1:]
	switch cmd {
	case "roots":
		return c.roots()
	case "add":
		return c.add(rest)
	case "remove":
		return c.remove(rest)
	case "scan":
		return c.scan(rest)
	case "rematch":
		return c.rematch()
	case "list":
		return c.list(rest)
	case "show":
		return c.show(rest)
	case "fix":
		return c.fix(rest)
	case "browse":
		return c.browse(rest)
	case "settings":
		return c.settings(rest)
	case "help", "-h", "--help":
		fs.Usage()
		return nil
	}
	return fmt.Errorf("unknown command %q (try --help)", cmd)
}

type cli struct {
	e    *engine.Engine
	ctx  context.Context
	json bool
}

func (c *cli) printJSON(v any) error {
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	return enc.Encode(v)
}

func table() *tabwriter.Writer { return tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0) }

func parseID(s string) (int64, error) {
	id, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("invalid id %q", s)
	}
	return id, nil
}

func parseCategory(s string) (db.Category, error) {
	cat := db.Category(strings.ToUpper(s))
	if !cat.Valid() {
		return "", fmt.Errorf("invalid category %q: use MOVIES, TV_SHOWS, CONCERTS or TUTORIALS", s)
	}
	return cat, nil
}

func (c *cli) roots() error {
	roots, err := c.e.Roots(c.ctx)
	if err != nil {
		return err
	}
	if c.json {
		return c.printJSON(roots)
	}
	w := table()
	fmt.Fprintln(w, "ID\tCATEGORY\tAVAILABLE\tPATH")
	for _, r := range roots {
		fmt.Fprintf(w, "%d\t%s\t%v\t%s\n", r.ID, r.Category, r.Available, r.Path)
	}
	return w.Flush()
}

func (c *cli) add(args []string) error {
	if len(args) != 2 {
		return errors.New("usage: add <dir> <category>")
	}
	cat, err := parseCategory(args[1])
	if err != nil {
		return err
	}
	abs, _ := filepath.Abs(args[0])
	r, err := c.e.AddRoot(c.ctx, abs, cat)
	if err != nil {
		return err
	}
	fmt.Printf("added #%d %s (%s)\n", r.ID, r.Path, r.Category)
	return nil
}

func (c *cli) remove(args []string) error {
	if len(args) != 1 {
		return errors.New("usage: remove <root-id>")
	}
	id, err := parseID(args[0])
	if err != nil {
		return err
	}
	return c.e.RemoveRoot(c.ctx, id)
}

func (c *cli) scan(args []string) error {
	fs := flag.NewFlagSet("scan", flag.ExitOnError)
	category := fs.String("category", "", "category when adding a folder")
	// Accept "scan <dir> --category X" as well as "scan --category X <dir>".
	var dir string
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		dir, args = args[0], args[1:]
	}
	fs.Parse(args)
	if dir == "" && fs.NArg() > 0 {
		dir = fs.Arg(0)
	}
	if dir != "" {
		if *category == "" {
			return errors.New("scan <dir> needs --category")
		}
		if err := c.add([]string{dir, *category}); err != nil {
			return err
		}
	}

	tty := isTerminal(os.Stderr)
	rep, err := c.e.Scan(c.ctx, func(ev engine.ScanEvent) {
		if c.json || !tty {
			return
		}
		switch ev.Phase {
		case "scanning":
			fmt.Fprintf(os.Stderr, "\r\033[Kscanning %s … %d files", filepath.Base(ev.Root), ev.FilesSeen)
		case "matching":
			fmt.Fprintf(os.Stderr, "\r\033[Kmatching %d/%d  %s", ev.Matched+1, ev.ToMatch, ev.Title)
		case "done":
			fmt.Fprint(os.Stderr, "\r\033[K")
		}
	})
	if err != nil {
		return err
	}
	if c.json {
		return c.printJSON(rep)
	}
	for _, r := range rep.Roots {
		if r.Unavailable {
			fmt.Printf("%s: not available (drive unplugged?) — index kept\n", r.Root)
			continue
		}
		fmt.Printf("%s: %d files, +%d new, %d changed, -%d removed\n", r.Root, r.Seen, r.Added, r.Updated, r.Removed)
	}
	fmt.Printf("TMDB: %d matched, %d not found\n", rep.Matched, rep.Unmatched)
	if rep.NeedsAPIKey {
		fmt.Println("Titles are waiting for metadata: run `lookee-cli settings set tmdb_api_key <key>` then `lookee-cli scan`.")
	}
	if rep.MatchError != "" {
		fmt.Println("Matching stopped early:", rep.MatchError)
	}
	return nil
}

func (c *cli) rematch() error {
	res, err := c.e.Rematch(c.ctx, nil)
	if err != nil {
		return err
	}
	if c.json {
		return c.printJSON(res)
	}
	fmt.Printf("TMDB: %d matched, %d still not found\n", res.Matched, res.Unmatched)
	return nil
}

func (c *cli) list(args []string) error {
	var cat db.Category
	if len(args) > 0 {
		var err error
		if cat, err = parseCategory(args[0]); err != nil {
			return err
		}
	}
	titles, err := c.e.ListTitles(c.ctx, cat, 0, 0)
	if err != nil {
		return err
	}
	if c.json {
		return c.printJSON(titles)
	}
	w := table()
	fmt.Fprintln(w, "ID\tCATEGORY\tSTATUS\tYEAR\tTITLE\tPOSTER")
	for _, t := range titles {
		year := ""
		if t.Year > 0 {
			year = strconv.Itoa(t.Year)
		}
		fmt.Fprintf(w, "%d\t%s\t%s\t%s\t%s\t%s\n", t.ID, t.Category, t.Status, year, t.Name, yesNo(t.PosterURL != ""))
	}
	return w.Flush()
}

func yesNo(b bool) string {
	if b {
		return "yes"
	}
	return "-"
}

func (c *cli) show(args []string) error {
	if len(args) != 1 {
		return errors.New("usage: show <title-id>")
	}
	id, err := parseID(args[0])
	if err != nil {
		return err
	}
	d, err := c.e.Details(c.ctx, id)
	if err != nil {
		return err
	}
	if c.json {
		return c.printJSON(d)
	}
	fmt.Printf("%s", d.Name)
	if d.Year > 0 {
		fmt.Printf(" (%d)", d.Year)
	}
	fmt.Printf("  [%s, %s", d.Category, d.Status)
	if d.TMDBID > 0 {
		fmt.Printf(", tmdb %s/%d", d.Kind, d.TMDBID)
	}
	fmt.Println("]")
	if d.Tagline != "" {
		fmt.Printf("“%s”\n", d.Tagline)
	}
	if len(d.Genres) > 0 {
		fmt.Printf("Genres: %s", strings.Join(d.Genres, ", "))
		if d.Runtime > 0 {
			fmt.Printf(" · %d min", d.Runtime)
		}
		if d.Rating > 0 {
			fmt.Printf(" · ★ %.1f", d.Rating)
		}
		fmt.Println()
	}
	if d.Overview != "" {
		fmt.Printf("\n%s\n", d.Overview)
	}
	if d.PosterURL != "" {
		fmt.Printf("\nPoster:   %s\nBackdrop: %s\n", d.PosterURL, d.BackdropURL)
	}
	if len(d.Cast) > 0 {
		fmt.Println("\nCast:")
		for _, cm := range d.Cast {
			fmt.Printf("  %-24s %s\n", cm.Name, cm.Character)
		}
	}
	if len(d.Episodes) > 0 {
		have := map[[2]int]bool{}
		for _, f := range d.Files {
			for ep := f.Episode; ep <= max(f.Episode, f.EpisodeEnd); ep++ {
				have[[2]int{f.Season, ep}] = true
			}
		}
		fmt.Println("\nEpisodes:")
		for _, ep := range d.Episodes {
			mark := " "
			if have[[2]int{ep.Season, ep.Episode}] {
				mark = "●"
			}
			fmt.Printf("  %s S%02dE%02d  %s\n", mark, ep.Season, ep.Episode, ep.Name)
		}
	}
	fmt.Println("\nFiles:")
	for _, f := range d.Files {
		kind := ""
		if f.Kind != "" && f.Kind != "file" {
			kind = " [" + f.Kind + "]"
		}
		fmt.Printf("  #%d%s  %s  (%s)\n", f.ID, kind, f.Path, humanSize(f.Size))
	}
	return nil
}

func (c *cli) fix(args []string) error {
	if len(args) < 1 {
		return errors.New("usage: fix <title-id> [query] | fix <title-id> --tmdb <id> [--kind movie|tv]")
	}
	id, err := parseID(args[0])
	if err != nil {
		return err
	}
	fs := flag.NewFlagSet("fix", flag.ExitOnError)
	tmdbID := fs.Int64("tmdb", 0, "TMDB id to lock to")
	kind := fs.String("kind", "", "movie or tv")
	var query []string
	rest := args[1:]
	for len(rest) > 0 && !strings.HasPrefix(rest[0], "-") {
		query, rest = append(query, rest[0]), rest[1:]
	}
	fs.Parse(rest)

	if *tmdbID > 0 {
		k := *kind
		if k == "" {
			t, err := c.e.Details(c.ctx, id)
			if err != nil {
				return err
			}
			k = map[db.Category]string{db.TVShows: db.KindTV}[t.Category]
			if k == "" {
				k = db.KindMovie
			}
		}
		newID, err := c.e.Rebind(c.ctx, id, k, *tmdbID)
		if err != nil {
			return err
		}
		fmt.Printf("locked title #%d to tmdb %s/%d\n", newID, k, *tmdbID)
		return c.show([]string{strconv.FormatInt(newID, 10)})
	}

	cands, err := c.e.SearchCandidates(c.ctx, id, strings.Join(query, " "), *kind)
	if err != nil {
		return err
	}
	if c.json {
		return c.printJSON(cands)
	}
	if len(cands) == 0 {
		fmt.Println("no results; try a different query")
		return nil
	}
	w := table()
	fmt.Fprintln(w, "TMDB ID\tKIND\tYEAR\tTITLE")
	for _, r := range cands {
		fmt.Fprintf(w, "%d\t%s\t%d\t%s\n", r.ID, r.Kind, r.Year, r.Title)
	}
	w.Flush()
	fmt.Printf("\nlock one with: lookee-cli fix %d --tmdb <TMDB ID> --kind <KIND>\n", id)
	return nil
}

func (c *cli) browse(args []string) error {
	if len(args) != 1 {
		return errors.New("usage: browse <dir>")
	}
	entries, err := c.e.Browse(args[0])
	if err != nil {
		return err
	}
	if c.json {
		return c.printJSON(entries)
	}
	for _, en := range entries {
		switch {
		case en.IsDir:
			fmt.Printf("  📁 %s/\n", en.Name)
		case en.Subtitle:
			fmt.Printf("  ▶  %s  (%s, subtitles)\n", en.Name, humanSize(en.Size))
		default:
			fmt.Printf("  ▶  %s  (%s)\n", en.Name, humanSize(en.Size))
		}
	}
	return nil
}

func (c *cli) settings(args []string) error {
	if len(args) == 0 {
		v, err := c.e.Settings(c.ctx)
		if err != nil {
			return err
		}
		return c.printJSON(v)
	}
	switch args[0] {
	case "get":
		fs := flag.NewFlagSet("get", flag.ExitOnError)
		reveal := fs.Bool("reveal", false, "show secret values")
		if len(args) < 2 {
			return errors.New("usage: settings get <key> [--reveal]")
		}
		fs.Parse(args[2:])
		v, err := c.e.GetSetting(c.ctx, args[1], *reveal)
		if err != nil {
			return err
		}
		fmt.Println(v)
		return nil
	case "set":
		if len(args) != 3 {
			return errors.New("usage: settings set <key> <value>")
		}
		return c.e.SetSetting(c.ctx, args[1], args[2])
	}
	return fmt.Errorf("unknown settings command %q", args[0])
}

func isTerminal(f *os.File) bool {
	st, err := f.Stat()
	return err == nil && st.Mode()&os.ModeCharDevice != 0
}

func humanSize(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), "KMGTPE"[exp])
}
