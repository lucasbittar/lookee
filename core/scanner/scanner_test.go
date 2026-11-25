package scanner

import (
	"context"
	"os"
	"path/filepath"
	"sort"
	"testing"
	"time"

	"github.com/lucasbittar/lookee/core/db"
)

func touch(t *testing.T, path string, size int) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, make([]byte, size), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestIdentify(t *testing.T) {
	root := "/media/tv"
	tests := []struct {
		path string
		cat  db.Category
		want Identity
	}{
		{"/media/tv/Breaking Bad/Season 1/Breaking.Bad.S01E02.mkv", db.TVShows,
			Identity{Parsed{Title: "Breaking Bad", Season: 1, Episode: 2}, "breaking bad"}},
		{"/media/tv/Breaking Bad/Season 2/S02E03.mkv", db.TVShows,
			Identity{Parsed{Title: "Breaking Bad", Season: 2, Episode: 3}, "breaking bad"}},
		{"/media/tv/Breaking Bad/Season 3/05 - Mas.mkv", db.TVShows,
			Identity{Parsed{Title: "Breaking Bad", Season: 3, Episode: 5}, "breaking bad"}},
		{"/media/tv/Doctor Who (2005)/S01/Rose.S01E01.mkv", db.TVShows,
			Identity{Parsed{Title: "Doctor Who", Year: 2005, Season: 1, Episode: 1}, "doctor who"}},
		{"/media/tv/The.Bear.S02.1080p.WEB-DL/The.Bear.S02E04.mkv", db.TVShows,
			Identity{Parsed{Title: "The Bear", Season: 2, Episode: 4}, "the bear"}},
		{"/media/tv/Friends/The Reunion/Friends.the.Reunion.2021.1080p.mkv", db.TVShows,
			Identity{Parsed{Title: "Friends", Year: 0}, "friends"}},
		{"/media/tv/Ken Tamplin Vocal Academy/Volume 1/5 Lip Roll.mp4", db.TVShows,
			Identity{Parsed{Title: "Ken Tamplin Vocal Academy", Season: 1, Episode: 5}, "ken tamplin vocal academy"}},
		{"/media/tv/Bon Jovi - The Crush Tour (2000)/Bon.Jovi.Live.The.Crush.Tour.2000.DVDRip.avi", db.Movies,
			Identity{Parsed{Title: "Bon Jovi - The Crush Tour", Year: 2000}, "bon jovi the crush tour"}},
		{"/media/tv/Marcus King - Four Of a Kind/Marcus King Four Of A Kind July 13th.mp4", db.Movies,
			Identity{Parsed{Title: "Marcus King - Four Of a Kind"}, "marcus king four of a kind"}},
		{"/media/tv/Eagles Live At The Capitol Centre/The.Eagles.Live.Capitol.Center.1977.avi", db.Movies,
			Identity{Parsed{Title: "The Eagles Live Capitol Center", Year: 1977}, "the eagles live capitol center"}},
		{"/media/tv/Season 1/Show.S01E01.mkv", db.TVShows,
			Identity{Parsed{Title: "Show", Season: 1, Episode: 1}, "show"}},
		{"/media/tv/Dark.S01E01.mkv", db.TVShows,
			Identity{Parsed{Title: "Dark", Season: 1, Episode: 1}, "dark"}},
		{"/media/tv/The Godfather (1972)/movie.mkv", db.Movies,
			Identity{Parsed{Title: "The Godfather", Year: 1972}, "the godfather"}},
		{"/media/tv/Heat.1995.mkv", db.Movies,
			Identity{Parsed{Title: "Heat", Year: 1995}, "heat"}},
	}
	for _, tt := range tests {
		got := Identify(root, tt.path, tt.cat)
		if got != tt.want {
			t.Errorf("Identify(%q)\n got  %+v\n want %+v", tt.path, got, tt.want)
		}
	}
}

