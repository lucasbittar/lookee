package disc

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"github.com/lucasbittar/lookee/core/scanner"
)

// ErrUnsupported: a disc lookee can't play yet (a Blu-ray image, or nothing playable found).
var ErrUnsupported = errors.New("disc: unsupported")

// Title is a disc's main title: the parts to join, in order.
type Title struct {
	Kind  string `json:"kind"` // "dvd", "bluray" or "iso"
	Name  string `json:"name"` // file name for the server: ".vob" (MPEG-PS) or ".ts"
	Parts []Part `json:"parts"`
	// M2TS: the parts are Blu-ray .m2ts and are served as plain TS (see m2tsAsTS).
	M2TS bool `json:"m2ts"`
	// Seconds is the playlist's length when a Blu-ray playlist chose the title (0 otherwise).
	Seconds float64 `json:"seconds,omitempty"`
}

// Size is the joined length in bytes.
func (t *Title) Size() int64 {
	var n int64
	for _, p := range t.Parts {
		n += p.Size
	}
	return n
}

// Find works out the main title of the disc rip at path. kind is the scanner's kind for
// it ("dvd", "bluray", "iso"); empty guesses from the path.
func Find(path, kind string) (*Title, error) {
	if kind == "" {
		kind = guessKind(path)
	}
	switch kind {
	case scanner.KindDVD:
		return dvdFolder(path)
	case scanner.KindBluray:
		return bluray(path)
	case scanner.KindISO:
		return isoImage(path)
	}
	return nil, fmt.Errorf("%w: %q is not a disc rip", ErrUnsupported, path)
}

func guessKind(path string) string {
	if strings.EqualFold(filepath.Ext(path), ".iso") {
		return scanner.KindISO
	}
	if strings.EqualFold(filepath.Base(path), "BDMV") {
		return scanner.KindBluray
	}
	entries, _ := os.ReadDir(path)
	for _, e := range entries {
		if scanner.IsBlurayMarker(e.Name()) || strings.EqualFold(e.Name(), "BDMV") {
			return scanner.KindBluray
		}
	}
	return scanner.KindDVD
}

// ---- DVD ----

// file is a name and its bytes, on disk or inside an ISO.
type file struct {
	name string
	part Part
}

var vtsName = regexp.MustCompile(`(?i)^VTS_(\d\d)_(\d)\.VOB$`)

// dvdMain picks the main title set: the VTS_xx group whose .VOBs (parts 1–9; part 0 is
// the menu) add up to the most bytes, joined in part order. Loose VOBs without VTS names
// are joined in natural order, leaving out the VIDEO_TS.VOB menu.
func dvdMain(files []file) []Part {
	groups := map[string][]file{}
	sizes := map[string]int64{}
	var loose []file
	for _, f := range files {
		if !strings.EqualFold(filepath.Ext(f.name), ".vob") {
			continue
		}
		if m := vtsName.FindStringSubmatch(f.name); m != nil {
			if m[2] == "0" {
				continue
			}
			groups[m[1]] = append(groups[m[1]], f)
			sizes[m[1]] += f.part.Size
			continue
		}
		if !strings.EqualFold(f.name, "VIDEO_TS.VOB") {
			loose = append(loose, f)
		}
	}
	best := ""
	for g := range groups {
		if best == "" || sizes[g] > sizes[best] || (sizes[g] == sizes[best] && g < best) {
			best = g
		}
	}
	chosen := loose
	if best != "" {
		chosen = groups[best]
	}
	sort.Slice(chosen, func(i, j int) bool { return scanner.NaturalLess(chosen[i].name, chosen[j].name) })
	parts := make([]Part, len(chosen))
	for i, f := range chosen {
		parts[i] = f.part
	}
	return parts
}

func dvdFolder(dir string) (*Title, error) {
	// The indexed path is the folder holding the VOBs; accept its parent too (VIDEO_TS inside).
	if sub := filepath.Join(dir, "VIDEO_TS"); isDir(sub) {
		dir = sub
	}
	files, err := listDir(dir)
	if err != nil {
		return nil, err
	}
	parts := dvdMain(files)
	if len(parts) == 0 {
		return nil, fmt.Errorf("%w: no title VOBs in %s", ErrUnsupported, dir)
	}
	return &Title{Kind: scanner.KindDVD, Name: "title.vob", Parts: parts}, nil
}

// ---- Blu-ray ----

