package com.lookee.tv.ui.player

import com.lookee.tv.data.Codes
import com.lookee.tv.data.LookeeException
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.lookee.tv.LookeeApp
import com.lookee.tv.R
import com.lookee.tv.ui.components.LookeeButton
import com.lookee.tv.ui.components.clock
import com.lookee.tv.ui.navigation.LocalNavigator
import com.lookee.tv.ui.navigation.ToastKind
import com.lookee.tv.ui.theme.LookeeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val Ink = Color(0xFF08090B)
private val Soft = Color(0xFFC9CED6)
private val Glass = Color(0xFF1C2027).copy(alpha = 0.7f)
private val GlassLine = Color(0xFFEEF0F3).copy(alpha = 0.18f)

private const val OSD_HIDE_MS = 4_000L
private const val SAVE_EVERY_MS = 5_000L
private const val SEEK_MS = 10_000L

enum class SubtitleSize(val label: String, val fraction: Float) {
    Small("Small", 0.045f),
    Medium("Medium", 0.055f),
    Large("Large", 0.068f),
}

private enum class Panel { Subtitles, Audio }

private enum class OsdFocus { Play, Bar }

/** Live player state the OSD draws from. */
private data class Ui(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val tracks: Tracks = Tracks.EMPTY,
    val error: String? = null,
)

