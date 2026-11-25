# lookee — Implementation Roadmap

This is the working plan for building lookee. `CLAUDE.md` is the spec (what the app is and the rules for building it); this file is the plan (what order to build it in, and how we know each step is finished).

Each phase ends with **exit criteria** that can be checked with a command or on the device. A phase is not done until every criterion passes.

**Status legend:** ⬜ not started · 🟨 in progress · ✅ done

| Phase | Name | Runs on | Status |
|---|---|---|---|
| 0 | Toolchain & repo scaffolding | macOS | ✅ |
| 1 | Go core foundation & CLI harness | macOS | ✅ |
| 2 | Torrent streamer & subtitle client | macOS | ✅ |
| 3 | gomobile binding & Android TV shell | macOS + emulator | ✅ |
| 4 | TV screens & USB storage | emulator + Mi Box | 🟨 |
| 5 | Player & subtitle hot-reload | Mi Box | 🟨 |
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
- [x] `db/`: open SQLite in WAL mode (`modernc.org/sqlite` at first, `mattn/go-sqlite3` since Phase 3, Decision 16), versioned migrations, and the schema:
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

- [x] `torrent/providers.go`: `TorrentProvider` interface, YTS and EZTV clients, and a generic Torznab client. All base URLs are settings, not constants, because these services move domains. Results merged and sorted by seeders, then quality.
- [x] `torrent/streamer.go`:
  - One on-demand `anacrolix/torrent` client, file-based storage (no mmap), conservative connection limits for a 2 GB RAM box.
  - Pick the largest video file; prioritise first and last pieces; 25 MB read-ahead.
  - `http.ServeContent` over `file.NewReader()` on a random loopback port.
  - Stats callback every second: speed, peers, buffered %, "ready to play" flag.
  - `StopStream` closes the client and `os.RemoveAll`s the piece directory. A startup sweep removes any directory left by a crash.
- [x] `subtitles/opensubtitles.go`: search by moviehash, TMDB id or text; language filter; download to `cacheDir`; optional login for the higher download quota.
- [x] Subtitle offset: `ShiftSubtitle(path, offsetMs)` writes a shifted copy of the `.srt`. This keeps sync logic in Go and gives the player a plain file to reload (see Decision 4).
- [x] `remote/`: phone settings page (see Decision 7). A small HTTP server in the Go core, bound to the LAN address, serving one embedded HTML form for API keys, subtitle languages and indexer URLs. It runs only while explicitly started, requires a 6-digit PIN that the TV displays, and shuts itself down after 10 minutes or on first successful save. Keys are never sent back to the browser; saved fields show as "set".
- [x] `logs/`: rolling log file (size-capped, e.g. 2 × 1 MB) in the app's files directory; the phone page shows it behind the same PIN (Decision 12).
- [x] CLI: `torrents`, `stream [--consume --seconds]`, `subs sidecars|search|download|shift`, `remote`, `logs`.

Implementation notes (2026-11-24):

- Streamer: mmap storage disabled (a 32-bit process cannot map multi-GB files); in-memory piece completion; per-session `stream-*` directory deleted on stop and swept at startup; only what is watched plus a 25 MB readahead is downloaded (the library drive had ~20 GB free), with the first and last pieces fetched first.
- Providers: no indexer URLs are built in; YTS/EZTV/Torznab/Stremio endpoints are settings.
- Subtitles are always handed to the player as UTF-8 copies (many pt-BR `.srt` files are Windows-1252); originals are never modified.
- `pion/webrtc` (pulled in by anacrolix for WebTorrent) depends on `wlynxg/anet`, whose `go:linkname` into `net.zoneCache` breaks Android linking. `core/third_party/anet` replaces it with plain `net` calls (only WebRTC peer discovery uses it).

**Exit criteria**

