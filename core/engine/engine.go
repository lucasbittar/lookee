// Package engine is lookee's application layer. It ties the database, scanner and
// TMDB client together and is the single API used by both lookee-cli and the
// gomobile bridge (lookeecore.go), so the CLI exercises exactly what the TV runs.
package engine

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/lucasbittar/lookee/core/db"
	"github.com/lucasbittar/lookee/core/logs"
	"github.com/lucasbittar/lookee/core/loopback"
	"github.com/lucasbittar/lookee/core/metadata"
	"github.com/lucasbittar/lookee/core/remote"
	"github.com/lucasbittar/lookee/core/scanner"
	"github.com/lucasbittar/lookee/core/torrent"
)

// Setting keys stored in the SQLite settings table.
const (
	KeyTMDBAPIKey          = "tmdb_api_key"
	KeyTMDBLanguage        = "tmdb_language"
	KeyOpenSubtitlesAPIKey = "opensubtitles_api_key"
	KeyOpenSubtitlesUser   = "opensubtitles_username"
	KeyOpenSubtitlesPass   = "opensubtitles_password"
	KeySubtitleLanguages   = "subtitle_languages"
	KeyYTSURL              = "yts_url"
	KeyEZTVURL             = "eztv_url"
	KeyTorznabURLs         = "torznab_urls"   // one Torznab API URL (with apikey) per line
	KeyStremioAddons       = "stremio_addons" // one add-on manifest URL per line
	KeySearchPages         = "search_pages"   // torrent sites' search addresses with {query}, one per line
)

// secretKeys are never returned to a UI; only whether they are set. Indexer URL lists
// are secret because Torznab URLs carry API keys and add-on URLs often carry tokens.
var secretKeys = map[string]bool{
	KeyTMDBAPIKey: true, KeyOpenSubtitlesAPIKey: true, KeyOpenSubtitlesPass: true,
	KeyTorznabURLs: true, KeyStremioAddons: true, KeySearchPages: true,
}

var knownKeys = map[string]bool{
	KeyTMDBAPIKey: true, KeyTMDBLanguage: true, KeyOpenSubtitlesAPIKey: true, KeySubtitleLanguages: true,
	KeyOpenSubtitlesUser: true, KeyOpenSubtitlesPass: true, KeyYTSURL: true, KeyEZTVURL: true,
	KeyTorznabURLs: true, KeyStremioAddons: true, KeySearchPages: true,
}

// ErrScanRunning is returned when a scan is requested while another is in progress.
var ErrScanRunning = errors.New("a scan is already running")

// Options configures an Engine.
type Options struct {
	DBPath   string
	CacheDir string
	// StreamCacheDir holds torrent pieces while streaming (default CacheDir/streams).
	// On Android, prefer external storage so the small internal flash is spared.
	StreamCacheDir string
	// LogDir holds the rotating diagnostics log (default: next to the database).
	LogDir string
	// LogToStderr mirrors the log to stderr (CLI).
	LogToStderr bool
	// TMDBBaseURL overrides the TMDB endpoint (tests only).
	TMDBBaseURL string
	// OpenSubtitlesBaseURL overrides the OpenSubtitles endpoint (tests only).
	OpenSubtitlesBaseURL string
}

// Engine is the core application object. It is safe for concurrent use.
type Engine struct {
	store    *db.Store
	opts     Options
	scanning atomic.Bool
	log      *slog.Logger
	logFile  *logs.Rotating

	streamMu sync.Mutex
	stream   *torrent.Session

	remoteMu sync.Mutex
	remote   *remote.Server

	discMu sync.Mutex
	disc   *loopback.Server
}

