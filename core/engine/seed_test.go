package engine

import (
	"context"
	"strings"
	"testing"
)

func TestSeedSettings(t *testing.T) {
	ctx := context.Background()
	e := openEngine(t)

	// The user already chose a language: the file must not override it.
	if err := e.SetSetting(ctx, KeyTMDBLanguage, "pt-BR"); err != nil {
		t.Fatal(err)
	}
	file := []byte(`{"eztv_url": ["https://a.example", " https://b.example "], "yts_url": "", "tmdb_language": "en-US"}`)
	n, err := e.SeedSettings(ctx, file)
	if err != nil || n != 1 {
		t.Fatalf("first seed: n=%d err=%v", n, err)
	}
	if v, _ := e.GetSetting(ctx, KeyEZTVURL, false); v != "https://a.example\nhttps://b.example" {
		t.Errorf("eztv = %q", v)
	}
	if v, _ := e.GetSetting(ctx, KeyTMDBLanguage, false); v != "pt-BR" {
		t.Errorf("user value overwritten: %q", v)
	}
	if s, _ := e.Settings(ctx); s.TorrentSources != 1 {
		t.Errorf("two EZTV mirrors should count as one source, got %d", s.TorrentSources)
	}

	// Cleared by the user, then the same file again: stays cleared.
	if err := e.SetSetting(ctx, KeyEZTVURL, ""); err != nil {
		t.Fatal(err)
	}
	if n, err := e.SeedSettings(ctx, file); err != nil || n != 0 {
		t.Fatalf("same file re-applied: n=%d err=%v", n, err)
	}

	// API keys can't be preset, and bad shapes are rejected.
	for _, bad := range []string{`{"tmdb_api_key":"x"}`, `{"eztv_url": 3}`, `{"eztv_url": [1]}`, `nope`} {
		if _, err := e.SeedSettings(ctx, []byte(bad)); err == nil {
			t.Errorf("%s: expected an error", bad)
		}
	}
	for _, line := range e.LogTail(50) {
		if strings.Contains(line, "a.example") {
			t.Fatalf("source URL leaked into the log: %s", line)
		}
	}
}
