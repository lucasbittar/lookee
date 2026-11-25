package engine

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
)

// seedableKeys are the settings a private defaults file may provide. API keys are left
// out on purpose: they go through the phone page, never into a file baked into an APK.
var seedableKeys = map[string]bool{
	KeyYTSURL: true, KeyEZTVURL: true, KeyTorznabURLs: true, KeyStremioAddons: true,
	KeyTMDBLanguage: true, KeySubtitleLanguages: true,
}

// keyDefaultsSeeded records the hash of the last defaults file applied (internal, not a
// user setting), so a file is applied once and a source the user later clears stays cleared.
const keyDefaultsSeeded = "defaults_seeded"

// SeedSettings applies a defaults file: a JSON object of setting key → string, or →
// list of strings (joined one per line, e.g. mirrors). Only empty settings are filled, so
// nothing the user set is overwritten, and the same file is applied only once. It returns
// how many settings were filled.
func (e *Engine) SeedSettings(ctx context.Context, raw []byte) (int, error) {
	var in map[string]any
	if err := json.Unmarshal(raw, &in); err != nil {
		return 0, fmt.Errorf("defaults file: %w", err)
	}
	sum := sha256.Sum256(raw)
	hash := hex.EncodeToString(sum[:])
	if prev, err := e.store.GetSetting(ctx, keyDefaultsSeeded); err != nil {
		return 0, err
	} else if prev == hash {
		return 0, nil
	}

	values := map[string]string{}
	for k, v := range in {
		if !seedableKeys[k] {
			return 0, fmt.Errorf("defaults file: %q can't be preset (API keys go through the phone page)", k)
		}
		switch t := v.(type) {
		case string:
			values[k] = strings.TrimSpace(t)
		case []any:
			var lines []string
			for _, item := range t {
				s, ok := item.(string)
				if !ok {
					return 0, fmt.Errorf("defaults file: %q must be a string or a list of strings", k)
				}
				if s = strings.TrimSpace(s); s != "" {
					lines = append(lines, s)
				}
			}
			values[k] = strings.Join(lines, "\n")
		default:
			return 0, fmt.Errorf("defaults file: %q must be a string or a list of strings", k)
		}
	}

	applied := 0
	for k, v := range values {
		if v == "" {
			continue
		}
		cur, err := e.store.GetSetting(ctx, k)
		if err != nil {
			return applied, err
		}
		if cur != "" {
			continue // the user's own value wins
		}
		if err := e.SetSetting(ctx, k, v); err != nil {
			return applied, err
		}
		applied++
	}
	if err := e.store.SetSetting(ctx, keyDefaultsSeeded, hash); err != nil {
		return applied, err
	}
	// Counts only: source URLs can carry tokens and never go to the log.
	e.log.Info("defaults file applied", "filled", applied, "keys", len(values))
	return applied, nil
}
