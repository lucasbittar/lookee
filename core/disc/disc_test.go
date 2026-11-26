package disc

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func write(t *testing.T, path string, data []byte) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

func readAll(t *testing.T, title *Title) []byte {
	t.Helper()
	s, err := title.Open()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	b, err := io.ReadAll(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestDVDPicksLargestTitleSet(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "Concert", "VIDEO_TS")
	write(t, filepath.Join(dir, "VIDEO_TS.VOB"), bytes.Repeat([]byte("m"), 50))
	write(t, filepath.Join(dir, "VTS_01_0.VOB"), bytes.Repeat([]byte("0"), 900)) // menu: ignored
	write(t, filepath.Join(dir, "VTS_01_1.VOB"), bytes.Repeat([]byte("x"), 100)) // extras
	write(t, filepath.Join(dir, "VTS_02_1.VOB"), []byte("AAA"))
	write(t, filepath.Join(dir, "VTS_02_2.VOB"), []byte("BBBB"))
	write(t, filepath.Join(dir, "VTS_02_10.VOB"), []byte("ignored: not a VTS part name"))
	write(t, filepath.Join(dir, "vts_03_1.vob"), bytes.Repeat([]byte("c"), 60))
	write(t, filepath.Join(dir, "VTS_03_2.VOB"), bytes.Repeat([]byte("d"), 60))
	// Title set 03 (120 bytes) beats 01 (100) and 02 (7).
	title, err := Find(filepath.Dir(dir), "dvd") // the parent of VIDEO_TS works too
	if err != nil {
		t.Fatal(err)
	}
	if got := string(readAll(t, title)); got != string(bytes.Repeat([]byte("c"), 60))+string(bytes.Repeat([]byte("d"), 60)) {
		t.Fatalf("joined %q", got)
	}
	if title.Name != "title.vob" || len(title.Parts) != 2 {
		t.Fatalf("title %+v", title)
	}
}

// vtsIFO is the start of a title set's IFO up to its video attributes: PAL or NTSC.
func vtsIFO(pal bool) []byte {
	b := make([]byte, 0x202)
	copy(b, "DVDVIDEO-VTS")
	b[0x200] = 0x40 // MPEG-2, NTSC
	if pal {
		b[0x200] |= 0x10
	}
	return b
}

func TestDVDFrameRateFromIFO(t *testing.T) {
	for _, c := range []struct {
		pal  bool
		want float64
	}{{true, 25}, {false, 30000.0 / 1001}} {
		dir := filepath.Join(t.TempDir(), "VIDEO_TS")
		write(t, filepath.Join(dir, "VTS_01_0.IFO"), vtsIFO(c.pal))
		write(t, filepath.Join(dir, "VTS_01_1.VOB"), []byte("feature"))
		title, err := Find(dir, "dvd")
		if err != nil {
			t.Fatal(err)
		}
		if title.FrameRate != c.want {
			t.Fatalf("pal=%v: frame rate %v, want %v", c.pal, title.FrameRate, c.want)
		}
	}
	// No IFO, or not a title set's: unknown.
	dir := filepath.Join(t.TempDir(), "VIDEO_TS")
	write(t, filepath.Join(dir, "VTS_01_0.IFO"), []byte("junk"))
	write(t, filepath.Join(dir, "VTS_01_1.VOB"), []byte("feature"))
	if title, _ := Find(dir, "dvd"); title.FrameRate != 0 {
		t.Fatalf("junk IFO gave %v fps", title.FrameRate)
	}
}

func TestDVDLooseVOBs(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "Show part 10.vob"), []byte("3"))
	write(t, filepath.Join(dir, "Show part 2.vob"), []byte("2"))
	write(t, filepath.Join(dir, "Show part 1.vob"), []byte("1"))
	title, err := Find(dir, "")
	if err != nil {
		t.Fatal(err)
	}
	if got := string(readAll(t, title)); got != "123" {
		t.Fatalf("natural order: %q", got)
	}
}

