# lookee — Implementation Roadmap

This is the working plan for building lookee. `CLAUDE.md` is the spec (what the app is and the rules for building it); this file is the plan (what order to build it in, and how we know each step is finished).

Each phase ends with **exit criteria** that can be checked with a command or on the device. A phase is not done until every criterion passes.

**Status legend:** ⬜ not started · 🟨 in progress · ✅ done

| Phase | Name | Runs on | Status |
|---|---|---|---|
| 0 | Toolchain & repo scaffolding | macOS | ✅ |
| 1 | Go core foundation & CLI harness | macOS | ✅ |
| 2 | Torrent streamer & subtitle client | macOS | ⬜ |
| 3 | gomobile binding & Android TV shell | macOS + emulator | ⬜ |
| 4 | TV screens & USB storage | emulator + Mi Box | ⬜ |
| 5 | Player & subtitle hot-reload | Mi Box | ⬜ |
| 6 | End-to-end build, CI & release | macOS + GitHub | ⬜ |

The guiding idea: everything in Phases 1–2 is plain Go that runs and is tested on the Mac through `lookee-cli`. No Android device is needed until Phase 3, and by then the engine already works.

---

## Phase 0 — Toolchain & repo scaffolding

The development machine is an Intel Mac with Go 1.27. Homebrew could not write to `/usr/local/libexec` without `sudo`, so the JDK and SDK were installed per-user from the official sources instead (checksums verified):

| Tool | Version | Location |
|---|---|---|
| JDK | Temurin 17.0.21.1 | `~/Library/Java/JavaVirtualMachines/` |
| Android SDK | cmdline-tools 16111833, platforms 34 + 35, build-tools 35.0.0 | `~/Library/Android/sdk` |
| NDK | r28 (`28.2.13676358`) | `~/Library/Android/sdk/ndk/` |
| Emulator image | `system-images;android-28;android-tv;x86` (Android 9, same as the Mi Box) | |
| gomobile / gobind | `x/mobile` 2026-11-17 | `~/go/bin` |

The `Makefile` sets `ANDROID_HOME`, `ANDROID_NDK_HOME` and `JAVA_HOME` itself and calls `adb`/`gomobile` by full path (macOS ships GNU Make 3.81, which ignores an exported `PATH`), so no shell profile changes are required. `make doctor` checks all of it.

**macOS Local Network privacy.** On macOS 15, third-party programs started from a terminal (`adb`, Go binaries) cannot reach LAN devices unless the terminal app is allowed under System Settings → Privacy & Security → Local Network. The symptom is `No route to host` while `ping` and `nc` (Apple binaries, exempt) work. After granting it, restart tmux (`tmux kill-server`) and `adb kill-server`, since already-running processes keep the old state.

**Tasks**

- [x] Install JDK 17.
- [x] Install the Android command-line tools, platform-tools, platforms 34/35, build-tools, NDK r28 and the Android 9 TV emulator image.
- [x] Toolchain paths handled by the `Makefile` (no shell profile edits needed).
- [x] `go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest && gomobile init`.
- [x] Add `.gitignore`, `LICENSE` (MIT) and `README.md`.
- [x] `Makefile` with `test`, `lint`, `cli`, `bind`, `bind-release`, `apk`, `install`, `devices`, `doctor`, `all`.
- [x] Record the Mi Box model and Android version (MIBOX3, Android 9).
- [x] Confirm the ABI: `make devices MIBOX_IP=<ip>` → `armeabi-v7a,armeabi` (needs USB debugging enabled on the box; it also opens network ADB on port 5555).

**Exit criteria**

- `go version`, `java -version`, `gomobile version`, `adb version` all succeed.
- ✅ `adb connect <MIBOX_IP>:5555` reaches the box and the ABI list is written into this file (see "Target device" below).

---

## Phase 1 — Go core foundation & CLI harness

**Goal:** point `lookee-cli` at a folder of video files on the Mac and get a fully scraped library in SQLite.

**Tasks**

