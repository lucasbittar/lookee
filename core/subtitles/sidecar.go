package subtitles

import (
	"encoding/binary"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Extensions of subtitle files the player can load.
var Extensions = map[string]bool{".srt": true, ".vtt": true, ".ass": true, ".ssa": true}

// Sidecar is a subtitle file found next to a video.
type Sidecar struct {
	Path     string `json:"path"`
	Name     string `json:"name"`
	Language string `json:"language,omitempty"` // best guess from the filename, e.g. "pt-BR"
	Format   string `json:"format"`             // "srt", "vtt", "ass", "ssa"
}

// languageTags maps filename tags to language codes.
var languageTags = map[string]string{
	"pt-br": "pt-BR", "ptbr": "pt-BR", "pob": "pt-BR", "pb": "pt-BR", "brazilian": "pt-BR", "portuguese-br": "pt-BR",
	"pt": "pt", "por": "pt", "portuguese": "pt", "pt-pt": "pt",
	"en": "en", "eng": "en", "english": "en",
	"es": "es", "spa": "es", "spanish": "es",
	"fr": "fr", "fre": "fr", "fra": "fr", "french": "fr",
	"de": "de", "ger": "de", "deu": "de", "german": "de",
	"it": "it", "ita": "it", "italian": "it",
}

// FindSidecars returns subtitles that belong to videoPath: files in the same folder whose
// name starts with the video's name ("Movie.srt", "Movie.pt-BR.srt"), plus everything in a
// "Subs"/"Subtitles"/"Legendas" folder beside it. When the folder holds a single video, any
// subtitle in that folder is accepted.
func FindSidecars(videoPath string) ([]Sidecar, error) {
	dir := filepath.Dir(videoPath)
	stem := strings.ToLower(strings.TrimSuffix(filepath.Base(videoPath), filepath.Ext(videoPath)))
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	videos := 0
	for _, e := range entries {
		if !e.IsDir() && isVideoExt(filepath.Ext(e.Name())) {
			videos++
		}
	}
	var out []Sidecar
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() {
			switch strings.ToLower(name) {
			case "subs", "subtitles", "legendas", "sub":
				out = append(out, subsIn(filepath.Join(dir, name))...)
			}
			continue
		}
		ext := strings.ToLower(filepath.Ext(name))
		if !Extensions[ext] || strings.HasPrefix(name, ".") {
			continue
		}
		lower := strings.ToLower(name)
		if videos > 1 && !strings.HasPrefix(lower, stem) {
			continue
		}
		out = append(out, sidecar(filepath.Join(dir, name)))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out, nil
}

func subsIn(dir string) []Sidecar {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []Sidecar
	for _, e := range entries {
		if !e.IsDir() && Extensions[strings.ToLower(filepath.Ext(e.Name()))] && !strings.HasPrefix(e.Name(), ".") {
			out = append(out, sidecar(filepath.Join(dir, e.Name())))
		}
	}
	return out
}

func sidecar(path string) Sidecar {
	name := filepath.Base(path)
	ext := strings.ToLower(filepath.Ext(name))
	s := Sidecar{Path: path, Name: name, Format: strings.TrimPrefix(ext, ".")}
	// Language tag is the last dotted/underscored token before the extension: "Movie.pt-BR.srt",
	// "2_Portuguese.srt", "Movie_eng.srt".
	stem := strings.ToLower(strings.TrimSuffix(name, filepath.Ext(name)))
	tokens := strings.FieldsFunc(stem, func(r rune) bool {
		return r == '.' || r == '_' || r == ' ' || r == '[' || r == ']' || r == '(' || r == ')'
	})
	for i := len(tokens) - 1; i >= 0 && i >= len(tokens)-2; i-- {
		if lang, ok := languageTags[tokens[i]]; ok {
			s.Language = lang
			break
		}
	}
	return s
}

func isVideoExt(ext string) bool {
	switch strings.ToLower(ext) {
	case ".mkv", ".mp4", ".avi", ".mov", ".webm", ".m4v", ".ts", ".m2ts", ".mts":
		return true
	}
	return false
}

// MovieHash computes the OpenSubtitles hash: file size plus the 64-bit little-endian
// word sums of the first and last 64 KiB. Matching by hash finds subtitles synced to
// that exact release.
func MovieHash(r io.ReaderAt, size int64) (string, error) {
	const chunk = 64 << 10
	if size < chunk*2 {
		return "", fmt.Errorf("file too small for a movie hash (%d bytes)", size)
	}
	hash := uint64(size)
	buf := make([]byte, chunk)
	for _, off := range []int64{0, size - chunk} {
		if _, err := r.ReadAt(buf, off); err != nil && err != io.EOF {
			return "", err
		}
		for i := 0; i < chunk; i += 8 {
			hash += binary.LittleEndian.Uint64(buf[i:])
		}
	}
	return fmt.Sprintf("%016x", hash), nil
}

// MovieHashFile hashes a file on disk.
func MovieHashFile(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return "", err
	}
	return MovieHash(f, st.Size())
}