/**
 * The player (DESIGN.md mockup "Player controls"). ExoPlayer decodes in hardware through
 * MediaCodec; Compose draws the OSD over a bare PlayerView (its own controls are off).
 *
 * Remote: with the OSD hidden, OK pauses or plays, ◀ ▶ jump 10 s (hold to keep going), ▲ ▼
 * show the controls. With the OSD up, the D-pad moves between the bar and the buttons, and on
 * the bar ◀ ▶ seek. Back closes a panel, then hides the OSD, then leaves.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(item: PlayItem) {
    val context = LocalContext.current
    val app = LookeeApp.from(context)
    val nav = LocalNavigator.current
    var current by remember { mutableStateOf(item) }
    var ui by remember { mutableStateOf(Ui()) }
    var subtitleSize by remember { mutableStateOf(SubtitleSize.Medium) }

    val player = remember {
        ExoPlayer.Builder(context)
            // A decoder that fails to start falls back to the next one the box has.
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
    }

    fun save(target: PlayItem = current, finished: Boolean = false) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (finished) duration else player.currentPosition
        if (position <= 0) return
        app.appScope.launch { runCatching { app.core.saveProgress(target.path, target.titleId, position, duration) } }
    }

    // Load the current item: sidecar subtitles go in with it, preferred subtitle language first.
    LaunchedEffect(current) {
        ui = Ui()
        val disc = current.disc
        val sidecars = if (current.stream || disc != null) emptyList() else runCatching { app.core.sidecarSubtitles(current.path) }.getOrDefault(emptyList())
        val preferred = runCatching { app.core.settings().subtitleLanguages }.getOrDefault("").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // A disc rip plays through the core, which joins its main title into one stream.
        val discUrl = if (disc == null) null else try {
            app.core.playDisc(current.path, disc).url
        } catch (e: LookeeException) {
            ui = ui.copy(error = discProblem(e), buffering = false)
            app.core.logEvent("disc ${File(current.path).name}: ${e.message}", warn = true)
            return@LaunchedEffect
        }
        val uri = when {
            discUrl != null -> Uri.parse(discUrl)
            current.path.startsWith("http") -> Uri.parse(current.path)
            else -> Uri.fromFile(File(current.path))
        }
        val media = MediaItem.Builder()
            .setUri(uri)
            .setSubtitleConfigurations(
                sidecars.map { s ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(s.path)))
                        .setId(SIDECAR_ID + s.name)
                        .setMimeType(
                            when (s.format) {
                                "vtt" -> MimeTypes.TEXT_VTT
                                "ass", "ssa" -> MimeTypes.TEXT_SSA
                                else -> MimeTypes.APPLICATION_SUBRIP
                            },
                        )
                        .setLanguage(s.language.ifEmpty { null })
                        .setLabel(s.language.ifEmpty { s.name })
                        .build()
                },
            )
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            // Subtitles come on by themselves only in the first preferred language (pt-BR here).
            .setPreferredTextLanguages(*preferred.take(1).toTypedArray())
            .build()
        player.setMediaItem(media, current.startMs)
        player.prepare()
        player.playWhenReady = true
        app.core.logEvent("play ${File(current.path).name} from ${clock(current.startMs)}")
    }

    // Player events → UI state, diagnostics, end of item.
    val latest by rememberUpdatedState(current)
    DisposableEffect(player) {
        var described = false
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui = ui.copy(playing = isPlaying)
                if (!isPlaying) save()
            }

            override fun onPlaybackStateChanged(state: Int) {
                ui = ui.copy(buffering = state == Player.STATE_BUFFERING)
                if (state == Player.STATE_ENDED) {
                    save(latest, finished = true)
                    val next = latest.next
                    if (next != null) current = next.copy(startMs = 0) else nav.pop()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                ui = ui.copy(tracks = tracks)
                if (tracks.isEmpty || described) return
                described = true
                app.appScope.launch { app.core.logEvent("tracks ${File(latest.path).name}: ${tracks.describe()}") }
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty() && audio.none { it.isSupported }) {
                    val codec = (0 until audio[0].length).firstNotNullOfOrNull { codecName(audio[0].getTrackFormat(it).sampleMimeType) } ?: "this"
                    nav.toast("This file's $codec audio can't play on this box", ToastKind.Problem)
                    app.appScope.launch { app.core.logEvent("no playable audio in ${File(latest.path).name} ($codec)", warn = true) }
                }
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                if (video.isNotEmpty() && video.none { it.isSupported }) {
                    val codec = codecName(video[0].getTrackFormat(0).sampleMimeType) ?: "this"
                    ui = ui.copy(error = "This box can't decode $codec video.")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                ui = ui.copy(error = explain(error), buffering = false)
                app.appScope.launch { app.core.logEvent("playback error ${File(latest.path).name}: ${error.errorCodeName} ${error.cause?.message.orEmpty()}", warn = true) }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                described = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            save()
            player.release()
            app.library.changed() // Continue Watching and progress bars catch up
            if (current.stream) app.appScope.launch { runCatching { app.core.stopStream() } }
            app.appScope.launch { app.core.stopDisc() }
        }
    }

    // Leaving the app (Home button) pauses and saves.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Position for the bar, and a save every few seconds while playing.
    LaunchedEffect(player) {
        var sinceSave = 0L
        while (true) {
            ui = ui.copy(
                positionMs = player.currentPosition,
                durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
                bufferedMs = player.bufferedPosition,
            )
            delay(250)
            sinceSave += 250
            if (sinceSave >= SAVE_EVERY_MS && player.isPlaying) {
                sinceSave = 0
                save()
            }
        }
    }

    // ---- OSD visibility and focus ----
    var osd by remember { mutableStateOf(true) }
    var osdFocus by remember { mutableStateOf(OsdFocus.Play) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var lastInput by remember { mutableLongStateOf(0L) }
    val root = remember { FocusRequester() }
    val playButton = remember { FocusRequester() }
    val bar = remember { FocusRequester() }
    val alpha by animateFloatAsState(if (osd || panel != null) 1f else 0f, tween(220), label = "osd")

    fun show(focus: OsdFocus) {
        osdFocus = focus
        osd = true
        lastInput = System.nanoTime()
    }
    LaunchedEffect(osd, ui.playing, lastInput, panel) {
        if (osd && ui.playing && panel == null && ui.error == null) {
            delay(OSD_HIDE_MS)
            osd = false
        }
    }
    LaunchedEffect(osd, osdFocus, panel, ui.error) {
        withFrameNanos { }
        if (panel != null || ui.error != null) return@LaunchedEffect
        runCatching {
            when {
                !osd -> root.requestFocus()
                osdFocus == OsdFocus.Bar -> bar.requestFocus()
                else -> playButton.requestFocus()
            }
        }
    }

    // Back is read straight from the key: the focus traps (panel, pushed screen) swallow it
    // before the activity's back dispatcher sees it.
    val back = {
        when {
            panel != null -> panel = null
            osd && ui.error == null && ui.playing -> osd = false
            else -> nav.pop()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) lastInput = System.nanoTime()
                if (e.key == Key.Back || e.key == Key.Escape) {
                    if (e.type == KeyEventType.KeyUp) back()
                    return@onPreviewKeyEvent true
                }
                // Media keys work everywhere.
                val down = e.type == KeyEventType.KeyDown
                when (e.key) {
                    Key.MediaPlayPause -> { if (down) togglePlay(player); return@onPreviewKeyEvent true }
                    Key.MediaPlay -> { if (down) player.play(); return@onPreviewKeyEvent true }
                    Key.MediaPause -> { if (down) player.pause(); return@onPreviewKeyEvent true }
                    Key.MediaFastForward -> { if (down) { player.seekForward(); show(OsdFocus.Bar) }; return@onPreviewKeyEvent true }
                    Key.MediaRewind -> { if (down) { player.seekBack(); show(OsdFocus.Bar) }; return@onPreviewKeyEvent true }
                }
                if (osd || panel != null || ui.error != null) return@onPreviewKeyEvent false
                // OSD hidden: the remote drives playback directly.
                when (e.key) {
                    Key.DirectionLeft -> { if (down) { player.seekBack(); show(OsdFocus.Bar) }; true }
                    Key.DirectionRight -> { if (down) { player.seekForward(); show(OsdFocus.Bar) }; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (down) { togglePlay(player); show(OsdFocus.Play) }; true }
                    Key.DirectionUp, Key.DirectionDown -> { if (down) show(OsdFocus.Play); true }
                    else -> false
                }
            }
            .focusRequester(root)
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    this.player = player
                    subtitleView?.apply {
                        setApplyEmbeddedFontSizes(false)
                        setStyle(
                            CaptionStyleCompat(
                                AndroidColor.WHITE,
                                AndroidColor.TRANSPARENT,
                                AndroidColor.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                AndroidColor.BLACK,
                                ResourcesCompat.getFont(ctx, R.font.inter_semi_bold),
                            ),
                        )
                    }
                }
            },
            update = { view -> view.subtitleView?.setFractionalTextSize(subtitleSize.fraction) },
            modifier = Modifier.fillMaxSize(),
        )

        if (ui.buffering && ui.error == null) Spinner(Modifier.align(Alignment.Center))

        // ---- OSD ----
        if (ui.error == null) Box(Modifier.fillMaxSize().alpha(alpha)) {
            Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.85f), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(210.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.92f)))))
            Header(current, ui.tracks, Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp))
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp),
            ) {
                SeekBar(
                    ui,
                    onSeek = { delta -> player.seekTo((player.currentPosition + delta).coerceIn(0, player.duration.coerceAtLeast(0))) },
                    modifier = Modifier.focusRequester(bar),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val text = ui.tracks.choices(C.TRACK_TYPE_TEXT)
                        val on = text.firstOrNull { it.selected }
                        PillButton("Subtitles" + (on?.let { " · ${it.label}" } ?: ""), Glyph.Subtitles) { panel = Panel.Subtitles }
                        if (ui.tracks.choices(C.TRACK_TYPE_AUDIO).size > 1) PillButton("Audio", Glyph.Audio) { panel = Panel.Audio }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(44.dp, onClick = { player.seekBack() }) { Skip(back = true, it) }
                        RoundButton(64.dp, primary = true, onClick = { togglePlay(player) }, modifier = Modifier.focusRequester(playButton)) { PlayPause(ui.playing, it) }
                        RoundButton(44.dp, onClick = { player.seekForward() }) { Skip(back = false, it) }
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        current.next?.let { next ->
                            PillButton("Next: ${next.title}", Glyph.Next, trailingIcon = true) {
                                save()
                                current = next.copy(startMs = 0)
                            }
                        }
                    }
                }
            }
        }

        ui.error?.let { message -> ErrorPanel(message, onBack = { nav.pop() }, Modifier.align(Alignment.Center)) }

        panel?.let { p ->
            TrackPanel(
                title = if (p == Panel.Subtitles) "Subtitles" else "Audio",
                choices = ui.tracks.choices(if (p == Panel.Subtitles) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO),
                offOption = p == Panel.Subtitles,
                offSelected = ui.tracks.choices(C.TRACK_TYPE_TEXT).none { it.selected },
                onPick = { choice -> if (choice == null) player.subtitlesOff() else player.select(choice) },
                size = if (p == Panel.Subtitles) subtitleSize else null,
                onSize = { subtitleSize = it },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private fun togglePlay(player: Player) {
    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
    if (player.isPlaying) player.pause() else player.play()
}

/** "Blu-ray disc images can't play yet." from the core's "disc: unsupported: …". */
private fun discProblem(e: LookeeException): String {
    if (e.code != Codes.UNSUPPORTED_DISC) return e.message ?: "This disc couldn't be opened."
    val reason = e.message.orEmpty().substringAfterLast("unsupported: ").trim()
    return reason.replaceFirstChar { it.uppercase() }.let { if (it.endsWith('.')) it else "$it." }
}

