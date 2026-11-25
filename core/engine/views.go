package engine

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"

	"github.com/lucasbittar/lookee/core/db"
	"github.com/lucasbittar/lookee/core/metadata"
)

// watchedFraction is where an item counts as finished (credits are rarely watched).
const watchedFraction = 0.95

// ContinueItem is one card in Continue Watching: what to resume and how to show it.
type ContinueItem struct {
	Key         string   `json:"key"` // file path or stream key; pass back to the player
	TitleID     int64    `json:"titleId,omitempty"`
	Title       string   `json:"title"`
	Category    string   `json:"category,omitempty"`
	Kind        string   `json:"kind,omitempty"`
	Year        int      `json:"year,omitempty"`
	Overview    string   `json:"overview,omitempty"`
	Genres      []string `json:"genres,omitempty"`
	PosterURL   string   `json:"posterUrl,omitempty"`
	BackdropURL string   `json:"backdropUrl,omitempty"`
	Season      int      `json:"season,omitempty"`
	Episode     int      `json:"episode,omitempty"`
	EpisodeName string   `json:"episodeName,omitempty"`
	StillURL    string   `json:"stillUrl,omitempty"` // episode still, else the backdrop
	PositionMS  int64    `json:"positionMs"`
	DurationMS  int64    `json:"durationMs"`
}

// HomeView is everything the Home screen shows, in one call.
type HomeView struct {
	ContinueWatching []ContinueItem `json:"continueWatching"`
	RecentlyAdded    []db.Title     `json:"recentlyAdded"`
	Counts           map[string]int `json:"counts"` // titles per category
}

// Home builds the Home screen: unfinished items (newest first) and recent additions.
func (e *Engine) Home(ctx context.Context, limit int) (HomeView, error) {
	if limit <= 0 {
		limit = 20
	}
	v := HomeView{ContinueWatching: []ContinueItem{}, Counts: map[string]int{}}
	progress, err := e.store.RecentProgress(ctx, limit)
	if err != nil {
		return v, err
	}
	episodes := map[int64][]db.Episode{}
	for _, p := range progress {
		item, ok, err := e.continueItem(ctx, p, episodes)
		if err != nil {
			return v, err
		}
		if ok {
			v.ContinueWatching = append(v.ContinueWatching, item)
		}
	}
	if v.RecentlyAdded, err = e.RecentlyAdded(ctx, limit); err != nil {
		return v, err
	}
	if v.RecentlyAdded, err = e.withPreviews(ctx, v.RecentlyAdded); err != nil {
		return v, err
	}
	counts, err := e.store.CountByCategory(ctx)
	if err != nil {
		return v, err
	}
	for c, n := range counts {
		v.Counts[string(c)] = n
	}
	return v, nil
}

func (e *Engine) continueItem(ctx context.Context, p db.Progress, episodes map[int64][]db.Episode) (ContinueItem, bool, error) {
	item := ContinueItem{Key: p.Key, TitleID: p.TitleID, PositionMS: p.PositionMS, DurationMS: p.DurationMS}
	if p.TitleID == 0 {
		// A Tutorials video or a stream: no library title, so name it after the file.
		item.Title = strings.TrimSuffix(filepath.Base(p.Key), filepath.Ext(p.Key))
		if !strings.Contains(p.Key, "://") && !strings.HasPrefix(p.Key, "magnet:") {
			item.Category = string(db.Tutorials)
		}
		return item, true, nil
	}
	t, err := e.store.Title(ctx, p.TitleID)
	if errors.Is(err, db.ErrNotFound) {
		return item, false, nil // the title was removed with its folder
	}
	if err != nil {
		return item, false, err
	}
	item.Title, item.Category, item.Kind, item.Year = t.Name, string(t.Category), t.Kind, t.Year
	item.Overview, item.Genres, item.PosterURL, item.BackdropURL = t.Overview, t.Genres, t.PosterURL, t.BackdropURL
	item.StillURL = t.BackdropURL
	if f, err := e.store.FileByPath(ctx, p.Key); err == nil && f.Episode > 0 {
		item.Season, item.Episode = f.Season, f.Episode
		eps, ok := episodes[t.ID]
		if !ok {
			if eps, err = e.store.Episodes(ctx, t.ID); err != nil {
				return item, false, err
			}
			episodes[t.ID] = eps
		}
		for _, ep := range eps {
			if ep.Season == f.Season && ep.Episode == f.Episode {
				item.EpisodeName = ep.Name
				if ep.StillURL != "" {
					item.StillURL = ep.StillURL
				}
				break
			}
		}
	}
	return item, true, nil
}

// EpisodeRef points at one episode file with its progress.
type EpisodeRef struct {
	Key        string `json:"key"`
	Season     int    `json:"season"`
	Episode    int    `json:"episode"`
	Name       string `json:"name,omitempty"`
	StillURL   string `json:"stillUrl,omitempty"`
	PositionMS int64  `json:"positionMs"`
	DurationMS int64  `json:"durationMs"`
}

