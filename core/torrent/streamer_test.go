package torrent

import (
	"bytes"
	"context"
	"crypto/rand"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	atorrent "github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/bencode"
	"github.com/anacrolix/torrent/metainfo"
	"github.com/anacrolix/torrent/storage"
)

type nopCloser struct{ *bytes.Reader }

func (nopCloser) Close() error { return nil }

func TestStreamServerRanges(t *testing.T) {
	data := make([]byte, 1<<20)
	rand.Read(data)
	srv, err := listenStream("127.0.0.1:0", "movie.mkv", func(context.Context) (io.ReadSeekCloser, error) {
		return nopCloser{bytes.NewReader(data)}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	req, _ := http.NewRequest(http.MethodGet, srv.URL(), nil)
	req.Header.Set("Range", "bytes=1000-1999")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusPartialContent || !bytes.Equal(body, data[1000:2000]) {
		t.Fatalf("status %d, %d bytes", resp.StatusCode, len(body))
	}
	if ct := resp.Header.Get("Content-Type"); ct != "video/x-matroska" {
		t.Fatalf("content-type %q", ct)
	}
	if cr := resp.Header.Get("Content-Range"); cr != fmt.Sprintf("bytes 1000-1999/%d", len(data)) {
		t.Fatalf("content-range %q", cr)
	}

	// Suffix range: players read the end of the file for the container index.
	req.Header.Set("Range", "bytes=-100")
	resp, _ = http.DefaultClient.Do(req)
	body, _ = io.ReadAll(resp.Body)
	resp.Body.Close()
	if !bytes.Equal(body, data[len(data)-100:]) {
		t.Fatal("suffix range mismatch")
	}

	head, _ := http.Head(srv.URL())
	if head.ContentLength != int64(len(data)) || head.Header.Get("Accept-Ranges") != "bytes" {
		t.Fatalf("HEAD: len %d ranges %q", head.ContentLength, head.Header.Get("Accept-Ranges"))
	}
}

func TestSweepCache(t *testing.T) {
	root := t.TempDir()
	os.MkdirAll(filepath.Join(root, "stream-123", "x"), 0o755)
	os.MkdirAll(filepath.Join(root, "keep-me"), 0o755)
	if err := SweepCache(root); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(root, "stream-123")); !os.IsNotExist(err) {
		t.Fatal("stale session dir not removed")
	}
	if _, err := os.Stat(filepath.Join(root, "keep-me")); err != nil {
		t.Fatal("unrelated dir removed")
	}
	if err := SweepCache(filepath.Join(root, "missing")); err != nil {
		t.Fatalf("missing root: %v", err)
	}
}

// TestStreamEndToEnd seeds a generated video file from one client and streams it through
// Start from another, over loopback only. No internet is used.
func TestStreamEndToEnd(t *testing.T) {
	if testing.Short() {
		t.Skip("end-to-end torrent test")
	}
	seedDir := t.TempDir()
	content := make([]byte, 3<<20+12345) // not a multiple of the piece size
	rand.Read(content)
	mustWrite(t, filepath.Join(seedDir, "Big.Buck.Bunny.2008.mkv"), content)
	mustWrite(t, filepath.Join(seedDir, "readme.txt"), []byte("hello"))

	info := metainfo.Info{PieceLength: 256 << 10}
	if err := info.BuildFromFilePath(seedDir); err != nil {
		t.Fatal(err)
	}
	var mi metainfo.MetaInfo
	var err error
	if mi.InfoBytes, err = bencode.Marshal(info); err != nil {
		t.Fatal(err)
	}
	torrentFile := filepath.Join(t.TempDir(), "test.torrent")
	f, _ := os.Create(torrentFile)
	if err := mi.Write(f); err != nil {
		t.Fatal(err)
	}
	f.Close()

	// Seeder: files already complete under seedDir's parent.
	sc := atorrent.NewDefaultClientConfig()
	sc.Seed = true
	sc.NoDHT = true
	sc.DisableTrackers = true
	sc.ListenPort = 0
	sc.NoDefaultPortForwarding = true
	sc.DataDir = filepath.Dir(seedDir)
	sc.DefaultStorage = storage.NewFileOpts(storage.NewFileClientOpts{ClientBaseDir: filepath.Dir(seedDir), PieceCompletion: storage.NewMapPieceCompletion(), DisableMmap: true})
	sc.Slogger = slog.New(slog.NewTextHandler(io.Discard, nil))
	seeder, err := atorrent.NewClient(sc)
	if err != nil {
		t.Fatal(err)
	}
	defer seeder.Close()
	st, err := seeder.AddTorrent(&mi)
	if err != nil {
		t.Fatal(err)
	}
	<-st.GotInfo()
	if err := st.VerifyDataContext(context.Background()); err != nil {
		t.Fatal(err)
	}

	cacheRoot := t.TempDir()
	var lastStats Stats
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	sess, err := Start(ctx, Config{
		CacheRoot: cacheRoot,
		addPeers:  []string{fmt.Sprintf("127.0.0.1:%d", seeder.LocalPort())},
	}, torrentFile, -1, func(s Stats) { lastStats = s })
	if err != nil {
		t.Fatal(err)
	}
	stats := sess.Stats()
	if stats.FileName != "Big.Buck.Bunny.2008.mkv" || stats.Size != int64(len(content)) || stats.VideoFilesCount != 1 {
		t.Fatalf("picked %+v", stats)
	}

	// A ranged read in the middle, as a player seeking would do.
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, sess.URL(), nil)
	req.Header.Set("Range", "bytes=2000000-2099999")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusPartialContent || !bytes.Equal(body, content[2000000:2100000]) {
		t.Fatalf("mid-file range: status %d, %d bytes, equal=%v", resp.StatusCode, len(body), bytes.Equal(body, content[2000000:2100000]))
	}

	// Whole file.
	resp, err = http.Get(sess.URL())
	if err != nil {
		t.Fatal(err)
	}
	body, _ = io.ReadAll(resp.Body)
	resp.Body.Close()
	if !bytes.Equal(body, content) {
		t.Fatalf("full read: %d bytes, equal=%v", len(body), bytes.Equal(body, content))
	}

	// Stats eventually report ready.
	deadline := time.Now().Add(10 * time.Second)
	for sess.Stats().State != "ready" && time.Now().Before(deadline) {
		time.Sleep(200 * time.Millisecond)
	}
	if s := sess.Stats(); s.State != "ready" || s.BufferPercent < 100 || s.Downloaded == 0 {
		t.Fatalf("stats = %+v", s)
	}

	dir := sess.Stats().CacheDir
	if _, err := os.Stat(dir); err != nil {
		t.Fatalf("session dir missing while streaming: %v", err)
	}
	sess.Stop()
	sess.Stop() // idempotent
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("cache not deleted after Stop: %v", err)
	}
	if _, err := http.Get(sess.URL()); err == nil {
		t.Fatal("server still answering after Stop")
	}
	<-sess.Done()
	_ = lastStats
}

func mustWrite(t *testing.T, path string, b []byte) {
	t.Helper()
	if err := os.WriteFile(path, b, 0o644); err != nil {
		t.Fatal(err)
	}
}
