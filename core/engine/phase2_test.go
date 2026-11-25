package engine

import (
	"context"
	"errors"
	"path/filepath"
	"strings"
	"testing"
)

func openEngine(t *testing.T) *Engine {
	t.Helper()
	dir := t.TempDir()
	e, err := Open(Options{DBPath: filepath.Join(dir, "db", "lookee.db"), CacheDir: filepath.Join(dir, "cache")})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { e.Close() })
	return e
}

func TestNoBuiltInTorrentSources(t *testing.T) {
	ctx := context.Background()
	e := openEngine(t)
	if _, err := e.SearchTorrentsText(ctx, "movie", "x"); !errors.Is(err, ErrNoTorrentSources) {
		t.Fatalf("err = %v", err)
	}
	e.SetSetting(ctx, KeyYTSURL, "https://yts.example")
	e.SetSetting(ctx, KeyTorznabURLs, "# comment\nhttp://jackett/api?apikey=1\n\nhttp://prowlarr/api?apikey=2\n")
	v, _ := e.Settings(ctx)
	if v.TorrentSources != 3 {
		t.Fatalf("sources = %d", v.TorrentSources)
	}
}

func TestPhoneFieldsHideSecrets(t *testing.T) {
	ctx := context.Background()
	e := openEngine(t)
	e.SetSetting(ctx, KeyTMDBAPIKey, "SECRET")
	e.SetSetting(ctx, KeyTorznabURLs, "http://jackett/api?apikey=SECRET2")
	fields, err := settingsStore{e}.Fields(ctx)
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for _, f := range fields {
		seen[f.Key] = true
		if f.Secret && !f.IsSet && (f.Key == KeyTMDBAPIKey || f.Key == KeyTorznabURLs) {
			t.Errorf("%s should report set", f.Key)
		}
	}
	for k := range knownKeys {
		if !seen[k] {
			t.Errorf("setting %s missing from the phone page", k)
		}
	}
	// The remote package strips secret values before rendering; here we only check that
	// every key the engine treats as secret is flagged as such.
	for _, f := range fields {
		if secretKeys[f.Key] != f.Secret {
			t.Errorf("%s secret flag = %v", f.Key, f.Secret)
		}
	}

	setup, err := e.StartRemoteSetup("127.0.0.1", nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(setup.PIN) != 6 || !strings.HasPrefix(setup.URL, "http://127.0.0.1:") {
		t.Fatalf("setup = %+v", setup)
	}
	e.StopRemoteSetup()
}

func TestSourceLabelHidesTrackers(t *testing.T) {
	m := "magnet:?xt=urn:btih:abc&dn=x&tr=udp://tracker/secret"
	if got := sourceLabel(m); got != "magnet:?xt=urn:btih:abc" {
		t.Fatalf("got %q", got)
	}
}
