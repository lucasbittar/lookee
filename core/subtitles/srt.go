// Package subtitles finds sidecar subtitles, searches and downloads from OpenSubtitles,
// converts subtitle text to UTF-8, and shifts timing for sync correction.
package subtitles

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/encoding/charmap"
)

// ErrUnsupportedFormat is returned for formats that cannot be shifted (e.g. .ass).
var ErrUnsupportedFormat = errors.New("subtitle format not supported for shifting")

// ToUTF8 returns b as UTF-8. Text that is not valid UTF-8 is assumed to be Windows-1252,
// the usual encoding of Portuguese and Western European .srt files. A UTF-8 BOM is removed.
func ToUTF8(b []byte) []byte {
	b = bytes.TrimPrefix(b, []byte("\xef\xbb\xbf"))
	if utf8.Valid(b) {
		return b
	}
	out, err := charmap.Windows1252.NewDecoder().Bytes(b)
	if err != nil {
		return b
	}
	return out
}

// srt/vtt cue timing: "00:01:02,345 --> 00:01:04,000" (VTT uses '.' and may omit hours).
var reCueTime = regexp.MustCompile(`(?m)((?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{3})(\s*-->\s*)((?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{3})`)

// Shift moves every cue in an .srt or .vtt text by offsetMS (negative = earlier).
// Cues that would start before zero are clamped to zero.
func Shift(text []byte, offsetMS int64) []byte {
	return reCueTime.ReplaceAllFunc(text, func(m []byte) []byte {
		g := reCueTime.FindSubmatch(m)
		return []byte(shiftStamp(string(g[1]), offsetMS) + string(g[2]) + shiftStamp(string(g[3]), offsetMS))
	})
}

func shiftStamp(s string, offsetMS int64) string {
	sep := ","
	if strings.Contains(s, ".") {
		sep = "."
	}
	parts := strings.FieldsFunc(s, func(r rune) bool { return r == ':' || r == ',' || r == '.' })
	var h, m, sec, ms int64
	switch len(parts) {
	case 4:
		h, m, sec, ms = num(parts[0]), num(parts[1]), num(parts[2]), num(parts[3])
	case 3:
		m, sec, ms = num(parts[0]), num(parts[1]), num(parts[2])
	}
	total := max(((h*60+m)*60+sec)*1000+ms+offsetMS, 0)
	return fmt.Sprintf("%02d:%02d:%02d%s%03d", total/3600000, total/60000%60, total/1000%60, sep, total%1000)
}

func num(s string) int64 {
	n, _ := strconv.ParseInt(s, 10, 64)
	return n
}

// PrepareFile writes a UTF-8 copy of a subtitle into dir, shifted by offsetMS, and returns
// its path. The player always loads these copies, never the originals, so the user's
// files are never modified.
func PrepareFile(src, dir string, offsetMS int64) (string, error) {
	ext := strings.ToLower(filepath.Ext(src))
	if offsetMS != 0 && ext != ".srt" && ext != ".vtt" {
		return "", ErrUnsupportedFormat
	}
	b, err := os.ReadFile(src)
	if err != nil {
		return "", err
	}
	b = ToUTF8(b)
	if offsetMS != 0 {
		b = Shift(b, offsetMS)
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	stem := strings.TrimSuffix(filepath.Base(src), filepath.Ext(src))
	name := stem + ext
	if offsetMS != 0 {
		name = fmt.Sprintf("%s.shift%+d%s", stem, offsetMS, ext)
	}
	out := filepath.Join(dir, name)
	if err := os.WriteFile(out, b, 0o644); err != nil {
		return "", err
	}
	return out, nil
}