/** Plain words for what went wrong; the codes go to the log. */
private fun explain(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
        "The file isn't there anymore. Is the drive connected?"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
        "lookee can't read this kind of file yet."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
        "This box can't decode this video."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "The stream stopped responding."
    else -> "Playback stopped (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})."
}

// ---- pieces ----

@Composable
private fun Header(item: PlayItem, tracks: Tracks, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            if (item.subtitle.isNotEmpty()) Text(item.subtitle, fontSize = 13.sp, color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = LookeeColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(start = 24.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(item.source)
            tracks.summary().takeIf { it.isNotEmpty() }?.let { Chip(it) }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Soft,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1C2027).copy(alpha = 0.85f)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun SeekBar(ui: Ui, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val duration = ui.durationMs.coerceAtLeast(1)
    val played = (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (ui.bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(
            modifier
                .fillMaxWidth()
                .height(20.dp)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    when (e.key) {
                        Key.DirectionLeft -> { if (e.type == KeyEventType.KeyDown) onSeek(-SEEK_MS); true }
                        Key.DirectionRight -> { if (e.type == KeyEventType.KeyDown) onSeek(SEEK_MS); true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val track = if (focused) 7.dp else 5.dp
            Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(50)).background(LookeeColors.Text.copy(alpha = 0.16f)))
            Box(Modifier.fillMaxWidth(buffered).height(track).clip(RoundedCornerShape(50)).background(LookeeColors.Text.copy(alpha = 0.32f)))
            Box(Modifier.fillMaxWidth(played).height(track).clip(RoundedCornerShape(50)).background(LookeeColors.Accent))
            val thumb = if (focused) 18.dp else 12.dp
            Box(
                Modifier
                    .offset(x = maxWidth * played - thumb / 2)
                    .size(thumb)
                    .then(if (focused) Modifier.background(LookeeColors.Accent.copy(alpha = 0.25f), CircleShape).padding(4.dp) else Modifier)
                    .clip(CircleShape)
                    .background(LookeeColors.Accent),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(ui.positionMs), fontSize = 13.sp, color = Soft)
            Text(if (ui.durationMs > 0) "−" + clock((ui.durationMs - ui.positionMs).coerceAtLeast(0)) else "", fontSize = 13.sp, color = Soft)
        }
    }
}

private enum class Glyph { Subtitles, Audio, Next }

@Composable
private fun PillButton(text: String, glyph: Glyph, trailingIcon: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(11.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Glass, focusedContainerColor = LookeeColors.Accent, pressedContainerColor = LookeeColors.Accent),
        border = ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, GlassLine), shape = RoundedCornerShape(11.dp))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(LookeeColors.AccentSoft, 8.dp)),
    ) {
        val color = if (focused) LookeeColors.AccentInk else LookeeColors.Text
        Row(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!trailingIcon) GlyphIcon(glyph, color)
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            if (trailingIcon) GlyphIcon(glyph, color)
        }
    }
}