// Open opens the database and prepares the cache directory.
func Open(opts Options) (*Engine, error) {
	if opts.CacheDir != "" {
		if err := os.MkdirAll(opts.CacheDir, 0o755); err != nil {
			return nil, fmt.Errorf("create cache dir: %w", err)
		}
	}
	if opts.StreamCacheDir == "" {
		opts.StreamCacheDir = filepath.Join(cacheDirOrTemp(opts.CacheDir), "streams")
	}
	if opts.LogDir == "" {
		opts.LogDir = filepath.Join(filepath.Dir(opts.DBPath), "logs")
	}
	// SQLite puts temp files (large sorts, statement journals) in the current directory when no
	// temp dir is set, and an Android app's current directory is "/", which is read-only: scans
	// then failed with "disk I/O error: read-only file system". Point it at our cache.
	if opts.CacheDir != "" {
		tmp := filepath.Join(opts.CacheDir, "sqlite-tmp")
		if err := os.MkdirAll(tmp, 0o755); err == nil {
			_ = os.Setenv("SQLITE_TMPDIR", tmp)
		}
	}
	store, err := db.Open(opts.DBPath)
	if err != nil {
		return nil, err
	}
	e := &Engine{store: store, opts: opts, log: logs.Discard}
	if lf, err := logs.Open(opts.LogDir, logs.DefaultSize); err == nil {
		e.logFile = lf
		var extra []io.Writer
		if opts.LogToStderr {
			extra = append(extra, os.Stderr)
		}
		e.log = logs.NewLogger(lf, slog.LevelInfo, extra...)
	}
	// A crash or kill during streaming leaves pieces behind: remove them now.
	if err := torrent.SweepCache(opts.StreamCacheDir); err != nil {
		e.log.Warn("sweep stream cache", "err", err)
	}
	e.log.Info("engine started", "db", opts.DBPath, "streamCache", opts.StreamCacheDir)
	return e, nil
}

func cacheDirOrTemp(dir string) string {
	if dir != "" {
		return dir
	}
	return os.TempDir()
}

// Close stops any stream or setup page and releases the database.
func (e *Engine) Close() error {
	e.StopStream()
	e.StopDisc()
	e.StopRemoteSetup()
	err := e.store.Close()
	if e.logFile != nil {
		e.log.Info("engine stopped")
		_ = e.logFile.Close()
	}
	return err
}

// Logger is the engine's diagnostics logger.
func (e *Engine) Logger() *slog.Logger { return e.log }

// LogTail returns the newest n lines of the diagnostics log.
func (e *Engine) LogTail(n int) []string {
	if e.logFile == nil {
		return nil
	}
	lines, err := e.logFile.Tail(n)
	if err != nil {
		return []string{"(could not read log: " + err.Error() + ")"}
	}
	return lines
}

// Store exposes the database for advanced callers (CLI debugging).
func (e *Engine) Store() *db.Store { return e.store }

// ---- settings ----

// SettingsView is the settings as shown to a UI. Secrets are reported only as set/unset.
type SettingsView struct {
	TMDBKeySet            bool   `json:"tmdbKeySet"`
	OpenSubtitlesKeySet   bool   `json:"openSubtitlesKeySet"`
	OpenSubtitlesLoginSet bool   `json:"openSubtitlesLoginSet"`
	TMDBLanguage          string `json:"tmdbLanguage"`
	SubtitleLanguages     string `json:"subtitleLanguages"`
	TorrentSources        int    `json:"torrentSources"` // configured indexers
}

// Settings returns the UI view of the settings.
func (e *Engine) Settings(ctx context.Context) (SettingsView, error) {
	all, err := e.store.AllSettings(ctx)
	if err != nil {
		return SettingsView{}, err
	}
	v := SettingsView{
		TMDBKeySet:          all[KeyTMDBAPIKey] != "",
		OpenSubtitlesKeySet: all[KeyOpenSubtitlesAPIKey] != "",
		TMDBLanguage:        all[KeyTMDBLanguage],
		SubtitleLanguages:   all[KeySubtitleLanguages],
	}
	v.OpenSubtitlesLoginSet = all[KeyOpenSubtitlesUser] != "" && all[KeyOpenSubtitlesPass] != ""
	v.TorrentSources = len(textProvidersFrom(all))
	if v.TMDBLanguage == "" {
		v.TMDBLanguage = "en-US"
	}
	if v.SubtitleLanguages == "" {
		v.SubtitleLanguages = "pt-BR,en"
	}
	return v, nil
}

// SetSetting stores a known setting. An empty value clears it.
func (e *Engine) SetSetting(ctx context.Context, key, value string) error {
	if !knownKeys[key] {
		return fmt.Errorf("unknown setting %q", key)
	}
	return e.store.SetSetting(ctx, key, strings.TrimSpace(value))
}

// GetSetting returns a raw setting value. Secrets are masked unless reveal is true.
func (e *Engine) GetSetting(ctx context.Context, key string, reveal bool) (string, error) {
	v, err := e.store.GetSetting(ctx, key)
	if err != nil || v == "" || reveal || !secretKeys[key] {
		return v, err
	}
	if len(v) <= 4 {
		return "****", nil
	}
	return strings.Repeat("*", len(v)-4) + v[len(v)-4:], nil
}

