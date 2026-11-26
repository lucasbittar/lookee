package engine

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"testing"
)

// A stream has no library title: the TMDB id comes from Discover, and the episode from the
// torrent's file name.
func TestSubtitleSearchForStream(t *testing.T) {
	var got url.Values
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.Query()
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[]}`))
	}))
	t.Cleanup(srv.Close)
	tmp := t.TempDir()
	e, err := Open(Options{DBPath: filepath.Join(tmp, "lookee.db"), CacheDir: tmp, OpenSubtitlesBaseURL: srv.URL})
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	ctx := context.Background()
	if err := e.SetSetting(ctx, KeyOpenSubtitlesAPIKey, "test-key"); err != nil {
		t.Fatal(err)
	}

	if _, err := e.SearchSubtitles(ctx, SubtitleRequest{TMDBID: 1668, Kind: "tv", FileName: "Friends.S05E08.1080p.WEB.mkv"}); err != nil {
		t.Fatal(err)
	}
	if got.Get("parent_tmdb_id") != "1668" || got.Get("season_number") != "5" || got.Get("episode_number") != "8" {
		t.Fatalf("episode query = %v", got)
	}

	if _, err := e.SearchSubtitles(ctx, SubtitleRequest{TMDBID: 603, Kind: "movie", FileName: "The.Matrix.1999.mkv"}); err != nil {
		t.Fatal(err)
	}
	if got.Get("tmdb_id") != "603" || got.Get("season_number") != "" {
		t.Fatalf("movie query = %v", got)
	}
}
