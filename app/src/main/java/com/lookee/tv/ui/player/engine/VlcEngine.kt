package com.lookee.tv.ui.player.engine

import android.content.Context
import android.net.Uri
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
import java.io.File

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

    private val libVlc = LibVLC(
        context,
        arrayListOf(
            "--audio-time-stretch",
            // No software deinterlacing: it forces every frame off the hardware decoder and the
            // Mi Box's CPU can't keep up (DVDs stuttered). The Amlogic decoder handles interlace.
            "--deinterlace=0",
            // Draw straight to Android's surface, not through OpenGL: VLC's GLES output crashed
            // in the Mi Box's Mali driver (eglSwapBuffers, SIGSEGV) when seeking a Blu-ray.
            "--vout=android_display",
            "--no-video-title-show",
            "--sub-text-scale=100",
            "--no-osd",
        ),
    )
    private val player = MediaPlayer(libVlc)
    private var label = ""
    private var alternates: List<Uri> = emptyList()
    private var described = false
    private var lastLoad: Pair<Source, Long>? = null
    private var durationHint = 0L

    /** VLC's own length, else the source's hint (a stream served by the core has none). */
    private val length: Long get() = player.length.takeIf { it > 0 } ?: durationHint

    init {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> {
                    _state.update { it.copy(playing = true, buffering = false) }
                    describe()
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> _state.update { it.copy(playing = false) }
                MediaPlayer.Event.Buffering -> _state.update { it.copy(buffering = e.buffering < 100f) }
                MediaPlayer.Event.TimeChanged -> _state.update { it.copy(positionMs = e.timeChanged) }
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
            log("vlc couldn't open $label as ${player.media?.uri?.scheme}; trying ${next.scheme}", true)
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
        lastLoad = source to startMs
        _state.value = EngineState(hasMenu = source.discMenu)
        open(source.uri, startMs, source.discMenu)
        source.sidecars.forEach { s ->
            player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(File(s.path)), false)
        }
    }

    private fun open(uri: Uri, startMs: Long, discMenu: Boolean) {
        described = false
        val media = Media(libVlc, uri)
        media.setHWDecoderEnabled(true, false) // MediaCodec when it can, software otherwise
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
                positionMs = if (player.time >= 0) player.time else it.positionMs,
                durationMs = length.takeIf { l -> l > 0 } ?: it.durationMs,
                inMenu = inMenu,
                hasMenu = it.hasMenu || hasMenu,
            )
        }
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
    private fun summary(): String {
        val media = player.media ?: return ""
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
        video?.takeIf { it.frameRateDen > 0 && it.frameRateNum > 0 }?.let { v ->
            val fps = v.frameRateNum.toFloat() / v.frameRateDen
            if (fps != _state.value.frameRate) _state.update { st -> st.copy(frameRate = fps) }
        }
        return listOfNotNull(res, video?.codec?.let(::fourcc), audio?.codec?.let(::fourcc)).joinToString(" · ")
    }

    private fun describe() {
        if (described) return
        val media = player.media ?: return
        if (media.trackCount == 0) return
        described = true
        val tracks = (0 until media.trackCount).joinToString("; ") { i ->
            when (val t = media.getTrack(i)) {
                is IMedia.VideoTrack -> "video: ${fourcc(t.codec)} ${t.width}x${t.height}"
                is IMedia.AudioTrack -> "audio: ${fourcc(t.codec)} ${t.channels}ch ${t.language.orEmpty()}".trim()
                is IMedia.SubtitleTrack -> "text: ${t.codec} ${t.language.orEmpty()}".trim()
                else -> "other"
            }
        }
        log("vlc tracks $label: $tracks", false)
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
        if (player.length <= 0 && total > 0) {
            // A stream VLC can't time: seek by position within the known length.
            player.position = target.toFloat() / total
        } else {
            player.time = target
        }
    }

    override fun seekBy(deltaMs: Long) = seekTo(player.time + deltaMs)

    override fun selectAudio(id: String) {
        player.audioTrack = id.toInt()
        refreshTracks()
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
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        libVlc.release()
    }

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        AndroidView(
            factory = { ctx -> VLCVideoLayout(ctx).apply { keepScreenOn = true } },
            modifier = modifier,
            update = { layout ->
                if (!player.vlcVout.areViewsAttached()) player.attachViews(layout, null, true, false)
            },
        )
        DisposableEffect(Unit) { onDispose { player.detachViews() } }
    }
}

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
