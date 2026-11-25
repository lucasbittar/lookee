# CLAUDE.md — lookee (Go Core + Android TV Kotlin Shell)

## 1. Project Overview
**lookee** is a lightweight, native Android TV media center engineered specifically for Xiaomi Mi Box (Android TV OS). It uses a **hybrid architecture**:
- **Go Core (`core/`):** Handles all heavy lifting—USB filesystem scanning, regex filename parsing, TMDB metadata scraping, SQLite persistence, OpenSubtitles searching, and sequential BitTorrent streaming (`anacrolix/torrent`) served over a local HTTP range server. Compiled into a native Android archive (`lookeecore.aar`) via `gomobile bind`.
- **Kotlin TV Shell (`app/`):** A thin, ultra-responsive 10-foot UI built with **Jetpack Compose for TV** and **AndroidX Media3 (ExoPlayer)** for hardware-accelerated video decoding and D-pad navigation.

**Core Workflows:**
1. **Local Media Library (Movies, TV Shows & Concerts):** Scans connected USB external hard drives on startup and automatically fetches titles, synopses, posters, backdrops, and cast photos from **The Movie Database (TMDB)**.
2. **Direct Folder Playback (Tutorials & Personal Videos):** Browses and plays unindexed videos (guitar lessons, solo tutorials, personal clips) directly from external storage folders without metadata scraping.
3. **On-Demand Subtitles & Torrent Streaming:** Searches subtitles mid-playback and streams movies, series, and live shows sequentially from torrents (Popcorn Time style) without full pre-downloads.

**Primary Build Deliverable:** A sideloadable Android TV APK (`app-debug.apk` / `app-release.apk`) with `lookeecore.aar` bundled inside.

---

## 2. Tech Stack & Architecture

### A. Go Core Engine (`core/` — Go 1.26+)
- **FFI Bridge:** `golang.org/x/mobile/bind` (`gomobile bind`) exporting clean, JSON-serialized APIs to Kotlin (avoiding complex Go struct/slice FFI restrictions).
- **Database:** `modernc.org/sqlite` + `database/sql` (pure Go SQLite driver—zero CGO cross-compilation issues on Android `arm64`/`arm`).
- **Torrent Engine:** `github.com/anacrolix/torrent` configured for sequential piece prioritization, exposing torrent files as Go `io.ReadSeeker` streams.
- **Local Stream Server:** Go standard library `net/http` (`http.ServeContent`) serving active torrent streams on `http://127.0.0.1:<dynamic_port>/stream` with full HTTP `206 Partial Content` byte-range seeking support.
- **HTTP Client:** Go `net/http` for TMDB v3 API, OpenSubtitles REST API, and torrent indexers (YTS, EZTV, configurable JSON endpoints).

### B. Android TV Kotlin Shell (`app/` — Kotlin 2.0+)
- **UI Framework:** Jetpack Compose for TV (`androidx.tv:tv-foundation`, `androidx.tv:tv-material`) — 100% D-pad directional navigation.
- **Media Playback:** AndroidX Media3 (`media3-exoplayer`, `media3-ui`, `media3-datasource`, `media3-session`) leveraging Mi Box hardware `MediaCodec` (H.264, HEVC/H.265, VP9, AC3/EAC3, DTS passthrough).
- **Image Loading:** Coil (`coil-compose`) with bounded disk/memory cache (`150MB` max).
- **Architecture:** Thin MVVM (`ViewModel` + `StateFlow`) calling the Go core on `Dispatchers.IO` and deserializing JSON payloads via `kotlinx.serialization`.
- **Build System:** Gradle Kotlin DSL (`.kts`), `minSdk = 26` (Android 8.0), `targetSdk = 34`, packaging `arm64-v8a` and `armeabi-v7a` ABIs.

---

