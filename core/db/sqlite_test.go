package db

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
)

func openTest(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func TestMigrateIsIdempotent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "test.db")
	for i := range 2 {
		s, err := Open(path)
		if err != nil {
			t.Fatalf("open #%d: %v", i, err)
		}
		s.Close()
	}
}

func TestSettings(t *testing.T) {
	ctx := context.Background()
	s := openTest(t)
	if v, _ := s.GetSetting(ctx, "missing"); v != "" {
		t.Fatalf("missing key = %q", v)
	}
	must(t, s.SetSetting(ctx, "tmdb_api_key", "abc"))
	must(t, s.SetSetting(ctx, "tmdb_api_key", "def"))
	if v, _ := s.GetSetting(ctx, "tmdb_api_key"); v != "def" {
		t.Fatalf("got %q", v)
	}
	must(t, s.SetSetting(ctx, "tmdb_api_key", ""))
	all, _ := s.AllSettings(ctx)
	if len(all) != 0 {
		t.Fatalf("empty value should delete, got %v", all)
	}
}

func TestRootsAndFiles(t *testing.T) {
	ctx := context.Background()
	s := openTest(t)

	if _, err := s.AddRoot(ctx, "/x", "BOGUS"); err == nil {
		t.Fatal("invalid category accepted")
	}
	r, err := s.AddRoot(ctx, "/media/movies", Movies)
	must(t, err)
	r2, err := s.AddRoot(ctx, "/media/movies", Concerts)
	must(t, err)
	if r2.ID != r.ID || r2.Category != Concerts {
		t.Fatalf("re-adding a root should update category: %+v", r2)
	}

	var titleID int64
	must(t, s.Tx(ctx, func(tx *Tx) error {
		var err error
		titleID, err = tx.FindOrCreateTitle(ctx, Concerts, "pink floyd pulse", 1995, "Pink Floyd - Pulse")
		if err != nil {
			return err
		}
		again, err := tx.FindOrCreateTitle(ctx, Concerts, "pink floyd pulse", 1995, "ignored")
		if err != nil {
			return err
		}
		if again != titleID {
			t.Errorf("FindOrCreateTitle created a duplicate")
		}
		_, err = tx.InsertFile(ctx, File{RootID: r.ID, Path: "/media/movies/pulse.mkv", Size: 10, MTime: 1, TitleID: titleID})
		return err
	}))

	stats, err := s.FileStats(ctx, r.ID)
	must(t, err)
	if len(stats) != 1 || stats["/media/movies/pulse.mkv"].Size != 10 {
		t.Fatalf("stats = %+v", stats)
	}

	must(t, s.RemoveRoot(ctx, r.ID))
	if _, err := s.Title(ctx, titleID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("orphan title should be removed with its root, err=%v", err)
	}
}

func TestApplyMetadataMergesDuplicateMatches(t *testing.T) {
	ctx := context.Background()
	s := openTest(t)
	r, _ := s.AddRoot(ctx, "/tv", TVShows)

	// Two differently-named folders of the same show.
	var a, b int64
	must(t, s.Tx(ctx, func(tx *Tx) error {
		a, _ = tx.FindOrCreateTitle(ctx, TVShows, "the office us", 0, "The Office US")
		b, _ = tx.FindOrCreateTitle(ctx, TVShows, "the office", 0, "The Office")
		tx.InsertFile(ctx, File{RootID: r.ID, Path: "/tv/a/S01E01.mkv", TitleID: a, Season: 1, Episode: 1})
		_, err := tx.InsertFile(ctx, File{RootID: r.ID, Path: "/tv/b/S02E01.mkv", TitleID: b, Season: 2, Episode: 1})
		return err
	}))

	meta := TitleMetadata{TMDBID: 2316, Kind: KindTV, Name: "The Office", Genres: []string{"Comedy"},
		Cast: []CastMember{{Order: 0, Name: "Steve Carell", Character: "Michael Scott"}}}
	idA, err := s.ApplyMetadata(ctx, a, StatusMatched, meta)
	must(t, err)
	idB, err := s.ApplyMetadata(ctx, b, StatusMatched, meta)
	must(t, err)
	if idA != idB {
		t.Fatalf("same TMDB show should merge: %d vs %d", idA, idB)
	}
	files, _ := s.Files(ctx, idA)
	if len(files) != 2 {
		t.Fatalf("merged title should own both files, got %d", len(files))
	}
	tt, _ := s.Title(ctx, idA)
	if tt.Name != "The Office" || tt.Status != StatusMatched || len(tt.Genres) != 1 {
		t.Fatalf("title = %+v", tt)
	}
	cast, _ := s.Cast(ctx, idA)
	if len(cast) != 1 || cast[0].Character != "Michael Scott" {
		t.Fatalf("cast = %+v", cast)
	}
	missing, _ := s.SeasonsMissingEpisodes(ctx, idA)
	if len(missing) != 2 {
		t.Fatalf("seasons missing = %v", missing)
	}
	must(t, s.ReplaceSeason(ctx, idA, 1, []Episode{{Season: 1, Episode: 1, Name: "Pilot"}}))
	missing, _ = s.SeasonsMissingEpisodes(ctx, idA)
	if len(missing) != 1 || missing[0] != 2 {
		t.Fatalf("seasons missing after fill = %v", missing)
	}
}

func TestProgress(t *testing.T) {
	ctx := context.Background()
	s := openTest(t)
	must(t, s.SaveProgress(ctx, Progress{Key: "a", PositionMS: 1000, DurationMS: 10000}))
	must(t, s.SaveProgress(ctx, Progress{Key: "done", PositionMS: 9900, DurationMS: 10000}))
	must(t, s.SaveProgress(ctx, Progress{Key: "a", PositionMS: 2000, DurationMS: 10000}))
	p, err := s.GetProgress(ctx, "a")
	must(t, err)
	if p.PositionMS != 2000 {
		t.Fatalf("progress = %+v", p)
	}
	recent, _ := s.RecentProgress(ctx, 10)
	if len(recent) != 1 || recent[0].Key != "a" {
		t.Fatalf("finished items should be excluded: %+v", recent)
	}
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}
