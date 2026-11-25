package lookeecore

import (
	"encoding/json"
	"testing"
)

func TestBridgeLifecycle(t *testing.T) {
	if _, err := GetSettings(); err != ErrNotInitialized {
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
