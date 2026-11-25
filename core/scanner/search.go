package scanner

import (
	"context"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode"

	"golang.org/x/text/runes"
	"golang.org/x/text/transform"
	"golang.org/x/text/unicode/norm"
)

// Hit is a folder or video found by Search. Folder is the parent folder relative to the
// searched root ("" at the root), shown under the name so equal names can be told apart.
type Hit struct {
	Entry
	Folder string `json:"folder"`
}

// Search walks root for folders and videos whose path below root contains every word of
// query, ignoring case, accents and the usual separators ("solo 3" finds "Solo/Lesson 03.mp4").
// Hits whose own name matches come first, then hits matched through their folders; each group
// in natural path order. At most limit hits; a cancelled ctx stops the walk.
func Search(ctx context.Context, root, query string, limit int) ([]Hit, error) {
	words := strings.Fields(Fold(query))
	if len(words) == 0 {
		return []Hit{}, nil
	}
	type scored struct {
		hit    Hit
		byName bool
	}
	var found []scored
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			// An unreadable folder is skipped, not fatal: the rest of the drive still counts.
			if d != nil && d.IsDir() && path != root {
				return filepath.SkipDir
			}
			return nil
		}
		if ctxErr := ctx.Err(); ctxErr != nil {
			return ctxErr
		}
		if path == root {
			return nil
		}
		name := d.Name()
		if strings.HasPrefix(name, ".") {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if d.IsDir() && skipDirs[strings.ToLower(name)] && !isExtrasDir(name) {
			return filepath.SkipDir
		}
		if !d.IsDir() && !IsVideo(name) {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		if !containsAll(Fold(rel), words) {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		folder := filepath.Dir(rel)
		if folder == "." {
			folder = ""
		}
		e := Entry{Name: name, Path: path, IsDir: d.IsDir(), ModTime: info.ModTime().Unix()}
		if !d.IsDir() {
			e.Size = info.Size()
			e.Subtitle = hasSidecar(path)
		}
		found = append(found, scored{Hit{Entry: e, Folder: folder}, containsAll(Fold(name), words)})
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.SliceStable(found, func(i, j int) bool {
		if found[i].byName != found[j].byName {
			return found[i].byName
		}
		return NaturalLess(filepath.Join(found[i].hit.Folder, found[i].hit.Name), filepath.Join(found[j].hit.Folder, found[j].hit.Name))
	})
	if len(found) > limit {
		found = found[:limit]
	}
	out := make([]Hit, len(found))
	for i, f := range found {
		out[i] = f.hit
	}
	return out, nil
}

// Fold lowers s, strips accents and turns separators into spaces: "Solo_Ação-03" → "solo acao 03".
func Fold(s string) string {
	t := transform.Chain(norm.NFD, runes.Remove(runes.In(unicode.Mn)), norm.NFC)
	folded, _, err := transform.String(t, s)
	if err != nil {
		folded = s
	}
	return strings.Map(func(r rune) rune {
		switch r {
		case '.', '_', '-', '/', '\\', '(', ')', '[', ']':
			return ' '
		}
		return unicode.ToLower(r)
	}, folded)
}

func containsAll(s string, words []string) bool {
	for _, w := range words {
		if !strings.Contains(s, w) {
			return false
		}
	}
	return true
}

func hasSidecar(video string) bool {
	stem := strings.TrimSuffix(video, filepath.Ext(video))
	for _, ext := range subtitleExts {
		for _, candidate := range []string{stem + ext, stem + strings.ToUpper(ext)} {
			if _, err := os.Stat(candidate); err == nil {
				return true
			}
		}
	}
	return false
}
