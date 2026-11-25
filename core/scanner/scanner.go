// Package scanner walks library folders, parses filenames and keeps the database index in sync.
package scanner

import (
	"context"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/lucasbittar/lookee/core/db"
)

// Progress is reported while a root is being synced.
type Progress struct {
	Root      string `json:"root"`
	FilesSeen int    `json:"filesSeen"`
}

// Result summarises one root sync.
type Result struct {
	RootID      int64  `json:"rootId"`
	Root        string `json:"root"`
	Unavailable bool   `json:"unavailable,omitempty"` // folder not mounted; index left untouched
	Seen        int    `json:"seen"`
	Added       int    `json:"added"`
	Updated     int    `json:"updated"`
	Removed     int    `json:"removed"`
}

// skipDirs are never descended into. "Android" holds app data (including lookee's own torrent cache).
var skipDirs = map[string]bool{
	"android": true, "lost.dir": true, "system volume information": true, "$recycle.bin": true,
	"@eadir": true, ".trashes": true, ".spotlight-v100": true, ".fseventsd": true,
	"extras": true, "featurettes": true, "behind the scenes": true, "deleted scenes": true, "trailers": true,
}

const sampleMaxSize = 300 << 20 // files named "sample" below this size are skipped

// Sync brings the index for one root up to date with the filesystem.
func Sync(ctx context.Context, store *db.Store, root db.Root, progress func(Progress)) (Result, error) {
	res := Result{RootID: root.ID, Root: root.Path}
	if !root.Category.Indexed() {
		return res, nil
	}

	if st, err := os.Stat(root.Path); err != nil || !st.IsDir() {
		res.Unavailable = true
		return res, store.MarkRootScanned(ctx, root.ID, false)
	}

	existing, err := store.FileStats(ctx, root.ID)
	if err != nil {
		return res, err
	}

	// Collect playable items: single video files, or whole disc rips aggregated
	// under one key path (size = sum of parts, mtime = newest part).
	type item struct {
		kind        string
		size, mtime int64
	}
	items := map[string]*item{}
	type entry struct {
		path, name  string
		size, mtime int64
	}
	var entries []entry
	flatBluray := map[string]bool{}

	walkErr := filepath.WalkDir(root.Path, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			if path == root.Path {
				return err
			}
			return nil // unreadable subfolder: skip it, keep going
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		name := d.Name()
		if d.IsDir() {
			if path != root.Path && (strings.HasPrefix(name, ".") || skipDirs[strings.ToLower(name)]) {
				return filepath.SkipDir
			}
			return nil
		}
		if strings.HasPrefix(name, ".") {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		if IsBlurayMarker(name) {
			flatBluray[filepath.Dir(path)] = true
		}
		entries = append(entries, entry{path, name, info.Size(), info.ModTime().Unix()})
		return nil
	})
	if walkErr != nil {
		return res, fmt.Errorf("walk %s: %w", root.Path, walkErr)
	}

	for _, en := range entries {
		key, kind, isDisc := discItem(root.Path, en.path, flatBluray)
		if !isDisc {
			if !IsVideo(en.name) {
				continue
			}
			key, kind = en.path, KindFile
		}
		if kind == KindFile && en.size < sampleMaxSize && strings.Contains(strings.ToLower(en.name), "sample") {
			continue
		}
		it := items[key]
		if it == nil {
			it = &item{kind: kind}
			items[key] = it
			res.Seen++
			if progress != nil && res.Seen%50 == 0 {
				progress(Progress{Root: root.Path, FilesSeen: res.Seen})
			}
		}
		it.size += en.size
		it.mtime = max(it.mtime, en.mtime)
	}

	type found struct {
		path, kind  string
		size, mtime int64
	}
	var added []found
	type changed struct{ id, size, mtime int64 }
	var updated []changed
	for path, it := range items {
		if old, ok := existing[path]; ok {
			delete(existing, path)
			if old.Size != it.size || old.MTime != it.mtime {
				updated = append(updated, changed{old.ID, it.size, it.mtime})
			}
			continue
		}
		added = append(added, found{path, it.kind, it.size, it.mtime})
	}
	// Deterministic insert order (map iteration is random).
	sort.Slice(added, func(i, j int) bool { return added[i].path < added[j].path })
	if progress != nil {
		progress(Progress{Root: root.Path, FilesSeen: res.Seen})
	}

	err = store.Tx(ctx, func(tx *db.Tx) error {
		for _, f := range added {
			id := Identify(root.Path, f.path, root.Category)
			titleID, err := tx.FindOrCreateTitle(ctx, root.Category, id.GroupKey, id.Year, id.Title)
			if err != nil {
				return err
			}
			if _, err := tx.InsertFile(ctx, db.File{
				RootID: root.ID, Path: f.path, Kind: f.kind, Size: f.size, MTime: f.mtime, TitleID: titleID,
				Season: id.Season, Episode: id.Episode, EpisodeEnd: id.EpisodeEnd,
			}); err != nil {
				return err
			}
		}
		for _, u := range updated {
			if err := tx.UpdateFileStat(ctx, u.id, u.size, u.mtime); err != nil {
				return err
			}
		}
		for _, gone := range existing {
			if err := tx.DeleteFile(ctx, gone.ID); err != nil {
				return err
			}
		}
		_, err := tx.DeleteOrphanTitles(ctx)
		return err
	})
	if err != nil {
		return res, err
	}
	res.Added, res.Updated, res.Removed = len(added), len(updated), len(existing)
	return res, store.MarkRootScanned(ctx, root.ID, true)
}

