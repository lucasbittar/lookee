package com.lookee.tv.ui.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC: disc rips with their menus (dvdnav, libbluray), and every audio format the box
 * can't decode itself (MP2, LPCM, DTS, DTS-HD, TrueHD), decoded in software. Video still
 * goes through MediaCodec where it can.
 */
class VlcEngine(context: Context, private val log: (String, Boolean) -> Unit) : PlaybackEngine {
    override val name = "vlc"

    // VLC sets subtitle size when it starts; the size control stays with ExoPlayer.
    override val canResizeSubtitles = false

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val context = context.applicationContext

    // Made on the first load, because their options depend on the source (see [create]).
    private lateinit var libVlc: LibVLC
    private lateinit var player: MediaPlayer
    private var softwareVideo = false

    /** A disc resumed from Continue Watching: skip its menu, play the main title from here. */
    private var resumeAt = 0L
    private var label = ""
    private var alternates: List<Uri> = emptyList()
    private var described = false
    private var lastLoad: Pair<Source, Long>? = null
    private var durationHint = 0L
    private var frameRateHint = 0f

    // Seeks wait for the presses to stop: each one makes VLC flush and refill the hardware
    // decoder, and a burst of them (holding fast-forward) used to bring the box down.
    private val main = Handler(Looper.getMainLooper())
    private var pendingSeek: Long? = null
    private val applySeek = Runnable {
        pendingSeek?.let { target ->
            pendingSeek = null
            seekNow(target)
        }
    }

    /** VLC's own length, else the source's hint (a stream served by the core has none). */
    private val length: Long get() = player.length.takeIf { it > 0 } ?: durationHint