@Composable
private fun RoundButton(size: androidx.compose.ui.unit.Dp, primary: Boolean = false, onClick: () -> Unit, modifier: Modifier = Modifier, icon: @Composable (Color) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.size(size).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) LookeeColors.Accent else Glass,
            focusedContainerColor = LookeeColors.Accent,
            pressedContainerColor = LookeeColors.Accent,
        ),
        border = ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, if (primary) Color.Transparent else GlassLine), shape = CircleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(LookeeColors.AccentSoft, 10.dp)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon(if (primary || focused) LookeeColors.AccentInk else LookeeColors.Text) }
    }
}

@Composable
private fun PlayPause(playing: Boolean, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        if (playing) {
            val w = size.width * 0.26f
            drawRoundRect(color, topLeft = Offset(size.width * 0.16f, size.height * 0.1f), size = Size(w, size.height * 0.8f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f))
            drawRoundRect(color, topLeft = Offset(size.width * 0.58f, size.height * 0.1f), size = Size(w, size.height * 0.8f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f))
        } else {
            val p = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.08f)
                lineTo(size.width * 0.92f, size.height * 0.5f)
                lineTo(size.width * 0.22f, size.height * 0.92f)
                close()
            }
            drawPath(p, color)
        }
    }
}

/** A circular arrow with "10" inside. */
@Composable
private fun Skip(back: Boolean, color: Color) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = size.width * 0.09f
            val start = if (back) -60f else -120f
            drawArc(color, startAngle = if (back) start + 40f else start, sweepAngle = if (back) 280f else -280f, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round), topLeft = Offset(stroke, stroke), size = Size(size.width - stroke * 2, size.height - stroke * 2))
            val tipX = if (back) size.width * 0.28f else size.width * 0.72f
            val head = Path().apply {
                moveTo(tipX, size.height * 0.02f)
                lineTo(tipX + (if (back) -1 else 1) * size.width * 0.2f, size.height * 0.14f)
                lineTo(tipX, size.height * 0.28f)
                close()
            }
            drawPath(head, color)
        }
        Text("10", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun GlyphIcon(glyph: Glyph, color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val s = size.width
        when (glyph) {
            Glyph.Subtitles -> {
                drawRoundRect(color, topLeft = Offset(0f, s * 0.15f), size = Size(s, s * 0.7f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.15f), style = Stroke(s * 0.1f))
                drawLine(color, Offset(s * 0.2f, s * 0.45f), Offset(s * 0.55f, s * 0.45f), strokeWidth = s * 0.1f, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.2f, s * 0.65f), Offset(s * 0.8f, s * 0.65f), strokeWidth = s * 0.1f, cap = StrokeCap.Round)
            }
            Glyph.Audio -> listOf(0.35f, 0.75f, 0.5f, 0.9f).forEachIndexed { i, h ->
                val x = s * (0.12f + i * 0.25f)
                drawLine(color, Offset(x, s * (1f - h) / 2 + s * 0.05f), Offset(x, s - s * (1f - h) / 2 - s * 0.05f), strokeWidth = s * 0.13f, cap = StrokeCap.Round)
            }
            Glyph.Next -> {
                val p = Path().apply {
                    moveTo(s * 0.1f, s * 0.12f)
                    lineTo(s * 0.7f, s * 0.5f)
                    lineTo(s * 0.1f, s * 0.88f)
                    close()
                }
                drawPath(p, color)
                drawLine(color, Offset(s * 0.86f, s * 0.14f), Offset(s * 0.86f, s * 0.86f), strokeWidth = s * 0.14f, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier) {
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    Canvas(modifier.size(44.dp)) {
        drawArc(LookeeColors.Text.copy(alpha = 0.15f), 0f, 360f, false, style = Stroke(size.width * 0.09f))
        drawArc(LookeeColors.Accent, angle, 90f, false, style = Stroke(size.width * 0.09f, cap = StrokeCap.Round))
    }
}

@Composable
private fun ErrorPanel(message: String, onBack: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Column(
        modifier
            .widthIn(max = 520.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LookeeColors.Surface.copy(alpha = 0.97f))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Can't play this", style = MaterialTheme.typography.headlineSmall, color = LookeeColors.Text)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Soft)
        Text("The details are in Settings → Diagnostics.", style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted)
        Spacer(Modifier.height(6.dp))
        LookeeButton("Back", onClick = onBack, modifier = Modifier.focusRequester(focus))
    }
}
