package com.lookee.tv.ui.player.engine

import android.net.Uri
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lookee.tv.data.SidecarSubtitle
import kotlinx.coroutines.flow.StateFlow

/**
 * One way of playing video. lookee has two (ROADMAP Decision 14, hybrid):
 *  - [ExoEngine]: Media3/ExoPlayer, hardware decoding through MediaCodec. Files and streams.
 *  - [VlcEngine]: libVLC. Disc rips with their menus, and any file ExoPlayer can't play
 *    (MP2, DTS-HD, odd containers): it decodes audio in software.
 * The player screen draws the same controls over either.
 */
interface PlaybackEngine {
    val state: StateFlow<EngineState>

    /** "exo" or "vlc", for the log. */
    val name: String

    /** Can the subtitle size be changed (the panel hides the control otherwise)? */
    val canResizeSubtitles: Boolean

    fun load(source: Source, startMs: Long)

    /** The position right now, read every frame while external subtitles are drawn. */
    fun positionNow(): Long

    /** Can embedded subtitles be moved in time ([setSubtitleDelay])? */
    val canDelaySubtitles: Boolean get() = false

    /** Shows the engine's own subtitles [ms] later (negative: earlier). */
    fun setSubtitleDelay(ms: Long) {}

    /** Called four times a second by the screen, for state with no events (position, menus). */
    fun poll() {}
    fun play()
    fun pause()
    fun togglePlay()
    fun seekTo(ms: Long)
    fun seekBy(deltaMs: Long)
    fun selectAudio(id: String)

    /** null turns subtitles off. */
    fun selectText(id: String?)
    fun setSubtitleSize(size: SubtitleSize) {}

    /** Moves the highlight in a disc menu; false when no menu is showing. */
    fun navigate(direction: MenuKey): Boolean = false

    /** Jumps to the disc's menu, when it has one. */
    fun showDiscMenu() {}

    fun release()

    /** The view holding the video surface, while it's on screen (for FrameGrab). */
    val videoView: View? get() = null

    @Composable
    fun VideoSurface(modifier: Modifier)
}

enum class MenuKey { Up, Down, Left, Right, Activate }

enum class SubtitleSize(val label: String, val fraction: Float, val vlcScale: Int) {
    Small("Small", 0.045f, 80),
    Medium("Medium", 0.055f, 100),
    Large("Large", 0.068f, 125),
}

/** What to play. [uri] is a file, an http stream, or a libVLC disc MRL (dvd://, bluray://). */
data class Source(
    val uri: Uri,
    /** Shown in logs: the file or folder name. */
    val label: String,
    /**
     * Subtitle files next to the video. The screen draws these itself (SubtitleOverlay), as it
     * does downloads; engines only play subtitles inside the file.
     */
    val sidecars: List<SidecarSubtitle> = emptyList(),
    /** The language embedded subtitles come on in by themselves ("pt-BR"); null keeps them off. */
    val preferredText: String? = null,
    /** A disc opened at its menu: no resume, and the D-pad drives the menu. */
    val discMenu: Boolean = false,
    /** Tried in order when [uri] fails to open (a disc image: DVD first, then Blu-ray). */
    val alternates: List<Uri> = emptyList(),
    /** The length when the source can't say (the core's Blu-ray stream: its playlist's length). */
    val durationHintMs: Long = 0,
    /** The frame rate when the source can't say (a DVD: the core reads it from the disc). */
    val frameRateHint: Float = 0f,
    /** Decode the video in software and deinterlace it (DVDs; see VlcEngine.create). */
    val softwareVideo: Boolean = false,
)

/** An audio or subtitle track to pick. */
data class Choice(
    val id: String,
    val label: String,
    val caption: String = "",
    val selected: Boolean = false,
    val supported: Boolean = true,
)

data class EngineState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val audio: List<Choice> = emptyList(),
    val text: List<Choice> = emptyList(),
    /** The header chip: "1080p · H.264 · AC3". */
    val summary: String = "",
    /** The video's frames per second, when known: the TV's refresh rate is matched to it. */
    val frameRate: Float = 0f,
    /** A disc menu is on screen: the D-pad goes to the menu, not the controls. */
    val inMenu: Boolean = false,
    val hasMenu: Boolean = false,
    val ended: Boolean = false,
    /** Can't play at all: shown to the viewer. */
    val error: String? = null,
    /**
     * This engine can't play the file but the other one may (ExoPlayer → VLC): the reason,
     * for the log. The screen switches engines at the current position.
     */
    val fallback: String? = null,
)
