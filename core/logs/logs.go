// Package logs keeps a small rotating log file so problems on the TV can be read later
// without adb/logcat (the MIBOX3 cannot use ADB and its USB drive at the same time).
package logs

import (
	"bytes"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

const (
	fileName    = "lookee.log"
	DefaultSize = 1 << 20 // rotate after 1 MiB; one previous file is kept
)

// Rotating is an io.Writer that appends to dir/lookee.log and rotates it to lookee.log.1.
type Rotating struct {
	mu      sync.Mutex
	dir     string
	maxSize int64
	f       *os.File
	size    int64
}

// Open opens (creating) the log in dir.
func Open(dir string, maxSize int64) (*Rotating, error) {
	if maxSize <= 0 {
		maxSize = DefaultSize
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	r := &Rotating{dir: dir, maxSize: maxSize}
	return r, r.openLocked()
}

func (r *Rotating) path() string { return filepath.Join(r.dir, fileName) }

func (r *Rotating) openLocked() error {
	f, err := os.OpenFile(r.path(), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	st, err := f.Stat()
	if err != nil {
		f.Close()
		return err
	}
	r.f, r.size = f, st.Size()
	return nil
}

// Write appends p, rotating first if the file would grow past the limit.
func (r *Rotating) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.f == nil {
		return 0, os.ErrClosed
	}
	if r.size+int64(len(p)) > r.maxSize && r.size > 0 {
		r.f.Close()
		_ = os.Rename(r.path(), r.path()+".1")
		if err := r.openLocked(); err != nil {
			r.f = nil
			return 0, err
		}
	}
	n, err := r.f.Write(p)
	r.size += int64(n)
	return n, err
}

// Close closes the current file.
func (r *Rotating) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.f == nil {
		return nil
	}
	err := r.f.Close()
	r.f = nil
	return err
}

// Tail returns up to n of the most recent lines, oldest first, across the current and
// previous files.
func (r *Rotating) Tail(n int) ([]string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	var buf bytes.Buffer
	for _, p := range []string{r.path() + ".1", r.path()} {
		b, err := os.ReadFile(p)
		if err != nil && !os.IsNotExist(err) {
			return nil, err
		}
		buf.Write(b)
	}
	lines := strings.Split(strings.TrimRight(buf.String(), "\n"), "\n")
	if len(lines) == 1 && lines[0] == "" {
		return []string{}, nil
	}
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return lines, nil
}

// NewLogger returns a text slog.Logger writing to w (and also to extra writers, e.g. stderr).
func NewLogger(w io.Writer, level slog.Level, extra ...io.Writer) *slog.Logger {
	if len(extra) > 0 {
		w = io.MultiWriter(append([]io.Writer{w}, extra...)...)
	}
	return slog.New(slog.NewTextHandler(w, &slog.HandlerOptions{Level: level}))
}

// Discard is a logger that drops everything.
var Discard = slog.New(slog.NewTextHandler(io.Discard, nil))

// Redact shortens a secret for logging: "abcdef123456" -> "abcd…(12)".
func Redact(s string) string {
	if len(s) <= 4 {
		return "…"
	}
	return fmt.Sprintf("%s…(%d)", s[:4], len(s))
}
