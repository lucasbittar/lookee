// Package lookeecore is the gomobile-exported API of the lookee Go core.
//
// Contract (CLAUDE.md §3, docs/BRIDGE.md):
//   - Exported functions take primitives and return (string, error). The string is JSON;
//     its shapes are pinned by golden files in testdata/golden, which the Kotlin tests
//     decode too, so neither side can drift silently.
//   - Errors cross the bridge as exceptions whose message starts with "lookee:<code>: "
//     for conditions the UI handles specially (see the Code* constants).
//   - Long-running work reports through small callback interfaces implemented in Kotlin.
//     Callbacks arrive on Go threads; Kotlin must hop to the main thread itself.
package lookeecore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"sync"
	"time"

	"github.com/lucasbittar/lookee/core/db"
	"github.com/lucasbittar/lookee/core/engine"
	"github.com/lucasbittar/lookee/core/metadata"
	"github.com/lucasbittar/lookee/core/remote"
	"github.com/lucasbittar/lookee/core/scanner"
	"github.com/lucasbittar/lookee/core/subtitles"
	"github.com/lucasbittar/lookee/core/torrent"
)

// Version of the core library.
const Version = "0.2.0"

// Error codes carried in exception messages as "lookee:<code>: <message>".
const (
	CodeNotInitialized = "not_initialized"
	CodeNotFound       = "not_found"
	CodeScanRunning    = "scan_running"
	CodeNoTMDBKey      = "no_tmdb_key"
	CodeBadTMDBKey     = "bad_tmdb_key"
	CodeNoSubtitleKey  = "no_subtitle_key"
	CodeSubtitleQuota  = "subtitle_quota"
	CodeNoSources      = "no_torrent_sources"
	CodeNoVideo        = "no_video"
	CodeNoLAN          = "no_lan"
	CodeCancelled      = "cancelled"
	CodeBadRequest     = "bad_request"
)

// networkTimeout bounds calls that reach TMDB, OpenSubtitles or indexers.
const networkTimeout = 45 * time.Second

var (
	mu  sync.Mutex
	eng *engine.Engine

	scanMu     sync.Mutex
	scanCancel context.CancelFunc

	streamMu     sync.Mutex
	streamCancel context.CancelFunc
)

// ErrNotInitialized is returned when a call is made before InitEngine.
var ErrNotInitialized = errors.New("lookee engine not initialized: call InitEngine first")

// ---- callbacks (implemented in Kotlin) ----

// ScanCallback receives scan progress. Each payload is a ScanUpdate JSON object.
type ScanCallback interface {
	OnProgress(json string)
}

// StreamCallback receives torrent stats about once a second while a stream runs.
type StreamCallback interface {
	OnStats(json string)
}

// RemoteCallback is told when the phone setup page closes.
type RemoteCallback interface {
	OnClosed(saved bool)
}

// ---- lifecycle ----

// InitEngine opens the database in filesDir and uses cacheDir for temporary data.
// externalDir is app-specific storage on the big drive (Context.getExternalFilesDir);
// when set, torrent pieces are cached there instead of the small internal flash.
// Calling it again is a no-op.
func InitEngine(filesDir, cacheDir, externalDir string) error {
	mu.Lock()
	defer mu.Unlock()
	if eng != nil {
		return nil
	}
	opts := engine.Options{DBPath: filepath.Join(filesDir, "lookee.db"), CacheDir: cacheDir}
	if externalDir != "" {
		opts.StreamCacheDir = filepath.Join(externalDir, "streams")
	}
	e, err := engine.Open(opts)
	if err != nil {
		return wrap(err)
	}
	eng = e
	return nil
}

// Shutdown stops scans and streams and closes the engine.
func Shutdown() error {
	CancelScan()
	cancelStreamStart()
	mu.Lock()
	defer mu.Unlock()
	if eng == nil {
		return nil
	}
	err := eng.Close()
	eng = nil
	return wrap(err)
}

// CoreVersion returns the library version.
func CoreVersion() string { return Version }