// ShowProgress is one card on the Series screen.
type ShowProgress struct {
	db.Title
	Next    *EpisodeRef `json:"next,omitempty"` // nil when every episode on the drive is watched
	Season  int         `json:"progressSeason"` // the season Next belongs to (or the last one)
	Watched int         `json:"watched"`        // watched episodes on the drive, in that season
	Total   int         `json:"total"`          // episodes on the drive, in that season
}

// SeriesView splits shows into the ones in progress (most recent first) and the rest.
type SeriesView struct {
	InProgress []ShowProgress `json:"inProgress"`
	All        []ShowProgress `json:"all"`
}

// Series builds the Series screen: for each show, the next episode and season progress.
func (e *Engine) Series(ctx context.Context) (SeriesView, error) {
	v := SeriesView{InProgress: []ShowProgress{}, All: []ShowProgress{}}
	shows, err := e.store.ListTitles(ctx, db.TitleFilter{Category: db.TVShows})
	if err != nil {
		return v, err
	}
	type started struct {
		sp   ShowProgress
		last int64
	}
	var inProgress []started
	for _, t := range shows {
		sp, last, err := e.showProgress(ctx, t)
		if err != nil {
			return v, err
		}
		v.All = append(v.All, sp)
		if last > 0 && sp.Next != nil {
			inProgress = append(inProgress, started{sp, last})
		}
	}
	sort.SliceStable(inProgress, func(i, j int) bool { return inProgress[i].last > inProgress[j].last })
	for _, s := range inProgress {
		v.InProgress = append(v.InProgress, s.sp)
	}
	return v, nil
}

// showProgress finds where the viewer is in a show. The most recently watched episode
// decides: unfinished, it's the next one to resume; finished, the next episode after it is.
// A show never started begins at its first episode. lastWatched is that newest progress
// update (0 = never started).
func (e *Engine) showProgress(ctx context.Context, t db.Title) (ShowProgress, int64, error) {
	sp := ShowProgress{Title: t}
	files, err := e.store.Files(ctx, t.ID)
	if err != nil {
		return sp, 0, err
	}
	eps, err := e.store.Episodes(ctx, t.ID)
	if err != nil {
		return sp, 0, err
	}
	meta := map[[2]int]db.Episode{}
	for _, ep := range eps {
		meta[[2]int{ep.Season, ep.Episode}] = ep
	}
	// Episode files in order (Files sorts by season, episode); specials (season 0) last.
	var episodes []db.File
	for _, f := range files {
		if f.Episode > 0 && f.Season > 0 {
			episodes = append(episodes, f)
		}
	}
	if len(episodes) == 0 {
		return sp, 0, nil
	}
	progress := make([]db.Progress, len(episodes))
	latest := -1
	for i, f := range episodes {
		p, err := e.store.GetProgress(ctx, f.Path)
		if err != nil && !errors.Is(err, db.ErrNotFound) {
			return sp, 0, err
		}
		progress[i] = p
		if p.UpdatedAt > 0 && (latest < 0 || p.UpdatedAt > progress[latest].UpdatedAt) {
			latest = i
		}
	}
	done := func(i int) bool {
		p := progress[i]
		return p.DurationMS > 0 && float64(p.PositionMS) >= watchedFraction*float64(p.DurationMS)
	}
	next := 0
	if latest >= 0 {
		next = latest
		if done(latest) {
			next = latest + 1
		}
	}
	if next < len(episodes) {
		f, p := episodes[next], progress[next]
		m := meta[[2]int{f.Season, f.Episode}]
		sp.Next = &EpisodeRef{Key: f.Path, Season: f.Season, Episode: f.Episode, Name: m.Name, StillURL: m.StillURL,
			PositionMS: p.PositionMS, DurationMS: p.DurationMS}
		sp.Season = f.Season
	} else {
		sp.Season = episodes[len(episodes)-1].Season
	}
	for i, f := range episodes {
		if f.Season == sp.Season {
			sp.Total++
			if done(i) {
				sp.Watched++
			}
		}
	}
	var last int64
	if latest >= 0 {
		last = progress[latest].UpdatedAt
	}
	return sp, last, nil
}

// Library views. The user's Movies folder mixes films and concert films, so the Concerts
// screen shows the CONCERTS category plus MOVIES titles that are concert films (see
// isConcertFilm), and the Movies screen leaves those out.
const (
	ViewMovies   = "movies"
	ViewConcerts = "concerts"
	ViewSeries   = "series"
)

// liveWords mark a live recording in a title, for concerts TMDB tags poorly or not at all.
var liveWords = []string{"live", "unplugged", "concert", "tour", "festival", "rock in rio", "montreux"}