func bluray(path string) (*Title, error) {
	playlistDir, streamDir := path, path // flattened copies keep everything in one folder
	if isDir(filepath.Join(path, "PLAYLIST")) || isDir(filepath.Join(path, "STREAM")) {
		playlistDir, streamDir = filepath.Join(path, "PLAYLIST"), filepath.Join(path, "STREAM")
	} else if isDir(filepath.Join(path, "BDMV")) {
		playlistDir, streamDir = filepath.Join(path, "BDMV", "PLAYLIST"), filepath.Join(path, "BDMV", "STREAM")
	}
	streams, err := listDir(streamDir)
	if err != nil {
		return nil, err
	}
	clips := map[string]Part{} // "00007" -> its .m2ts
	var largest *Part
	for _, f := range streams {
		if !strings.EqualFold(filepath.Ext(f.name), ".m2ts") {
			continue
		}
		clips[clipNumber(f.name)] = f.part
		if largest == nil || f.part.Size > largest.Size {
			p := f.part
			largest = &p
		}
	}
	if largest == nil {
		return nil, fmt.Errorf("%w: no .m2ts in %s", ErrUnsupported, streamDir)
	}
	t := &Title{Kind: scanner.KindBluray, Name: "title.ts", M2TS: isM2TS(largest.Path)}
	// The longest playlist whose clips are all here is the main movie.
	if lists, err := listDir(playlistDir); err == nil {
		var bestSecs float64
		var best []Part
		for _, f := range lists {
			if !strings.EqualFold(filepath.Ext(f.name), ".mpls") {
				continue
			}
			data, err := os.ReadFile(f.part.Path)
			if err != nil {
				continue
			}
			items, secs, err := parseMPLS(data)
			if err != nil || secs <= bestSecs || !plausible(items, secs) {
				continue
			}
			parts := make([]Part, 0, len(items))
			for _, it := range items {
				p, ok := clips[it]
				if !ok {
					parts = nil
					break
				}
				parts = append(parts, p)
			}
			if len(parts) > 0 {
				bestSecs, best = secs, parts
			}
		}
		if best != nil {
			t.Parts, t.Seconds = best, bestSecs
			return t, nil
		}
	}
	t.Parts = []Part{*largest}
	return t, nil
}

// maxPlaylist: no concert or film runs six hours. Longer playlists are decoys.
const maxPlaylist = 6 * 3600

// plausible rejects decoy playlists, which copy-protected discs ship by the hundred: they
// loop the same clips (Norah Jones' disc picked a 501-item, 8-hour one) or run absurdly long.
// A real main title plays each clip once.
func plausible(clips []string, seconds float64) bool {
	if seconds > maxPlaylist {
		return false
	}
	seen := make(map[string]bool, len(clips))
	for _, c := range clips {
		if seen[c] {
			return false
		}
		seen[c] = true
	}
	return true
}

var clipDigits = regexp.MustCompile(`(\d{5})\.(?i:m2ts)$`)

// clipNumber is the clip id in a stream file name, ignoring copy prefixes ("(2) 00007.m2ts").
func clipNumber(name string) string {
	if m := clipDigits.FindStringSubmatch(name); m != nil {
		return m[1]
	}
	return strings.TrimSuffix(name, filepath.Ext(name))
}

// parseMPLS reads a Blu-ray playlist: its clips in play order and its length in seconds.
func parseMPLS(b []byte) (clips []string, seconds float64, err error) {
	if len(b) < 20 || string(b[:4]) != "MPLS" {
		return nil, 0, errors.New("not an MPLS playlist")
	}
	start := int(binary.BigEndian.Uint32(b[8:12]))
	if start+10 > len(b) {
		return nil, 0, errors.New("short playlist")
	}
	count := int(binary.BigEndian.Uint16(b[start+6 : start+8]))
	pos := start + 10
	var ticks uint64
	for i := 0; i < count; i++ {
		if pos+22 > len(b) {
			return nil, 0, errors.New("truncated play item")
		}
		length := int(binary.BigEndian.Uint16(b[pos : pos+2]))
		item := b[pos+2:]
		clips = append(clips, string(item[0:5]))
		in := binary.BigEndian.Uint32(item[12:16])
		out := binary.BigEndian.Uint32(item[16:20])
		if out > in {
			ticks += uint64(out - in)
		}
		pos += 2 + length
	}
	return clips, float64(ticks) / 45000, nil
}