## 3. Go ↔ Kotlin Bridge Contract (`gomobile` Rules)
Because `gomobile bind` only supports a strict subset of Go types (`string`, `int`, `int64`, `float64`, `bool`, `[]byte`, `error`, and simple exported interfaces), follow this contract strictly:
- **Pass JSON over the boundary:** Exported Go functions accept primitive arguments (`string`, `int64`, `bool`) and return `(string, error)` where the `string` is a JSON-encoded DTO.
- **Callbacks via Interface:** Export a `ScanCallback` or `StreamCallback` Go interface (`OnProgress(jsonPayload string)`) that Kotlin implements to receive real-time sync progress or torrent buffering stats.
- **Initialization:** Kotlin calls `Lookeecore.initEngine(dbPath, cacheDir, externalUsbPath)` once in `Application.onCreate()`, passing Android's writable `filesDir` and `cacheDir` paths to Go.

---

## 4. Functional Specifications

### A. External USB Storage Scanner & Unindexed Videos
- **USB Access:** Kotlin requests persistent folder permissions via Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) or `MANAGE_EXTERNAL_STORAGE` and passes resolved mount paths (`/storage/<UUID>/...`) to Go. (For SAF URIs without direct POSIX paths, expose a lightweight `DocumentFile` directory lister callback to Go).
- **Folder Categories:**
  - `MOVIES` → Scraped via TMDB Movie API.
  - `TV_SHOWS` → Scraped via TMDB TV API.
  - `CONCERTS` → Scraped via TMDB Movie/TV API with clean filename fallback.
  - `TUTORIALS` → Raw hierarchical folder browser; **zero** TMDB scraping (ideal for guitar solo lessons, backing tracks, and personal videos).
- **Supported Extensions:** `.mkv`, `.mp4`, `.avi`, `.mov`, `.webm`, `.m4v`, `.ts`.
- **Startup Incremental Sync:** On app launch, Go runs an asynchronous goroutine scan comparing file paths + `mtime`/`size` against SQLite, fetching metadata only for new files and pruning disconnected/deleted entries without blocking UI rendering.

### B. Filename Parser & TMDB Scraper (Go)
- Normalize filenames using compiled Go `regexp` rules (strip `1080p`/`2160p`/`4K`, `x264`/`x265`/`HEVC`, `DTS`/`AAC`, release tags; extract `title`, `year`, `season`, `episode`).
- Query TMDB v3 API and cache in SQLite:
  - Title, Tagline, Synopsis/Overview, Release Date, Runtime, Genres, Rating.
  - Poster (`w500`) and Backdrop (`w1280`) URLs.
  - Top 15 Cast members (Name, Character, Profile Photo `w185`).
  - Episode titles, synopses, and stills for `TV_SHOWS`.
- Provide a **"Fix Match"** endpoint (`SearchAndRebindTMDB(mediaId, manualQuery)`) so the user can manually override inaccurate matches from the TV UI.

### C. Subtitle Search & Hot-Reload
- Detect sidecar `.srt`, `.vtt`, `.ass` files matching local video filenames plus embedded MKV/MP4 subtitle tracks in ExoPlayer.
- **Online Subtitle Search (Go):** Query OpenSubtitles REST API by file hash, TMDB ID, or text query filtered by preferred languages (`pt-BR`, `en`). Download `.srt` to `cacheDir` and return the local file path to Kotlin.
- **Player Integration:** Hot-load downloaded `.srt` tracks into ExoPlayer (`MediaItem.SubtitleConfiguration`) mid-playback without restarting the stream, plus D-pad controls for font size and sync delay (`-5.0s` to `+5.0s` in `100ms` increments).