// GetLogs returns the last n lines of the diagnostics log as a JSON array of strings.
func GetLogs(n int) (string, error) {
	e, err := get()
	if err != nil {
		return "", err
	}
	lines := e.LogTail(n)
	if lines == nil {
		lines = []string{}
	}
	return toJSON(lines)
}

// ---- settings ----

// GetSettings returns the settings view (secrets reported only as set/unset).
func GetSettings() (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) { return e.Settings(ctx) })
}

// SetSetting stores a setting value. An empty value clears it.
func SetSetting(key, value string) error {
	_, err := call(func(ctx context.Context, e *engine.Engine) (any, error) {
		return nil, e.SetSetting(ctx, key, value)
	})
	return err
}

// SeedResult reports how many settings a defaults file filled in.
type SeedResult struct {
	Applied int `json:"applied"`
}

// SeedDefaultSettings applies a private defaults file (torrent sources and languages; see
// docs/SOURCES.md). Only empty settings are filled, and the same file is applied once.
func SeedDefaultSettings(defaultsJSON string) (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) {
		n, err := e.SeedSettings(ctx, []byte(defaultsJSON))
		if err != nil {
			e.Logger().Warn("defaults file rejected", "err", err)
			return nil, badRequest("%v", err)
		}
		return SeedResult{Applied: n}, nil
	})
}

// StartRemoteSetup starts the PIN-protected phone settings page and returns
// {"url","pin","expiresInSeconds"}. host is the TV's LAN IP ("" = detect).
func StartRemoteSetup(host string, cb RemoteCallback) (string, error) {
	e, err := get()
	if err != nil {
		return "", err
	}
	r, err := e.StartRemoteSetup(host, func(saved bool) {
		if cb != nil {
			cb.OnClosed(saved)
		}
	})
	if err != nil {
		return "", wrap(err)
	}
	return toJSON(r)
}

// StopRemoteSetup closes the phone settings page.
func StopRemoteSetup() {
	if e, err := get(); err == nil {
		e.StopRemoteSetup()
	}
}

// ---- library roots ----

// ListRoots returns the library folders.
func ListRoots() (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) { return nonNil(e.Roots(ctx)) })
}

// AddRoot registers a folder under a category (MOVIES, TV_SHOWS, CONCERTS, TUTORIALS).
func AddRoot(path, category string) (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) {
		cat := db.Category(category)
		if !cat.Valid() {
			return nil, badRequest("unknown category %q", category)
		}
		return e.AddRoot(ctx, path, cat)
	})
}

// RemoveRoot removes a folder and everything indexed under it.
func RemoveRoot(id int64) error {
	_, err := call(func(ctx context.Context, e *engine.Engine) (any, error) { return nil, e.RemoveRoot(ctx, id) })
	return err
}

// ListStorageVolumes returns the mounted storage volumes the folder picker can start from.
func ListStorageVolumes() (string, error) {
	return toJSON(listVolumes(storageBase))
}

// ---- scan ----

// ScanUpdate is one scan callback payload. Phase is "scanning" or "matching" while
// running, then exactly one final "finished" (with Report) or "failed" (with Error).
type ScanUpdate struct {
	engine.ScanEvent
	Report *engine.ScanReport `json:"report,omitempty"`
	Error  string             `json:"error,omitempty"`
	Code   string             `json:"code,omitempty"`
}

// StartScan syncs every library folder and matches new titles in the background.
// It returns at once; progress and the result arrive through cb.
func StartScan(cb ScanCallback) error {
	e, err := get()
	if err != nil {
		return err
	}
	scanMu.Lock()
	if scanCancel != nil {
		scanMu.Unlock()
		return wrap(engine.ErrScanRunning)
	}
	ctx, cancel := context.WithCancel(context.Background())
	scanCancel = cancel
	scanMu.Unlock()

	send := func(u ScanUpdate) {
		if cb == nil {
			return
		}
		if s, err := toJSON(u); err == nil {
			cb.OnProgress(s)
		}
	}
	go func() {
		defer func() {
			scanMu.Lock()
			scanCancel = nil
			scanMu.Unlock()
			cancel()
		}()
		rep, err := e.Scan(ctx, func(ev engine.ScanEvent) {
			if ev.Phase != "done" {
				send(ScanUpdate{ScanEvent: ev})
			}
		})
		if err != nil {
			code, msg := classify(err)
			send(ScanUpdate{ScanEvent: engine.ScanEvent{Phase: "failed"}, Error: msg, Code: code})
			return
		}
		if rep.Roots == nil {
			rep.Roots = []scanner.Result{}
		}
		send(ScanUpdate{ScanEvent: engine.ScanEvent{Phase: "finished", Matched: rep.Matched}, Report: &rep})
	}()
	return nil
}

