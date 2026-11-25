package lookeecore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestBridgeLifecycle(t *testing.T) {
	if _, err := GetSettings(); err == nil || !strings.HasPrefix(err.Error(), "lookee:"+CodeNotInitialized+": ") {
		t.Fatalf("before init: %v", err)
	}
	dir := t.TempDir()
	if err := InitEngine(dir, dir+"/cache", ""); err != nil {
		t.Fatal(err)
	}
	defer Shutdown()
	if err := InitEngine(dir, dir+"/cache", ""); err != nil {
		t.Fatalf("second init should be a no-op: %v", err)
	}
	if err := SetSetting("tmdb_api_key", "secret"); err != nil {
		t.Fatal(err)
	}
	s, err := GetSettings()
	if err != nil {
		t.Fatal(err)
	}
	var v map[string]any
	if err := json.Unmarshal([]byte(s), &v); err != nil {
		t.Fatal(err)
	}
	if v["tmdbKeySet"] != true {
		t.Fatalf("settings = %s", s)
	}
	for _, val := range v {
		if val == "secret" {
			t.Fatalf("secret leaked over the bridge: %s", s)
		}
	}
}

func TestBridgeErrorCodes(t *testing.T) {
	dir := t.TempDir()
	if err := InitEngine(dir, dir+"/cache", ""); err != nil {
		t.Fatal(err)
	}
	defer Shutdown()

	cases := []struct {
		name string
		err  error
		code string
	}{
		{"unknown category", func() error { _, err := AddRoot(dir, "CARTOONS"); return err }(), CodeBadRequest},
		{"missing title", func() error { _, err := GetMediaDetails(999); return err }(), CodeNotFound},
		{"no tmdb key", func() error { _, err := GetTrending("movie"); return err }(), CodeNoTMDBKey},
		{"no subtitle key", func() error { _, err := SearchSubtitles(`{"query":"x"}`); return err }(), CodeNoSubtitleKey},
		{"bad subtitle request", func() error { _, err := SearchSubtitles(`{`); return err }(), CodeBadRequest},
		{"no torrent sources", func() error { _, err := SearchTorrentsText("movie", "x"); return err }(), CodeNoSources},
	}
	for _, c := range cases {
		if c.err == nil || !strings.HasPrefix(c.err.Error(), "lookee:"+c.code+": ") {
			t.Errorf("%s: got %v, want code %s", c.name, c.err, c.code)
		}
	}
	// Unknown setting keys are plain errors without a code.
	if err := SetSetting("nope", "x"); err == nil || strings.HasPrefix(err.Error(), "lookee:") {
		t.Errorf("unknown setting: %v", err)
	}
}

type scanRecorder struct{ ch chan string }

func (r scanRecorder) OnProgress(s string) { r.ch <- s }

func TestBridgeScanAndLibrary(t *testing.T) {
	dir := t.TempDir()
	lib := filepath.Join(dir, "Movies")
	movie := filepath.Join(lib, "Heat (1995)", "Heat.1995.1080p.BluRay.x264.mkv")
	if err := os.MkdirAll(filepath.Dir(movie), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(movie, []byte("video"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := InitEngine(dir, dir+"/cache", dir+"/external"); err != nil {
		t.Fatal(err)
	}
	defer Shutdown()
	if _, err := AddRoot(lib, "MOVIES"); err != nil {
		t.Fatal(err)
	}

	rec := scanRecorder{ch: make(chan string, 64)}
	if err := StartScan(rec); err != nil {
		t.Fatal(err)
	}
	var final ScanUpdate
	timeout := time.After(10 * time.Second)
	for final.Phase == "" || final.Phase == "scanning" || final.Phase == "matching" {
		select {
		case s := <-rec.ch:
			final = ScanUpdate{}
			if err := json.Unmarshal([]byte(s), &final); err != nil {
				t.Fatal(err)
			}
		case <-timeout:
			t.Fatal("scan did not finish")
		}
	}
	if final.Phase != "finished" || final.Report == nil || !final.Report.NeedsAPIKey || len(final.Report.Roots) != 1 || final.Report.Roots[0].Added != 1 {
		t.Fatalf("final update = %+v", final)
	}

	list, err := ListMedia("MOVIES", 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	var titles []struct {
		ID    int64  `json:"id"`
		Title string `json:"title"`
	}
	if err := json.Unmarshal([]byte(list), &titles); err != nil || len(titles) != 1 || titles[0].Title != "Heat" {
		t.Fatalf("list = %s (%v)", list, err)
	}
	if _, err := GetMediaDetails(titles[0].ID); err != nil {
		t.Fatal(err)
	}
	if err := SaveProgress(movie, titles[0].ID, 60_000, 600_000); err != nil {
		t.Fatal(err)
	}
	cw, err := GetContinueWatching(10)
	if err != nil || !strings.Contains(cw, `"positionMs":60000`) {
		t.Fatalf("continue watching = %s (%v)", cw, err)
	}
	browse, err := BrowseFolder(lib)
	if err != nil || !strings.Contains(browse, `"isDir":true`) {
		t.Fatalf("browse = %s (%v)", browse, err)
	}
	if s, err := GetStreamStats(); err != nil || s != `{"state":"stopped","downloaded":0,"speedBps":0,"peers":0,"seeders":0,"bufferPercent":0,"elapsedSeconds":0,"fileIndex":0}` {
		t.Fatalf("idle stream stats = %s (%v)", s, err)
	}
	if err := ClearStreamCache(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "external", "streams")); err != nil {
		t.Fatalf("stream cache should live under the external dir: %v", err)
	}
}

func TestListVolumes(t *testing.T) {
	base := t.TempDir()
	for _, d := range []string{"emulated/0", "self/primary", "1234-ABCD", ".hidden"} {
		if err := os.MkdirAll(filepath.Join(base, d), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	vols := listVolumes(base)
	if len(vols) != 2 {
		t.Fatalf("volumes = %+v", vols)
	}
	if vols[0].Label != "Internal storage" || vols[0].Removable || vols[0].TotalBytes <= 0 {
		t.Errorf("internal = %+v", vols[0])
	}
	if vols[1].Path != filepath.Join(base, "1234-ABCD") || !vols[1].Removable {
		t.Errorf("usb = %+v", vols[1])
	}
	if got := listVolumes(filepath.Join(base, "missing")); len(got) != 0 {
		t.Errorf("missing base = %+v", got)
	}
}