### D. Torrent Search & Sequential Streaming (Popcorn Time Mode)
- **Discovery:** Browse trending Movies/Shows via TMDB or search any movie, series, or live concert not on the local HDD.
- **Torrent Providers (Go):** Pluggable `TorrentProvider` interface querying public JSON APIs (YTS for movies, EZTV for TV episodes, and configurable Torznab/Stremio JSON endpoints), sorted by seeders and quality (`720p`, `1080p`, `4K`).
- **Sequential Streaming (`core/streaming`):**
  - Start `anacrolix/torrent` client on demand when a user selects a magnet/torrent.
  - Select the largest video file in the torrent, call `file.SetPriority(torrent.PiecePriorityNormal)` with read-ahead window (`file.SetReadahead(25 * 1024 * 1024)`), and prioritize header/footer pieces so MP4/MKV metadata atoms resolve immediately.
  - Serve `file.NewReader()` via Go's `http.ServeContent` on `http://127.0.0.1:<port>/stream`.
  - Stream buffering progress (download speed KB/s, active peers, buffer %) to Kotlin's buffering overlay before ExoPlayer starts playback.
  - **Mi Box Storage Hygiene:** Store active torrent piece cache on external USB storage if available, otherwise in `cacheDir` with strict automatic deletion (`os.RemoveAll`) when playback stops (critical for Mi Box's 8GB internal storage).

---

## 5. Repository Structure
```text
lookee/
├── CLAUDE.md
├── README.md
├── docs/ROADMAP.md                  # Phased plan, exit criteria, decisions & risks
├── Makefile                         # Orchestrates Go tests, gomobile bind, and Gradle APK build
├── core/                            # Pure Go Media & Torrent Engine
│   ├── go.mod
│   ├── lookeecore.go                # Exported gomobile API surface (JSON-in / JSON-out)
│   ├── engine/
│   │   └── engine.go                # Application layer shared by lookee-cli and lookeecore.go
│   ├── db/
│   │   ├── sqlite.go                # modernc.org/sqlite migrations & queries
│   │   └── models.go
│   ├── scanner/
│   │   ├── scanner.go               # Incremental filesystem walker
│   │   ├── browse.go                # Live folder listing for TUTORIALS
│   │   └── parser.go                # Release filename regex normalizer
│   ├── metadata/
│   │   ├── tmdb.go                  # TMDB v3 client
│   │   └── binder.go                # Filename → TMDB match scoring
│   ├── subtitles/
│   │   └── opensubtitles.go         # Subtitle search & .srt downloader
│   ├── remote/
│   │   └── server.go                # PIN-protected LAN settings page for pasting API keys from a phone
│   ├── torrent/
│   │   ├── providers.go             # YTS / EZTV / configurable indexer clients
│   │   └── streamer.go              # anacrolix/torrent + http.ServeContent range server
│   └── cmd/
│       └── lookee-cli/main.go       # Local CLI harness to test scanning & streaming on macOS
└── app/                             # Thin Android TV Kotlin Shell
    ├── libs/lookeecore.aar          # gomobile bind output (git-ignored)
    ├── build.gradle.kts             # Imports libs/lookeecore.aar
    └── src/main/
        ├── AndroidManifest.xml      # Leanback launcher, TV banner, D-pad hardware flags
        └── java/com/lookee/tv/
            ├── LookeeApp.kt         # Initializes Lookeecore Go engine
            ├── MainActivity.kt
            ├── data/
            │   └── GoCoreBridge.kt  # Kotlin wrapper & kotlinx.serialization parser
            └── ui/
                ├── theme/           # Dark-first cinema theme (#0D0F12) & D-pad focus styles
                ├── navigation/      # Top navigation rail (Home, Movies, Series, Concerts, Tutorials, Discover, Settings)
                ├── home/            # Carousels & startup sync indicator
                ├── details/         # Backdrop, synopsis, cast avatars, Fix Match dialog
                ├── tutorials/       # Unindexed hierarchical folder & video browser
                ├── discover/        # TMDB + Torrent search & quality picker
                ├── player/          # Media3 ExoPlayer, D-pad OSD, subtitle search & sync offset
                └── settings/        # USB folder picker, TMDB/OpenSubtitles API keys, Clear Stream Cache

---

## 6. Build, Test & Packaging Commands

**1. Run Go Core Unit Tests (Filename Parser, SQLite, Providers)**
```bash
cd core && go test -v ./...
```

**2. Compile Go Core into Android `.aar` (arm64 + arm)**
(Requires `go install golang.org/x/mobile/cmd/gomobile@latest` and `gomobile init`)
```bash
mkdir -p app/libs
cd core && gomobile bind -v -target=android/arm64,android/arm -androidapi 26 -o ../app/libs/lookeecore.aar .
```
Add `android/386` to `-target` for debug builds that run in the Android 9 TV emulator (32-bit x86). `make bind` does this; `make bind-release` is ARM-only. Prefer the `Makefile` targets (`make test`, `make lint`, `make bind`, `make apk`, `make install MIBOX_IP=…`, `make doctor`), which also set the SDK/NDK/JDK paths.

**3. Build Sideloadable Android TV APK**
```bash
./gradlew assembleDebug
# Output APK: app/build/outputs/apk/debug/app-debug.apk
```

**4. Deploy Directly to Mi Box via ADB**
```bash
adb connect <MIBOX_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 7. Implementation Roadmap (Execute in Order)

The detailed plan — tasks, exit criteria, decisions and risks for each phase — lives in [`docs/ROADMAP.md`](docs/ROADMAP.md). Update the status table there when a phase starts or finishes. Where the roadmap's **Decisions** section differs from sections 1–5 above, the roadmap wins.

- **Phase 0 — Toolchain & Repo Scaffolding:** Install JDK 17, Android SDK/NDK, `gomobile` and `adb`; add `Makefile`; confirm the Mi Box ABI. Target device: **MIBOX3, Android 9 (API 28), `armeabi-v7a` only, ~500 MB free RAM**. Its single USB port leaves host mode while USB debugging is on, so ADB and the library drive cannot be used together (see ROADMAP Decision 12).
- **Phase 1 — Go Core Foundation & CLI Harness:** Initialize `core/go.mod`, implement `modernc.org/sqlite` schema, regex filename parser (with unit tests), TMDB client, and `cmd/lookee-cli` so everything can be verified locally on macOS.
- **Phase 2 — Go Torrent Streamer & Subtitle Client:** Implement `anacrolix/torrent` sequential streamer with `http.ServeContent` byte-range server, torrent search providers, OpenSubtitles downloader, and the phone settings page server (`core/remote/`).
- **Phase 3 — gomobile Binding & Android TV Shell:** Export JSON FFI functions in `lookeecore.go`, generate `lookeecore.aar`, and scaffold the Jetpack Compose for TV project with Leanback manifest, D-pad focus styles, and `GoCoreBridge.kt`.
- **Phase 4 — TV Screens & USB Storage Integration:** Build Home, Movies/Series/Concerts carousels, Media Details (with cast photos and Fix Match), unindexed Tutorials folder browser, Discover/Torrent search, and Settings (USB folder picker, in-app API key storage, and "Set up from your phone" QR + PIN screen).
- **Phase 5 — Media3 ExoPlayer & Subtitle Hot-Reload:** Wire ExoPlayer to local USB files and `http://127.0.0.1:<port>/stream`, add D-pad OSD controls, resume playback progress, and live OpenSubtitles search/sync offset.
- **Phase 6 — End-to-End APK Build:** Run `Makefile` pipeline (`go test` → `gomobile bind` → `./gradlew assembleDebug`) and verify zero lint/packaging errors; add CI and tagged releases.

---

## 8. Coding Conventions for Claude Code

- **Keep Kotlin Strictly Presentation-Only:** Never duplicate HTTP clients, regex parsers, or SQLite databases in Kotlin—all business logic and persistence live in `core/`.
- **Strict D-Pad Focus:** Every clickable card, button, and player control in Compose must support D-pad directional focus with visible scale/border highlights (`BorderStroke` on focus) and no touch-only gestures.
- **Mi Box Resource Discipline:** Enforce automatic cleanup of temporary torrent pieces when playback ends, and allow configuring API keys (`TMDB_API_KEY`, `OPENSUBTITLES_API_KEY`) inside the TV Settings screen (stored in Go's SQLite `settings` table) so the APK works out of the box without hardcoding secrets.
- **Phone Settings Page Stays Locked Down:** The LAN settings server is off unless the user opens the setup screen, requires the PIN shown on the TV, shuts down on save or timeout, and never returns stored secrets to the browser.
- **Diagnose Without ADB:** Errors that matter on the device must reach the Go core's rolling log (viewable from the phone page), because drive-related testing happens with USB debugging, and therefore `logcat`, off.
- **Verify Before Moving On:** A phase is finished only when its exit criteria in `docs/ROADMAP.md` pass. Unit tests never hit the live network; use `httptest` fixtures.
- **Keep Docs Current:** When a decision changes or a risk is retired, update `docs/ROADMAP.md` in the same change.
