// Package remote serves a short-lived, PIN-protected settings page on the LAN so API keys
// and indexer URLs can be pasted from a phone instead of typed with a TV remote.
//
// Security rules (CLAUDE.md): off unless explicitly started; PIN shown only on the TV;
// shuts down after a successful save, after Timeout, or after too many wrong PINs;
// secret values are never sent back to the browser.
package remote

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"embed"
	"encoding/hex"
	"errors"
	"fmt"
	"html/template"
	"math/big"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

//go:embed page.html
var pageFS embed.FS

var page = template.Must(template.ParseFS(pageFS, "page.html"))

const (
	DefaultTimeout = 10 * time.Minute
	MaxAttempts    = 5
	cookieName     = "lookee_session"
)

// Field is one setting shown on the page.
type Field struct {
	Key       string
	Label     string
	Help      string
	Secret    bool   // never rendered; shown as set / not set
	Multiline bool   // one entry per line
	Value     string // current value (non-secret fields only)
	IsSet     bool
	Group     string
}

// Store reads and writes settings. Implemented by the engine.
type Store interface {
	Fields(ctx context.Context) ([]Field, error)
	Save(ctx context.Context, values map[string]string) error
}

// Options configure a Server.
type Options struct {
	Addr    string        // listen address; default ":0" (all interfaces, random port)
	Host    string        // address shown in the URL; default: detected LAN IPv4
	PIN     string        // default: random 6 digits
	Timeout time.Duration // default 10 minutes
	Logs    func(n int) []string
	OnSaved func()
}

// Server is a running settings page.
type Server struct {
	opts  Options
	store Store
	srv   *http.Server
	ln    net.Listener
	url   string
	done  chan struct{}
	once  sync.Once
	timer *time.Timer

	mu       sync.Mutex
	attempts int
	session  string
}

// ErrNoLAN is returned when no LAN address can be found to advertise.
var ErrNoLAN = errors.New("no LAN address found; is the TV connected to Wi-Fi or Ethernet?")

// Start begins serving. Call Close (or wait for Done) to stop.
func Start(store Store, opts Options) (*Server, error) {
	if opts.Addr == "" {
		opts.Addr = ":0"
	}
	if opts.Timeout <= 0 {
		opts.Timeout = DefaultTimeout
	}
	if opts.PIN == "" {
		pin, err := randomPIN()
		if err != nil {
			return nil, err
		}
		opts.PIN = pin
	}
	if opts.Host == "" {
		opts.Host = LANAddress()
		if opts.Host == "" {
			return nil, ErrNoLAN
		}
	}
	ln, err := net.Listen("tcp", opts.Addr)
	if err != nil {
		return nil, err
	}
	s := &Server{opts: opts, store: store, ln: ln, done: make(chan struct{})}
	port := ln.Addr().(*net.TCPAddr).Port
	s.url = fmt.Sprintf("http://%s:%d/", opts.Host, port)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", s.handleHome)
	mux.HandleFunc("POST /unlock", s.handleUnlock)
	mux.HandleFunc("POST /save", s.handleSave)
	mux.HandleFunc("GET /logs", s.handleLogs)
	s.srv = &http.Server{Handler: secureHeaders(mux), ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = s.srv.Serve(ln) }()
	s.timer = time.AfterFunc(opts.Timeout, s.Close)
	return s, nil
}

// URL is the address to open on the phone (also encoded in the TV's QR code).
func (s *Server) URL() string { return s.url }

// PIN is the code shown on the TV.
func (s *Server) PIN() string { return s.opts.PIN }

// Done is closed when the server stops.
func (s *Server) Done() <-chan struct{} { return s.done }

// Close stops the server. Safe to call more than once.
func (s *Server) Close() {
	s.once.Do(func() {
		close(s.done)
		s.timer.Stop()
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := s.srv.Shutdown(ctx); err != nil {
			_ = s.srv.Close()
		}
	})
}

// closeSoon lets the current response reach the phone before shutting down.
func (s *Server) closeSoon() { time.AfterFunc(300*time.Millisecond, s.Close) }

func secureHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Cache-Control", "no-store")
		h.Set("X-Frame-Options", "DENY")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
		next.ServeHTTP(w, r)
	})
}

type view struct {
	Stage     string // "pin", "settings", "saved", "logs", "locked"
	Error     string
	CSRF      string
	Groups    []group
	Logs      []string
	Remaining int
}

type group struct {
	Name   string
	Fields []Field
}

