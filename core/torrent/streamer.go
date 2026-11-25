// Package torrent streams a video out of a torrent while it downloads (Popcorn Time style)
// and searches public indexers for torrents.
package torrent

import (
	"context"
	"errors"
	"fmt"
	"github.com/lucasbittar/lookee/core/loopback"
	"io"
	"log/slog"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	atorrent "github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/storage"

	"github.com/lucasbittar/lookee/core/scanner"
)

// Defaults sized for the MIBOX3 (2 GB RAM, ~500 MB free, 32-bit ARM).
const (
	DefaultReadahead   = 25 << 20 // bytes prioritized ahead of the read position
	DefaultMaxConns    = 35       // established peer connections per torrent
	DefaultBufferBytes = 8 << 20  // contiguous bytes at the start needed before "ready"
	sessionDirPrefix   = "stream-"
	metadataTimeout    = 90 * time.Second
	edgePieces         = 2 // pieces fetched up front at each end of the file (MP4 moov / MKV cues)
)

// ErrNoVideo is returned when a torrent contains no playable video file.
var ErrNoVideo = errors.New("torrent has no video file")

// Config tunes the streamer.
type Config struct {
	CacheRoot   string // where per-session piece directories are created
	ListenAddr  string // stream server address; default "127.0.0.1:0"
	Readahead   int64
	MaxConns    int
	BufferBytes int64
	Logger      *slog.Logger // optional; torrent library chatter is discarded otherwise

	addPeers []string // tests only: peers to connect to directly
}

// Stats is reported to the UI about once a second.
type Stats struct {
	State           string  `json:"state"` // "metadata", "buffering", "ready", "stopped", "error"
	Name            string  `json:"name,omitempty"`
	FileName        string  `json:"fileName,omitempty"`
	Size            int64   `json:"size,omitempty"`
	Downloaded      int64   `json:"downloaded"`
	SpeedBps        int64   `json:"speedBps"`
	Peers           int     `json:"peers"`
	Seeders         int     `json:"seeders"`
	BufferPercent   float64 `json:"bufferPercent"`
	URL             string  `json:"url,omitempty"`
	Error           string  `json:"error,omitempty"`
	CacheDir        string  `json:"-"`
	ElapsedSeconds  int     `json:"elapsedSeconds"`
	FileIndex       int     `json:"fileIndex"`
	VideoFilesCount int     `json:"videoFiles,omitempty"`
}

// Session is one active stream. Stop must be called to free memory and delete the cache.
type Session struct {
	cfg    Config
	dir    string
	client *atorrent.Client
	tor    *atorrent.Torrent
	file   *atorrent.File
	server *loopback.Server
	start  time.Time

	mu        sync.Mutex
	stats     Stats
	lastBytes int64
	lastTick  time.Time
	stopOnce  sync.Once
	done      chan struct{}
}

