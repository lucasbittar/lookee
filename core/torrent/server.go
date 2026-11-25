package torrent

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"path"
	"strings"
	"sync"
	"time"
)

// Opener returns a fresh seekable reader for each HTTP request. Media players open
// several overlapping range requests, so readers must be independent.
type Opener func(ctx context.Context) (io.ReadSeekCloser, error)

// streamServer serves one file at /stream with full HTTP Range support (206 Partial Content).
type streamServer struct {
	srv  *http.Server
	ln   net.Listener
	once sync.Once
}

var contentTypes = map[string]string{
	".mkv": "video/x-matroska", ".mp4": "video/mp4", ".m4v": "video/mp4", ".webm": "video/webm",
	".avi": "video/x-msvideo", ".mov": "video/quicktime", ".ts": "video/mp2t", ".m2ts": "video/mp2t",
}

// listenStream starts a server on addr ("127.0.0.1:0" for a random loopback port).
func listenStream(addr, name string, open Opener) (*streamServer, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	ctype := contentTypes[strings.ToLower(path.Ext(name))]
	mux := http.NewServeMux()
	mux.HandleFunc("/stream", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		rd, err := open(r.Context())
		if err != nil {
			http.Error(w, err.Error(), http.StatusServiceUnavailable)
			return
		}
		defer rd.Close()
		if ctype != "" {
			w.Header().Set("Content-Type", ctype)
		}
		// ServeContent handles Range, If-Range and HEAD, seeking the reader as needed.
		http.ServeContent(w, r, name, time.Time{}, rd)
	})
	s := &streamServer{
		srv: &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second},
		ln:  ln,
	}
	go func() {
		if err := s.srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			_ = err // listener closed underneath us; nothing else to do
		}
	}()
	return s, nil
}

// URL is where a player can fetch the stream.
func (s *streamServer) URL() string { return fmt.Sprintf("http://%s/stream", s.ln.Addr()) }

// Close stops the server and aborts in-flight requests.
func (s *streamServer) Close() {
	s.once.Do(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := s.srv.Shutdown(ctx); err != nil {
			_ = s.srv.Close()
		}
	})
}
