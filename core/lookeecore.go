// Package lookeecore is the gomobile-exported API of the lookee Go core.
//
// Contract (see CLAUDE.md §3): exported functions take primitives and return
// (string, error) where the string is a JSON-encoded DTO. Long-running work reports
// progress through small callback interfaces implemented in Kotlin.
//
// Phase 1 exports only lifecycle and settings, enough to prove the core (including
// the pure-Go SQLite driver) compiles and runs on Android. The full surface is Phase 3.
package lookeecore

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"sync"

	"github.com/lucasbittar/lookee/core/engine"
)

// Version of the core library.
const Version = "0.1.0"

var (
	mu  sync.Mutex
	eng *engine.Engine
)

// ErrNotInitialized is returned when a call is made before InitEngine.
var ErrNotInitialized = errors.New("lookee engine not initialized: call InitEngine first")

// InitEngine opens the database in filesDir and uses cacheDir for temporary data.
// usbPath is the preferred external storage path for the torrent cache ("" if none).
// Calling it again is a no-op.
func InitEngine(filesDir, cacheDir, usbPath string) error {
	mu.Lock()
	defer mu.Unlock()
	if eng != nil {
		return nil
	}
	e, err := engine.Open(engine.Options{
		DBPath:   filepath.Join(filesDir, "lookee.db"),
		CacheDir: cacheDir,
	})
	if err != nil {
		return err
	}
	eng = e
	return nil
}

// Shutdown closes the engine.
func Shutdown() error {
	mu.Lock()
	defer mu.Unlock()
	if eng == nil {
		return nil
	}
	err := eng.Close()
	eng = nil
	return err
}

// CoreVersion returns the library version.
func CoreVersion() string { return Version }

// GetSettings returns the settings view as JSON (secrets reported only as set/unset).
func GetSettings() (string, error) {
	e, err := get()
	if err != nil {
		return "", err
	}
	v, err := e.Settings(context.Background())
	if err != nil {
		return "", err
	}
	return toJSON(v)
}

// SetSetting stores a setting value.
func SetSetting(key, value string) error {
	e, err := get()
	if err != nil {
		return err
	}
	return e.SetSetting(context.Background(), key, value)
}

func get() (*engine.Engine, error) {
	mu.Lock()
	defer mu.Unlock()
	if eng == nil {
		return nil, ErrNotInitialized
	}
	return eng, nil
}

func toJSON(v any) (string, error) {
	b, err := json.Marshal(v)
	return string(b), err
}