func (e *Engine) tmdb(ctx context.Context) (*metadata.Client, error) {
	key, err := e.store.GetSetting(ctx, KeyTMDBAPIKey)
	if err != nil {
		return nil, err
	}
	lang, _ := e.store.GetSetting(ctx, KeyTMDBLanguage)
	c := metadata.NewClient(key, lang)
	if e.opts.TMDBBaseURL != "" {
		c.BaseURL = e.opts.TMDBBaseURL
	}
	return c, nil
}

// ---- library roots ----

// AddRoot registers a library folder.
func (e *Engine) AddRoot(ctx context.Context, path string, cat db.Category) (db.Root, error) {
	st, err := os.Stat(path)
	if err != nil {
		return db.Root{}, fmt.Errorf("folder not accessible: %w", err)
	}
	if !st.IsDir() {
		return db.Root{}, fmt.Errorf("%s is not a folder", path)
	}
	return e.store.AddRoot(ctx, strings.TrimRight(path, string(os.PathSeparator)), cat)
}

// Roots lists library folders.
func (e *Engine) Roots(ctx context.Context) ([]db.Root, error) { return e.store.Roots(ctx) }

// RemoveRoot removes a folder and everything indexed under it.
func (e *Engine) RemoveRoot(ctx context.Context, id int64) error { return e.store.RemoveRoot(ctx, id) }

// ---- scanning & matching ----

// ScanEvent is streamed to the UI during a scan.
type ScanEvent struct {
	Phase     string `json:"phase"` // "scanning", "matching", "done"
	Root      string `json:"root,omitempty"`
	FilesSeen int    `json:"filesSeen,omitempty"`
	Matched   int    `json:"matched,omitempty"`
	ToMatch   int    `json:"toMatch,omitempty"`
	Title     string `json:"title,omitempty"`
}

// ScanReport summarises a full scan.
type ScanReport struct {
	Roots       []scanner.Result `json:"roots"`
	Matched     int              `json:"matched"`
	Unmatched   int              `json:"unmatched"`
	NeedsAPIKey bool             `json:"needsApiKey,omitempty"` // titles are waiting for a TMDB key
	MatchError  string           `json:"matchError,omitempty"`  // matching stopped early (bad key, offline)
}

// Scan syncs every indexed root, then matches new titles against TMDB.
// Matching problems (no key, offline) are reported in the result rather than as an error,
// because the local index is still valid and usable.
func (e *Engine) Scan(ctx context.Context, onEvent func(ScanEvent)) (rep ScanReport, err error) {
	if !e.scanning.CompareAndSwap(false, true) {
		return ScanReport{}, ErrScanRunning
	}
	defer e.scanning.Store(false)
	// Every scan leaves a line in the log: a failed one was invisible without ADB.
	start := time.Now()
	defer func() {
		switch {
		case err == nil:
			e.log.Info("scan finished", "roots", len(rep.Roots), "matched", rep.Matched, "unmatched", rep.Unmatched, "took", time.Since(start).Round(time.Millisecond))
		case !errors.Is(err, context.Canceled):
			e.log.Warn("scan failed", "err", err)
		}
	}()
	emit := func(ev ScanEvent) {
		if onEvent != nil {
			onEvent(ev)
		}
	}

	roots, err := e.store.Roots(ctx)
	if err != nil {
		return rep, err
	}
	for _, r := range roots {
		if !r.Category.Indexed() {
			continue
		}
		emit(ScanEvent{Phase: "scanning", Root: r.Path})
		res, err := scanner.Sync(ctx, e.store, r, func(p scanner.Progress) {
			emit(ScanEvent{Phase: "scanning", Root: p.Root, FilesSeen: p.FilesSeen})
		})
		if err != nil {
			return rep, err
		}
		rep.Roots = append(rep.Roots, res)
	}

	m, err := e.MatchPending(ctx, emit)
	rep.Matched, rep.Unmatched, rep.NeedsAPIKey = m.Matched, m.Unmatched, m.NeedsAPIKey
	if err != nil {
		if ctx.Err() != nil {
			return rep, ctx.Err()
		}
		rep.MatchError = err.Error()
	}
	emit(ScanEvent{Phase: "done", Matched: rep.Matched})
	return rep, nil
}