func (s *Server) render(w http.ResponseWriter, status int, v view) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	_ = page.Execute(w, v)
}

func (s *Server) authed(r *http.Request) (string, bool) {
	c, err := r.Cookie(cookieName)
	if err != nil {
		return "", false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.session == "" || subtle.ConstantTimeCompare([]byte(c.Value), []byte(s.session)) != 1 {
		return "", false
	}
	return s.session, true
}

func (s *Server) handleHome(w http.ResponseWriter, r *http.Request) {
	if tok, ok := s.authed(r); ok {
		s.renderSettings(w, r, tok, "")
		return
	}
	s.render(w, http.StatusOK, view{Stage: "pin"})
}

func (s *Server) handleUnlock(w http.ResponseWriter, r *http.Request) {
	pin := strings.TrimSpace(r.PostFormValue("pin"))
	s.mu.Lock()
	if s.attempts >= MaxAttempts {
		s.mu.Unlock()
		s.render(w, http.StatusForbidden, view{Stage: "locked"})
		return
	}
	if subtle.ConstantTimeCompare([]byte(pin), []byte(s.opts.PIN)) != 1 {
		s.attempts++
		left := MaxAttempts - s.attempts
		s.mu.Unlock()
		if left <= 0 {
			s.render(w, http.StatusForbidden, view{Stage: "locked"})
			s.closeSoon()
			return
		}
		s.render(w, http.StatusUnauthorized, view{Stage: "pin", Error: "That PIN doesn't match the one on the TV.", Remaining: left})
		return
	}
	tok := randomToken()
	s.session = tok
	s.mu.Unlock()
	http.SetCookie(w, &http.Cookie{Name: cookieName, Value: tok, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode})
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (s *Server) renderSettings(w http.ResponseWriter, r *http.Request, tok, errMsg string) {
	fields, err := s.store.Fields(r.Context())
	if err != nil {
		s.render(w, http.StatusInternalServerError, view{Stage: "pin", Error: "Couldn't read settings: " + err.Error()})
		return
	}
	var groups []group
	for _, f := range fields {
		if f.Secret {
			f.Value = "" // never sent to the browser
		}
		if len(groups) == 0 || groups[len(groups)-1].Name != f.Group {
			groups = append(groups, group{Name: f.Group})
		}
		groups[len(groups)-1].Fields = append(groups[len(groups)-1].Fields, f)
	}
	s.render(w, http.StatusOK, view{Stage: "settings", CSRF: tok, Groups: groups, Error: errMsg, Logs: s.logTail(1)})
}

func (s *Server) handleSave(w http.ResponseWriter, r *http.Request) {
	tok, ok := s.authed(r)
	if !ok || subtle.ConstantTimeCompare([]byte(r.PostFormValue("csrf")), []byte(tok)) != 1 {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	fields, err := s.store.Fields(r.Context())
	if err != nil {
		s.renderSettings(w, r, tok, err.Error())
		return
	}
	values := map[string]string{}
	for _, f := range fields {
		v := strings.TrimSpace(strings.ReplaceAll(r.PostFormValue(f.Key), "\r\n", "\n"))
		switch {
		case f.Secret && r.PostFormValue("clear_"+f.Key) == "on":
			values[f.Key] = ""
		case f.Secret && v == "":
			// blank secret = keep the stored value
		default:
			values[f.Key] = v
		}
	}
	if err := s.store.Save(r.Context(), values); err != nil {
		s.renderSettings(w, r, tok, "Couldn't save: "+err.Error())
		return
	}
	s.render(w, http.StatusOK, view{Stage: "saved"})
	if s.opts.OnSaved != nil {
		s.opts.OnSaved()
	}
	s.closeSoon()
}

func (s *Server) handleLogs(w http.ResponseWriter, r *http.Request) {
	tok, ok := s.authed(r)
	if !ok {
		http.Redirect(w, r, "/", http.StatusSeeOther)
		return
	}
	s.render(w, http.StatusOK, view{Stage: "logs", CSRF: tok, Logs: s.logTail(400)})
}

func (s *Server) logTail(n int) []string {
	if s.opts.Logs == nil {
		return nil
	}
	return s.opts.Logs(n)
}

func randomPIN() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}

func randomToken() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// LANAddress returns the first private IPv4 address of an up, non-loopback interface.
func LANAddress() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ipn, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			if ip4 := ipn.IP.To4(); ip4 != nil && ip4.IsPrivate() {
				return ip4.String()
			}
		}
	}
	return ""
}
