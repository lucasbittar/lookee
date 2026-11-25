package com.lookee.tv.data

import com.lookee.core.lookeecore.Lookeecore
import com.lookee.core.lookeecore.RemoteCallback
import com.lookee.core.lookeecore.ScanCallback
import com.lookee.core.lookeecore.StreamCallback
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * An error from the Go core. [code] is set for conditions the UI handles specially
 * (docs/BRIDGE.md), e.g. [Codes.NO_TMDB_KEY]; it is null for unexpected errors.
 */
class LookeeException(val code: String?, message: String, cause: Throwable? = null) : Exception(message, cause)

object Codes {
    const val NOT_INITIALIZED = "not_initialized"
    const val NOT_FOUND = "not_found"
    const val SCAN_RUNNING = "scan_running"
    const val NO_TMDB_KEY = "no_tmdb_key"
    const val BAD_TMDB_KEY = "bad_tmdb_key"
    const val NO_SUBTITLE_KEY = "no_subtitle_key"
    const val SUBTITLE_QUOTA = "subtitle_quota"
    const val NO_TORRENT_SOURCES = "no_torrent_sources"
    const val NO_VIDEO = "no_video"
    const val NO_LAN = "no_lan"
    const val CANCELLED = "cancelled"
    const val BAD_REQUEST = "bad_request"
}

/**
 * The only door to the Go core. Every call runs on [io] after the engine has started,
 * decodes the JSON reply, and turns Go errors into [LookeeException]. Kotlin keeps no
 * business logic of its own (CLAUDE.md §8).
 */
