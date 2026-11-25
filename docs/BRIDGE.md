# Go ↔ Kotlin bridge

`core/lookeecore.go` is the only API the Android app calls. `gomobile bind` generates `com.lookee.core.lookeecore.Lookeecore` from it, and `app/.../data/GoCoreBridge.kt` wraps that class. Kotlin code never calls `Lookeecore` directly.

## Rules

- **JSON out.** Functions take primitives and return a JSON string. Functions that only act return nothing.
- **Golden files pin every JSON shape.** They live in `core/testdata/golden/*.json`.
  - Go's `TestGolden` fails if a DTO changes.
  - The Kotlin `BridgeContractTest` decodes the same files with `ignoreUnknownKeys = false`, so a field added or renamed in Go also fails on the Kotlin side until its model is updated.
  - After an intentional change, run `cd core && go test . -run TestGolden -update`.
- **Errors are exceptions.** For conditions the UI handles specially, the message starts with `lookee:<code>: `. Any other error is unexpected and is shown as-is, and the core's log has the details.

  | Code | Meaning | UI response |
  |---|---|---|
  | `not_initialized` | `initEngine` not called | Bug |
  | `not_found` | The title or row is gone | Go back and refresh |
  | `scan_running` | A scan is already in progress | Keep showing its progress |
  | `no_tmdb_key` / `bad_tmdb_key` | TMDB key missing or rejected | Offer "Set up from your phone" |
  | `no_subtitle_key` / `subtitle_quota` | OpenSubtitles key missing, or the daily limit is hit | Explain it, and offer setup |
  | `no_torrent_sources` | No indexer configured | Offer setup |
  | `no_video` | The torrent has no playable file | Pick another result |
  | `no_lan` | The TV is not on a network | Explain it |
  | `cancelled` | The user cancelled | Silent |
  | `bad_request` | The UI sent an invalid argument | Bug |

- **Threads.**
  - Every call blocks, so `GoCoreBridge` runs them on `Dispatchers.IO`.
  - Network calls time out after 45 s inside Go.
  - Callbacks (`ScanCallback`, `StreamCallback`, `RemoteCallback`) arrive on Go threads. The bridge turns them into `Flow`s, so the UI never touches them directly.
- **Long work.**
  - `startScan` returns at once and reports progress through its callback. It ends with exactly one `finished` or `failed` update.
  - `startStream` blocks until the torrent's file list is known, which can take up to about 90 s, and `stopStream` cancels it.

## Surface

| Area | Functions |
|---|---|
| Lifecycle | `initEngine(filesDir, cacheDir, externalDir)`, `shutdown`, `coreVersion`, `getLogs(n)` |
| Settings | `getSettings`, `setSetting(key, value)`, `startRemoteSetup(host, cb)`, `stopRemoteSetup` |
| Library roots | `listRoots`, `addRoot(path, category)`, `removeRoot(id)`, `listStorageVolumes` |
| Scan | `startScan(cb)`, `cancelScan` |
| Library | `listMedia(category, offset, limit)`, `getRecentlyAdded(limit)`, `getMediaDetails(id)`, `searchMatchCandidates(id, query, kind)`, `rebindTMDB(id, kind, tmdbId)`, `searchAndRebindTMDB(id, query)`, `browseFolder(path)`, `searchLessons(query)` |
| Progress | `saveProgress(key, titleId, positionMs, durationMs)`, `getContinueWatching(limit)` |
| Subtitles | `findSidecarSubtitles(videoPath)`, `searchSubtitles(requestJson)`, `downloadSubtitle(fileId)`, `shiftSubtitle(path, offsetMs)` |
| Discover | `getTrending(kind)`, `searchTMDB(query, kind)`, `searchTorrents(kind, tmdbId, season, episode)`, `searchTorrentsText(kind, text)` |
| Streaming | `startStream(source, fileIndex, cb)`, `getStreamStats`, `stopStream`, `clearStreamCache` |

`externalDir` is `Context.getExternalFilesDir(null)`. On the Mi Box that directory is on the 1 TB drive, so torrent pieces never fill the internal flash (Decision 2).