- ✅ `go test ./...` passes, including range requests against a fake reader and an offline end-to-end test (seed a generated torrent on loopback, stream it through `Start`, seek, verify bytes, verify cleanup).
- ✅ *Sintel* (Blender, CC-BY) from the live swarm: ready within seconds at 15–18 MB/s; a mid-file range request returned 206 with the right length. (Played via `--consume`; open the printed URL in VLC to watch.)
- ✅ After stopping, the session directory (139 MB during the test) no longer exists.
- ✅ The streamer, cross-compiled for the MIBOX3 and run over `adb shell`, streams a test torrent with resident memory staying under 150 MB. *Sintel* (123 MB) was ready after ~5 s, downloaded in full within 90 s from up to 18 peers, peaked at **48.5 MB RSS**, and left the cache directory empty. The test binary must be built as `GOOS=android` with cgo (NDK clang, plus the NDK's `libc++_shared.so` beside it): a static `linux/arm` build uses Go's own DNS resolver, which reads `/etc/resolv.conf`, absent on Android, so every tracker and DHT lookup failed. The app's `.aar` is built as `android` and uses the system resolver. `make push-cli MIBOX_IP=…` builds and copies both files.
- ✅ `lookee-cli remote` prints a URL and PIN; saving a key from a phone on the same Wi-Fi shows up in `lookee-cli settings get`, and a wrong PIN is rejected. Saved from the user's phone (5 fields, page closed after saving); a live run returned 401 for wrong PINs, 303 for the right one, 403 at the fifth wrong attempt, and then the server shut down.
- ✅ `gomobile bind` succeeds for arm, arm64 and x86 with the torrent library (after the `anet` replacement). Unstripped `libgojni.so` is ~45 MB per ABI; measure the release APK in Phase 6.

---

## Phase 3 — gomobile binding & Android TV shell

**Goal:** an APK that launches on the TV, initialises the Go engine and shows real data from it.

**Tasks**

- [x] `core/lookeecore.go`: the exported surface. Every function takes primitives and returns `(string, error)` with a JSON body.

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

- [x] JSON DTO contract test in Go (golden files) so Kotlin and Go cannot drift silently.
- [x] Gradle project: Kotlin DSL, version catalog, `minSdk 26`, `targetSdk 34`, `app/libs/lookeecore.aar` as a file dependency.
- [x] Manifest: `LEANBACK_LAUNCHER` intent filter, banner, `android.software.leanback` required, touchscreen not required, `INTERNET`, storage permissions, and a network security config that allows cleartext to `127.0.0.1` only (needed for the loopback stream).
- [x] `LookeeApp.kt` calls `InitEngine`; `GoCoreBridge.kt` wraps every call in `withContext(Dispatchers.IO)` and decodes with `kotlinx.serialization`.
- [x] **Design pass with the user (Decision 15)** before building screens: visual direction, type scale, color, motion, focus treatment, and mockups of Home, Details and the player OSD. *Colors, type, focus and components are agreed (`docs/DESIGN.md`); the Home, Details and OSD layout mockups are still to do, before Phase 4.*
- [x] Theme and core components built to that design (focusable card, buttons, rows, navigation), plus the navigation with seven destinations.
- [x] `make serve-apk`: serve the debug APK on the LAN and print the URL to type into *Downloader* on the TV (Decision 12).
- [x] Debug builds also bind `android/386`, because the Android 9 TV emulator image is 32-bit x86 (`make bind` does this; `make bind-release` is ARM-only).

**Implementation notes**

- The bridge (`docs/BRIDGE.md`) exports the whole core. Known errors cross as `lookee:<code>: …` messages, and every JSON shape is pinned by `core/testdata/golden`. Kotlin's `BridgeContractTest` decodes those files strictly. The golden files are declared as Gradle test inputs: without that, Gradle reused the cached test result and missed a renamed field.
- **SQLite driver switched to `mattn/go-sqlite3` (Decision 16).** On the Mi Box, the app crashed at start with `SIGSYS`. modernc's emulated libc calls `statx` on 32-bit targets and expects `ENOSYS` back. Android 9's app seccomp filter kills the process instead. The `adb shell` streaming test in Phase 2 didn't catch this, because the shell isn't under the app sandbox.
- Android builds use the tags `purego_utp,nosqlite` (`GO_TAGS` in the `Makefile`, used by every Go build: without `nosqlite` two SQLite copies collide at link time, and `core/db/build_tags.go` turns that into one clear error). anacrolix's default uTP is C++ (`go-libutp`) and needs `libc++_shared.so`, which gomobile doesn't package. Its SQLite piece store is unused. `make test` and `make lint` use the same tags, so they cover what ships.
- The UI was checked on the emulator and on the Mi Box. The top navigation selects a tab when it gets focus, and Back steps content → tab → Home → exit. Phone setup works from the TV end to end: the PIN, a save from another device, and the "Settings saved" callback from Go. A focused button that vanished on a state change used to throw focus to the first tab, and so to Home. Each card now keeps one button across states, and lost focus re-enters on the selected tab.
- The app uses about 63 MB of memory at idle on the box, in a debug build. Debug builds are slow there (unoptimised dex), so D-pad speed gets judged on a release build in Phase 4.
- `getExternalFilesDir` fails while the drive is unplugged (USB debugging on). The core then falls back to `cacheDir` for torrent pieces.

**Exit criteria**

- ✅ `make bind && ./gradlew assembleDebug` succeeds, and the Kotlin contract tests pass.
- ✅ In the Android TV emulator and on the Mi Box: the app is registered with the TV launcher, opens, the navigation is fully usable with the D-pad, and the Settings → Diagnostics card shows the settings JSON returned by Go.
- ✅ The layout mockups (Home, Details, OSD, every category, Discover) are agreed with the user before Phase 4 starts: see the [layouts canvas](https://claude.ai/artifact/VpFhyAz42B5QG4wgGs3Tai) and `docs/DESIGN.md`.

---

## Phase 4 — TV screens & USB storage

**Goal:** the library is browsable from the couch.

**Tasks**

- [x] Settings: storage permission flow, folder picker, category per folder, Clear Stream Cache. API keys, subtitle languages and indexer URLs are set from the phone page.
- [x] Settings → "Set up from your phone": calls `StartRemoteSetup`, shows the URL as a QR code plus the PIN, and refreshes the settings screen when the phone saves. Leaving the screen calls `StopRemoteSetup`. On-screen keyboard entry stays available as a fallback.
- [x] Home: Continue Watching, Recently Added, per-category rows, startup sync indicator driven by `ScanCallback`.
- [x] Movies / Series / Concerts: paged poster grids.
- [x] Details: backdrop, synopsis, cast row, season/episode list for series, Play / Resume, Fix Match dialog.
- [x] Tutorials: folder browser backed by `BrowseFolder`, no metadata.
- [x] Discover: trending rows, search, torrent list with quality and seeders, buffering overlay driven by `StreamCallback`.
- [x] Coil configured with the 150 MB disk cap.

**Implementation notes (so far)**

- **Built in Go, not Kotlin:** Home, Series "up next" and the library views are computed by the core (`GetHome`, `GetSeries`, `GetLibraryView`).
  - "Next episode" follows the latest viewing: an unfinished episode is resumed, and after a finished one the next episode is offered.
  - The Movies folder mixes films and concerts. A title counts as a concert film when every TMDB genre is Music, Documentary or TV Movie, and either Music is among them or the name says it's live ("Live", "Unplugged", "Tour", "Festival"…). *Almost Famous* (Drama, Music) stays a movie.
  - Known misses: *History of the Eagles* (tagged only Documentary) and unmatched, unnamed shows like Marcus King's stay in Movies. Moving a title by hand is a possible follow-up.
- **Every card has a picture.** Titles without TMDB artwork, and every Tutorials lesson, show a frame from the video. Go supplies `previewPath`; Android's `MediaMetadataRetriever` grabs a frame about 10% in (retrying at 30% and 50% if it's black) and caches it as a JPEG.
- **D-pad scrolling:** Compose 1.7's TV default pivots a focused item to about 30% of its row, which clipped the first card. A "scroll only when needed" spec fixed the clipping, but the user found it jumpy. `FollowFocusScroll` scrolls on every move and keeps the focused item anchored at the screen margin (the left of a row, the top of a list), as on Google TV. Row padding lives inside rows, and screen headings are pinned above the scrolling content. The scroll itself is instant (`snap()`): an animated glide felt unlike the native launcher to the user.
- **Boot splash:** a cold start on the Mi Box takes seconds (loading the 24 MB `libgojni.so`, the Go runtime, SQLite and a debug Compose build), and a dark empty window looked broken. The window background now draws the mark the moment the app opens (`splash_window.xml`). Compose then takes over with the mark in the same spot, and shows the name, a progress bar and the current step: engine, library, sources, drives (live "Drive connected · 3 folders"), Home. The steps are real work tracked by `Boot`, not a timer. The bar creeps within a step so it never looks stuck. Keys are ignored under the splash, except Back. Step timings go to logcat under `lookee.boot`.
- **Tab focus from the content:** Up from a screen's top-left item went to the *nearest* tab (Home, from Settings), and since tabs select on focus, that switched screens. The tab row is now a focus group whose `enter` is always the selected tab. Settings also scrolls back to the very top when its first row takes focus, so the phone card's heading isn't left under the page title.
- **Big titles:** `FitText` used to shrink 4 sp per layout pass, so a long banner title showed big for a few frames. It now measures with a `TextMeasurer` against the available width before drawing.
- **Toasts:** `ToastHost` (slide-up pill with a working/done/problem icon) replaces the plain text toast. `SyncToasts` announces when a library sync starts and what it changed when it ends ("Library synced · 3 new", "Library up to date", "Drive not connected · showing your saved library"). It starts listening after the splash, so the start toast isn't spent behind it.
- **Automatic sync:** a sync runs every time the app comes to the front (`MainActivity.onStart`), not only on a cold start: on a TV the process usually survives between uses. There's a one-minute cooldown. Plugging in or removing a drive (`ACTION_MEDIA_MOUNTED`/`UNMOUNTED`/`REMOVED`) also syncs after a 2 s settle. A removed drive's folders are left untouched (`unavailable`), so this is safe. Automatic syncs stay quiet: "Syncing…" only shows if a sync is still running after 1 s, and the result only when something changed or the drive is missing. Syncs the user asks for always report. Each start is logged to logcat as `lookee.sync`.
- **Startup time on the Mi Box (measured):** the Go side (library load, SQLite, presets, drives) takes about 0.3 s. Almost all of the wait was the debug build: unoptimised, debuggable Compose took 8.4 s to the first frame. The R8 release build takes 1.5 s, and 0.9 s after `cmd package compile -m speed-profile` (Android also does this on its own when the box is idle). Install on the box with `make apk-release install-release`. Release builds are signed with the debug key until Phase 6 adds a real one, so they install over debug builds and keep the app's data.
- **Pushed screens take focus.** Add folder looked stuck: its first item loaded after the screen asked for focus, so focus stayed on the hidden button underneath, and every OK reopened the screen. Opening a screen now moves focus into it, the covered tabs refuse focus, and lists ask for focus once their items exist.
- **Settings:** the storage permission, adding a folder (volume → folder → category), scan progress, Remove with a second-press confirmation, Clear stream cache, and a QR code for phone setup.
- **Testing:** everything was checked on the emulator against a copy of the real index (82 titles, keys stripped), with sample progress and lessons made from *Sintel*'s trailer.
- **Not done yet:** the run on the Mi Box with the drive attached (exit criteria 1–4).

**Exit criteria**

- With a USB drive attached to the Mi Box: first launch scans it, posters appear, and every screen is reachable and operable with the remote only.
- Unplugging the drive and relaunching does not crash; entries are pruned or shown as unavailable.
- Fix Match changes a title and the change survives a rescan.
- Scanning the QR code with a phone, entering the PIN and pasting a TMDB key makes posters load on the TV without touching the remote's keyboard; the page is unreachable after leaving the screen.

---

## Phase 5 — Media3 player & subtitle hot-reload

**Tasks**

- [x] Spike on the box (Decision 14). Media3 played H.264 and HEVC files well. It failed on MP2 (no decoder: silent, stuttering picture), on AC3 passthrough (`AudioTrack init failed`), on The Last Waltz's DTS-HD MKV (`READ_POSITION_OUT_OF_RANGE`) and on Van Halen's ISO stream (extractor crash). It also can't do DVD menus, which the user expects, as in Kodi. Decided: the **hybrid** in Decision 14.
- [x] Player screen with ExoPlayer for `file://` paths and the loopback stream URL; custom OSD (not the stock `PlayerView` controls).
- [x] D-pad OSD: play/pause, ±10 s seek, hold-to-scrub, audio track and subtitle track pickers.
- [x] Resume: save position every few seconds and on stop; prompt "Resume from 43:10?" on Details.
- [x] Sidecar and embedded subtitles. [ ] Online search panel inside the player.
- [ ] Hot-reload: rebuild the `MediaItem` with the new `SubtitleConfiguration` and re-prepare at the current position.
- [ ] Subtitle size control and sync offset (−5.0 s to +5.0 s, 100 ms steps) through `ShiftSubtitle`.
- [x] Leaving the player always calls `StopStream`, including on process death recovery at next launch (the core sweeps the stream cache at start).
- [x] Disc rips (Decision 13), in the core's `disc` package and served through `loopback` (shared with torrent streams):
  - `dvd`: the main title set (the `VTS_xx` group with the largest total `.VOB` size, menus excluded), its VOBs joined into one MPEG-PS stream. Seeking works across parts.
  - `bluray`: the longest `.mpls` playlist whose clips are all present, falling back to the largest `.m2ts`. The core converts `.m2ts` (192-byte packets) to plain TS on the fly, because Media3's TS extractor expects 188-byte packets.
  - `iso`: the core reads the ISO 9660 file table and serves the DVD's main title straight from the image. Blu-ray images are UDF-only and show "Blu-ray disc images can't play yet" (open: a UDF 2.5 reader).
  - Checked on the emulator with rips made from *Sintel*: the Blu-ray rip plays and seeks. The DVD folder and the ISO are parsed and served, but the emulator has no MPEG-2/AC3 decoders, while the Mi Box lists both (`OMX.amlogic.mpeg2`, `.ac3`). The box run is pending.
- [x] **Fixed: VLC crashed when seeking a Blu-ray on the box.** The crash report (`logcat -b crash`) showed SIGSEGV in the Mali driver's `eglSwapBuffers`, called from VLC's OpenGL output. VLC now draws straight to the Android surface (`--vout=android_display`).
- [x] **Refresh-rate matching:** the MIBOX3 offers 1080p at 60, 59.94, 50, 30, 29.97, 25, 24 and 23.976 Hz. The player picks the highest rate that's a whole multiple of the video's frame rate (50 Hz for PAL DVDs) and restores the system's choice when it closes. At 60 Hz, PAL DVDs stuttered.
- [x] **Fixed: scans could fail with "disk I/O error: read-only file system".** SQLite writes temp files (big deletes, sorts) to the current directory when no temp dir is set, and an app's is `/`. The core now sets `SQLITE_TMPDIR` to its cache. Pruning ~70 titles at once triggered it. Every scan now logs "scan finished"/"scan failed".

**Implementation notes**

- **Player:** ExoPlayer with decoder fallback, drawn in a bare `PlayerView`; Compose draws the OSD from the mockup. That means a title header with "On your drive"/"Streaming" and a codec chip ("1080p · H.264 · AC3"), a focusable seek bar, ±10 s and play/pause, Subtitles/Audio pills, and "Next: …" for episodes and lessons. The OSD hides after 4 s. With it hidden, OK pauses, ◀ ▶ jump 10 s (holding repeats) and ▲ ▼ show it. Back closes the panel, then the OSD, then the player. Back is read in the player's own key handler, because the focus traps swallowed it before the activity's back dispatcher saw it.
- **Queues:** playing an episode queues the rest of the show (up to 30), and a lesson queues the rest of its folder. When one ends, the next starts.
- **Progress** is saved every 5 s, on pause, on leaving and when playback ends (as finished). Continue Watching resumes straight into the player.
- **Subtitles:** sidecars (`.srt/.vtt/.ass`) are attached at load. Subtitles turn on by themselves only in the first preferred language (`subtitle_languages`, pt-BR here). Size has three steps, Small/Medium/Large.
- **Diagnose without ADB:** a new bridge call, `LogEvent`, writes the shell's playback events to the core log: what played, every track with whether the box can decode it, and errors with their Media3 code. These are readable in Settings → Diagnostics or from the phone page. That makes them the record for the format spike. A file whose audio can't play shows a toast ("This file's DTS audio can't play on this box"). An undecodable video shows "Can't play this" with the reason.
- **Torrent streams:** the buffering screen hands the stream to the player once it's ready. From then on the player stops it (deleting its pieces) when it closes.

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
10. **Go 1.26 minimum.** Current `x/mobile` and other dependencies require it, so the spec's "Go 1.23+" is raised.
11. **gomobile Java package is `com.lookee.core`.** Kotlin imports `com.lookee.core.lookeecore.Lookeecore`.
12. **ADB-free install and diagnostics, because the MIBOX3 cannot use ADB and the USB drive at the same time.**
    - *Install:* Phase 3 adds `make serve-apk`, which serves the APK over the LAN for the free *Downloader* app on the TV. `make install` stays for when debugging is on.
    - *Logs:* the Go core writes a rolling log file (Phase 2, `core/logs`). The phone settings page gets a PIN-protected "Diagnostics" view that shows it, so errors can be read from a phone without `logcat`.
13. **Disc rips are indexed and played.** (Decided 2026-11-24.) Each rip is one library item with a `kind`: a folder of `.VOB/.IFO/.BUP` → `dvd`; a `BDMV` folder, or a folder of flattened Blu-ray files (`.mpls/.clpi` next to `.m2ts`) → `bluray`; an `.iso` → `iso`. Loose `.m2ts/.mts` are ordinary files. Titles come from the `Title (Year)` folder. Playback is Phase 5 work, verified on the box; anything the box cannot decode must show a clear message, never fail silently.
14. **Playback engine: hybrid, Media3 for files and streams, libVLC for discs and as the fallback (decided 2026-11-25 after the spike).** The player UI (OSD, panels, progress) is ours and drives either engine through `PlaybackEngine`. **Media3** plays files and torrent streams on the MIBOX3's hardware decoders, with AC3 passthrough turned off because the box's HDMI passthrough failed. **libVLC 3.6.5** (`libvlc-all`, LGPL) plays DVD folders and DVD ISOs at their **menus** (dvdnav; the D-pad and OK drive the menu, and a "Disc menu" button returns to it) and Blu-ray folders through libbluray. When the disc structure can't be opened (VOBs without `.IFO`, flattened Blu-ray copies, Blu-ray images), it plays the core's joined main title instead. It also takes over **any file Media3 can't play** (no playable audio track, a decoder or extractor error) at the same position, decoding MP2, LPCM, DTS, DTS-HD and TrueHD in software. Cost: about 20 MB per ABI. Earlier context: the user's previous setup, Kodi, played all of these formats; its UI, not playback, was the problem.
15. **UI quality is a first-class requirement.** The user wants a polished, sleek, modern interface. This was the main problem with Kodi. Before any Phase 4 screen is built, there is a design pass with the user: visual direction, typography, motion, focus states, a component set, and mockups of Home, Details and the player OSD. Phase 3 builds the theme and core components to that standard rather than placeholders. **Visual language approved (2026-11-25):** the phone setup page is the reference. Its dark palette, `#F2B544` yellow accent, gradient "l" mark and card style carry across the app, as specified in [`DESIGN.md`](DESIGN.md). The design pass now covers layouts (Home, Details, OSD), not colors.

16. **SQLite through `mattn/go-sqlite3` (cgo), not `modernc.org/sqlite`.** The pure-Go driver crashed the app on the 32-bit Mi Box. Its libc emulation calls `statx(2)`, which Android 9's app seccomp policy kills instead of rejecting. mattn compiles the real SQLite with the NDK, uses bionic, and needs only `libc.so`. The cost is a C compile in `make bind` (~45 s for three ABIs) and cgo for desktop tests. This was the fallback already listed under Risk 1.

## Risks

1. **`anacrolix/torrent` on 32-bit Android.** ~~SQLite~~ retired: the core's test suites pass on the MIBOX3, and inside the app the fallback driver is now in use (Decision 16), because the pure-Go one hit the app seccomp filter. Streaming retired for the CLI build: a real torrent streamed on the box at 48.5 MB peak RSS (Phase 2 exit criteria); the in-app run comes in Phase 3. The box is confirmed 32-bit only, so `armeabi-v7a` is likely the ABI that matters, and it is the less-travelled path for both libraries. Mitigation: `gomobile bind` for both ARM targets is an exit criterion from Phase 1 onward, and Phase 3 runs it on the real box before any UI is built. Fallback for SQLite: `mattn/go-sqlite3` with CGO through the NDK.
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