    /**
     * DVDs are decoded in software: through MediaCodec, the Mi Box's MPEG-2 decoder handed VLC
     * pictures late and VLC dropped 4–9 a second (the stutter; the stats line showed it, the
     * emulator dropped none). SD MPEG-2 is light work for the CPU, and then VLC can deinterlace:
     * bob turns 25 interlaced frames into 50 pictures, one per refresh at 50 Hz. Everything else
     * keeps hardware decoding and no deinterlacing (which would force frames off the decoder).
     */
    private fun create(software: Boolean) {
        softwareVideo = software
        libVlc = LibVLC(
            context,
            arrayListOf(
                "--audio-time-stretch",
                if (software) "--deinterlace=-1" else "--deinterlace=0",
                "--deinterlace-mode=bob",
                // Draw straight to Android's surface, not through OpenGL: VLC's GLES output crashed
                // in the Mi Box's Mali driver (eglSwapBuffers, SIGSEGV) when seeking a Blu-ray.
                "--vout=android_display",
                "--no-video-title-show",
                "--sub-text-scale=100",
                "--no-osd",
            ),
        )
        player = MediaPlayer(libVlc)
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> {
                    _state.update { it.copy(playing = true, buffering = false) }
                    describe()
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> _state.update { it.copy(playing = false) }
                MediaPlayer.Event.Buffering -> _state.update { it.copy(buffering = e.buffering < 100f) }
                MediaPlayer.Event.TimeChanged -> if (pendingSeek == null) _state.update { it.copy(positionMs = e.timeChanged) }
                MediaPlayer.Event.LengthChanged -> if (e.lengthChanged > 0) _state.update { it.copy(durationMs = e.lengthChanged) }
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected, MediaPlayer.Event.Vout -> refreshTracks()
                MediaPlayer.Event.EndReached -> _state.update { it.copy(ended = true, playing = false) }
                MediaPlayer.Event.EncounteredError -> onError()
            }
        }
    }

    private fun onError() {
        val next = alternates.firstOrNull()
        if (next != null) {
            // A disc image that isn't a DVD is tried as a Blu-ray next.
            log("vlc couldn't open $label as ${withMedia { it.uri?.scheme }}; trying ${next.scheme}", true)
            alternates = alternates.drop(1)
            _state.update { it.copy(hasMenu = false, error = null) }
            open(next, 0, discMenu = true)
            return
        }
        log("vlc error $label", true)
        _state.update { it.copy(error = "This file couldn't be played.", buffering = false, playing = false) }
    }

    override fun load(source: Source, startMs: Long) {
        label = source.label
        alternates = source.alternates
        durationHint = source.durationHintMs
        frameRateHint = source.frameRateHint
        lastLoad = source to startMs
        if (!::player.isInitialized) create(source.softwareVideo)
        resumeAt = if (source.discMenu && startMs > 0) startMs else 0
        _state.value = EngineState(hasMenu = source.discMenu)
        open(source.uri, startMs, source.discMenu)
    }

    private fun open(uri: Uri, startMs: Long, discMenu: Boolean) {
        described = false
        lastStats = null
        val media = Media(libVlc, uri)
        media.setHWDecoderEnabled(!softwareVideo, false) // MediaCodec when it can, software otherwise
        if (startMs > 0 && !discMenu) media.addOption(":start-time=${startMs / 1000.0}")
        player.media = media
        media.release()
        player.play()
    }

    /** Titles change without an event (menu → feature): checked by the screen's ticker. */
    override fun poll() {
        val titles = player.titles
        val current = player.title
        val inMenu = titles != null && current in titles.indices && titles[current].isMenu
        val hasMenu = titles?.any { it.isMenu } == true
        _state.update {
            it.copy(
                positionMs = pendingSeek ?: if (player.time >= 0) player.time else it.positionMs,
                durationMs = length.takeIf { l -> l > 0 } ?: it.durationMs,
                inMenu = inMenu,
                hasMenu = it.hasMenu || hasMenu,
            )
        }
        if (resumeAt > 0 && titles != null) resumeDisc(titles, current)
        if (_state.value.frameRate == 0f) frameRate()?.let { fps -> _state.update { it.copy(frameRate = fps) } }
        logStats()
    }

    private fun resumeDisc(titles: Array<MediaPlayer.Title>, current: Int) {
        val main = titles.indices.filter { !titles[it].isMenu }.maxByOrNull { titles[it].duration } ?: return
        when {
            current != main -> player.title = main
            player.isPlaying && player.length > 0 -> {
                player.time = resumeAt.coerceAtMost(player.length - 5_000)
                log("vlc resumed $label at title $main, ${resumeAt / 1000} s", false)
                resumeAt = 0
            }
        }
    }

    private var statsAt = 0L
    private var lastStats: IntArray? = null

    /**
     * Every 20 s while playing: pictures decoded, shown and lost, and audio buffers lost, since
     * the last line. Lost pictures mean the decoder or the display fell behind (a stutter).
     */
    private fun logStats() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!player.isPlaying || now - statsAt < 20_000) return
        statsAt = now
        val st = withMedia { it.stats } ?: return
        val cur = intArrayOf(st.decodedVideo, st.displayedPictures, st.lostPictures, st.playedAbuffers, st.lostAbuffers, st.demuxCorrupted)
        val prev = lastStats
        lastStats = cur
        if (prev == null) return
        val d = IntArray(cur.size) { cur[it] - prev[it] }
        log("vlc stats $label: video decoded ${d[0]}, shown ${d[1]}, lost ${d[2]}; audio played ${d[3]}, lost ${d[4]}; corrupt ${d[5]}", d[2] > 0 || d[4] > 0)
    }

    /**
     * The playing video's frame rate. VLC knows it for most files but not for a DVD (its
     * video stays "0x0, 0/0" for the whole film), so a disc's comes from the core instead.
     */
    private fun frameRate(): Float? {
        val v = player.currentVideoTrack
        if (v != null && v.frameRateNum > 0 && v.frameRateDen > 0) return v.frameRateNum.toFloat() / v.frameRateDen
        return frameRateHint.takeIf { it > 0f }
    }

    private fun refreshTracks() {
        val audioNow = player.audioTrack
        val spuNow = player.spuTrack
        _state.update {
            it.copy(
                audio = player.audioTracks.orEmpty().filter { t -> t.id >= 0 }.map { t -> Choice(t.id.toString(), t.name.cleanTrackName("Audio"), selected = t.id == audioNow) },
                text = player.spuTracks.orEmpty().filter { t -> t.id >= 0 }.map { t -> Choice(t.id.toString(), t.name.cleanTrackName("Subtitles"), selected = t.id == spuNow) },
                summary = summary(),
            )
        }
    }

    /** "1080p · H.264 · DTS" from the playing media's tracks. */
    private fun summary(): String = withMedia { media ->
        var video: IMedia.VideoTrack? = null
        var audio: IMedia.AudioTrack? = null
        for (i in 0 until media.trackCount) {
            when (val t = media.getTrack(i)) {
                is IMedia.VideoTrack -> if (video == null) video = t
                is IMedia.AudioTrack -> if (audio == null) audio = t
            }
        }
        val res = video?.let { v ->
            when {
                v.width >= 3500 || v.height >= 2000 -> "4K"
                v.width >= 1800 || v.height >= 1000 -> "1080p"
                v.width >= 1200 || v.height >= 700 -> "720p"
                v.height > 0 -> "${v.height}p"
                else -> null
            }
        }
        frameRate()?.let { fps -> if (fps != _state.value.frameRate) _state.update { st -> st.copy(frameRate = fps) } }
        listOfNotNull(res, video?.codec?.let(::fourcc), audio?.codec?.let(::fourcc)).joinToString(" · ")
    }.orEmpty()

    private fun describe() {
        if (described) return
        val tracks = withMedia { media ->
            if (media.trackCount == 0) return@withMedia null
            (0 until media.trackCount).joinToString("; ") { i ->
                when (val t = media.getTrack(i)) {
                    is IMedia.VideoTrack -> "video: ${fourcc(t.codec)} ${t.width}x${t.height}"
                    is IMedia.AudioTrack -> "audio: ${fourcc(t.codec)} ${t.channels}ch ${t.language.orEmpty()}".trim()
                    is IMedia.SubtitleTrack -> "text: ${t.codec} ${t.language.orEmpty()}".trim()
                    else -> "other"
                }
            }
        } ?: return
        described = true
        log("vlc tracks $label: $tracks", false)
    }

    /** [MediaPlayer.getMedia] takes a native reference each call: give it back after use. */
    private inline fun <T> withMedia(block: (IMedia) -> T?): T? {
        val media = player.media ?: return null
        return try {
            block(media)
        } finally {
            media.release()
        }
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun togglePlay() {
        if (_state.value.ended) {
            lastLoad?.let { (s, _) -> load(s, 0) }
            return
        }
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun seekTo(ms: Long) {
        val total = length
        val target = if (total > 0) ms.coerceIn(0, total) else ms.coerceAtLeast(0)
        pendingSeek = target
        _state.update { it.copy(positionMs = target) }
        main.removeCallbacks(applySeek)
        main.postDelayed(applySeek, SEEK_SETTLE_MS)
    }

    private fun seekNow(ms: Long) {
        val total = length
        val target = if (total > 0) ms.coerceIn(0, total) else ms.coerceAtLeast(0)
        if (player.length <= 0 && total > 0) {
            // A stream VLC can't time: seek by position within the known length.
            player.position = target.toFloat() / total
        } else {
            player.setTime(target, true) // the nearest keyframe: far less work for the decoder
        }
    }

    override fun seekBy(deltaMs: Long) = seekTo((pendingSeek ?: player.time) + deltaMs)

    override fun selectAudio(id: String) {
        player.audioTrack = id.toInt()
        refreshTracks()
    }

    override fun positionNow(): Long = pendingSeek ?: if (::player.isInitialized) player.time else 0

    override val canDelaySubtitles = true

    override fun setSubtitleDelay(ms: Long) {
        if (::player.isInitialized) player.spuDelay = ms * 1000 // microseconds; positive is later
    }

    override fun selectText(id: String?) {
        player.spuTrack = id?.toInt() ?: -1
        refreshTracks()
    }

    override fun navigate(direction: MenuKey): Boolean {
        if (!_state.value.inMenu) return false
        player.navigate(
            when (direction) {
                MenuKey.Up -> MediaPlayer.Navigate.Up
                MenuKey.Down -> MediaPlayer.Navigate.Down
                MenuKey.Left -> MediaPlayer.Navigate.Left
                MenuKey.Right -> MediaPlayer.Navigate.Right
                MenuKey.Activate -> MediaPlayer.Navigate.Activate
            },
        )
        return true
    }

    override fun showDiscMenu() {
        val titles = player.titles ?: return
        val menu = titles.indexOfFirst { it.isMenu }
        if (menu >= 0) player.title = menu
    }

    override fun release() {
        main.removeCallbacks(applySeek)
        if (!::player.isInitialized) return
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        libVlc.release()
    }

    private var layout: VLCVideoLayout? = null
    override val videoView: android.view.View? get() = layout

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        AndroidView(
            factory = { ctx -> VLCVideoLayout(ctx).apply { keepScreenOn = true }.also { layout = it } },
            modifier = modifier,
            update = { layout ->
                if (!player.vlcVout.areViewsAttached()) player.attachViews(layout, null, true, false)
            },
        )
        DisposableEffect(Unit) {
            onDispose {
                layout = null
                player.detachViews()
            }
        }
    }
}

