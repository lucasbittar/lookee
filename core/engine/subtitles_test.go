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

// Search pages answer typed searches only; a title's own sources never include them.
func TestSearchPagesOnlyInTextSearch(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`<a href="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Big.Buck.Bunny.1080p">m</a>`))
	}))
	t.Cleanup(srv.Close)
	tmp := t.TempDir()
	e, err := Open(Options{DBPath: filepath.Join(tmp, "lookee.db"), CacheDir: tmp})
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	ctx := context.Background()
	if err := e.SetSetting(ctx, KeySearchPages, srv.URL+"/s/{query}"); err != nil {
		t.Fatal(err)
	}
	res, err := e.SearchTorrentsText(ctx, "", "big buck")
	if err != nil || len(res.Results) != 1 || res.Results[0].Provider != "127.0.0.1" {
		t.Fatalf("text search = %+v, %v", res, err)
	}
	all, _ := e.store.AllSettings(ctx)
	if n := len(providersFrom(all)); n != 0 {
		t.Fatalf("title search providers = %d, want none", n)
	}
	if v, _ := e.Settings(ctx); v.TorrentSources != 1 {
		t.Fatalf("sources = %d", v.TorrentSources)
	}
}
