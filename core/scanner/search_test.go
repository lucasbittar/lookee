package scanner

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestSearch(t *testing.T) {
	root := t.TempDir()
	for _, f := range []string{
		"Solo Ação/Lesson 03.mp4",
		"Solo Ação/Lesson 03.srt",
		"Solo Ação/Lesson 10.mp4",
		"Rhythm/solo_intro.mkv",
		"Rhythm/notes.txt",
		".hidden/solo.mp4",
		"Rhythm/Lesson 3 backing.mp4",
	} {
		p := filepath.Join(root, f)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	names := func(hits []Hit) []string {
		out := make([]string, len(hits))
		for i, h := range hits {
			out[i] = filepath.Join(h.Folder, h.Name)
		}
		return out
	}
	check := func(query string, want ...string) {
		t.Helper()
		hits, err := Search(context.Background(), root, query, 50)
		if err != nil {
			t.Fatal(err)
		}
		got := names(hits)
		if len(got) != len(want) {
			t.Fatalf("Search(%q) = %v, want %v", query, got, want)
		}
		for i := range want {
			if got[i] != want[i] {
				t.Fatalf("Search(%q) = %v, want %v", query, got, want)
			}
		}
	}

	// Name matches first (the folder, then a file named solo), then files matched through their folder.
	check("solo", "Rhythm/solo_intro.mkv", "Solo Ação", "Solo Ação/Lesson 03.mp4", "Solo Ação/Lesson 10.mp4")
	// Accents and case don't matter; words can span folder and file.
	check("ACAO lesson 03", "Solo Ação/Lesson 03.mp4")
	check("rhythm 3", "Rhythm/Lesson 3 backing.mp4")
	check("   ")

	hits, _ := Search(context.Background(), root, "acao 03", 50)
	if len(hits) != 1 || !hits[0].Subtitle {
		t.Fatalf("sidecar not detected: %+v", hits)
	}
	if hits, _ := Search(context.Background(), root, "lesson", 2); len(hits) != 2 {
		t.Fatalf("limit not applied: %d hits", len(hits))
	}
}