// Start adds a magnet link (or a path to a .torrent file), waits for its metadata, picks
// a video file (fileIndex < 0 = the largest), and starts serving it. onStats, if not nil,
// is called about once a second until the session stops.
func Start(ctx context.Context, cfg Config, source string, fileIndex int, onStats func(Stats)) (*Session, error) {
	if cfg.CacheRoot == "" {
		return nil, errors.New("streamer: CacheRoot is required")
	}
	if cfg.ListenAddr == "" {
		cfg.ListenAddr = "127.0.0.1:0"
	}
	if cfg.Readahead <= 0 {
		cfg.Readahead = DefaultReadahead
	}
	if cfg.MaxConns <= 0 {
		cfg.MaxConns = DefaultMaxConns
	}
	if cfg.BufferBytes <= 0 {
		cfg.BufferBytes = DefaultBufferBytes
	}
	if err := os.MkdirAll(cfg.CacheRoot, 0o755); err != nil {
		return nil, err
	}
	dir, err := os.MkdirTemp(cfg.CacheRoot, sessionDirPrefix)
	if err != nil {
		return nil, err
	}

	s := &Session{cfg: cfg, dir: dir, start: time.Now(), done: make(chan struct{})}
	s.stats = Stats{State: "metadata", CacheDir: dir, FileIndex: -1}
	fail := func(err error) (*Session, error) {
		s.Stop()
		return nil, err
	}

	tc := atorrent.NewDefaultClientConfig()
	tc.DataDir = dir
	tc.DefaultStorage = storage.NewFileOpts(storage.NewFileClientOpts{
		ClientBaseDir: dir,
		// In-memory completion: no bolt/sqlite files to clean up, nothing persists.
		PieceCompletion: storage.NewMapPieceCompletion(),
		// Never mmap: a 32-bit process cannot map a multi-GB file.
		DisableMmap: true,
	})
	tc.Seed = false
	tc.ListenPort = 0
	tc.NoDefaultPortForwarding = true // no UPnP changes on the user's router
	tc.EstablishedConnsPerTorrent = cfg.MaxConns
	tc.HalfOpenConnsPerTorrent = cfg.MaxConns / 2
	tc.TotalHalfOpenConns = cfg.MaxConns
	if cfg.Logger != nil {
		tc.Slogger = cfg.Logger
	} else {
		tc.Slogger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}

	s.client, err = atorrent.NewClient(tc)
	if err != nil {
		return fail(fmt.Errorf("start torrent client: %w", err))
	}
	if strings.HasPrefix(source, "magnet:") {
		s.tor, err = s.client.AddMagnet(source)
	} else {
		s.tor, err = s.client.AddTorrentFromFile(source)
	}
	if err != nil {
		return fail(fmt.Errorf("add torrent: %w", err))
	}
	for _, addr := range cfg.addPeers {
		ap, err := netip.ParseAddrPort(addr)
		if err != nil {
			return fail(err)
		}
		s.tor.AddPeers([]atorrent.PeerInfo{{Addr: ap, Trusted: true}})
	}

	go s.reportLoop(onStats)

	mctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	select {
	case <-s.tor.GotInfo():
	case <-mctx.Done():
		if ctx.Err() != nil {
			return fail(ctx.Err())
		}
		return fail(errors.New("no peers sent the torrent metadata in time"))
	}

	s.file, s.stats.VideoFilesCount, err = pickFile(s.tor.Files(), fileIndex)
	if err != nil {
		return fail(err)
	}
	// Fetch both ends first: players read the container index (MP4 moov, MKV cues) before
	// they can start, and it is often at the end of the file.
	begin, end := s.file.BeginPieceIndex(), s.file.EndPieceIndex()
	s.tor.DownloadPieces(begin, min(begin+edgePieces, end))
	s.tor.DownloadPieces(max(end-edgePieces, begin), end)

	s.server, err = loopback.Listen(cfg.ListenAddr, filepath.Base(s.file.Path()), s.open)
	if err != nil {
		return fail(fmt.Errorf("start stream server: %w", err))
	}

	s.mu.Lock()
	s.stats.State = "buffering"
	s.stats.Name = s.tor.Name()
	s.stats.FileName = filepath.Base(s.file.DisplayPath())
	s.stats.Size = s.file.Length()
	s.stats.URL = s.server.URL()
	s.stats.FileIndex = indexOf(s.tor.Files(), s.file)
	s.mu.Unlock()

	// A reader at the start pulls the initial buffer even before the player connects.
	go s.prefetchStart()
	return s, nil
}

func (s *Session) open(ctx context.Context) (io.ReadSeekCloser, error) {
	s.mu.Lock()
	f := s.file
	s.mu.Unlock()
	if f == nil {
		return nil, errors.New("stream stopped")
	}
	r := f.NewReader()
	r.SetContext(ctx)
	r.SetReadahead(s.cfg.Readahead)
	return r, nil
}

func (s *Session) prefetchStart() {
	ctx, cancel := context.WithCancel(context.Background())
	go func() { <-s.done; cancel() }()
	r, err := s.open(ctx)
	if err != nil {
		return
	}
	defer r.Close()
	_, _ = io.CopyN(io.Discard, r, min(s.cfg.BufferBytes, s.file.Length()))
}

// URL is the loopback address the player should open.
func (s *Session) URL() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stats.URL
}