- [x] `core/go.mod` (module `github.com/lucasbittar/lookee/core`, Go 1.26+), package layout from `CLAUDE.md` §5, plus `core/engine` (Decision 9).
- [x] `db/`: open `modernc.org/sqlite` in WAL mode, versioned migrations, and the schema:
  - `settings(key, value)` — API keys, TMDB language, preferred subtitle languages.
  - `library_roots(id, path, category, available, last_scan)` — category is `MOVIES`, `TV_SHOWS`, `CONCERTS` or `TUTORIALS`.
  - `media_files(id, root_id, path, size, mtime, title_id, season, episode, episode_end)`.
  - `titles(id, category, group_key, status, tmdb_id, kind, name, …, poster_url, backdrop_url)` — `status` is `pending`, `matched`, `unmatched` or `manual` (a user fix, never overwritten).
  - `episodes(title_id, season, episode, name, overview, still_url)`.
  - `cast_members(title_id, ord, name, character, profile_url)`.
  - `playback_progress(key, title_id, position_ms, duration_ms, updated_at)` — key is a file path or stream key.
- [x] `scanner/parser.go`: filename → `{title, year, season, episode}`. Table-driven tests with 47 real-world release names (movies, `S01E02`, `1x02`, multi-episode, year-in-title such as "2001 A Space Odyssey", concerts, non-English titles).
- [x] `scanner/scanner.go`: incremental walk that compares path + size + mtime against `media_files`, inserts new files, prunes missing ones, and reports progress through a callback. An unmounted root is marked unavailable and **not** pruned. Skips macOS `._` files, `Android/`, system folders, extras and samples. Show folders name TV shows; `Title (Year)/` folders name movies with poor filenames.
- [x] `scanner/browse.go`: live folder listing for `TUTORIALS` with natural sort and sidecar-subtitle detection.
- [x] `metadata/tmdb.go`: search (movie/TV), details, credits (top 15), season/episode details, trending. Accepts a v3 key or v4 token, throttled, retries on HTTP 429, tested against `httptest` fixtures.
- [x] Match binder: scores title similarity (including original titles, e.g. "Cidade de Deus"), year proximity (±1 tolerated) and popularity; retries without year and without a country suffix ("The Office US"). Fix Match is two-step: `SearchCandidates` then `Rebind` (locks with status `manual`), plus one-step `SearchAndRebind`. Two folders that match the same TMDB entry are merged into one title.
- [x] `engine/`: scan → match pipeline. Without a key, titles wait as `pending`; a bad key or network error stops matching and leaves titles `pending` for next time.
- [x] `cmd/lookee-cli`: `roots`, `add`, `remove`, `scan [dir --category]`, `rematch`, `list`, `show`, `fix`, `browse`, `settings`; `--json` prints the DTOs the TV app will receive.
- [x] `.golangci.yml`; `make lint` is clean.

**Exit criteria**