func hasLiveWord(name string) bool {
	n := " " + strings.ToLower(name) + " "
	for _, w := range liveWords {
		if strings.Contains(n, " "+w+" ") || strings.Contains(n, " "+w+":") || strings.Contains(n, "@ "+w) {
			return true
		}
	}
	return false
}

// isConcertFilm reports a concert film. Every genre must be Music, Documentary or TV Movie
// (or none at all, for titles TMDB tags poorly or that aren't matched yet), and either
// Music is one of them or the name says it's live ("Live at…", "MTV Unplugged",
// "… Festival"). A drama about music ("Almost Famous": Drama, Music) stays a movie.
func isConcertFilm(t db.Title) bool {
	music := false
	for _, g := range t.Genres {
		switch strings.ToLower(g) {
		case "music":
			music = true
		case "documentary", "tv movie":
		default:
			return false
		}
	}
	return music || hasLiveWord(t.Name)
}

// withPreviews fills PreviewPath on titles without a poster, so the TV can show a frame
// from the video instead of an empty card. One query per such title; there are few.
func (e *Engine) withPreviews(ctx context.Context, ts []db.Title) ([]db.Title, error) {
	for i := range ts {
		if ts[i].PosterURL != "" {
			continue
		}
		files, err := e.store.Files(ctx, ts[i].ID)
		if err != nil {
			return ts, err
		}
		for _, f := range files {
			if f.Kind == "" || f.Kind == "file" { // a disc folder or ISO has no single video to grab from
				ts[i].PreviewPath = f.Path
				break
			}
		}
	}
	return ts, nil
}

// sectionOf is the screen a title belongs to (see LibraryView).
func sectionOf(t db.Title) string {
	switch {
	case t.Category == db.TVShows:
		return ViewSeries
	case t.Category == db.Concerts || (t.Category == db.Movies && isConcertFilm(t)):
		return ViewConcerts
	case t.Category == db.Tutorials:
		return "tutorials"
	}
	return ViewMovies
}

// LibraryView lists the titles of a screen ("movies", "concerts" or "series"), A–Z.
func (e *Engine) LibraryView(ctx context.Context, view string) ([]db.Title, error) {
	out := []db.Title{}
	switch view {
	case ViewSeries:
		ts, err := e.store.ListTitles(ctx, db.TitleFilter{Category: db.TVShows})
		if err != nil {
			return nil, err
		}
		return e.withPreviews(ctx, append(out, ts...))
	case ViewMovies, ViewConcerts:
	default:
		return nil, fmt.Errorf("unknown view %q", view)
	}
	movies, err := e.store.ListTitles(ctx, db.TitleFilter{Category: db.Movies})
	if err != nil {
		return nil, err
	}
	for _, t := range movies {
		if isConcertFilm(t) == (view == ViewConcerts) {
			out = append(out, t)
		}
	}
	if view == ViewConcerts {
		concerts, err := e.store.ListTitles(ctx, db.TitleFilter{Category: db.Concerts})
		if err != nil {
			return nil, err
		}
		out = append(out, concerts...)
		sort.SliceStable(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	}
	return e.withPreviews(ctx, out)
}

// DiscoverItem is a TMDB result plus whether it's already in the library.
type DiscoverItem struct {
	metadata.SearchResult
	LibraryTitleID int64 `json:"libraryTitleId,omitempty"` // set when the title is on the drive
}

func (e *Engine) markLibrary(ctx context.Context, res []metadata.SearchResult) ([]DiscoverItem, error) {
	owned := map[string]int64{}
	all, err := e.store.ListTitles(ctx, db.TitleFilter{})
	if err != nil {
		return nil, err
	}
	for _, t := range all {
		if t.TMDBID > 0 {
			owned[fmt.Sprintf("%s/%d", t.Kind, t.TMDBID)] = t.ID
		}
	}
	out := make([]DiscoverItem, 0, len(res))
	for _, r := range res {
		out = append(out, DiscoverItem{SearchResult: r, LibraryTitleID: owned[fmt.Sprintf("%s/%d", r.Kind, r.ID)]})
	}
	return out, nil
}

// DiscoverTrending is Trending with "on your drive" marks.
func (e *Engine) DiscoverTrending(ctx context.Context, kind string) ([]DiscoverItem, error) {
	res, err := e.Trending(ctx, kind)
	if err != nil {
		return nil, err
	}
	return e.markLibrary(ctx, res)
}

// DiscoverSearch is SearchTMDB with "on your drive" marks.
func (e *Engine) DiscoverSearch(ctx context.Context, query, kind string) ([]DiscoverItem, error) {
	res, err := e.SearchTMDB(ctx, query, kind)
	if err != nil {
		return nil, err
	}
	return e.markLibrary(ctx, res)
}