// Stats returns the latest statistics.
func (s *Session) Stats() Stats {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stats
}

// Done is closed when the session has stopped.
func (s *Session) Done() <-chan struct{} { return s.done }

// Stop closes the server and torrent client and deletes every downloaded piece.
// It is safe to call more than once.
func (s *Session) Stop() {
	s.stopOnce.Do(func() {
		close(s.done)
		if s.server != nil {
			s.server.Close()
		}
		if s.tor != nil {
			s.tor.Drop()
		}
		if s.client != nil {
			s.client.Close()
		}
		s.mu.Lock()
		s.file = nil
		s.stats.State = "stopped"
		s.mu.Unlock()
		_ = os.RemoveAll(s.dir)
	})
}

func (s *Session) reportLoop(onStats func(Stats)) {
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		select {
		case <-s.done:
			if onStats != nil {
				onStats(s.Stats())
			}
			return
		case now := <-tick.C:
			st := s.sample(now)
			if onStats != nil {
				onStats(st)
			}
		}
	}
}

func (s *Session) sample(now time.Time) Stats {
	ts := s.tor.Stats()
	read := ts.BytesReadUsefulData.Int64()

	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.lastTick.IsZero() {
		if dt := now.Sub(s.lastTick).Seconds(); dt > 0 {
			s.stats.SpeedBps = int64(float64(read-s.lastBytes) / dt)
		}
	}
	s.lastBytes, s.lastTick = read, now
	s.stats.Peers = ts.ActivePeers
	s.stats.Seeders = ts.ConnectedSeeders
	s.stats.ElapsedSeconds = int(now.Sub(s.start).Seconds())
	if s.file != nil {
		s.stats.Downloaded = s.file.BytesCompleted()
		s.stats.BufferPercent = s.bufferPercentLocked()
		if s.stats.State == "buffering" && s.stats.BufferPercent >= 100 {
			s.stats.State = "ready"
		}
	}
	return s.stats
}

// bufferPercentLocked is the share of the start buffer and the end pieces that is complete.
func (s *Session) bufferPercentLocked() float64 {
	f := s.file
	begin, end := f.BeginPieceIndex(), f.EndPieceIndex()
	plen := s.tor.Info().PieceLength
	need := map[int]bool{}
	startPieces := int((min(s.cfg.BufferBytes, f.Length())+plen-1)/plen) + 1
	for i := begin; i < min(begin+startPieces, end); i++ {
		need[i] = true
	}
	for i := max(end-edgePieces, begin); i < end; i++ {
		need[i] = true
	}
	if len(need) == 0 {
		return 100
	}
	done := 0
	for i := range need {
		if s.tor.PieceState(i).Complete {
			done++
		}
	}
	return float64(done) * 100 / float64(len(need))
}

// pickFile chooses the requested file, or the largest video file.
func pickFile(files []*atorrent.File, index int) (*atorrent.File, int, error) {
	var best *atorrent.File
	videos := 0
	for _, f := range files {
		if !scanner.IsVideo(f.Path()) {
			continue
		}
		videos++
		if best == nil || f.Length() > best.Length() {
			best = f
		}
	}
	if index >= 0 {
		if index >= len(files) {
			return nil, videos, fmt.Errorf("file index %d out of range (%d files)", index, len(files))
		}
		return files[index], videos, nil
	}
	if best == nil {
		return nil, 0, ErrNoVideo
	}
	return best, videos, nil
}

func indexOf(files []*atorrent.File, f *atorrent.File) int {
	for i, x := range files {
		if x == f {
			return i
		}
	}
	return -1
}

// SweepCache deletes session directories left behind by a crash or a killed process.
func SweepCache(cacheRoot string) error {
	entries, err := os.ReadDir(cacheRoot)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var errs []error
	for _, e := range entries {
		if e.IsDir() && strings.HasPrefix(e.Name(), sessionDirPrefix) {
			errs = append(errs, os.RemoveAll(filepath.Join(cacheRoot, e.Name())))
		}
	}
	return errors.Join(errs...)
}
