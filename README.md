# lookee

A lightweight media center for Android TV, built for the Xiaomi Mi Box.

I wanted one app on my TV that plays the movies, series and concerts on my USB drive with proper posters and synopses, opens my guitar lesson folders without trying to "match" them to anything, finds subtitles while the film is already playing, and can stream something I don't have yet. Nothing I tried did all of that well on a box with 2 GB of RAM, so I'm building it.

> **Status:** the Go core's library engine (scanner, filename parser, TMDB, SQLite) works and is tested; streaming and the TV app are next. See the [roadmap](docs/ROADMAP.md).

## What it does

- **Local library** — scans a USB drive, parses release filenames, and pulls titles, artwork and cast from TMDB.
- **Plain folder playback** — browse and play tutorials and personal videos exactly as they sit on disk.
- **Subtitles on demand** — search OpenSubtitles mid-playback, load the result without leaving the player, and nudge the timing.
- **Torrent streaming** — search, pick a quality and start watching while it downloads, with the cache cleaned up afterwards.
- **Remote-first** — every screen works with a D-pad.

## How it's built

lookee is two pieces:

| Piece | Language | Job |
|---|---|---|
| `core/` | Go | Scanning, filename parsing, TMDB, SQLite, subtitles, torrent streaming over a local HTTP server |
| `app/` | Kotlin | Jetpack Compose for TV interface and Media3 (ExoPlayer) playback |

The Go core is compiled to an Android library with `gomobile bind` and talks to Kotlin through a small JSON API. Kotlin holds no business logic. Because the core is plain Go, all of it can be run and tested on a laptop through `lookee-cli` before it ever touches a TV.

## Building

Requirements: Go 1.26+, JDK 17, Android SDK + NDK r28, `gomobile`. Setup steps are in [Phase 0 of the roadmap](docs/ROADMAP.md#phase-0--toolchain--repo-scaffolding); `make doctor` checks them.

```bash
make test      # Go unit tests
make lint      # go vet + golangci-lint
make bind      # Go core → app/libs/lookeecore.aar
make apk       # debug APK
make install MIBOX_IP=192.168.1.50
```

Or step by step:

```bash
# Go unit tests
cd core && go test -v ./...

# Go core → Android library
mkdir -p app/libs
cd core && gomobile bind -v -target=android/arm64,android/arm -androidapi 26 -o ../app/libs/lookeecore.aar .

# APK
./gradlew assembleDebug

# Install on the box
adb connect <MIBOX_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Try the core on your computer

Everything except playback runs on a laptop through `lookee-cli`:

```bash
make cli
core/lookee-cli settings set tmdb_api_key <your key>
core/lookee-cli scan ~/Movies --category MOVIES
core/lookee-cli list
core/lookee-cli show 1
core/lookee-cli fix 1 "the right title"   # Fix Match
core/lookee-cli browse ~/Videos/Guitar    # Tutorials browser
```

Add `--json` to see exactly what the TV app receives.

## API keys

lookee ships with no keys. You add your own free [TMDB](https://www.themoviedb.org/settings/api) and [OpenSubtitles](https://www.opensubtitles.com/consumers) keys in the Settings screen; they are stored on the device only. To avoid typing them with a remote, Settings can show a QR code that opens a PIN-protected page on your phone where you paste them.

## Documentation

- [Roadmap](docs/ROADMAP.md) — phases, exit criteria, decisions and risks
- [CLAUDE.md](CLAUDE.md) — full specification and coding conventions

## Legal

lookee is a media player. It does not host, bundle or index any content. Torrent search uses third-party sources configured by the user, and you are responsible for making sure what you stream is legal where you live.

This product uses the TMDB API but is not endorsed or certified by TMDB.

## License

[MIT](LICENSE)

lookee plays discs and some audio formats with [libVLC](https://www.videolan.org/vlc/libvlc.html) (LGPL-2.1-or-later), linked dynamically from VideoLAN's `libvlc-all` Android library. The fonts (Inter, Bebas Neue, JetBrains Mono) are under the SIL Open Font License; see `app/licenses/`.
