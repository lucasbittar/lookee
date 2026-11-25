package engine

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"

	"github.com/lucasbittar/lookee/core/db"
)

// fakeTMDB answers the handful of endpoints a scan needs and counts requests.
func fakeTMDB(t *testing.T) (*httptest.Server, *atomic.Int32) {
	var hits atomic.Int32
	routes := map[string]string{
		"/search/movie?The Matrix": `{"results":[{"id":603,"title":"The Matrix","release_date":"1999-03-30","vote_count":25000}]}`,
		"/search/movie?Heat":       `{"results":[{"id":949,"title":"Heat","release_date":"1995-12-15"}]}`,
		"/search/tv?Breaking Bad":  `{"results":[{"id":1396,"name":"Breaking Bad","first_air_date":"2008-01-20"}]}`,
		"/movie/603":               `{"id":603,"title":"The Matrix","release_date":"1999-03-30","runtime":136,"credits":{"cast":[{"name":"Keanu Reeves","character":"Neo"}]}}`,
		"/movie/949":               `{"id":949,"title":"Heat","release_date":"1995-12-15","credits":{"cast":[]}}`,
		"/tv/1396":                 `{"id":1396,"name":"Breaking Bad","first_air_date":"2008-01-20","credits":{"cast":[]}}`,
		"/tv/1396/season/1":        `{"episodes":[{"season_number":1,"episode_number":1,"name":"Pilot"},{"season_number":1,"episode_number":2,"name":"Cat's in the Bag..."}]}`,
		"/tv/1396/season/2":        `{"episodes":[{"season_number":2,"episode_number":1,"name":"Seven Thirty-Seven"}]}`,
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		key := r.URL.Path
		if q := r.URL.Query().Get("query"); q != "" {
			key += "?" + q
		}
		body, ok := routes[key]
		if !ok {
			body = `{"results":[]}`
		}
		w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return srv, &hits
}

func touch(t *testing.T, path string) {
	t.Helper()
	os.MkdirAll(filepath.Dir(path), 0o755)
	if err := os.WriteFile(path, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestScanMatchAndFix(t *testing.T) {
	ctx := context.Background()
	srv, hits := fakeTMDB(t)
	tmp := t.TempDir()
	e, err := Open(Options{DBPath: filepath.Join(tmp, "db", "lookee.db"), CacheDir: filepath.Join(tmp, "cache"), TMDBBaseURL: srv.URL})
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()

	movies := filepath.Join(tmp, "Movies")
	tv := filepath.Join(tmp, "TV")
	touch(t, filepath.Join(movies, "The.Matrix.1999.1080p.mkv"))
	touch(t, filepath.Join(movies, "Heat.1995.mkv"))
	touch(t, filepath.Join(movies, "Totally.Unknown.Home.Video.2011.mkv"))
	touch(t, filepath.Join(tv, "Breaking Bad", "Season 1", "Breaking.Bad.S01E01.mkv"))
	touch(t, filepath.Join(tv, "Breaking Bad", "Season 1", "Breaking.Bad.S01E02.mkv"))

	if _, err := e.AddRoot(ctx, movies, db.Movies); err != nil {
		t.Fatal(err)
	}
	if _, err := e.AddRoot(ctx, tv, db.TVShows); err != nil {
		t.Fatal(err)
	}

	// 1. No API key: index is built, titles wait.
	rep, err := e.Scan(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !rep.NeedsAPIKey || hits.Load() != 0 {
		t.Fatalf("without a key: %+v, %d requests", rep, hits.Load())
	}

	// 2. With a key: pending titles get matched.
	e.SetSetting(ctx, KeyTMDBAPIKey, "k")
	var events int
	rep, err = e.Scan(ctx, func(ScanEvent) { events++ })
	if err != nil {
		t.Fatal(err)
	}
	if rep.Matched != 3 || rep.Unmatched != 1 || rep.MatchError != "" || events == 0 {
		t.Fatalf("report = %+v events=%d", rep, events)
	}

	// 3. Rescan with no changes makes no TMDB requests.
	before := hits.Load()
	if _, err := e.Scan(ctx, nil); err != nil {
		t.Fatal(err)
	}
	if hits.Load() != before {
		t.Fatalf("idle rescan made %d TMDB requests", hits.Load()-before)
	}

	// Details for the show include its episodes.
	shows, _ := e.ListTitles(ctx, db.TVShows, 0, 0)
	if len(shows) != 1 {
		t.Fatalf("shows = %+v", shows)
	}
	d, err := e.Details(ctx, shows[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	if d.Name != "Breaking Bad" || len(d.Episodes) != 2 || len(d.Files) != 2 {
		t.Fatalf("details = %+v", d)
	}

	// 4. A new season appears: only that season is fetched.
	touch(t, filepath.Join(tv, "Breaking Bad", "Season 2", "Breaking.Bad.S02E01.mkv"))
	if _, err := e.Scan(ctx, nil); err != nil {
		t.Fatal(err)
	}
	d, _ = e.Details(ctx, shows[0].ID)
	if len(d.Episodes) != 3 || len(d.Files) != 3 {
		t.Fatalf("after new season: %d episodes, %d files", len(d.Episodes), len(d.Files))
	}

	// 5. Fix Match: point the unknown movie at Heat. It merges with the existing Heat
	// title and stays locked across rescans.
	unknown, _ := e.store.ListTitles(ctx, db.TitleFilter{Statuses: []db.MatchStatus{db.StatusUnmatched}})
	if len(unknown) != 1 {
		t.Fatalf("unmatched = %+v", unknown)
	}
	id, err := e.Rebind(ctx, unknown[0].ID, db.KindMovie, 603)
	if err != nil {
		t.Fatal(err)
	}
	fixed, _ := e.Details(ctx, id)
	if fixed.Name != "The Matrix" || fixed.Status != db.StatusManual || len(fixed.Files) != 2 {
		t.Fatalf("fixed = %+v", fixed.Title)
	}
	if _, err := e.Scan(ctx, nil); err != nil {
		t.Fatal(err)
	}
	if again, _ := e.Details(ctx, id); again.Status != db.StatusManual {
		t.Fatalf("rescan overwrote manual match: %+v", again.Title)
	}
}

func TestSettingsNeverExposeSecrets(t *testing.T) {
	ctx := context.Background()
	e, err := Open(Options{DBPath: filepath.Join(t.TempDir(), "x.db")})
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	if err := e.SetSetting(ctx, "bogus", "x"); err == nil {
		t.Fatal("unknown key accepted")
	}
	e.SetSetting(ctx, KeyTMDBAPIKey, "  abcdef123456  ")
	v, _ := e.Settings(ctx)
	if !v.TMDBKeySet || v.OpenSubtitlesKeySet || v.TMDBLanguage != "en-US" {
		t.Fatalf("view = %+v", v)
	}
	masked, _ := e.GetSetting(ctx, KeyTMDBAPIKey, false)
	if masked != "********3456" {
		t.Fatalf("masked = %q", masked)
	}
	raw, _ := e.GetSetting(ctx, KeyTMDBAPIKey, true)
	if raw != "abcdef123456" {
		t.Fatalf("raw = %q (should be trimmed)", raw)
	}
}

func TestScanRejectsConcurrentRuns(t *testing.T) {
	e, err := Open(Options{DBPath: filepath.Join(t.TempDir(), "x.db")})
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	e.scanning.Store(true)
	if _, err := e.Scan(context.Background(), nil); err != ErrScanRunning {
		t.Fatalf("err = %v", err)
	}
}