- ✅ `cd core && go test ./...` passes (`make test`).
- ✅ `lookee-cli scan ~/Movies --category MOVIES` followed by `lookee-cli list` prints titles with posters and cast; running the scan a second time makes zero TMDB requests.
- ✅ **Real-library run** (2026-11-24): the box's library tree (3,968 files, 441 folders, ~766 GB) was read over the LAN and rebuilt on the Mac as sparse placeholder files (see "Testing with the real library"). Result after fixes: 53 titles matched, **0 wrong matches**, 5 unmatched (a vocal course filed under TV, a bootleg, and titles TMDB doesn't list). Fixes that came out of it, each with a regression test:
  - TV show = top-level folder under the root (`Friends/The Reunion/…` is Friends; `Course/Volume 1/…` is not a show called "Volume 1"); `Volume N` folders count as seasons.
  - Movie title = top-most folder with a year (`Title (Year)/`), else the filename; year-less multi-part folders group by folder.
  - Dotted acronyms collapse (`P. U. L. S. E.` → `PULSE`, `S.H.I.E.L.D.` → `SHIELD`) in the parser and in match scoring.
  - Match tiers: close title + agreeing year wins immediately; a near-exact title with a different year (concert vs. release year) is accepted only if nothing better exists; weak titles with a disagreeing year are rejected.
  - Extra TMDB attempts: without filler words (`Live at` vs TMDB's `Live @`), with the filename's title, and TV after movie for the Movies category.
- ✅ **On-device check:** the `db`, `scanner` and `engine` test suites, cross-compiled for `linux/arm` (GOARM=7), all pass on the MIBOX3 itself.
- ✅ **Early bind check:** `make bind` produces `app/libs/lookeecore.aar` (23 MB) with `armeabi-v7a`, `arm64-v8a` and `x86` libraries, including the SQLite driver, in about 45 s.

---

## Phase 2 — Torrent streamer & subtitle client

**Goal:** `lookee-cli stream <magnet>` prints a `http://127.0.0.1:<port>/stream` URL that VLC on the Mac can play and seek.

**Tasks**

- [ ] `torrent/providers.go`: `TorrentProvider` interface, YTS and EZTV clients, and a generic Torznab client. All base URLs are settings, not constants, because these services move domains. Results merged and sorted by seeders, then quality.
- [ ] `torrent/streamer.go`:
  - One on-demand `anacrolix/torrent` client, file-based storage (no mmap), conservative connection limits for a 2 GB RAM box.
  - Pick the largest video file; prioritise first and last pieces; 25 MB read-ahead.
  - `http.ServeContent` over `file.NewReader()` on a random loopback port.
  - Stats callback every second: speed, peers, buffered %, "ready to play" flag.
  - `StopStream` closes the client and `os.RemoveAll`s the piece directory. A startup sweep removes any directory left by a crash.
- [ ] `subtitles/opensubtitles.go`: search by moviehash, TMDB id or text; language filter; download to `cacheDir`; optional login for the higher download quota.
- [ ] Subtitle offset: `ShiftSubtitle(path, offsetMs)` writes a shifted copy of the `.srt`. This keeps sync logic in Go and gives the player a plain file to reload (see Decision 4).
- [ ] `remote/`: phone settings page (see Decision 7). A small HTTP server in the Go core, bound to the LAN address, serving one embedded HTML form for API keys, subtitle languages and indexer URLs. It runs only while explicitly started, requires a 6-digit PIN that the TV displays, and shuts itself down after 10 minutes or on first successful save. Keys are never sent back to the browser; saved fields show as "set".
- [ ] `logs/`: rolling log file (size-capped, e.g. 2 × 1 MB) in the app's files directory; the phone page shows it behind the same PIN (Decision 12).
- [ ] CLI: `torrents search <query>`, `stream <magnet>`, `subs search|download|shift`, `remote serve`.

**Exit criteria**

- `go test ./...` passes, including a range-request test of the stream server against a fake `io.ReadSeeker`.
- A legal, well-seeded torrent (for example a Blender open movie) starts playing in VLC from the loopback URL within about 30 seconds, and seeking to the middle works.
- After `Ctrl-C`, the piece cache directory no longer exists.
- The streamer, cross-compiled for `linux/arm` and run on the MIBOX3 over `adb shell`, streams a test torrent with resident memory staying under 150 MB.
- `lookee-cli remote serve` prints a URL and PIN; saving a key from a phone on the same Wi-Fi shows up in `lookee-cli settings get`, and a wrong PIN is rejected.
- `gomobile bind` for both ARM targets still succeeds with the torrent library linked in.

---

## Phase 3 — gomobile binding & Android TV shell

**Goal:** an APK that launches on the TV, initialises the Go engine and shows real data from it.

**Tasks**

- [ ] `core/lookeecore.go`: the exported surface. Every function takes primitives and returns `(string, error)` with a JSON body.

  | Area | Functions |
  |---|---|
  | Lifecycle | `InitEngine`, `Shutdown` |
  | Settings | `GetSettings`, `SetSetting`, `StartRemoteSetup`, `StopRemoteSetup` |
  | Library roots | `ListRoots`, `AddRoot`, `RemoveRoot`, `ListStorageVolumes` |
  | Scan | `StartScan(cb ScanCallback)`, `CancelScan` |
  | Library | `ListMedia(category, offset, limit)`, `GetMediaDetails`, `SearchAndRebindTMDB`, `BrowseFolder` |
  | Progress | `SaveProgress`, `GetContinueWatching` |
  | Subtitles | `FindSidecarSubtitles`, `SearchSubtitles`, `DownloadSubtitle`, `ShiftSubtitle` |
  | Discover | `GetTrending`, `SearchTMDB`, `SearchTorrents` |
  | Streaming | `StartStream(magnet, cb StreamCallback)`, `StopStream`, `ClearStreamCache` |

- [ ] JSON DTO contract test in Go (golden files) so Kotlin and Go cannot drift silently.
- [ ] Gradle project: Kotlin DSL, version catalog, `minSdk 26`, `targetSdk 34`, `app/libs/lookeecore.aar` as a file dependency.
- [ ] Manifest: `LEANBACK_LAUNCHER` intent filter, banner, `android.software.leanback` required, touchscreen not required, `INTERNET`, storage permissions, and a network security config that allows cleartext to `127.0.0.1` only (needed for the loopback stream).
- [ ] `LookeeApp.kt` calls `InitEngine`; `GoCoreBridge.kt` wraps every call in `withContext(Dispatchers.IO)` and decodes with `kotlinx.serialization`.
- [ ] Theme (`#0D0F12`), a shared focusable-card component with scale + border on focus, and the navigation rail with seven empty destinations.
- [ ] `make serve-apk`: serve the debug APK on the LAN and print the URL to type into *Downloader* on the TV (Decision 12).
- [x] Debug builds also bind `android/386`, because the Android 9 TV emulator image is 32-bit x86 (`make bind` does this; `make bind-release` is ARM-only).

**Exit criteria**

- `make bind && ./gradlew assembleDebug` succeeds.
- In the Android TV emulator and on the Mi Box: the app appears on the launcher, opens, the rail is fully navigable with the D-pad, and a debug screen shows the settings JSON returned by Go.

---

## Phase 4 — TV screens & USB storage

**Goal:** the library is browsable from the couch.

**Tasks**

- [ ] Settings: storage permission flow, folder picker, category per folder, API keys, preferred subtitle languages, indexer URLs, Clear Stream Cache.
- [ ] Settings → "Set up from your phone": calls `StartRemoteSetup`, shows the URL as a QR code plus the PIN, and refreshes the settings screen when the phone saves. Leaving the screen calls `StopRemoteSetup`. On-screen keyboard entry stays available as a fallback.
- [ ] Home: Continue Watching, Recently Added, per-category rows, startup sync indicator driven by `ScanCallback`.
- [ ] Movies / Series / Concerts: paged poster grids.
- [ ] Details: backdrop, synopsis, cast row, season/episode list for series, Play / Resume, Fix Match dialog.
- [ ] Tutorials: folder browser backed by `BrowseFolder`, no metadata.
- [ ] Discover: trending rows, search, torrent list with quality and seeders, buffering overlay driven by `StreamCallback`.
- [ ] Coil configured with the 150 MB disk cap.

**Exit criteria**

- With a USB drive attached to the Mi Box: first launch scans it, posters appear, and every screen is reachable and operable with the remote only.
- Unplugging the drive and relaunching does not crash; entries are pruned or shown as unavailable.
- Fix Match changes a title and the change survives a rescan.
- Scanning the QR code with a phone, entering the PIN and pasting a TMDB key makes posters load on the TV without touching the remote's keyboard; the page is unreachable after leaving the screen.

---

## Phase 5 — Media3 player & subtitle hot-reload

**Tasks**

- [ ] Player screen with ExoPlayer for `file://` paths and the loopback stream URL.
- [ ] D-pad OSD: play/pause, ±10 s seek, hold-to-scrub, audio track and subtitle track pickers.
- [ ] Resume: save position every few seconds and on stop; prompt "Resume from 43:10?" on Details.
- [ ] Sidecar and embedded subtitles; online search panel inside the player.
- [ ] Hot-reload: rebuild the `MediaItem` with the new `SubtitleConfiguration` and re-prepare at the current position.
- [ ] Subtitle size control and sync offset (−5.0 s to +5.0 s, 100 ms steps) through `ShiftSubtitle`.
- [ ] Leaving the player always calls `StopStream`, including on process death recovery at next launch.
- [ ] Disc rips (Decision 13):
  - `dvd`: play the main title set (the `VTS_xx` group with the largest total `.VOB` size) as one continuous stream, served by the Go core from the loopback server if Media3 cannot play a concatenated VOB playlist directly.
  - `bluray`: play the largest `.m2ts`, or the main `.mpls` playlist's clips in order.
  - `iso`: the Go core reads the ISO 9660/UDF image and serves the main title over the loopback server.
  - First, a spike on the box: does Media3 play a `.VOB` and an `.m2ts` from your library, with sound? The result decides how much of the above the Go core must do.

**Exit criteria (on the Mi Box)**

- A 1080p H.264 file and a 4K HEVC file from USB play with hardware decoding.
- A torrent stream plays, seeks, and its cache is gone after exiting the player.
- A subtitle downloaded mid-playback appears within about two seconds, and the offset control visibly shifts it.
- A DVD rip, the Blu-ray rip and an ISO from the library each play with sound, or show a clear "can't play this disc" message.
- Internal storage use returns to baseline after a streaming session.

---

## Phase 6 — End-to-end build, CI & release

**Tasks**

- [ ] `make all` runs `go test` → `gomobile bind` → `./gradlew lint assembleDebug`.
- [ ] GitHub Actions: Go tests and `golangci-lint` on every push; full APK build on tags, attached to a GitHub Release.
- [ ] Release signing config read from environment variables, never committed.
- [ ] README: screenshots, install guide (sideload via `adb` or a downloader app), how to get TMDB and OpenSubtitles keys, legal note.
- [ ] `docs/ARCHITECTURE.md` with the Go ↔ Kotlin diagram and the bridge API reference.

**Exit criteria**

- A clean clone plus the Phase 0 toolchain produces an installable APK with one command.
- Zero lint errors; CI is green.

---

## Testing with the real library

The library drive is adopted storage (see Target device), so no computer can read it directly. To test the scanner and matcher against the real names:

1. Turn USB debugging **off** on the box (so the drive is connected) and start *WiFi File Explorer PRO* (serves the box's storage on port 8000).
2. Crawl the folder listings — names and sizes only, plain `GET`s of folder pages, never URLs with a query string (the server's Delete/Zip/Unzip actions are query-string URLs, and file links download the file).
3. Rebuild the tree locally with `truncate` (sparse files: real sizes, no disk used) and run `lookee-cli scan` against it with a copy of the dev database.

## Decisions

These are places where the plan departs from, or tightens, `CLAUDE.md`. Each lists the default the plan assumes.

1. **Storage access uses real paths, not the Storage Access Framework.** On the MIBOX3 the library is on adopted storage under `/storage/emulated/0/`, which `READ_EXTERNAL_STORAGE` covers on Android 9; drives used as removable storage on other devices appear at `/storage/<UUID>` and work the same way. Go can only read POSIX paths, and many Android TV builds ship without the system folder picker that `ACTION_OPEN_DOCUMENT_TREE` needs. Default: request `READ_EXTERNAL_STORAGE` on Android 10 and below (this is the path the MIBOX3 on Android 9 uses) and `MANAGE_EXTERNAL_STORAGE` on 11 and above (acceptable for a sideloaded app; can be granted with `adb shell appops set com.lookee.tv MANAGE_EXTERNAL_STORAGE allow` if the TV has no settings screen for it), and build our own folder picker on top of `BrowseFolder`. The `DocumentFile` callback bridge in §4A is dropped unless a device proves it necessary.
2. **Torrent cache goes in the app's external files directory** (`getExternalFilesDir()` → `/storage/emulated/0/Android/data/com.lookee.tv/files`). On this box that directory is on the 1 TB adopted drive, so the 8 GB internal flash is not at risk; the cache is still deleted when playback stops.
3. **Go package naming.** A local package called `torrent` collides with `github.com/anacrolix/torrent`, and §4D already refers to `core/streaming`. Default: keep the `core/torrent/` directory from §5 and import the library as `atorrent`.
4. **Subtitle sync is done by rewriting the file in Go.** ExoPlayer has no public subtitle-offset setting; shifting timestamps in Go and reloading the track is simple and keeps Kotlin presentation-only.
5. **Compose for TV lazy lists.** `tv-foundation`'s `TvLazyRow`/`TvLazyColumn` are deprecated in favour of the standard Compose lazy lists; the plan uses `tv-material` plus standard `LazyRow`/`LazyColumn`. Versions will be confirmed against current docs in Phase 3.
6. **`.aar` location is `app/libs/lookeecore.aar`** (the build commands), not `core/build/` (the old comment in the tree).
7. **Phone settings page.** Typing a 32-character API key with a D-pad is painful, so the Go core serves a settings form on the LAN that the TV advertises with a QR code. It is plain HTTP on the home network, so it is locked down: off by default, PIN shown only on the TV, auto-shutdown, write-only for secrets. The server and form live in `core/remote/` (built and tested in Phase 2); the TV screen is Phase 4.
8. **Licence is MIT.**
9. **`core/engine` is the application layer.** The spec's tree has no place for the logic that joins scanner, TMDB and database. `engine` holds it, and both `lookee-cli` and `lookeecore.go` call it, so the CLI tests exactly what the TV runs and the FFI file stays a thin JSON wrapper.
10. **Go 1.26 minimum.** Current `modernc.org/sqlite` and `x/mobile` require it, so the spec's "Go 1.23+" is raised.
11. **gomobile Java package is `com.lookee.core`.** Kotlin imports `com.lookee.core.lookeecore.Lookeecore`.
12. **ADB-free install and diagnostics, because the MIBOX3 cannot use ADB and the USB drive at the same time.**
    - *Install:* Phase 3 adds `make serve-apk`, which serves the APK over the LAN for the free *Downloader* app on the TV. `make install` stays for when debugging is on.
    - *Logs:* the Go core writes a rolling log file (Phase 2, `core/logs`). The phone settings page gets a PIN-protected "Diagnostics" view that shows it, so errors can be read from a phone without `logcat`.
13. **Disc rips are indexed and played.** (Decided 2026-11-24.) Each rip is one library item with a `kind`: a folder of `.VOB/.IFO/.BUP` → `dvd`; a `BDMV` folder, or a folder of flattened Blu-ray files (`.mpls/.clpi` next to `.m2ts`) → `bluray`; an `.iso` → `iso`. Loose `.m2ts/.mts` are ordinary files. Titles come from the `Title (Year)` folder. Playback is Phase 5 work, verified on the box; anything the box cannot decode must show a clear message, never fail silently.

## Risks

1. **`anacrolix/torrent` on 32-bit Android.** ~~SQLite~~ retired: the core's test suites pass on the MIBOX3. The box is confirmed 32-bit only, so `armeabi-v7a` is likely the ABI that matters, and it is the less-travelled path for both libraries. Mitigation: `gomobile bind` for both ARM targets is an exit criterion from Phase 1 onward, and Phase 3 runs it on the real box before any UI is built. Fallback for SQLite: `mattn/go-sqlite3` with CGO through the NDK.
2. **Codec coverage.** ExoPlayer relies on the device decoders. DTS and some AC3 audio only work as passthrough to a capable receiver, and odd AVI codecs may not play at all. Mitigation if it bites: add a Media3 FFmpeg audio decoder extension in Phase 5.
3. **Memory and storage on the box.** 2 GB RAM with only ~500 MB free at idle, and 8 GB flash. The torrent client needs a hard memory budget (Phase 2 exit criterion: measure RSS while streaming). Mitigation: capped torrent connections, no mmap storage, bounded image cache, cache sweep at startup and at player exit.
4. **Indexer churn.** YTS and EZTV endpoints change. Mitigation: URLs are settings; the provider interface makes adding Torznab sources a configuration change.
5. **Disc rips are a third of the movie library.** 24 of 72 movie folders hold no regular video file: DVD rips in 19, Blu-ray/`.m2ts` in 3, `.ISO` images in 2, almost all concerts. They are now indexed (Decision 13; 18 of 19 DVDs match TMDB). Playback is the open part: Media3 support for MPEG-PS (`.VOB`), `.m2ts`, and DVD audio (AC3, MP2, LPCM) on the MIBOX3 must be proven in Phase 5, and ISO images need the Go core to read the image. One DVD in the library (*Eric Clapton – MTV Unplugged*) has a 66 MB main VOB and is probably a truncated rip.
6. **Legal.** The app ships no content and no bundled keys. The README states that users are responsible for what they stream; demo material uses freely licensed films only.

## Target device

| Property | Value |
|---|---|
| Model | MIBOX3 (Xiaomi Mi Box, 2 GB RAM / 8 GB flash) |
| Android version | 9 (API 28) |
| ABI list | `armeabi-v7a,armeabi` — 32-bit only, although the CPU is ARMv8 ("ARMv8 Processor rev 4 (v8l)") |
| RAM | 1987 MB total, ~513 MB free at idle |
| Library drive | Samsung M3 Portable 1 TB, **adopted as device storage** (GPT partitions `android_meta` + `android_expand`, encrypted with a key held by the box — unreadable on any computer). It backs the box's shared storage, so the library is at `/storage/emulated/0/Download/{Movies,TV Shows,Lessons}`. Movies mixes films and concerts. |
| USB | One USB-A port. **USB debugging switches it out of host mode**: with debugging on, no USB drive is detected (`sm list-disks` empty, only root hubs on the bus). Network ADB only runs while debugging is on, so ADB and the 1 TB library drive are mutually exclusive. |

What Android 9 means for the build:

- Storage is the simple case: `READ_EXTERNAL_STORAGE` gives read access to the USB drive at `/storage/<UUID>`. The `MANAGE_EXTERNAL_STORAGE` path is only for other people's newer devices.
- Cleartext HTTP is blocked by default, so the network security config for `127.0.0.1` is required for the torrent stream to play.
- `minSdk 26` already covers it.

What the USB/ADB conflict means for development (Decision 12):

- Tests that need the drive (Phase 4 scanning, Phase 5 local playback) run with debugging **off**, so there is no `logcat`.
- Tests that don't (UI, streaming, subtitles) can run with ADB on.