// CancelScan stops a running scan. The index stays consistent; the next scan resumes.
func CancelScan() {
	scanMu.Lock()
	defer scanMu.Unlock()
	if scanCancel != nil {
		scanCancel()
	}
}

// ---- library ----

// ListMedia returns titles in a category ("" = all), paged.
func ListMedia(category string, offset, limit int) (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) {
		cat := db.Category(category)
		if category != "" && !cat.Valid() {
			return nil, badRequest("unknown category %q", category)
		}
		return e.ListTitles(ctx, cat, offset, limit)
	})
}

// GetLibraryView lists a screen's titles A–Z: "movies" (films, without concert films),
// "concerts" (the Concerts folder plus concert films from Movies: TMDB genres only Music
// and Documentary) or "series".
func GetLibraryView(view string) (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) {
		ts, err := e.LibraryView(ctx, view)
		if err != nil && ts == nil {
			return nil, badRequest("%v", err)
		}
		return ts, err
	})
}

// GetRecentlyAdded returns the newest titles across the library.
func GetRecentlyAdded(limit int) (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) { return e.RecentlyAdded(ctx, limit) })
}

// GetHome returns the Home screen in one call: Continue Watching (unfinished items, newest
// first, with episode names and stills), Recently Added, and title counts per category.
func GetHome(limit int) (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) { return e.Home(ctx, limit) })
}

// GetSeries returns every show with its next episode and season progress; shows being
// watched are also listed in "inProgress", most recent first.
func GetSeries() (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) { return e.Series(ctx) })
}

// GetMediaDetails returns a title with cast, episodes and files (with saved progress).
func GetMediaDetails(titleID int64) (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) { return e.Details(ctx, titleID) })
}

// SearchMatchCandidates lists TMDB entries for the Fix Match picker. kind may be "".
func SearchMatchCandidates(titleID int64, query, kind string) (string, error) {
	return netCall(func(ctx context.Context, e *engine.Engine) (any, error) {
		return nonNil(e.SearchCandidates(ctx, titleID, query, kind))
	})
}

// RebindTMDB locks a title to the TMDB entry the user picked and returns {"titleId"}
// (the id can change when the title merges into one already matched to that entry).
func RebindTMDB(titleID int64, kind string, tmdbID int64) (string, error) {
	return netCall(func(ctx context.Context, e *engine.Engine) (any, error) {
		id, err := e.Rebind(ctx, titleID, kind, tmdbID)
		return TitleRef{TitleID: id}, err
	})
}

// SearchAndRebindTMDB is one-step Fix Match: search, take the best hit, lock it.
func SearchAndRebindTMDB(titleID int64, query string) (string, error) {
	return netCall(func(ctx context.Context, e *engine.Engine) (any, error) {
		id, err := e.SearchAndRebind(ctx, titleID, query)
		return TitleRef{TitleID: id}, err
	})
}

// TitleRef identifies a title after a rebind.
type TitleRef struct {
	TitleID int64 `json:"titleId"`
}

// BrowseFolder lists a folder for the Tutorials browser.
func BrowseFolder(path string) (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) { return e.Browse(ctx, path) })
}

// SearchLessons finds folders and videos in every Tutorials folder whose path contains each
// word of query (case and accents ignored). JSON: an array of lesson hits.
func SearchLessons(query string) (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) { return e.SearchLessons(ctx, query) })
}

// ---- progress ----

// SaveProgress records the playback position of a file path or stream key.
func SaveProgress(key string, titleID, positionMS, durationMS int64) error {
	_, err := call(func(ctx context.Context, e *engine.Engine) (any, error) {
		return nil, e.SaveProgress(ctx, key, titleID, positionMS, durationMS)
	})
	return err
}

