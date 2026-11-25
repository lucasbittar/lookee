// Package disc plays disc rips (ROADMAP Decision 13): it finds the main title of a DVD
// folder, a Blu-ray folder or an ISO image, and reads it as one continuous stream that the
// loopback server hands to the player.
package disc

import (
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"sync"
)

// Part is a byte range of a file: a whole .VOB or .m2ts, or a file's extent inside an ISO.
type Part struct {
	Path   string `json:"path"`
	Offset int64  `json:"offset"`
	Size   int64  `json:"size"`
}

// concat reads parts back to back as if they were one file. Files open on first use.
type concat struct {
	parts  []Part
	starts []int64 // start of each part in the joined stream
	size   int64

	mu    sync.Mutex
	files map[string]*os.File
}

func newConcat(parts []Part) *concat {
	c := &concat{parts: parts, starts: make([]int64, len(parts)), files: map[string]*os.File{}}
	for i, p := range parts {
		c.starts[i] = c.size
		c.size += p.Size
	}
	return c
}

func (c *concat) file(path string) (*os.File, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if f, ok := c.files[path]; ok {
		return f, nil
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	c.files[path] = f
	return f, nil
}

// ReadAt fills p from the joined stream, crossing part boundaries as needed.
func (c *concat) ReadAt(p []byte, off int64) (int, error) {
	if off >= c.size {
		return 0, io.EOF
	}
	n := 0
	for n < len(p) && off < c.size {
		i := sort.Search(len(c.starts), func(i int) bool { return c.starts[i] > off }) - 1
		part := c.parts[i]
		within := off - c.starts[i]
		chunk := min(int64(len(p)-n), part.Size-within)
		f, err := c.file(part.Path)
		if err != nil {
			return n, err
		}
		got, err := f.ReadAt(p[n:n+int(chunk)], part.Offset+within)
		n += got
		off += int64(got)
		// EOF exactly at the end of a part is fine: the next part continues the stream.
		atPartEnd := errors.Is(err, io.EOF) && int64(got) == chunk
		if err != nil && !atPartEnd {
			if errors.Is(err, io.EOF) {
				return n, fmt.Errorf("disc: %s is shorter than expected", part.Path)
			}
			return n, err
		}
	}
	if n < len(p) {
		return n, io.EOF
	}
	return n, nil
}

func (c *concat) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	var first error
	for _, f := range c.files {
		if err := f.Close(); err != nil && first == nil {
			first = err
		}
	}
	c.files = map[string]*os.File{}
	return first
}

// m2tsAsTS presents Blu-ray .m2ts (192-byte packets: a 4-byte timecode, then a standard
// 188-byte transport packet) as plain MPEG-TS, which the player's extractor reads.
type m2tsAsTS struct {
	src     io.ReaderAt
	packets int64
}

const (
	tsPacket   = 188
	m2tsPacket = 192
)

func (m *m2tsAsTS) size() int64 { return m.packets * tsPacket }

func (m *m2tsAsTS) ReadAt(p []byte, off int64) (int, error) {
	total := m.size()
	if off >= total {
		return 0, io.EOF
	}
	end := min(off+int64(len(p)), total)
	first, last := off/tsPacket, (end-1)/tsPacket
	buf := make([]byte, (last-first+1)*m2tsPacket)
	got, err := m.src.ReadAt(buf, first*m2tsPacket)
	if err != nil && !errors.Is(err, io.EOF) {
		return 0, err
	}
	n := 0
	for pk := first; pk <= last; pk++ {
		rel := (pk - first) * m2tsPacket
		if rel+m2tsPacket > int64(got) {
			break
		}
		packet := buf[rel+4 : rel+m2tsPacket]
		from := int64(0)
		if pk == first {
			from = off % tsPacket
		}
		to := int64(tsPacket)
		if pk == last {
			to = (end-1)%tsPacket + 1
		}
		n += copy(p[n:], packet[from:to])
	}
	if int64(n) < int64(len(p)) {
		return n, io.EOF
	}
	return n, nil
}

// Stream is the main title as one seekable stream. Name's extension tells the server the
// container (".vob" for MPEG-PS, ".ts" for transport stream).
type Stream struct {
	*io.SectionReader
	closer io.Closer
}

func (s *Stream) Close() error { return s.closer.Close() }

// Open returns a fresh reader for t (one per HTTP request; players open several at once).
func (t *Title) Open() (*Stream, error) {
	c := newConcat(t.Parts)
	if !t.M2TS {
		return &Stream{io.NewSectionReader(c, 0, c.size), c}, nil
	}
	m := &m2tsAsTS{src: c, packets: c.size / m2tsPacket}
	return &Stream{io.NewSectionReader(m, 0, m.size()), c}, nil
}
