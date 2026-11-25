// Package loopback serves one seekable stream to the player on 127.0.0.1, with full HTTP
// Range support. Torrent streams and disc rips both play through it.
package loopback

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

// ReadSeekCloser is what an Opener returns.
type ReadSeekCloser = io.ReadSeekCloser

// Opener returns a fresh seekable reader for each HTTP request. Media players open
// several overlapping range requests, so readers must be independent.
type Opener func(ctx context.Context) (io.ReadSeekCloser, error)

// Server serves one stream at /stream with full HTTP Range support (206 Partial Content).
type Server struct {
	srv  *http.Server
	ln   net.Listener
	once sync.Once
}

var contentTypes = map[string]string{
	".mkv": "video/x-matroska", ".mp4": "video/mp4", ".m4v": "video/mp4", ".webm": "video/webm",
	".avi": "video/x-msvideo", ".mov": "video/quicktime", ".ts": "video/mp2t", ".m2ts": "video/mp2t",
	".vob": "video/mp2p", ".mpg": "video/mp2p",
}

// Listen starts a server on addr ("127.0.0.1:0" for a random loopback port). name only
// sets the content type and the file name ServeContent reports.
func Listen(addr, name string, open Opener) (*Server, error) {
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
	s := &Server{
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
func (s *Server) URL() string { return fmt.Sprintf("http://%s/stream", s.ln.Addr()) }

// Close stops the server and aborts in-flight requests.
func (s *Server) Close() {
	s.once.Do(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := s.srv.Shutdown(ctx); err != nil {
			_ = s.srv.Close()
		}
	})
}