// Identity is how a file is grouped into a title before any TMDB lookup.
type Identity struct {
	Parsed
	GroupKey string
}

var (
	reLeadingNumber  = regexp.MustCompile(`^(\d{1,3})(?:\s|[-._]|$)`)
	reTrailingSeason = regexp.MustCompile(`(?i)\s+(?:S\d{1,2}|Season\s?\d{1,2}|Temporada\s?\d{1,2})(?:\s?-\s?S?\d{1,2})?$`)
)

// Identify works out title, year, season and episode for a file, using its parent
// folders when the filename alone is not enough (e.g. "Show/Season 1/01 - Pilot.mkv").
func Identify(rootPath, filePath string, cat db.Category) Identity {
	base := filepath.Base(filePath)
	p := Parse(base)
	if isDiscFolderName(base) {
		p = Parsed{} // "VIDEO_TS"/"BDMV": the title comes from the folders above
	}

	// Folders between the root and the file, nearest first.
	var dirs []string
	for dir := filepath.Dir(filePath); dir != rootPath && strings.HasPrefix(dir, rootPath) && dir != filepath.Dir(dir); dir = filepath.Dir(dir) {
		dirs = append(dirs, filepath.Base(dir))
	}

	switch cat {
	case db.TVShows:
		// The show is the top-level folder under the root ("Friends/The Reunion/x.mkv" is
		// still Friends); the nearest season-like folder gives the season number.
		folderSeason := 0
		for _, d := range dirs {
			if n, ok := ParseSeasonFolder(d); ok {
				folderSeason = n
				break
			}
		}
		var showDir string
		if len(dirs) > 0 {
			if _, isSeason := ParseSeasonFolder(dirs[len(dirs)-1]); !isSeason {
				showDir = dirs[len(dirs)-1]
			}
		}
		if p.Season == 0 && folderSeason > 0 {
			p.Season = folderSeason
		}
		if p.Episode == 0 && p.Season > 0 {
			stem := strings.TrimSuffix(base, filepath.Ext(base))
			if m := reLeadingNumber.FindStringSubmatch(stem); m != nil {
				p.Episode = atoi(m[1])
			}
		}
		// A show folder ("Show/Season 1/...", "Show.S01.1080p/...") names the show more
		// reliably than episode filenames, which are often abbreviated or episode titles.
		if showDir != "" {
			sp := Parse(showDir)
			if t := reTrailingSeason.ReplaceAllString(sp.Title, ""); t != "" {
				p.Title, p.Year = t, sp.Year
			}
		}
	default:
		// Curated "Title (Year)" folders name movies better than release filenames.
		// Use the top-most folder that carries a year; otherwise keep the filename.
		named := false
		for i := len(dirs) - 1; i >= 0; i-- {
			if dp := Parse(dirs[i]); dp.Year > 0 && dp.Title != "" {
				p.Title, p.Year = dp.Title, dp.Year
				named = true
				break
			}
		}
		// No year anywhere: the top-level folder still groups multi-part items
		// ("Marcus King - Four Of a Kind/…July 13th.mp4", "…July 20th.mp4").
		if !named && p.Year == 0 && len(dirs) > 0 {
			if dp := Parse(dirs[len(dirs)-1]); dp.Title != "" {
				p.Title = dp.Title
			}
		}
	}

	if p.Title == "" {
		p.Title = cleanTitle(strings.TrimSuffix(base, filepath.Ext(base)))
	}
	key := NormalizeKey(p.Title)
	if key == "" {
		key = strings.ToLower(base)
	}
	return Identity{Parsed: p, GroupKey: key}
}