// isM2TS: 192-byte packets (sync byte at offset 4) rather than plain 188-byte TS.
func isM2TS(path string) bool {
	f, err := os.Open(path)
	if err != nil {
		return true
	}
	defer f.Close()
	head := make([]byte, 2*m2tsPacket)
	if n, _ := f.ReadAt(head, 0); n < len(head) {
		return true
	}
	if head[0] == 0x47 && head[tsPacket] == 0x47 {
		return false
	}
	return head[4] == 0x47 && head[m2tsPacket+4] == 0x47
}

// ---- helpers ----

func isDir(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.IsDir()
}

func listDir(dir string) ([]file, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	var out []file
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, file{e.Name(), Part{Path: filepath.Join(dir, e.Name()), Size: info.Size()}})
	}
	return out, nil
}

// ---- ISO 9660 ----

const sector = 2048

// isoImage finds a DVD's VIDEO_TS inside an ISO 9660 image and serves its main title straight
// from the image (each VOB is one contiguous extent). Blu-ray images are UDF-only: not yet.
func isoImage(path string) (*Title, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	pvd := make([]byte, sector)
	if _, err := f.ReadAt(pvd, 16*sector); err != nil || pvd[0] != 1 || !bytes.Equal(pvd[1:6], []byte("CD001")) {
		return nil, fmt.Errorf("%w: Blu-ray disc images can't play yet", ErrUnsupported)
	}
	root := pvd[156 : 156+34]
	videoTS, err := isoFind(f, extent(root), "VIDEO_TS", true)
	if err != nil {
		return nil, fmt.Errorf("%w: no VIDEO_TS in this image (Blu-ray images can't play yet)", ErrUnsupported)
	}
	entries, err := isoList(f, videoTS)
	if err != nil {
		return nil, err
	}
	var files []file
	for _, e := range entries {
		if !e.dir {
			files = append(files, file{e.name, Part{Path: path, Offset: e.lba * sector, Size: e.size}})
		}
	}
	parts := dvdMain(files)
	if len(parts) == 0 {
		return nil, fmt.Errorf("%w: no title VOBs in this image", ErrUnsupported)
	}
	return &Title{Kind: scanner.KindISO, Name: "title.vob", Parts: parts}, nil
}

type isoEntry struct {
	name string
	lba  int64
	size int64
	dir  bool
}

func extent(record []byte) isoEntry {
	return isoEntry{lba: int64(binary.LittleEndian.Uint32(record[2:6])), size: int64(binary.LittleEndian.Uint32(record[10:14])), dir: record[25]&2 != 0}
}

func isoList(f *os.File, dir isoEntry) ([]isoEntry, error) {
	data := make([]byte, dir.size)
	if _, err := f.ReadAt(data, dir.lba*sector); err != nil {
		return nil, err
	}
	var out []isoEntry
	for pos := 0; pos < len(data); {
		n := int(data[pos])
		if n == 0 { // records never cross a sector: skip to the next one
			pos = (pos/sector + 1) * sector
			continue
		}
		if pos+n > len(data) || n < 34 {
			break
		}
		rec := data[pos : pos+n]
		nameLen := int(rec[32])
		if 33+nameLen <= len(rec) {
			name := string(rec[33 : 33+nameLen])
			if name != "\x00" && name != "\x01" { // "." and ".."
				e := extent(rec)
				e.name = strings.TrimSuffix(strings.SplitN(name, ";", 2)[0], ".")
				out = append(out, e)
			}
		}
		pos += n
	}
	return out, nil
}

func isoFind(f *os.File, dir isoEntry, name string, wantDir bool) (isoEntry, error) {
	entries, err := isoList(f, dir)
	if err != nil {
		return isoEntry{}, err
	}
	for _, e := range entries {
		if e.dir == wantDir && strings.EqualFold(e.name, name) {
			return e, nil
		}
	}
	return isoEntry{}, os.ErrNotExist
}

// String is a short description for the log: "dvd: 4 parts, 3.9 GB".
func (t *Title) String() string {
	s := t.Kind + ": " + strconv.Itoa(len(t.Parts)) + " part"
	if len(t.Parts) != 1 {
		s += "s"
	}
	s += fmt.Sprintf(", %.1f GB", float64(t.Size())/1e9)
	if t.Seconds > 0 {
		s += fmt.Sprintf(", %.0f min playlist", t.Seconds/60)
	}
	if t.M2TS {
		s += ", m2ts→ts"
	}
	return s
}