// GetContinueWatching returns unfinished items, most recent first.
func GetContinueWatching(limit int) (string, error) {
	return call(func(ctx context.Context, e *engine.Engine) (any, error) { return e.ContinueWatching(ctx, limit) })
}

// ---- subtitles ----

// FindSidecarSubtitles lists subtitle files next to a local video.
func FindSidecarSubtitles(videoPath string) (string, error) {
	return call(func(_ context.Context, e *engine.Engine) (any, error) { return e.Sidecars(videoPath) })
}

// SearchSubtitles searches OpenSubtitles. requestJSON is a SubtitleRequest:
// {"videoPath","titleId","tmdbId","kind","season","episode","query"}, all optional.
func SearchSubtitles(requestJSON string) (string, error) {
	var req engine.SubtitleRequest
	if err := json.Unmarshal([]byte(requestJSON), &req); err != nil {
		return "", wrap(badRequest("subtitle request: %v", err))
	}
	return netCall(func(ctx context.Context, e *engine.Engine) (any, error) { return e.SearchSubtitles(ctx, req) })
}

// DownloadSubtitle downloads a subtitle as UTF-8 and returns {"path"}.
func DownloadSubtitle(fileID int64) (string, error) {
	return netCall(func(ctx context.Context, e *engine.Engine) (any, error) {
		p, err := e.DownloadSubtitle(ctx, fileID)
		return PathRef{Path: p}, err
	})
}

// ShiftSubtitle writes a UTF-8 copy of a subtitle shifted by offsetMS and returns {"path"}.
// The original file is never modified. offsetMS 0 only normalises the encoding.
func ShiftSubtitle(path string, offsetMS int64) (string, error) {
	return call(func(_ context.Context, e *engine.Engine) (any, error) {
		p, err := e.PrepareSubtitle(path, offsetMS)
		return PathRef{Path: p}, err
	})
}

// PathRef carries a local file path.
type PathRef struct {
	Path string `json:"path"`
}

// ---- discover ----

// GetTrending returns this week's trending titles on TMDB; kind is "movie" or "tv".
// Titles already on the drive carry "libraryTitleId".
func GetTrending(kind string) (string, error) {
	return netCall(func(ctx context.Context, e *engine.Engine) (any, error) { return e.DiscoverTrending(ctx, kind) })
}

// SearchTMDB searches TMDB by text; kind is "movie", "tv" or "" for both.
func SearchTMDB(query, kind string) (string, error) {
	return netCall(func(ctx context.Context, e *engine.Engine) (any, error) { return e.DiscoverSearch(ctx, query, kind) })
}

// SearchTorrents finds torrents for a TMDB title; season/episode apply to TV.
func SearchTorrents(kind string, tmdbID int64, season, episode int) (string, error) {
	return netCall(func(ctx context.Context, e *engine.Engine) (any, error) {
		return e.SearchTorrents(ctx, kind, tmdbID, season, episode)
	})
}

// SearchTorrentsText searches indexers by free text (concerts and other titles TMDB lacks).
func SearchTorrentsText(kind, text string) (string, error) {
	return netCall(func(ctx context.Context, e *engine.Engine) (any, error) {
		return e.SearchTorrentsText(ctx, kind, text)
	})
}

// ---- streaming ----

// StartStream starts streaming a magnet link or .torrent path, replacing any current
// stream, and blocks until the file is known (up to ~90 s). It returns the first stats,
// whose "url" is the loopback address for the player. fileIndex < 0 picks the largest
// video. StopStream called meanwhile cancels the start.
func StartStream(source string, fileIndex int, cb StreamCallback) (string, error) {
	e, err := get()
	if err != nil {
		return "", err
	}
	cancelStreamStart()
	ctx, cancel := context.WithCancel(context.Background())
	streamMu.Lock()
	streamCancel = cancel
	streamMu.Unlock()

	st, err := e.StartStream(ctx, source, fileIndex, func(s torrent.Stats) {
		if cb == nil {
			return
		}
		if j, err := toJSON(s); err == nil {
			cb.OnStats(j)
		}
	})
	if err != nil {
		if ctx.Err() != nil {
			err = context.Canceled
		}
		return "", wrap(err)
	}
	return toJSON(st)
}

