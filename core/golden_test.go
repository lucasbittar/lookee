package lookeecore

import (
	"bytes"
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"testing"

	"github.com/lucasbittar/lookee/core/db"
	"github.com/lucasbittar/lookee/core/engine"
	"github.com/lucasbittar/lookee/core/metadata"
	"github.com/lucasbittar/lookee/core/scanner"
	"github.com/lucasbittar/lookee/core/subtitles"
	"github.com/lucasbittar/lookee/core/torrent"
)

var update = flag.Bool("update", false, "rewrite testdata/golden from the current DTOs")

// goldenDTOs holds one fully populated example of every JSON shape the bridge returns.
// The Kotlin unit tests decode these same files strictly (unknown keys fail), so a field
// renamed or added in Go breaks a test on one side or the other. After an intentional
// change: go test . -run TestGolden -update, then update the Kotlin models.
func goldenDTOs() map[string]any {
	title := db.Title{
		ID: 7, Category: db.Movies, Status: db.StatusMatched, TMDBID: 949, Kind: db.KindMovie,
		Name: "Heat", Original: "Heat", Year: 1995, Tagline: "A Los Angeles crime saga",
		Overview: "Obsessive master thief Neil McCauley…", ReleaseDate: "1995-12-15", Runtime: 170,
		Genres: []string{"Action", "Crime", "Drama"}, Rating: 7.9,
		PosterURL:   "https://image.tmdb.org/t/p/w500/poster.jpg",
		BackdropURL: "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
		AddedAt:     1764000000, UpdatedAt: 1764000100,
	}
	file := db.File{ID: 3, RootID: 1, Path: "/storage/emulated/0/Download/Movies/Heat (1995)/Heat.mkv", Kind: "file",
		Size: 9_100_000_000, MTime: 1700000000, TitleID: 7, Season: 1, Episode: 2, EpisodeEnd: 3, AddedAt: 1764000000}
	return map[string]any{
		"settings": engine.SettingsView{TMDBKeySet: true, OpenSubtitlesKeySet: true, OpenSubtitlesLoginSet: false,
			TMDBLanguage: "en-US", SubtitleLanguages: "pt-BR,en", TorrentSources: 2},
		"remote_setup": engine.RemoteSetup{URL: "http://192.168.1.20:41234/", PIN: "076386", ExpiresIn: 600},
		"roots":        []db.Root{{ID: 1, Path: "/storage/emulated/0/Download/Movies", Category: db.Movies, Available: true, LastScan: 1764000000}},
		"volumes":      []Volume{{Path: "/storage/emulated/0", Label: "Internal storage", Removable: false, FreeBytes: 20_000_000_000, TotalBytes: 1_000_000_000_000}},
		"scan_progress": ScanUpdate{ScanEvent: engine.ScanEvent{Phase: "matching", Root: "/storage/emulated/0/Download/Movies",
			FilesSeen: 120, Matched: 4, ToMatch: 9, Title: "Heat"}},
		"scan_finished": ScanUpdate{ScanEvent: engine.ScanEvent{Phase: "finished", Matched: 9}, Report: &engine.ScanReport{
			Roots:   []scanner.Result{{RootID: 1, Root: "/storage/emulated/0/Download/Movies", Unavailable: true, Seen: 120, Added: 9, Updated: 1, Removed: 2}},
			Matched: 9, Unmatched: 1, NeedsAPIKey: true, MatchError: "TMDB rejected the API key"}},
		"scan_failed": ScanUpdate{ScanEvent: engine.ScanEvent{Phase: "failed"}, Error: "cancelled", Code: CodeCancelled},
		"titles":      []db.Title{title, {ID: 8, Category: db.Movies, Status: db.StatusUnmatched, Name: "Marcus King - Four Of a Kind",
			PreviewPath: "/storage/emulated/0/Download/Movies/Marcus King/marcus.mkv", AddedAt: 1764000000, UpdatedAt: 1764000000}},
		"details": engine.TitleDetails{Title: title, Section: "movies",
			Cast:     []db.CastMember{{Order: 0, Name: "Al Pacino", Character: "Vincent Hanna", ProfileURL: "https://image.tmdb.org/t/p/w185/pacino.jpg"}},
			Episodes: []db.Episode{{Season: 1, Episode: 2, Name: "Pilot", Overview: "It begins.", StillURL: "https://image.tmdb.org/t/p/w300/still.jpg", AirDate: "2020-01-01"}},
			Files:    []engine.FileView{{File: file, PositionMS: 60_000, DurationMS: 10_200_000}},
		},
		"title_ref": TitleRef{TitleID: 7},
		"home": engine.HomeView{
			ContinueWatching: []engine.ContinueItem{{Key: file.Path, TitleID: 7, Title: "Doctor Who", Category: "TV_SHOWS", Kind: "tv",
				Year: 2005, Overview: "The Doctor travels in time.", Genres: []string{"Sci-Fi"}, PosterURL: title.PosterURL,
				BackdropURL: title.BackdropURL, Season: 1, Episode: 3, EpisodeName: "The Unquiet Dead",
				StillURL: "https://image.tmdb.org/t/p/w300/still.jpg", PositionMS: 1_122_000, DurationMS: 2_661_000}},
			RecentlyAdded: []db.Title{title},
			Counts:        map[string]int{"MOVIES": 72, "TV_SHOWS": 6},
		},
		"series": engine.SeriesView{
			InProgress: []engine.ShowProgress{{Title: title, Next: &engine.EpisodeRef{Key: file.Path, Season: 1, Episode: 3,
				Name: "The Unquiet Dead", StillURL: "https://image.tmdb.org/t/p/w300/still.jpg", PositionMS: 1_122_000, DurationMS: 2_661_000},
				Season: 1, Watched: 2, Total: 13}},
			All: []engine.ShowProgress{{Title: title, Season: 2, Watched: 10, Total: 10}},
		},
		"seed_result": SeedResult{Applied: 2},
		"browse": []engine.BrowseEntry{
			{Entry: scanner.Entry{Name: "Lesson 2", Path: "/storage/emulated/0/Download/Lessons/Lesson 2", IsDir: true, ModTime: 1700000000}},
			{Entry: scanner.Entry{Name: "solo.mp4", Path: "/storage/emulated/0/Download/Lessons/solo.mp4", Size: 1_000_000, ModTime: 1700000000, Subtitle: true},
				PositionMS: 30_000, DurationMS: 600_000},
		},
		"progress":  []db.Progress{{Key: file.Path, TitleID: 7, PositionMS: 60_000, DurationMS: 10_200_000, UpdatedAt: 1764000200}},
		"sidecars":  []subtitles.Sidecar{{Path: "/movies/Heat.pt-BR.srt", Name: "Heat.pt-BR.srt", Language: "pt-BR", Format: "srt"}},
		"subtitles": []subtitles.Result{{FileID: 123, FileName: "Heat.1995.srt", Language: "pt-BR", Release: "Heat.1995.1080p.BluRay", Downloads: 5000, Rating: 8.5, HearingImpaired: true, HashMatch: true, Trusted: true}},
		"path_ref":  PathRef{Path: "/data/user/0/com.lookee.tv/cache/subtitles/Heat.srt"},
		"discover_items": []engine.DiscoverItem{{SearchResult: metadata.SearchResult{ID: 45745, Kind: "movie", Title: "Sintel",
			OriginalTitle: "Sintel", Year: 2010, Overview: "A lonely young woman searches for the baby dragon she raised.",
			PosterURL: "https://image.tmdb.org/t/p/w500/sintel.jpg", Popularity: 12.5, VoteCount: 900}, LibraryTitleID: 7}},
		"tmdb_results": []metadata.SearchResult{{ID: 949, Kind: "movie", Title: "Heat", OriginalTitle: "Heat", Year: 1995,
			Overview: "Obsessive master thief…", PosterURL: "https://image.tmdb.org/t/p/w500/poster.jpg", Popularity: 55.2, VoteCount: 7000}},
		"torrent_search": engine.TorrentSearch{Results: []torrent.Result{{Provider: "yts", Title: "Heat (1995) 1080p", Quality: "1080p",
			Size: 2_000_000_000, Seeders: 120, Peers: 30, InfoHash: "08ada5a7a6183aae1e09d831df6748d566095a10",
			Magnet: "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10", FileIndex: -1}},
			Warnings: []string{"eztv: timeout"}},
		"stream_stats": torrent.Stats{State: "ready", Name: "Sintel", FileName: "Sintel.mp4", Size: 129_241_752, Downloaded: 30_000_000,
			SpeedBps: 4_800_000, Peers: 11, Seeders: 10, BufferPercent: 100, URL: "http://127.0.0.1:33569/stream",
			Error: "", ElapsedSeconds: 12, FileIndex: 1, VideoFilesCount: 1},
		"logs": []string{"time=2026-11-25T12:00:00Z level=INFO msg=\"engine started\""},
	}
}

func TestGolden(t *testing.T) {
	dir := filepath.Join("testdata", "golden")
	if *update {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	for name, v := range goldenDTOs() {
		got, err := json.MarshalIndent(v, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		got = append(got, '\n')
		path := filepath.Join(dir, name+".json")
		if *update {
			if err := os.WriteFile(path, got, 0o644); err != nil {
				t.Fatal(err)
			}
			continue
		}
		want, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("%s: %v (run: go test . -run TestGolden -update)", name, err)
		}
		if !bytes.Equal(got, want) {
			t.Errorf("%s.json changed; if intended, run `go test . -run TestGolden -update` and update the Kotlin models.\ngot:\n%s", name, got)
		}
	}
}