func TestSeekAcrossParts(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "VTS_01_1.VOB"), []byte("hello "))
	write(t, filepath.Join(dir, "VTS_01_2.VOB"), []byte("world"))
	title, _ := Find(dir, "dvd")
	s, _ := title.Open()
	defer s.Close()
	if _, err := s.Seek(4, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 5)
	if _, err := io.ReadFull(s, buf); err != nil || string(buf) != "o wor" {
		t.Fatalf("read %q, %v", buf, err)
	}
}

// mpls builds a playlist with the given clips, each lasting secs[i] seconds.
func mpls(clips []string, secs []uint32) []byte {
	var items bytes.Buffer
	for i, c := range clips {
		item := make([]byte, 22)
		copy(item[0:5], c)
		copy(item[5:9], "M2TS")
		binary.BigEndian.PutUint32(item[12:16], 0)
		binary.BigEndian.PutUint32(item[16:20], secs[i]*45000)
		_ = binary.Write(&items, binary.BigEndian, uint16(len(item)))
		items.Write(item)
	}
	head := make([]byte, 20)
	copy(head, "MPLS0200")
	binary.BigEndian.PutUint32(head[8:12], 20)
	list := make([]byte, 10)
	binary.BigEndian.PutUint16(list[6:8], uint16(len(clips)))
	return append(append(head, list...), items.Bytes()...)
}

// m2ts makes n 192-byte packets: 4 timecode bytes, then a 188-byte TS packet filled with fill.
func m2ts(n int, fill byte) []byte {
	var b bytes.Buffer
	for i := 0; i < n; i++ {
		b.Write([]byte{0, 0, 0, byte(i)})
		pkt := bytes.Repeat([]byte{fill}, tsPacket)
		pkt[0] = 0x47
		b.Write(pkt)
	}
	return b.Bytes()
}

func TestBlurayLongestPlaylistAsTS(t *testing.T) {
	bdmv := filepath.Join(t.TempDir(), "Live", "BDMV")
	write(t, filepath.Join(bdmv, "STREAM", "00001.m2ts"), m2ts(3, 'a'))
	write(t, filepath.Join(bdmv, "STREAM", "00002.m2ts"), m2ts(2, 'b'))
	write(t, filepath.Join(bdmv, "STREAM", "00009.m2ts"), m2ts(9, 'z')) // biggest, but not in the main playlist
	write(t, filepath.Join(bdmv, "PLAYLIST", "00000.mpls"), mpls([]string{"00009"}, []uint32{60}))
	write(t, filepath.Join(bdmv, "PLAYLIST", "00800.mpls"), mpls([]string{"00001", "00002"}, []uint32{3000, 2400}))
	write(t, filepath.Join(bdmv, "PLAYLIST", "00900.mpls"), mpls([]string{"00404"}, []uint32{9000})) // missing clip
	// Decoys: the same clips looped, and an absurd length.
	write(t, filepath.Join(bdmv, "PLAYLIST", "00901.mpls"), mpls([]string{"00001", "00002", "00001", "00002"}, []uint32{3000, 2400, 3000, 2400}))
	write(t, filepath.Join(bdmv, "PLAYLIST", "00902.mpls"), mpls([]string{"00009", "00002"}, []uint32{30000, 2400}))

	title, err := Find(bdmv, "bluray")
	if err != nil {
		t.Fatal(err)
	}
	if !title.M2TS || len(title.Parts) != 2 || title.Seconds != 5400 {
		t.Fatalf("title %+v", title)
	}
	data := readAll(t, title)
	if len(data) != 5*tsPacket {
		t.Fatalf("got %d bytes, want %d", len(data), 5*tsPacket)
	}
	for i := 0; i < 5; i++ {
		if data[i*tsPacket] != 0x47 {
			t.Fatalf("packet %d not aligned", i)
		}
	}
	if data[1] != 'a' || data[3*tsPacket+1] != 'b' {
		t.Fatal("clips out of order")
	}
	// A read starting mid-packet lines up too.
	s, _ := title.Open()
	defer s.Close()
	buf := make([]byte, 3)
	if _, err := s.ReadAt(buf, tsPacket*3-1); err != nil || !bytes.Equal(buf, []byte{'a', 0x47, 'b'}) {
		t.Fatalf("mid-packet read %q %v", buf, err)
	}
}