// MatchResult counts the outcome of a matching pass.
type MatchResult struct {
	Matched     int  `json:"matched"`
	Unmatched   int  `json:"unmatched"`
	NeedsAPIKey bool `json:"needsApiKey,omitempty"`
}

func kindsFor(cat db.Category) []string {
	switch cat {
	case db.TVShows:
		return []string{db.KindTV}
	case db.Concerts:
		return []string{db.KindMovie, db.KindTV}
	default:
		// Movie folders often hold concerts and documentaries that TMDB lists as TV.
		// TV is only tried after every movie search fails to give a strong hit.
		return []string{db.KindMovie, db.KindTV}
	}
}

// MatchPending looks up every pending title on TMDB and fills in missing TV seasons.
// It stops at the first authentication or network error, leaving the rest pending for next time.
func (e *Engine) MatchPending(ctx context.Context, emit func(ScanEvent)) (MatchResult, error) {
	var res MatchResult
	pending, err := e.store.ListTitles(ctx, db.TitleFilter{Statuses: []db.MatchStatus{db.StatusPending}})
	if err != nil {
		return res, err
	}
	client, err := e.tmdb(ctx)
	if err != nil {
		return res, err
	}
	if client.Key == "" {
		res.NeedsAPIKey = len(pending) > 0
		return res, nil
	}

	for i, t := range pending {
		if emit != nil {
			emit(ScanEvent{Phase: "matching", Matched: i, ToMatch: len(pending), Title: t.Name})
		}
		q := metadata.Query{Title: t.Name, Year: t.Year, Kinds: kindsFor(t.Category)}
		if files, err := e.store.Files(ctx, t.ID); err == nil && len(files) > 0 {
			// The folder usually names the title; the filename is a second opinion.
			q.AltTitles = []string{scanner.Parse(filepath.Base(files[0].Path)).Title}
		}
		hit, ok, err := client.Match(ctx, q)
		if err != nil {
			return res, err
		}
		if !ok {
			if err := e.store.SetTitleStatus(ctx, t.ID, db.StatusUnmatched); err != nil {
				return res, err
			}
			res.Unmatched++
			continue
		}
		if _, err := e.bind(ctx, client, t.ID, hit.Kind, hit.ID, db.StatusMatched); err != nil {
			return res, err
		}
		res.Matched++
	}

	// New episodes of shows that were already matched.
	ids, err := e.store.TitlesWithMissingSeasons(ctx)
	if err != nil {
		return res, err
	}
	for _, id := range ids {
		t, err := e.store.Title(ctx, id)
		if err != nil {
			return res, err
		}
		if err := e.fillSeasons(ctx, client, t.ID, t.TMDBID); err != nil {
			return res, err
		}
	}
	return res, nil
}

// Rematch retries every title that previously found no match (e.g. after changing language or fixing a key).
func (e *Engine) Rematch(ctx context.Context, emit func(ScanEvent)) (MatchResult, error) {
	unmatched, err := e.store.ListTitles(ctx, db.TitleFilter{Statuses: []db.MatchStatus{db.StatusUnmatched}})
	if err != nil {
		return MatchResult{}, err
	}
	for _, t := range unmatched {
		if err := e.store.SetTitleStatus(ctx, t.ID, db.StatusPending); err != nil {
			return MatchResult{}, err
		}
	}
	return e.MatchPending(ctx, emit)
}

// bind fetches full details for a TMDB entry and stores them on a title.
func (e *Engine) bind(ctx context.Context, client *metadata.Client, titleID int64, kind string, tmdbID int64, status db.MatchStatus) (int64, error) {
	d, err := client.Details(ctx, kind, tmdbID)
	if err != nil {
		return 0, err
	}
	meta := db.TitleMetadata{
		TMDBID: d.ID, Kind: d.Kind, Name: d.Title, Original: d.Original, Year: d.Year, Tagline: d.Tagline,
		Overview: d.Overview, ReleaseDate: d.ReleaseDate, Runtime: d.Runtime, Genres: d.Genres, Rating: d.Rating,
		PosterURL: d.PosterURL, BackdropURL: d.BackdropURL,
	}
	for _, c := range d.Cast {
		meta.Cast = append(meta.Cast, db.CastMember{Order: c.Order, Name: c.Name, Character: c.Character, ProfileURL: c.ProfileURL})
	}
	id, err := e.store.ApplyMetadata(ctx, titleID, status, meta)
	if err != nil {
		return 0, err
	}
	if kind == db.KindTV {
		if err := e.fillSeasons(ctx, client, id, d.ID); err != nil {
			return id, err
		}
	}
	return id, nil
}

