package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/lucasbittar/lookee/core/engine"
	"github.com/lucasbittar/lookee/core/torrent"
)

// splitArgs separates leading positional words from flags.
func splitArgs(args []string) (pos, flags []string) {
	for i, a := range args {
		if strings.HasPrefix(a, "-") {
			return pos, args[i:]
		}
		pos = append(pos, a)
	}
	return pos, nil
}

func (c *cli) remote() error {
	closed := make(chan bool, 1)
	setup, err := c.e.StartRemoteSetup("", func(saved bool) { closed <- saved })
	if err != nil {
		return err
	}
	fmt.Printf("Open on your phone:  %s\nPIN:                 %s\n(closes after saving, after %d minutes, or on Ctrl-C)\n",
		setup.URL, setup.PIN, setup.ExpiresIn/60)
	select {
	case saved := <-closed:
		if saved {
			fmt.Println("Settings saved.")
		} else {
			fmt.Println("Setup closed without saving.")
		}
	case <-c.ctx.Done():
		c.e.StopRemoteSetup()
	}
	return nil
}

func (c *cli) logs(args []string) error {
	n := 50
	if len(args) > 0 {
		var err error
		if n, err = strconv.Atoi(args[0]); err != nil {
			return fmt.Errorf("invalid line count %q", args[0])
		}
	}
	for _, l := range c.e.LogTail(n) {
		fmt.Println(l)
	}
	return nil
}

func (c *cli) torrents(args []string) error {
	pos, rest := splitArgs(args)
	fs := flag.NewFlagSet("torrents", flag.ExitOnError)
	kind := fs.String("kind", "movie", "movie or tv")
	tmdbID := fs.Int64("tmdb", 0, "TMDB id")
	season := fs.Int("season", 0, "season")
	episode := fs.Int("episode", 0, "episode")
	fs.Parse(rest)

	var res engine.TorrentSearch
	var err error
	switch {
	case *tmdbID > 0:
		res, err = c.e.SearchTorrents(c.ctx, *kind, *tmdbID, *season, *episode)
	case len(pos) > 0:
		res, err = c.e.SearchTorrentsText(c.ctx, *kind, strings.Join(pos, " "))
	default:
		return errors.New("usage: torrents <text> | torrents --tmdb ID --kind movie|tv")
	}
	if err != nil {
		return err
	}
	if c.json {
		return c.printJSON(res)
	}
	for _, w := range res.Warnings {
		fmt.Fprintln(os.Stderr, "warning:", w)
	}
	w := table()
	fmt.Fprintln(w, "#\tSEEDS\tQUALITY\tSIZE\tSOURCE\tTITLE")
	for i, r := range res.Results {
		fmt.Fprintf(w, "%d\t%d\t%s\t%s\t%s\t%s\n", i+1, r.Seeders, r.Quality, humanSize(r.Size), r.Provider, truncate(r.Title, 70))
	}
	w.Flush()
	if len(res.Results) > 0 {
		fmt.Printf("\nstream one with: lookee-cli stream '<magnet>'  (use --json to see magnets)\n")
	}
	return nil
}

func truncate(s string, n int) string {
	if r := []rune(s); len(r) > n {
		return string(r[:n-1]) + "…"
	}
	return s
}