private const val SEEK_SETTLE_MS = 400L

/**
 * VLC's codec names as people know them. libVLC reports either a fourcc ("h264", "a52 ") or a
 * description ("H264 - MPEG-4 AVC (PART 10)", "A52 Audio (aka AC3)"), so match loosely.
 */
private fun fourcc(code: String?): String {
    val c = code?.trim()?.uppercase().orEmpty()
    return when {
        c.isEmpty() -> ""
        "HEVC" in c || "H265" in c -> "HEVC"
        "H264" in c || "AVC" in c -> "H.264"
        "MPEG-1/2 VIDEO" in c || c == "MPGV" || c == "MP2V" -> "MPEG-2"
        "MPEG-4 VIDEO" in c || c == "MP4V" -> "MPEG-4"
        "VC-1" in c || "VC1" in c || c == "WVC1" -> "VC-1"
        "VP9" in c || c == "VP90" -> "VP9"
        "AV1" in c || c == "AV01" -> "AV1"
        "E-AC" in c || "EAC3" in c || "ENHANCED" in c -> "E-AC3"
        "A52" in c || "AC3" in c || "AC-3" in c -> "AC3"
        "TRUEHD" in c || "MLP" in c -> "TrueHD"
        "DTS" in c -> "DTS"
        "LAYER 1/2" in c || c == "MPGA" || c == "MP2A" -> "MP2"
        "LAYER 3" in c || c == "MP3" -> "MP3"
        "AAC" in c || c == "MP4A" -> "AAC"
        "PCM" in c || c == "ARAW" || c.startsWith("S16") || c.startsWith("S24") -> "PCM"
        "FLAC" in c -> "FLAC"
        "OPUS" in c -> "Opus"
        "VORBIS" in c -> "Vorbis"
        else -> c.substringBefore(" - ").lowercase().replaceFirstChar { it.uppercase() }
    }
}

/** VLC names tracks "Track 1 - [English]" or "Stereo - [Português]": keep the useful part. */
private fun String?.cleanTrackName(fallback: String): String {
    val raw = this?.trim().orEmpty()
    if (raw.isEmpty()) return fallback
    val bracket = Regex("\\[(.+?)]").find(raw)?.groupValues?.get(1)
    val head = raw.substringBefore(" - [").removePrefix("Track ").trim()
    return when {
        bracket != null && head.isNotEmpty() && head.toIntOrNull() == null -> "$bracket · $head"
        bracket != null -> bracket
        else -> raw
    }
}