func (e *Engine) fillSeasons(ctx context.Context, client *metadata.Client, titleID, tmdbID int64) error {
	seasons, err := e.store.SeasonsMissingEpisodes(ctx, titleID)
	if err != nil {
		return err
	}
	for _, s := range seasons {
		eps, err := client.Season(ctx, tmdbID, s)
		if err != nil {
			return err
		}
		out := make([]db.Episode, 0, len(eps))
		for _, ep := range eps {
			out = append(out, db.Episode{Season: ep.Season, Episode: ep.Episode, Name: ep.Name,
				Overview: ep.Overview, StillURL: ep.StillURL, AirDate: ep.AirDate})
		}
		if len(out) == 0 {
			// Season unknown to TMDB: store a placeholder so we don't ask again every scan.
			out = append(out, db.Episode{Season: s, Episode: 0})
		}
		if err := e.store.ReplaceSeason(ctx, titleID, s, out); err != nil {
			return err
		}
	}
	return nil
}

// ---- Fix Match ----

// SearchCandidates searches TMDB for alternatives to a title's current match.
// kind may be "" to use the kinds implied by the title's category.
func (e *Engine) SearchCandidates(ctx context.Context, titleID int64, query, kind string) ([]metadata.SearchResult, error) {
	t, err := e.store.Title(ctx, titleID)
	if err != nil {
		return nil, err
	}
	client, err := e.tmdb(ctx)
	if err != nil {
		return nil, err
	}
	kinds := kindsFor(t.Category)
	if kind != "" {
		kinds = []string{kind}
	}
	if strings.TrimSpace(query) == "" {
		query = t.Name
	}
	out := []metadata.SearchResult{}
	for _, k := range kinds {
		res, err := client.Search(ctx, k, query, 0)
		if err != nil {
			return nil, err
		}
		out = append(out, res...)
	}
	return out, nil
}

// Rebind points a title at a specific TMDB entry chosen by the user. The match is
// locked: later scans never change it. Returns the (possibly merged) title id.
func (e *Engine) Rebind(ctx context.Context, titleID int64, kind string, tmdbID int64) (int64, error) {
	if kind != db.KindMovie && kind != db.KindTV {
		return 0, fmt.Errorf("kind must be %q or %q", db.KindMovie, db.KindTV)
	}
	if _, err := e.store.Title(ctx, titleID); err != nil {
		return 0, err
	}
	client, err := e.tmdb(ctx)
	if err != nil {
		return 0, err
	}
	return e.bind(ctx, client, titleID, kind, tmdbID, db.StatusManual)
}

// SearchAndRebind is the one-step Fix Match: search, take the best hit, lock it.
func (e *Engine) SearchAndRebind(ctx context.Context, titleID int64, query string) (int64, error) {
	cands, err := e.SearchCandidates(ctx, titleID, query, "")
	if err != nil {
		return 0, err
	}
	if len(cands) == 0 {
		return 0, fmt.Errorf("no TMDB results for %q", query)
	}
	return e.Rebind(ctx, titleID, cands[0].Kind, cands[0].ID)
}

// ---- library queries ----

// ListTitles returns titles in a category ("" = all), paged.
func (e *Engine) ListTitles(ctx context.Context, cat db.Category, offset, limit int) ([]db.Title, error) {
	ts, err := e.store.ListTitles(ctx, db.TitleFilter{Category: cat, Offset: offset, Limit: limit})
	if ts == nil {
		ts = []db.Title{}
	}
	return ts, err
}

// RecentlyAdded returns the newest titles across the library.
func (e *Engine) RecentlyAdded(ctx context.Context, limit int) ([]db.Title, error) {
	ts, err := e.store.ListTitles(ctx, db.TitleFilter{OrderBy: "added", Limit: limit})
	if ts == nil {
		ts = []db.Title{}
	}
	return ts, err
}

// FileView is a playable file with its saved progress.
type FileView struct {
	db.File
	PositionMS int64 `json:"positionMs,omitempty"`
	DurationMS int64 `json:"durationMs,omitempty"`
}