func (c *cli) stream(args []string) error {
	pos, rest := splitArgs(args)
	fs := flag.NewFlagSet("stream", flag.ExitOnError)
	fileIdx := fs.Int("file", -1, "file index inside the torrent (default: largest video)")
	consume := fs.Bool("consume", false, "read the stream sequentially like a player")
	seconds := fs.Int("seconds", 0, "stop after N seconds")
	fs.Parse(rest)
	if len(pos) != 1 {
		return errors.New("usage: stream <magnet|file.torrent> [--file N] [--consume] [--seconds N]")
	}

	tty := isTerminal(os.Stdout)
	st, err := c.e.StartStream(c.ctx, pos[0], *fileIdx, func(s torrent.Stats) {
		if c.json {
			return
		}
		line := fmt.Sprintf("%-9s %5.1f%% buffered  %s/%s  %s/s  %d peers (%d seeds)",
			s.State, s.BufferPercent, humanSize(s.Downloaded), humanSize(s.Size), humanSize(s.SpeedBps), s.Peers, s.Seeders)
		if tty {
			fmt.Printf("\r\033[K%s", line)
		} else {
			fmt.Println(line)
		}
	})
	if err != nil {
		return err
	}
	fmt.Printf("%s (%s)\nstream URL: %s\nCtrl-C to stop; the downloaded pieces are deleted on exit.\n", st.FileName, humanSize(st.Size), st.URL)

	done := make(chan error, 1)
	if *consume {
		go func() { done <- consumeStream(c, st.URL) }()
	}
	var timeout <-chan time.Time
	if *seconds > 0 {
		timeout = time.After(time.Duration(*seconds) * time.Second)
	}
	select {
	case <-c.ctx.Done():
	case <-timeout:
	case err := <-done:
		if err != nil {
			fmt.Fprintln(os.Stderr, "\nconsume:", err)
		}
	}
	final := c.e.StreamStats()
	c.e.StopStream()
	fmt.Printf("\nstopped after %ds, downloaded %s; cache removed.\n", final.ElapsedSeconds, humanSize(final.Downloaded))
	return nil
}

// consumeStream reads the stream at roughly 1.2 MB/s (a 10 Mbit/s video) to mimic playback.
func consumeStream(c *cli, url string) error {
	req, err := http.NewRequestWithContext(c.ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	buf := make([]byte, 128<<10)
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	for range tick.C {
		if _, err := io.ReadFull(resp.Body, buf); err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
				return nil
			}
			return err
		}
	}
	return nil
}

func (c *cli) subs(args []string) error {
	if len(args) == 0 {
		return errors.New("usage: subs sidecars|search|download|shift …")
	}
	switch args[0] {
	case "sidecars":
		if len(args) != 2 {
			return errors.New("usage: subs sidecars <video>")
		}
		s, err := c.e.Sidecars(args[1])
		if err != nil {
			return err
		}
		if c.json {
			return c.printJSON(s)
		}
		for _, x := range s {
			fmt.Printf("%-6s %-4s %s\n", x.Language, x.Format, x.Path)
		}
		return nil
	case "search":
		fs := flag.NewFlagSet("subs search", flag.ExitOnError)
		var req engine.SubtitleRequest
		fs.StringVar(&req.VideoPath, "video", "", "local video file (enables hash matching)")
		fs.Int64Var(&req.TitleID, "title", 0, "library title id")
		fs.Int64Var(&req.TMDBID, "tmdb", 0, "TMDB id (show id for episodes)")
		fs.StringVar(&req.Kind, "kind", "", "movie or tv")
		fs.IntVar(&req.Season, "season", 0, "season")
		fs.IntVar(&req.Episode, "episode", 0, "episode")
		fs.StringVar(&req.Query, "query", "", "free text")
		fs.Parse(args[1:])
		res, err := c.e.SearchSubtitles(c.ctx, req)
		if err != nil {
			return err
		}
		if c.json {
			return c.printJSON(res)
		}
		w := table()
		fmt.Fprintln(w, "FILE ID\tLANG\tSYNC\tDOWNLOADS\tRELEASE")
		for _, r := range res {
			sync := ""
			if r.HashMatch {
				sync = "exact"
			}
			fmt.Fprintf(w, "%d\t%s\t%s\t%d\t%s\n", r.FileID, r.Language, sync, r.Downloads, truncate(r.Release, 60))
		}
		return w.Flush()
	case "download":
		if len(args) != 2 {
			return errors.New("usage: subs download <file-id>")
		}
		id, err := strconv.ParseInt(args[1], 10, 64)
		if err != nil {
			return fmt.Errorf("invalid file id %q", args[1])
		}
		path, err := c.e.DownloadSubtitle(c.ctx, id)
		if err != nil {
			return err
		}
		fmt.Println(path)
		return nil
	case "shift":
		if len(args) != 3 {
			return errors.New("usage: subs shift <file> <ms>")
		}
		ms, err := strconv.ParseInt(args[2], 10, 64)
		if err != nil {
			return fmt.Errorf("invalid offset %q", args[2])
		}
		path, err := c.e.PrepareSubtitle(args[1], ms)
		if err != nil {
			return err
		}
		fmt.Println(path)
		return nil
	}
	return fmt.Errorf("unknown subs command %q", args[0])
}
