package com.lookee.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// JSON models for the Go core. Each one mirrors a golden file in core/testdata/golden;
// BridgeContractTest decodes those files strictly, so a change on the Go side fails here
// until the model is updated. Every field has a default because Go omits empty values.

@Serializable
data class Settings(
    val tmdbKeySet: Boolean = false,
    val openSubtitlesKeySet: Boolean = false,
    val openSubtitlesLoginSet: Boolean = false,
    val tmdbLanguage: String = "",
    val subtitleLanguages: String = "",
    val torrentSources: Int = 0,
)

@Serializable
data class RemoteSetup(
    val url: String = "",
    val pin: String = "",
    val expiresInSeconds: Int = 0,
)

@Serializable
data class LibraryRoot(
    val id: Long = 0,
    val path: String = "",
    val category: String = "",
    val available: Boolean = false,
    val lastScan: Long = 0,
)

@Serializable
data class StorageVolume(
    val path: String = "",
    val label: String = "",
    val removable: Boolean = false,
    val freeBytes: Long = 0,
    val totalBytes: Long = 0,
)

@Serializable
data class ScanUpdate(
    val phase: String = "",
    val root: String = "",
    val filesSeen: Int = 0,
    val matched: Int = 0,
    val toMatch: Int = 0,
    val title: String = "",
    val report: ScanReport? = null,
    val error: String = "",
    val code: String = "",
) {
    val isFinal: Boolean get() = phase == "finished" || phase == "failed"
}

@Serializable
data class ScanReport(
    val roots: List<RootScanResult> = emptyList(),
    val matched: Int = 0,
    val unmatched: Int = 0,
    val needsApiKey: Boolean = false,
    val matchError: String = "",
)

@Serializable
data class RootScanResult(
    val rootId: Long = 0,
    val root: String = "",
    val unavailable: Boolean = false,
    val seen: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
)

@Serializable
data class MediaTitle(
    val id: Long = 0,
    val category: String = "",
    val status: String = "",
    val tmdbId: Long = 0,
    val kind: String = "",
    val title: String = "",
    val originalTitle: String = "",
    val year: Int = 0,
    val tagline: String = "",
    val overview: String = "",
    val releaseDate: String = "",
    val runtime: Int = 0,
    val genres: List<String> = emptyList(),
    val rating: Double = 0.0,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    /** A video to grab a preview frame from, when there's no poster (Go decides). */
    val previewPath: String = "",
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
)

/** A title with everything the Details screen shows. Go flattens the title's fields in. */
@Serializable
data class MediaDetails(
    val id: Long = 0,
    val category: String = "",
    val status: String = "",
    val tmdbId: Long = 0,
    val kind: String = "",
    val title: String = "",
    val originalTitle: String = "",
    val year: Int = 0,
    val tagline: String = "",
    val overview: String = "",
    val releaseDate: String = "",
    val runtime: Int = 0,
    val genres: List<String> = emptyList(),
    val rating: Double = 0.0,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val previewPath: String = "",
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    /** The screen this title shows on: "movies", "concerts", "series" or "tutorials". */
    val section: String = "",
    val cast: List<CastMember> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val files: List<MediaFile> = emptyList(),
)

@Serializable
data class CastMember(
    val order: Int = 0,
    val name: String = "",
    val character: String = "",
    val profileUrl: String = "",
)

@Serializable
data class Episode(
    val season: Int = 0,
    val episode: Int = 0,
    val name: String = "",
    val overview: String = "",
    val stillUrl: String = "",
    val airDate: String = "",
)

