package engine

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lucasbittar/lookee/core/db"
)

func mkfile(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("v"), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestHomeAndSeriesViews(t *testing.T) {
	ctx := context.Background()
	e := openEngine(t)
	lib := t.TempDir()
	shows, movies, lessons := filepath.Join(lib, "TV Shows"), filepath.Join(lib, "Movies"), filepath.Join(lib, "Lessons")
	ep := func(n string) string {
		return filepath.Join(shows, "Doctor Who", "Season 1", "Doctor.Who.S01E0"+n+".mkv")
	}
	for _, n := range []string{"1", "2", "3"} {
		mkfile(t, ep(n))
	}
	mkfile(t, filepath.Join(shows, "Friends", "Season 5", "Friends.S05E01.mkv"))
	heat := filepath.Join(movies, "Heat (1995)", "Heat.1995.mkv")
	mkfile(t, heat)
	lesson := filepath.Join(lessons, "Blues", "10 Vibrato.mp4")
	mkfile(t, lesson)
	for dir, cat := range map[string]db.Category{shows: db.TVShows, movies: db.Movies, lessons: db.Tutorials} {
		if _, err := e.AddRoot(ctx, dir, cat); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := e.Scan(ctx, nil); err != nil {
		t.Fatal(err)
	}

	f1, err := e.store.FileByPath(ctx, ep("1"))
	if err != nil {
		t.Fatal(err)
	}
	who := f1.TitleID
	hf, _ := e.store.FileByPath(ctx, heat)
	// E1 finished, E2 half watched; Heat started; a lesson started.
	must := func(err error) {
		if err != nil {
			t.Fatal(err)
		}
	}
	must(e.SaveProgress(ctx, ep("1"), who, 2_600_000, 2_700_000))
	must(e.SaveProgress(ctx, ep("2"), who, 1_000_000, 2_700_000))
	must(e.SaveProgress(ctx, heat, hf.TitleID, 60_000, 10_000_000))
	must(e.SaveProgress(ctx, lesson, 0, 30_000, 600_000))

	home, err := e.Home(ctx, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(home.ContinueWatching) != 3 {
		t.Fatalf("continue watching = %+v", home.ContinueWatching)
	}
	byKey := map[string]ContinueItem{}
	for _, it := range home.ContinueWatching {
		byKey[it.Key] = it
	}
	if it := byKey[ep("2")]; it.Season != 1 || it.Episode != 2 || it.Title != "Doctor Who" || it.Category != string(db.TVShows) {
		t.Errorf("episode item = %+v", it)
	}
	if it := byKey[lesson]; it.Title != "10 Vibrato" || it.Category != string(db.Tutorials) || it.TitleID != 0 {
		t.Errorf("lesson item = %+v", it)
	}
	if _, ok := byKey[ep("1")]; ok {
		t.Error("a finished episode must not be in Continue Watching")
	}
	if home.Counts["TV_SHOWS"] != 2 || home.Counts["MOVIES"] != 1 || len(home.RecentlyAdded) != 3 {
		t.Errorf("counts = %v, recent = %d", home.Counts, len(home.RecentlyAdded))
	}

	series, err := e.Series(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(series.All) != 2 || len(series.InProgress) != 1 {
		t.Fatalf("series = %+v", series)
	}
	dw := series.InProgress[0]
	if dw.Name != "Doctor Who" || dw.Next == nil || dw.Next.Episode != 2 || dw.Next.PositionMS != 1_000_000 ||
		dw.Season != 1 || dw.Watched != 1 || dw.Total != 3 {
		t.Errorf("doctor who = %+v next=%+v", dw, dw.Next)
	}

	// Skipping ahead: E3 finished most recently, so the show is done on this drive even
	// though E2 is still half watched. "Next" follows the latest viewing, not the first gap.
	if err := e.store.SaveProgress(ctx, db.Progress{Key: ep("3"), TitleID: who, PositionMS: 2_650_000, DurationMS: 2_700_000}); err != nil {
		t.Fatal(err)
	}
	if _, err := e.store.DB().ExecContext(ctx, `UPDATE playback_progress SET updated_at = updated_at + 10 WHERE key = ?`, ep("3")); err != nil {
		t.Fatal(err)
	}
	series, _ = e.Series(ctx)
	for _, sp := range series.All {
		if sp.Name == "Doctor Who" && (sp.Next != nil || sp.Watched != 2) {
			t.Errorf("after finishing E3: next=%+v watched=%d", sp.Next, sp.Watched)
		}
	}
}

func TestLibraryViewSplitsConcertFilms(t *testing.T) {
	ctx := context.Background()
	e := openEngine(t)
	movies := filepath.Join(t.TempDir(), "Movies")
	mkfile(t, filepath.Join(movies, "Heat (1995)", "Heat.mkv"))
	mkfile(t, filepath.Join(movies, "History of the Eagles (2013)", "Eagles.mkv"))
	mkfile(t, filepath.Join(movies, "Almost Famous (2000)", "Almost.Famous.mkv"))
	mkfile(t, filepath.Join(movies, "Stevie Ray Vaughan - Live at the El Mocambo", "srv.mkv"))
	mkfile(t, filepath.Join(movies, "Pearl Jam MTV Unplugged (1992)", "pj.mkv"))
	if _, err := e.AddRoot(ctx, movies, db.Movies); err != nil {
		t.Fatal(err)
	}
	if _, err := e.Scan(ctx, nil); err != nil {
		t.Fatal(err)
	}
	all, _ := e.ListTitles(ctx, db.Movies, 0, 0)
	for _, ti := range all {
		genres := []string{"Crime"}
		switch ti.Name {
		case "History of the Eagles":
			genres = []string{"Documentary", "Music"}
		case "Almost Famous":
			genres = []string{"Drama", "Music"} // a film about music, not a concert
		case "Pearl Jam MTV Unplugged":
			genres = []string{"Music", "Documentary", "TV Movie"}
		case "Stevie Ray Vaughan - Live at the El Mocambo":
			genres = nil // unmatched: no genres, but the name says it's live
		}
		if _, err := e.store.ApplyMetadata(ctx, ti.ID, db.StatusMatched, db.TitleMetadata{TMDBID: ti.ID + 100, Kind: db.KindMovie, Name: ti.Name, Genres: genres}); err != nil {
			t.Fatal(err)
		}
	}
	names := func(view string) []string {
		ts, err := e.LibraryView(ctx, view)
		if err != nil {
			t.Fatal(err)
		}
		var out []string
		for _, ti := range ts {
			out = append(out, ti.Name)
		}
		return out
	}
	if got := names(ViewMovies); len(got) != 2 || got[0] != "Almost Famous" || got[1] != "Heat" {
		t.Errorf("movies = %v", got)
	}
	if got := names(ViewConcerts); len(got) != 3 || got[0] != "History of the Eagles" || got[1] != "Pearl Jam MTV Unplugged" {
		t.Errorf("concerts = %v", got)
	}
	// No posters in this test library: every title points at a video to grab a frame from.
	ts, _ := e.LibraryView(ctx, ViewMovies)
	for _, ti := range ts {
		if !strings.HasSuffix(ti.PreviewPath, ".mkv") {
			t.Errorf("%s: previewPath = %q", ti.Name, ti.PreviewPath)
		}
	}
	if _, err := e.LibraryView(ctx, "nope"); err == nil {
		t.Error("unknown view should fail")
	}
}
