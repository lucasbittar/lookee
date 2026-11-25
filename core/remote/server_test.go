package remote

import (
	"context"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"
)

type memStore struct {
	mu   sync.Mutex
	vals map[string]string
}

func (m *memStore) Fields(context.Context) ([]Field, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return []Field{
		{Key: "tmdb_api_key", Label: "TMDB key", Secret: true, IsSet: m.vals["tmdb_api_key"] != "", Value: m.vals["tmdb_api_key"], Group: "Keys"},
		{Key: "tmdb_language", Label: "Language", Value: m.vals["tmdb_language"], Group: "Library"},
	}, nil
}

func (m *memStore) Save(_ context.Context, v map[string]string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for k, val := range v {
		m.vals[k] = val
	}
	return nil
}

func start(t *testing.T, store Store, saved func()) (*Server, *http.Client) {
	t.Helper()
	s, err := Start(store, Options{Addr: "127.0.0.1:0", Host: "127.0.0.1", PIN: "123456", OnSaved: saved,
		Logs: func(n int) []string { return []string{"level=INFO msg=hello"} }})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	jar, _ := cookiejar.New(nil)
	return s, &http.Client{Jar: jar}
}

// reader returns a helper that reads a response body: reader(t)(c.Get(url)).
func reader(t *testing.T) func(*http.Response, error) string {
	return func(resp *http.Response, err error) string {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return string(b)
	}
}

func TestFlowNeverLeaksSecrets(t *testing.T) {
	store := &memStore{vals: map[string]string{"tmdb_api_key": "SUPERSECRETKEY", "tmdb_language": "pt-BR"}}
	savedCalled := make(chan struct{}, 1)
	s, c := start(t, store, func() { savedCalled <- struct{}{} })

	if len(s.PIN()) != 6 || !strings.HasPrefix(s.URL(), "http://127.0.0.1:") {
		t.Fatalf("pin %q url %q", s.PIN(), s.URL())
	}
	home := reader(t)(c.Get(s.URL()))
	if !strings.Contains(home, "Enter the PIN") {
		t.Fatal("home should ask for the PIN")
	}
	// Not authenticated: save is refused.
	resp, _ := c.PostForm(s.URL()+"save", url.Values{"tmdb_language": {"x"}})
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("unauthenticated save: %d", resp.StatusCode)
	}

	settings := reader(t)(c.PostForm(s.URL()+"unlock", url.Values{"pin": {"123456"}}))
	if !strings.Contains(settings, "Settings") || !strings.Contains(settings, `value="pt-BR"`) || !strings.Contains(settings, "Saved") {
		t.Fatalf("settings page:\n%s", settings)
	}
	if strings.Contains(settings, "SUPERSECRETKEY") {
		t.Fatal("secret leaked into the page")
	}
	logs := reader(t)(c.Get(s.URL() + "logs"))
	if !strings.Contains(logs, "msg=hello") || strings.Contains(logs, "SUPERSECRETKEY") {
		t.Fatal("logs page")
	}

	// Missing CSRF token is refused even with the cookie.
	resp, _ = c.PostForm(s.URL()+"save", url.Values{"tmdb_language": {"en-US"}})
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("save without csrf: %d", resp.StatusCode)
	}

	csrf := between(settings, `name="csrf" value="`, `"`)
	done := reader(t)(c.PostForm(s.URL()+"save", url.Values{"csrf": {csrf}, "tmdb_api_key": {""}, "tmdb_language": {"en-US"}}))
	if !strings.Contains(done, "Saved to your TV") {
		t.Fatalf("save response:\n%s", done)
	}
	if store.vals["tmdb_api_key"] != "SUPERSECRETKEY" || store.vals["tmdb_language"] != "en-US" {
		t.Fatalf("blank secret must keep the old value: %v", store.vals)
	}
	select {
	case <-savedCalled:
	case <-time.After(time.Second):
		t.Fatal("OnSaved not called")
	}
	select {
	case <-s.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("server should close after saving")
	}
}

func TestClearSecret(t *testing.T) {
	store := &memStore{vals: map[string]string{"tmdb_api_key": "K"}}
	s, c := start(t, store, nil)
	page := reader(t)(c.PostForm(s.URL()+"unlock", url.Values{"pin": {"123456"}}))
	reader(t)(c.PostForm(s.URL()+"save", url.Values{"csrf": {between(page, `name="csrf" value="`, `"`)}, "clear_tmdb_api_key": {"on"}}))
	if store.vals["tmdb_api_key"] != "" {
		t.Fatalf("not cleared: %v", store.vals)
	}
}

func TestLockoutAfterWrongPINs(t *testing.T) {
	s, c := start(t, &memStore{vals: map[string]string{}}, nil)
	for i := 1; i < MaxAttempts; i++ {
		b := reader(t)(c.PostForm(s.URL()+"unlock", url.Values{"pin": {"000000"}}))
		if !strings.Contains(b, "doesn&#39;t match") {
			t.Fatalf("attempt %d:\n%s", i, b)
		}
	}
	b := reader(t)(c.PostForm(s.URL()+"unlock", url.Values{"pin": {"000000"}}))
	if !strings.Contains(b, "Too many attempts") {
		t.Fatalf("final attempt:\n%s", b)
	}
	select {
	case <-s.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("server should shut down after lockout")
	}
}

func TestTimeoutAndHeaders(t *testing.T) {
	s, err := Start(&memStore{vals: map[string]string{}}, Options{Addr: "127.0.0.1:0", Host: "127.0.0.1", Timeout: 200 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	resp, err := http.Get(s.URL())
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.Header.Get("Cache-Control") != "no-store" || !strings.Contains(resp.Header.Get("Content-Security-Policy"), "default-src 'none'") {
		t.Fatalf("headers: %v", resp.Header)
	}
	select {
	case <-s.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("server should time out")
	}
	if _, err := http.Get(s.URL()); err == nil {
		t.Fatal("server still reachable after timeout")
	}
}

func between(s, a, b string) string {
	i := strings.Index(s, a)
	if i < 0 {
		return ""
	}
	s = s[i+len(a):]
	return s[:strings.Index(s, b)]
}
