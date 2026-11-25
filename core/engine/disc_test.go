package engine

import (
	"context"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"github.com/lucasbittar/lookee/core/disc"
)

func TestPlayDiscServesRanges(t *testing.T) {
	e := openEngine(t)
	dir := filepath.Join(t.TempDir(), "Gig", "VIDEO_TS")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(filepath.Join(dir, "VTS_01_1.VOB"), []byte("0123456789"), 0o644)
	_ = os.WriteFile(filepath.Join(dir, "VTS_01_2.VOB"), []byte("abcdef"), 0o644)

	play, err := e.PlayDisc(context.Background(), dir, "dvd")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(e.StopDisc)
	if play.Parts != 2 || play.Size != 16 || play.Kind != "dvd" {
		t.Fatalf("play %+v", play)
	}
	req, _ := http.NewRequest(http.MethodGet, play.URL, nil)
	req.Header.Set("Range", "bytes=8-11") // across the two VOBs
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusPartialContent || string(body) != "89ab" {
		t.Fatalf("got %d %q", resp.StatusCode, body)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "video/mp2p" {
		t.Fatalf("content type %q", ct)
	}

	e.StopDisc()
	if _, err := http.Get(play.URL); err == nil {
		t.Fatal("server still up after StopDisc")
	}
	if _, err := e.PlayDisc(context.Background(), filepath.Join(t.TempDir(), "x.iso"), ""); err == nil {
		t.Fatal("missing image played")
	}
	bluray := filepath.Join(t.TempDir(), "Movie.iso")
	_ = os.WriteFile(bluray, make([]byte, 64*1024), 0o644)
	if _, err := e.PlayDisc(context.Background(), bluray, "iso"); !errors.Is(err, disc.ErrUnsupported) {
		t.Fatalf("want ErrUnsupported, got %v", err)
	}
}