func TestSyncIncremental(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	store, err := db.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	touch(t, filepath.Join(dir, "Show", "Season 1", "Show.S01E01.mkv"), 10)
	touch(t, filepath.Join(dir, "Show", "Season 1", "Show.S01E02.mkv"), 10)
	touch(t, filepath.Join(dir, "Show", "Season 1", "._Show.S01E02.mkv"), 1) // macOS resource fork
	touch(t, filepath.Join(dir, "Show", "Season 1", "Show.S01E02.srt"), 1)
	touch(t, filepath.Join(dir, "Show", "Season 1", "sample.mkv"), 1)
	touch(t, filepath.Join(dir, "Android", "data", "x.mkv"), 1)
	touch(t, filepath.Join(dir, "Other.S02E01.mp4"), 10)

	root, err := store.AddRoot(ctx, dir, db.TVShows)
	if err != nil {
		t.Fatal(err)
	}

	res, err := Sync(ctx, store, root, nil)
	if err != nil {
		t.Fatal(err)
	}
	if res.Added != 3 || res.Removed != 0 {
		t.Fatalf("first sync = %+v", res)
	}
	titles, _ := store.ListTitles(ctx, db.TitleFilter{Category: db.TVShows})
	var names []string
	for _, tt := range titles {
		names = append(names, tt.Name)
		if tt.Status != db.StatusPending {
			t.Errorf("new title %q should be pending", tt.Name)
		}
	}
	sort.Strings(names)
	if len(names) != 2 || names[0] != "Other" || names[1] != "Show" {
		t.Fatalf("titles = %v", names)
	}

	// Second sync with no changes touches nothing.
	res, _ = Sync(ctx, store, root, nil)
	if res.Added != 0 || res.Updated != 0 || res.Removed != 0 || res.Seen != 3 {
		t.Fatalf("idempotent sync = %+v", res)
	}

	// Modify one, delete one.
	later := time.Now().Add(time.Hour)
	os.Chtimes(filepath.Join(dir, "Show", "Season 1", "Show.S01E01.mkv"), later, later)
	os.Remove(filepath.Join(dir, "Other.S02E01.mp4"))
	res, _ = Sync(ctx, store, root, nil)
	if res.Updated != 1 || res.Removed != 1 {
		t.Fatalf("change sync = %+v", res)
	}
	titles, _ = store.ListTitles(ctx, db.TitleFilter{})
	if len(titles) != 1 {
		t.Fatalf("orphan title not pruned: %+v", titles)
	}

	// Unplugged drive: nothing is pruned, root is marked unavailable.
	gone := root
	gone.Path = filepath.Join(dir, "not-mounted")
	res, err = Sync(ctx, store, gone, nil)
	if err != nil || !res.Unavailable {
		t.Fatalf("unavailable sync = %+v err=%v", res, err)
	}
	if files, _ := store.FileStats(ctx, root.ID); len(files) != 2 {
		t.Fatalf("files pruned for an unmounted root: %d left", len(files))
	}
}

func TestBrowse(t *testing.T) {
	dir := t.TempDir()
	touch(t, filepath.Join(dir, "Lesson 10.mp4"), 1)
	touch(t, filepath.Join(dir, "Lesson 2.mp4"), 1)
	touch(t, filepath.Join(dir, "Lesson 2.srt"), 1)
	touch(t, filepath.Join(dir, "notes.pdf"), 1)
	touch(t, filepath.Join(dir, ".hidden.mp4"), 1)
	touch(t, filepath.Join(dir, "Backing Tracks", "a.mp4"), 1)

	got, err := Browse(dir)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, e := range got {
		names = append(names, e.Name)
	}
	want := []string{"Backing Tracks", "Lesson 2.mp4", "Lesson 10.mp4"}
	if len(names) != len(want) {
		t.Fatalf("got %v want %v", names, want)
	}
	for i := range want {
		if names[i] != want[i] {
			t.Fatalf("got %v want %v", names, want)
		}
	}
	if !got[0].IsDir || !got[1].Subtitle || got[2].Subtitle {
		t.Fatalf("flags wrong: %+v", got)
	}
}

func TestNaturalLess(t *testing.T) {
	in := []string{"part 10", "Part 2", "part 1", "intro", "Part 02b", "part 002"}
	sort.SliceStable(in, func(i, j int) bool { return NaturalLess(in[i], in[j]) })
	want := []string{"intro", "part 1", "Part 2", "part 002", "Part 02b", "part 10"}
	for i := range want {
		if in[i] != want[i] {
			t.Fatalf("got %v want %v", in, want)
		}
	}
}
