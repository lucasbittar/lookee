package scanner

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode"
)

// Entry is one item in a folder listing.
type Entry struct {
	Name     string `json:"name"`
	Path     string `json:"path"`
	IsDir    bool   `json:"isDir"`
	Size     int64  `json:"size,omitempty"`
	ModTime  int64  `json:"modTime"`
	Subtitle bool   `json:"hasSubtitle,omitempty"` // a sidecar .srt/.vtt/.ass sits next to the video
}

var subtitleExts = []string{".srt", ".vtt", ".ass", ".ssa"}

// Browse lists sub-folders and video files of dir, folders first, in natural order
// ("Lesson 2" before "Lesson 10"). Hidden files and system folders are omitted.
func Browse(dir string) ([]Entry, error) {
	items, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	names := map[string]bool{}
	for _, it := range items {
		names[strings.ToLower(it.Name())] = true
	}
	out := []Entry{}
	for _, it := range items {
		name := it.Name()
		if strings.HasPrefix(name, ".") {
			continue
		}
		info, err := it.Info()
		if err != nil {
			continue
		}
		full := filepath.Join(dir, name)
		if it.IsDir() {
			if skipDirs[strings.ToLower(name)] && !isExtrasDir(name) {
				continue
			}
			out = append(out, Entry{Name: name, Path: full, IsDir: true, ModTime: info.ModTime().Unix()})
			continue
		}
		if !IsVideo(name) {
			continue
		}
		e := Entry{Name: name, Path: full, Size: info.Size(), ModTime: info.ModTime().Unix()}
		stem := strings.ToLower(strings.TrimSuffix(name, filepath.Ext(name)))
		for _, ext := range subtitleExts {
			if names[stem+ext] {
				e.Subtitle = true
				break
			}
		}
		out = append(out, e)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].IsDir != out[j].IsDir {
			return out[i].IsDir
		}
		return NaturalLess(out[i].Name, out[j].Name)
	})
	return out, nil
}

// isExtrasDir: bonus-material folders are hidden from the library scan but useful when browsing.
func isExtrasDir(name string) bool {
	switch strings.ToLower(name) {
	case "extras", "featurettes", "behind the scenes", "deleted scenes", "trailers":
		return true
	}
	return false
}

// NaturalLess compares strings case-insensitively, treating digit runs as numbers.
func NaturalLess(a, b string) bool {
	ar, br := []rune(strings.ToLower(a)), []rune(strings.ToLower(b))
	i, j := 0, 0
	for i < len(ar) && j < len(br) {
		if unicode.IsDigit(ar[i]) && unicode.IsDigit(br[j]) {
			si := i
			for i < len(ar) && unicode.IsDigit(ar[i]) {
				i++
			}
			sj := j
			for j < len(br) && unicode.IsDigit(br[j]) {
				j++
			}
			na := strings.TrimLeft(string(ar[si:i]), "0")
			nb := strings.TrimLeft(string(br[sj:j]), "0")
			if len(na) != len(nb) {
				return len(na) < len(nb)
			}
			if na != nb {
				return na < nb
			}
			continue
		}
		if ar[i] != br[j] {
			return ar[i] < br[j]
		}
		i++
		j++
	}
	return len(ar)-i < len(br)-j
}