// GetStreamStats returns the current stream's stats ({"state":"stopped"} when idle).
func GetStreamStats() (string, error) {
	e, err := get()
	if err != nil {
		return "", err
	}
	return toJSON(e.StreamStats())
}

// StopStream stops streaming and deletes the downloaded pieces.
func StopStream() {
	cancelStreamStart()
	if e, err := get(); err == nil {
		e.StopStream()
	}
}

// ClearStreamCache stops streaming and removes every cached piece.
func ClearStreamCache() error {
	cancelStreamStart()
	_, err := call(func(_ context.Context, e *engine.Engine) (any, error) { return nil, e.ClearStreamCache() })
	return err
}

func cancelStreamStart() {
	streamMu.Lock()
	defer streamMu.Unlock()
	if streamCancel != nil {
		streamCancel()
		streamCancel = nil
	}
}

// ---- plumbing ----

func get() (*engine.Engine, error) {
	mu.Lock()
	defer mu.Unlock()
	if eng == nil {
		return nil, wrap(ErrNotInitialized)
	}
	return eng, nil
}

func call(fn func(context.Context, *engine.Engine) (any, error)) (string, error) {
	return run(context.Background(), fn)
}

func netCall(fn func(context.Context, *engine.Engine) (any, error)) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), networkTimeout)
	defer cancel()
	return run(ctx, fn)
}

func run(ctx context.Context, fn func(context.Context, *engine.Engine) (any, error)) (string, error) {
	e, err := get()
	if err != nil {
		return "", err
	}
	v, err := fn(ctx, e)
	if err != nil {
		return "", wrap(err)
	}
	if v == nil {
		return "", nil
	}
	return toJSON(v)
}

func nonNil[T any](s []T, err error) ([]T, error) {
	if s == nil {
		s = []T{}
	}
	return s, err
}

type badRequestError struct{ msg string }

func (b badRequestError) Error() string { return b.msg }

func badRequest(format string, a ...any) error { return badRequestError{fmt.Sprintf(format, a...)} }

// classify maps an error to a bridge code ("" for unexpected errors) and message.
func classify(err error) (string, string) {
	var br badRequestError
	switch {
	case errors.Is(err, ErrNotInitialized):
		return CodeNotInitialized, err.Error()
	case errors.Is(err, db.ErrNotFound):
		return CodeNotFound, err.Error()
	case errors.Is(err, engine.ErrScanRunning):
		return CodeScanRunning, err.Error()
	case errors.Is(err, metadata.ErrNoAPIKey):
		return CodeNoTMDBKey, err.Error()
	case errors.Is(err, metadata.ErrUnauthorized):
		return CodeBadTMDBKey, err.Error()
	case errors.Is(err, subtitles.ErrNoAPIKey):
		return CodeNoSubtitleKey, err.Error()
	case errors.Is(err, subtitles.ErrQuota):
		return CodeSubtitleQuota, err.Error()
	case errors.Is(err, engine.ErrNoTorrentSources):
		return CodeNoSources, err.Error()
	case errors.Is(err, torrent.ErrNoVideo):
		return CodeNoVideo, err.Error()
	case errors.Is(err, remote.ErrNoLAN):
		return CodeNoLAN, err.Error()
	case errors.Is(err, context.Canceled):
		return CodeCancelled, "cancelled"
	case errors.As(err, &br):
		return CodeBadRequest, err.Error()
	}
	return "", err.Error()
}

// wrap prefixes known errors with "lookee:<code>: " so Kotlin can branch on the code.
func wrap(err error) error {
	if err == nil {
		return nil
	}
	code, msg := classify(err)
	if code == "" {
		return err
	}
	return fmt.Errorf("lookee:%s: %s", code, msg)
}

func toJSON(v any) (string, error) {
	b, err := json.Marshal(v)
	return string(b), err
}