@Serializable
data class MediaFile(
    val id: Long = 0,
    val rootId: Long = 0,
    val path: String = "",
    /** "file", "dvd", "bluray" or "iso". */
    val kind: String = "file",
    val size: Long = 0,
    val mtime: Long = 0,
    val titleId: Long = 0,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeEnd: Int = 0,
    val addedAt: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

@Serializable
data class TitleRef(val titleId: Long = 0)

@Serializable
data class PathRef(val path: String = "")

@Serializable
data class SeedResult(val applied: Int = 0)

/** A disc rip being served by the core: play [url]. */
@Serializable
data class DiscPlay(
    val url: String = "",
    val kind: String = "",
    val parts: Int = 0,
    val size: Long = 0,
    val seconds: Double = 0.0,
)

/** One Continue Watching card. [key] is the file path (or stream key) to resume. */
@Serializable
data class ContinueItem(
    val key: String = "",
    val titleId: Long = 0,
    val title: String = "",
    val category: String = "",
    val kind: String = "",
    val year: Int = 0,
    val overview: String = "",
    val genres: List<String> = emptyList(),
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val episodeName: String = "",
    val stillUrl: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

@Serializable
data class HomeView(
    val continueWatching: List<ContinueItem> = emptyList(),
    val recentlyAdded: List<MediaTitle> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
)

@Serializable
data class EpisodeRef(
    val key: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val name: String = "",
    val stillUrl: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** A show on the Series screen: Go flattens the title's fields in, then adds progress. */
@Serializable
data class ShowProgress(
    val id: Long = 0,
    val category: String = "",
    val status: String = "",
    val tmdbId: Long = 0,
    val kind: String = "",
    val title: String = "",
    val originalTitle: String = "",
    val year: Int = 0,
    val tagline: String = "",
    val overview: String = "",
    val releaseDate: String = "",
    val runtime: Int = 0,
    val genres: List<String> = emptyList(),
    val rating: Double = 0.0,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val previewPath: String = "",
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val next: EpisodeRef? = null,
    val progressSeason: Int = 0,
    val watched: Int = 0,
    val total: Int = 0,
)

@Serializable
data class SeriesView(
    val inProgress: List<ShowProgress> = emptyList(),
    val all: List<ShowProgress> = emptyList(),
)

@Serializable
data class FolderEntry(
    val name: String = "",
    val path: String = "",
    val isDir: Boolean = false,
    val size: Long = 0,
    val modTime: Long = 0,
    val hasSubtitle: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** A Tutorials search hit: a folder or video, with [folder] its parent relative to the lessons folder. */
@Serializable
data class LessonHit(
    val name: String = "",
    val path: String = "",
    val isDir: Boolean = false,
    val size: Long = 0,
    val modTime: Long = 0,
    val hasSubtitle: Boolean = false,
    val folder: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    fun asEntry() = FolderEntry(name, path, isDir, size, modTime, hasSubtitle, positionMs, durationMs)
}

@Serializable
data class PlaybackProgress(
    val key: String = "",
    val titleId: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class SidecarSubtitle(
    val path: String = "",
    val name: String = "",
    val language: String = "",
    val format: String = "",
)

@Serializable
data class SubtitleResult(
    val fileId: Long = 0,
    val fileName: String = "",
    val language: String = "",
    val release: String = "",
    val downloads: Int = 0,
    val rating: Double = 0.0,
    val hearingImpaired: Boolean = false,
    val hashMatch: Boolean = false,
    val trusted: Boolean = false,
)

/** What is playing, for an online subtitle search. Any subset may be set. */
@Serializable
data class SubtitleRequest(
    val videoPath: String? = null,
    val titleId: Long? = null,
    val tmdbId: Long? = null,
    val kind: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val query: String? = null,
)

@Serializable
data class TmdbResult(
    val id: Long = 0,
    val kind: String = "",
    val title: String = "",
    val originalTitle: String = "",
    val year: Int = 0,
    val overview: String = "",
    val posterUrl: String = "",
    val popularity: Double = 0.0,
    val voteCount: Int = 0,
)

/** A Discover result; [libraryTitleId] is set when the title is already on the drive. */
@Serializable
data class DiscoverItem(
    val id: Long = 0,
    val kind: String = "",
    val title: String = "",
    val originalTitle: String = "",
    val year: Int = 0,
    val overview: String = "",
    val posterUrl: String = "",
    val popularity: Double = 0.0,
    val voteCount: Int = 0,
    val libraryTitleId: Long = 0,
)

@Serializable
data class TorrentSearch(
    val results: List<TorrentResult> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class TorrentResult(
    val provider: String = "",
    val title: String = "",
    val quality: String = "",
    val size: Long = 0,
    val seeders: Int = 0,
    val peers: Int = 0,
    val infoHash: String = "",
    val magnet: String = "",
    val fileIndex: Int = -1,
)

@Serializable
data class StreamStats(
    /** "metadata", "buffering", "ready", "stopped" or "error". */
    val state: String = "stopped",
    val name: String = "",
    val fileName: String = "",
    val size: Long = 0,
    val downloaded: Long = 0,
    val speedBps: Long = 0,
    val peers: Int = 0,
    val seeders: Int = 0,
    val bufferPercent: Double = 0.0,
    val url: String = "",
    val error: String = "",
    val elapsedSeconds: Int = 0,
    val fileIndex: Int = 0,
    @SerialName("videoFiles") val videoFilesCount: Int = 0,
)

/** Library categories as the Go core names them. */
object Category {
    const val MOVIES = "MOVIES"
    const val TV_SHOWS = "TV_SHOWS"
    const val CONCERTS = "CONCERTS"
    const val TUTORIALS = "TUTORIALS"
}