func TestBlurayFlattenedFallsBackToLargest(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "(2) 00000.clpi"), []byte("clip info"))
	write(t, filepath.Join(dir, "00003.m2ts"), m2ts(2, 's'))
	write(t, filepath.Join(dir, "(3) 00007.m2ts"), m2ts(4, 'm'))
	title, err := Find(dir, "")
	if err != nil {
		t.Fatal(err)
	}
	if title.Kind != "bluray" || len(title.Parts) != 1 || filepath.Base(title.Parts[0].Path) != "(3) 00007.m2ts" {
		t.Fatalf("title %+v", title)
	}
}

// iso builds a minimal ISO 9660 image: root → VIDEO_TS → the given files.
func iso(t *testing.T, path string, files map[string][]byte, names []string) {
	t.Helper()
	record := func(name string, lba, size uint32, dir bool) []byte {
		n := 33 + len(name)
		if n%2 == 1 {
			n++
		}
		r := make([]byte, n)
		r[0] = byte(n)
		binary.LittleEndian.PutUint32(r[2:6], lba)
		binary.BigEndian.PutUint32(r[6:10], lba)
		binary.LittleEndian.PutUint32(r[10:14], size)
		binary.BigEndian.PutUint32(r[14:18], size)
		if dir {
			r[25] = 2
		}
		r[32] = byte(len(name))
		copy(r[33:], name)
		return r
	}
	img := make([]byte, 40*sector)
	// Root directory at sector 20, VIDEO_TS at 21, file data from 22.
	pvd := img[16*sector:]
	pvd[0] = 1
	copy(pvd[1:6], "CD001")
	copy(pvd[156:], record("\x00", 20, sector, true))
	root := append(record("\x00", 20, sector, true), record("\x01", 20, sector, true)...)
	root = append(root, record("VIDEO_TS", 21, sector, true)...)
	copy(img[20*sector:], root)
	vts := append(record("\x00", 21, sector, true), record("\x01", 20, sector, true)...)
	lba := uint32(22)
	for _, name := range names {
		data := files[name]
		vts = append(vts, record(name+";1", lba, uint32(len(data)), false)...)
		copy(img[int(lba)*sector:], data)
		lba += uint32((len(data) + sector - 1) / sector)
	}
	copy(img[21*sector:], vts)
	write(t, path, img[:int(lba)*sector])
}

func TestISOServesMainTitleFromImage(t *testing.T) {
	path := filepath.Join(t.TempDir(), "Gig.iso")
	files := map[string][]byte{
		"VIDEO_TS.VOB": []byte("menu"),
		"VTS_01_1.VOB": bytes.Repeat([]byte("1"), 3000),
		"VTS_01_2.VOB": bytes.Repeat([]byte("2"), 100),
		"VTS_02_1.VOB": []byte("extra"),
	}
	iso(t, path, files, []string{"VIDEO_TS.VOB", "VTS_01_1.VOB", "VTS_01_2.VOB", "VTS_02_1.VOB"})
	title, err := Find(path, "")
	if err != nil {
		t.Fatal(err)
	}
	want := append(bytes.Repeat([]byte("1"), 3000), bytes.Repeat([]byte("2"), 100)...)
	if got := readAll(t, title); !bytes.Equal(got, want) {
		t.Fatalf("got %d bytes, want %d", len(got), len(want))
	}
}

func TestBlurayISOIsUnsupported(t *testing.T) {
	path := filepath.Join(t.TempDir(), "Movie.iso")
	write(t, path, make([]byte, 20*sector)) // no ISO 9660 descriptor: UDF only
	if _, err := Find(path, "iso"); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("want ErrUnsupported, got %v", err)
	}
}
