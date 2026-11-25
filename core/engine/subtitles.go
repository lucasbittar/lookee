package engine

import (
	"context"
	"path/filepath"
	"strings"

	"github.com/lucasbittar/lookee/core/db"
	"github.com/lucasbittar/lookee/core/subtitles"
)

func (e *Engine) subsDir() string { return filepath.Join(cacheDirOrTemp(e.opts.CacheDir), "subtitles") }

func (e *Engine) languages(ctx context.Context) []string {
	v, _ := e.Settings(ctx)
	var out []string
	for _, l := range strings.Split(v.SubtitleLanguages, ",") {
		if l = strings.TrimSpace(l); l != "" {
			out = append(out, l)
		}
	}
	return out
}

func (e *Engine) openSubtitles(ctx context.Context) (*subtitles.Client, error) {
	all, err := e.store.AllSettings(ctx)
	if err != nil {
		return nil, err
	}
	c := subtitles.NewClient(all[KeyOpenSubtitlesAPIKey])
	if u, p := all[KeyOpenSubtitlesUser], all[KeyOpenSubtitlesPass]; u != "" && p != "" && c.APIKey != "" {
		if err := c.Login(ctx, u, p); err != nil {
			// Downloads still work anonymously with a lower daily quota.
			e.log.Warn("opensubtitles login failed; continuing anonymously", "err", err)
		}
	}
	return c, nil
}

// Sidecars lists subtitle files next to a local video.
func (e *Engine) Sidecars(videoPath string) ([]subtitles.Sidecar, error) {
	s, err := subtitles.FindSidecars(videoPath)
	if s == nil {
		s = []subtitles.Sidecar{}
	}
	return s, err
}

// SubtitleRequest identifies what is playing. Any subset may be set.
type SubtitleRequest struct {
	VideoPath string `json:"videoPath,omitempty"` // local file: enables hash matching
	TitleID   int64  `json:"titleId,omitempty"`   // library title
	TMDBID    int64  `json:"tmdbId,omitempty"`    // for streams/Discover
	Kind      string `json:"kind,omitempty"`      // "movie" or "tv"
	Season    int    `json:"season,omitempty"`
	Episode   int    `json:"episode,omitempty"`
	Query     string `json:"query,omitempty"` // free text override
}

// SearchSubtitles searches OpenSubtitles in the preferred languages.
func (e *Engine) SearchSubtitles(ctx context.Context, req SubtitleRequest) ([]subtitles.Result, error) {
	c, err := e.openSubtitles(ctx)
	if err != nil {
		return nil, err
	}
	q := subtitles.Query{Languages: e.languages(ctx), Query: req.Query, TMDBID: req.TMDBID,
		Season: req.Season, Episode: req.Episode, IsEpisode: req.Kind == db.KindTV || req.Episode > 0}
	if req.TitleID > 0 {
		if t, err := e.store.Title(ctx, req.TitleID); err == nil {
			if q.TMDBID == 0 {
				q.TMDBID = t.TMDBID
			}
			if t.Kind == db.KindTV {
				q.IsEpisode = true
			}
			if q.TMDBID == 0 && q.Query == "" {
				q.Query = t.Name
			}
		}
	}
	if req.VideoPath != "" {
		if h, err := subtitles.MovieHashFile(req.VideoPath); err == nil {
			q.MovieHash = h
		}
		if q.TMDBID == 0 && q.Query == "" {
			q.Query = strings.TrimSuffix(filepath.Base(req.VideoPath), filepath.Ext(req.VideoPath))
		}
	}
	res, err := c.Search(ctx, q)
	if err != nil {
		e.log.Warn("subtitle search", "err", err)
		return nil, err
	}
	if res == nil {
		res = []subtitles.Result{}
	}
	e.log.Info("subtitle search", "tmdb", q.TMDBID, "hash", q.MovieHash != "", "results", len(res))
	return res, nil
}

// DownloadSubtitle downloads an OpenSubtitles file (as UTF-8) and returns its local path.
func (e *Engine) DownloadSubtitle(ctx context.Context, fileID int64) (string, error) {
	c, err := e.openSubtitles(ctx)
	if err != nil {
		return "", err
	}
	path, err := c.Download(ctx, fileID, e.subsDir())
	if err != nil {
		e.log.Warn("subtitle download", "fileId", fileID, "err", err)
		return "", err
	}
	e.log.Info("subtitle downloaded", "fileId", fileID, "path", path)
	return path, nil
}

// PrepareSubtitle returns a UTF-8 copy of any subtitle file, shifted by offsetMS, for the
// player to load. Originals on the user's drive are never modified.
func (e *Engine) PrepareSubtitle(path string, offsetMS int64) (string, error) {
	return subtitles.PrepareFile(path, e.subsDir(), offsetMS)
}