class GoCoreBridge(
    private val engineReady: Deferred<Unit>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _streamStats = MutableStateFlow(StreamStats())

    /** Live stats of the current torrent stream, updated about once a second. */
    val streamStats: StateFlow<StreamStats> = _streamStats.asStateFlow()

    // ---- lifecycle & diagnostics ----

    fun coreVersion(): String = Lookeecore.coreVersion()

    /** Writes to the core's diagnostics log (readable from the phone page); never throws. */
    suspend fun logEvent(message: String, warn: Boolean = false) {
        runCatching { call { Lookeecore.logEvent(if (warn) "warn" else "info", message) } }
    }

    suspend fun logs(lines: Int = 200): List<String> = get { Lookeecore.getLogs(lines.toLong()) }

    // ---- settings ----

    suspend fun settings(): Settings = get { Lookeecore.getSettings() }

    /** The raw settings JSON, shown on the diagnostics card. */
    suspend fun settingsJson(): String = call { Lookeecore.getSettings() }

    suspend fun setSetting(key: String, value: String) = call { Lookeecore.setSetting(key, value) }

    /** Applies a private defaults file (docs/SOURCES.md): fills empty settings only, once per file. */
    suspend fun seedDefaults(defaultsJson: String): SeedResult = get { Lookeecore.seedDefaultSettings(defaultsJson) }

    /** Starts the phone setup page. [onClosed] runs on a Go thread with whether settings were saved. */
    suspend fun startRemoteSetup(host: String, onClosed: (saved: Boolean) -> Unit): RemoteSetup =
        get {
            Lookeecore.startRemoteSetup(host, object : RemoteCallback {
                override fun onClosed(saved: Boolean) = onClosed(saved)
            })
        }

    suspend fun stopRemoteSetup() = call { Lookeecore.stopRemoteSetup() }

    // ---- library roots ----

    suspend fun roots(): List<LibraryRoot> = get { Lookeecore.listRoots() }

    suspend fun addRoot(path: String, category: String): LibraryRoot = get { Lookeecore.addRoot(path, category) }

    suspend fun removeRoot(id: Long) = call { Lookeecore.removeRoot(id) }

    suspend fun storageVolumes(): List<StorageVolume> = get { Lookeecore.listStorageVolumes() }

    // ---- scan ----

    /** Runs a scan and emits its progress; completes after the final update. Cancelling the collector cancels the scan. */
    fun scan(): Flow<ScanUpdate> = callbackFlow {
        engineReady.await()
        guard {
            Lookeecore.startScan(object : ScanCallback {
                override fun onProgress(json: String) {
                    val update = decode<ScanUpdate>(json)
                    trySend(update)
                    if (update.isFinal) channel.close()
                }
            })
        }
        awaitClose { Lookeecore.cancelScan() }
    }.flowOn(io)

    // ---- library ----

    suspend fun media(category: String, offset: Int = 0, limit: Int = 200): List<MediaTitle> =
        get { Lookeecore.listMedia(category, offset.toLong(), limit.toLong()) }

    /** "movies", "concerts" or "series", A–Z (concert films in the Movies folder count as concerts). */
    suspend fun libraryView(view: String): List<MediaTitle> = get { Lookeecore.getLibraryView(view) }

    suspend fun home(limit: Int = 20): HomeView = get { Lookeecore.getHome(limit.toLong()) }

    suspend fun series(): SeriesView = get { Lookeecore.getSeries() }

    suspend fun recentlyAdded(limit: Int = 20): List<MediaTitle> = get { Lookeecore.getRecentlyAdded(limit.toLong()) }

    suspend fun details(titleId: Long): MediaDetails = get { Lookeecore.getMediaDetails(titleId) }

    suspend fun matchCandidates(titleId: Long, query: String, kind: String = ""): List<TmdbResult> =
        get { Lookeecore.searchMatchCandidates(titleId, query, kind) }

    suspend fun rebind(titleId: Long, kind: String, tmdbId: Long): TitleRef =
        get { Lookeecore.rebindTMDB(titleId, kind, tmdbId) }

    suspend fun searchAndRebind(titleId: Long, query: String): TitleRef =
        get { Lookeecore.searchAndRebindTMDB(titleId, query) }

    suspend fun browse(path: String): List<FolderEntry> = get { Lookeecore.browseFolder(path) }

    suspend fun searchLessons(query: String): List<LessonHit> = get { Lookeecore.searchLessons(query) }

    // ---- progress ----

    suspend fun saveProgress(key: String, titleId: Long, positionMs: Long, durationMs: Long) =
        call { Lookeecore.saveProgress(key, titleId, positionMs, durationMs) }

    suspend fun continueWatching(limit: Int = 20): List<PlaybackProgress> =
        get { Lookeecore.getContinueWatching(limit.toLong()) }

    // ---- subtitles ----

    suspend fun sidecarSubtitles(videoPath: String): List<SidecarSubtitle> =
        get { Lookeecore.findSidecarSubtitles(videoPath) }

    suspend fun searchSubtitles(request: SubtitleRequest): List<SubtitleResult> =
        get { Lookeecore.searchSubtitles(requestJson.encodeToString(request)) }

    suspend fun downloadSubtitle(fileId: Long): PathRef = get { Lookeecore.downloadSubtitle(fileId) }

    suspend fun shiftSubtitle(path: String, offsetMs: Long): PathRef = get { Lookeecore.shiftSubtitle(path, offsetMs) }

    // ---- discover ----

    suspend fun trending(kind: String): List<DiscoverItem> = get { Lookeecore.getTrending(kind) }

    suspend fun searchTmdb(query: String, kind: String = ""): List<DiscoverItem> = get { Lookeecore.searchTMDB(query, kind) }

    suspend fun searchTorrents(kind: String, tmdbId: Long, season: Int = 0, episode: Int = 0): TorrentSearch =
        get { Lookeecore.searchTorrents(kind, tmdbId, season.toLong(), episode.toLong()) }

    suspend fun searchTorrentsText(kind: String, text: String): TorrentSearch =
        get { Lookeecore.searchTorrentsText(kind, text) }

    // ---- streaming ----

    /** Starts a stream and returns once its file is known; follow [streamStats] for buffering. */
    suspend fun startStream(source: String, fileIndex: Int = -1): StreamStats {
        val stats = get<StreamStats> {
            Lookeecore.startStream(source, fileIndex.toLong(), object : StreamCallback {
                override fun onStats(json: String) {
                    _streamStats.value = decode(json)
                }
            })
        }
        _streamStats.value = stats
        return stats
    }

    suspend fun stopStream() {
        call { Lookeecore.stopStream() }
        _streamStats.value = StreamStats()
    }

    suspend fun clearStreamCache() = call { Lookeecore.clearStreamCache() }

    // ---- plumbing ----

    private suspend inline fun <reified T> get(noinline block: () -> String): T {
        val json = call(block)
        return decode(json)
    }

    private suspend fun <T> call(block: () -> T): T = withContext(io) {
        engineReady.await()
        guard(block)
    }

    companion object {
        /** Lenient at runtime so an older app build survives a newer field; the contract test is strict. */
        internal val json = Json { ignoreUnknownKeys = true }
        private val requestJson = Json { explicitNulls = false }
        private val codePrefix = Regex("^lookee:([a-z_]+): ")

        internal inline fun <reified T> decode(text: String): T =
            try {
                json.decodeFromString<T>(text)
            } catch (e: Exception) {
                throw LookeeException(null, "Unexpected reply from the core: ${e.message}", e)
            }

        /** Runs a core call, turning its exceptions into [LookeeException] with the bridge code. */
        internal fun <T> guard(block: () -> T): T =
            try {
                block()
            } catch (e: LookeeException) {
                throw e
            } catch (e: Exception) {
                throw toLookeeException(e)
            }

        internal fun toLookeeException(e: Exception): LookeeException {
            val msg = e.message.orEmpty()
            val match = codePrefix.find(msg)
            return if (match != null) {
                LookeeException(match.groupValues[1], msg.removeRange(match.range), e)
            } else {
                LookeeException(null, msg.ifEmpty { e.javaClass.simpleName }, e)
            }
        }
    }
}