// TitleDetails is everything the Details screen needs.
type TitleDetails struct {
	db.Title
	// Section is the screen the title shows on: "movies", "concerts", "series" or "tutorials".
	// A concert film in the Movies folder is "concerts".
	Section  string          `json:"section"`
	Cast     []db.CastMember `json:"cast"`
	Episodes []db.Episode    `json:"episodes"`
	Files    []FileView      `json:"files"`
}

// Details returns a title with its cast, episodes and files.
func (e *Engine) Details(ctx context.Context, titleID int64) (TitleDetails, error) {
	t, err := e.store.Title(ctx, titleID)
	if err != nil {
		return TitleDetails{}, err
	}
	d := TitleDetails{Title: t, Section: sectionOf(t), Cast: []db.CastMember{}, Episodes: []db.Episode{}, Files: []FileView{}}
	if c, err := e.store.Cast(ctx, titleID); err != nil {
		return d, err
	} else if c != nil {
		d.Cast = c
	}
	if eps, err := e.store.Episodes(ctx, titleID); err != nil {
		return d, err
	} else {
		for _, ep := range eps {
			if ep.Episode > 0 {
				d.Episodes = append(d.Episodes, ep)
			}
		}
	}
	files, err := e.store.Files(ctx, titleID)
	if err != nil {
		return d, err
	}
	for _, f := range files {
		fv := FileView{File: f}
		if p, err := e.store.GetProgress(ctx, f.Path); err == nil {
			fv.PositionMS, fv.DurationMS = p.PositionMS, p.DurationMS
		}
		d.Files = append(d.Files, fv)
	}
	return d, nil
}

// BrowseEntry is a folder listing entry with the video's saved progress.
type BrowseEntry struct {
	scanner.Entry
	PositionMS int64 `json:"positionMs,omitempty"`
	DurationMS int64 `json:"durationMs,omitempty"`
}

// Browse lists a folder for the Tutorials browser, with each video's progress.
func (e *Engine) Browse(ctx context.Context, path string) ([]BrowseEntry, error) {
	entries, err := scanner.Browse(path)
	if err != nil {
		return nil, err
	}
	out := make([]BrowseEntry, 0, len(entries))
	for _, en := range entries {
		be := BrowseEntry{Entry: en}
		if !en.IsDir {
			if p, err := e.store.GetProgress(ctx, en.Path); err == nil {
				be.PositionMS, be.DurationMS = p.PositionMS, p.DurationMS
			}
		}
		out = append(out, be)
	}
	return out, nil
}

// LessonHit is a Tutorials search hit: a folder or video, with progress like a browse entry.
type LessonHit struct {
	scanner.Hit
	PositionMS int64 `json:"positionMs,omitempty"`
	DurationMS int64 `json:"durationMs,omitempty"`
}

// maxLessonHits caps a Tutorials search; a short list is all a TV screen can show anyway.
const maxLessonHits = 200

// SearchLessons searches every Tutorials folder on the drive by name (scanner.Search).
// Folders that aren't mounted are skipped.
func (e *Engine) SearchLessons(ctx context.Context, query string) ([]LessonHit, error) {
	roots, err := e.store.Roots(ctx)
	if err != nil {
		return nil, err
	}
	out := []LessonHit{}
	for _, r := range roots {
		if r.Category != db.Tutorials || len(out) >= maxLessonHits {
			continue
		}
		if _, err := os.Stat(r.Path); err != nil {
			continue
		}
		hits, err := scanner.Search(ctx, r.Path, query, maxLessonHits-len(out))
		if err != nil {
			return nil, err
		}
		for _, h := range hits {
			lh := LessonHit{Hit: h}
			if !h.IsDir {
				if p, err := e.store.GetProgress(ctx, h.Path); err == nil {
					lh.PositionMS, lh.DurationMS = p.PositionMS, p.DurationMS
				}
			}
			out = append(out, lh)
		}
	}
	return out, nil
}

// ---- playback progress ----

// SaveProgress records the playback position for a file path or stream key.
func (e *Engine) SaveProgress(ctx context.Context, key string, titleID, positionMS, durationMS int64) error {
	return e.store.SaveProgress(ctx, db.Progress{Key: key, TitleID: titleID, PositionMS: positionMS, DurationMS: durationMS})
}

// ContinueWatching returns unfinished items, most recent first.
func (e *Engine) ContinueWatching(ctx context.Context, limit int) ([]db.Progress, error) {
	p, err := e.store.RecentProgress(ctx, limit)
	if p == nil {
		p = []db.Progress{}
	}
	return p, err
}
