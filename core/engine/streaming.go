package engine

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"

	"github.com/lucasbittar/lookee/core/db"
	"github.com/lucasbittar/lookee/core/torrent"
)

// ErrNoTorrentSources is returned when no indexer is configured.
var ErrNoTorrentSources = errors.New("no torrent sources configured: add one in Settings")

// providersFrom builds indexer clients from settings. None are built in: every URL is
// configured by the user, so the app ships neutral and survives indexers moving domains.
func providersFrom(s map[string]string) []torrent.Provider {
	var ps []torrent.Provider
	if u := strings.TrimSpace(s[KeyYTSURL]); u != "" {
		ps = append(ps, torrent.YTS{BaseURL: u})
	}
	if u := strings.TrimSpace(s[KeyEZTVURL]); u != "" {
		ps = append(ps, torrent.EZTV{BaseURL: u})
	}
	for i, u := range lines(s[KeyTorznabURLs]) {
		ps = append(ps, torrent.Torznab{Label: fmt.Sprintf("torznab%d", i+1), URL: u})
	}
	for i, u := range lines(s[KeyStremioAddons]) {
		ps = append(ps, torrent.Stremio{Label: fmt.Sprintf("addon%d", i+1), BaseURL: u})
	}
	return ps
}

func lines(s string) []string {
	var out []string
	for _, l := range strings.Split(s, "\n") {
		if l = strings.TrimSpace(l); l != "" && !strings.HasPrefix(l, "#") {
			out = append(out, l)
		}
	}
	return out
}

func (e *Engine) providers(ctx context.Context) ([]torrent.Provider, error) {
	all, err := e.store.AllSettings(ctx)
	if err != nil {
		return nil, err
	}
	ps := providersFrom(all)
	if len(ps) == 0 {
		return nil, ErrNoTorrentSources
	}
	return ps, nil
}

// TorrentSearch is the result of a torrent search. Errors from individual sources are
// reported as warnings so partial results still reach the UI.
type TorrentSearch struct {
	Results  []torrent.Result `json:"results"`
	Warnings []string         `json:"warnings,omitempty"`
}

// SearchTorrents finds torrents for a TMDB movie or episode. kind is "movie" or "tv";
// season/episode apply to TV.
func (e *Engine) SearchTorrents(ctx context.Context, kind string, tmdbID int64, season, episode int) (TorrentSearch, error) {
	if kind != db.KindMovie && kind != db.KindTV {
		return TorrentSearch{}, fmt.Errorf("kind must be %q or %q", db.KindMovie, db.KindTV)
	}
	ps, err := e.providers(ctx)
	if err != nil {
		return TorrentSearch{}, err
	}
	client, err := e.tmdb(ctx)
	if err != nil {
		return TorrentSearch{}, err
	}
	d, err := client.Details(ctx, kind, tmdbID)
	if err != nil {
		return TorrentSearch{}, err
	}
	imdb, err := client.IMDbID(ctx, kind, tmdbID)
	if err != nil {
		e.log.Warn("imdb id lookup", "kind", kind, "tmdb", tmdbID, "err", err)
	}
	q := torrent.Query{Kind: kind, Title: d.Title, Year: d.Year, IMDbID: imdb, Season: season, Episode: episode}
	return e.runSearch(ctx, ps, q)
}

// SearchTorrentsText searches indexers by free text (concerts, things TMDB lacks).
func (e *Engine) SearchTorrentsText(ctx context.Context, kind, text string) (TorrentSearch, error) {
	ps, err := e.providers(ctx)
	if err != nil {
		return TorrentSearch{}, err
	}
	if kind == "" {
		kind = db.KindMovie
	}
	return e.runSearch(ctx, ps, torrent.Query{Kind: kind, Title: text})
}

func (e *Engine) runSearch(ctx context.Context, ps []torrent.Provider, q torrent.Query) (TorrentSearch, error) {
	res, err := torrent.Search(ctx, ps, q)
	out := TorrentSearch{Results: res}
	if out.Results == nil {
		out.Results = []torrent.Result{}
	}
	if err != nil {
		out.Warnings = append(out.Warnings, strings.Split(err.Error(), "\n")...)
		e.log.Warn("torrent search", "query", q.Title, "imdb", q.IMDbID, "err", err)
	}
	e.log.Info("torrent search", "query", q.Title, "imdb", q.IMDbID, "results", len(out.Results))
	return out, nil
}

// StartStream starts streaming a magnet link or .torrent file, replacing any current
// stream. fileIndex < 0 picks the largest video. onStats receives JSON-friendly stats
// about once a second until the stream stops.
func (e *Engine) StartStream(ctx context.Context, source string, fileIndex int, onStats func(torrent.Stats)) (torrent.Stats, error) {
	e.StopStream()
	e.log.Info("stream start", "source", sourceLabel(source), "fileIndex", fileIndex)
	sess, err := torrent.Start(ctx, torrent.Config{CacheRoot: e.opts.StreamCacheDir, Logger: e.log}, source, fileIndex, onStats)
	if err != nil {
		e.log.Error("stream start failed", "err", err)
		return torrent.Stats{State: "error", Error: err.Error()}, err
	}
	e.streamMu.Lock()
	e.stream = sess
	e.streamMu.Unlock()
	st := sess.Stats()
	e.log.Info("stream serving", "file", st.FileName, "size", st.Size, "url", st.URL)
	return st, nil
}

// StreamStats returns the current stream's stats, or State "stopped".
func (e *Engine) StreamStats() torrent.Stats {
	e.streamMu.Lock()
	defer e.streamMu.Unlock()
	if e.stream == nil {
		return torrent.Stats{State: "stopped"}
	}
	return e.stream.Stats()
}

// StopStream stops the current stream and deletes its downloaded pieces.
func (e *Engine) StopStream() {
	e.streamMu.Lock()
	s := e.stream
	e.stream = nil
	e.streamMu.Unlock()
	if s != nil {
		st := s.Stats()
		s.Stop()
		e.log.Info("stream stopped", "file", st.FileName, "downloaded", st.Downloaded)
	}
}

// ClearStreamCache stops streaming and removes every cached piece.
func (e *Engine) ClearStreamCache() error {
	e.StopStream()
	if err := torrent.SweepCache(e.opts.StreamCacheDir); err != nil {
		return err
	}
	return os.MkdirAll(e.opts.StreamCacheDir, 0o755)
}

// sourceLabel keeps magnet trackers and tokens out of the log.
func sourceLabel(source string) string {
	if i := strings.Index(source, "&"); strings.HasPrefix(source, "magnet:") && i > 0 {
		return source[:i]
	}
	return source
}
