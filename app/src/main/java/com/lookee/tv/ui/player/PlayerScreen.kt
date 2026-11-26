package com.lookee.tv.ui.player

import android.net.Uri
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.lookee.tv.LookeeApp
import com.lookee.tv.data.Codes
import com.lookee.tv.data.Cue
import com.lookee.tv.data.LookeeException
import com.lookee.tv.data.SubtitleRequest
import com.lookee.tv.data.SubtitleResult
import com.lookee.tv.data.VideoFrames
import com.lookee.tv.ui.components.LookeeButton
import com.lookee.tv.ui.components.clock
import com.lookee.tv.ui.navigation.LocalNavigator
import com.lookee.tv.ui.navigation.ToastKind
import com.lookee.tv.ui.player.engine.Choice
import com.lookee.tv.ui.player.engine.EngineState
import com.lookee.tv.ui.player.engine.ExoEngine
import com.lookee.tv.ui.player.engine.MenuKey
import com.lookee.tv.ui.player.engine.PlaybackEngine
import com.lookee.tv.ui.player.engine.Source
import com.lookee.tv.ui.player.engine.SubtitleSize
import com.lookee.tv.ui.player.engine.VlcEngine
import com.lookee.tv.ui.theme.LookeeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private val Ink = Color(0xFF08090B)
private val Glass = Color(0xFF1C2027).copy(alpha = 0.7f)
private val GlassLine = Color(0xFFEEF0F3).copy(alpha = 0.18f)

private const val OSD_HIDE_MS = 4_000L
private const val SAVE_EVERY_MS = 5_000L
private const val SYNC_NOTE = "◀ ▶ moves 0.1 s, from −5.0 s to +5.0 s. Text later →, earlier ←."

/** What to tell the viewer when OpenSubtitles says no. */
private fun subtitleProblem(e: LookeeException): SearchState.Failed = when (e.code) {
    Codes.NO_SUBTITLE_KEY -> SearchState.Failed("Add your OpenSubtitles API key first: Settings → Set up from your phone.", canRetry = false)
    Codes.SUBTITLE_QUOTA -> SearchState.Failed("Today's OpenSubtitles download limit is used up. It resets within a day.", canRetry = false)
    else -> SearchState.Failed("Couldn't reach OpenSubtitles. Check the connection and try again.", canRetry = true)
}
private const val GRAB_EVERY_MS = 20_000L
private const val SEEK_MS = 10_000L

private enum class Panel { Subtitles, Search, Audio }

private enum class OsdFocus { Play, Bar }

/** An engine playing a source. A new session replaces the old one (next item, VLC fallback). */
private class Session(val engine: PlaybackEngine, val source: Source, val item: PlayItem) {
    /** The latest picture grabbed from the video, for the Continue Watching card. */
    @Volatile var frame: android.graphics.Bitmap? = null
}

/**
 * The player (DESIGN.md mockup "Player controls"), drawn the same over either engine
 * (ExoPlayer for files and streams, VLC for discs and for files ExoPlayer can't play).
 *
 * Remote: with the OSD hidden, OK pauses or plays, ◀ ▶ jump 10 s (hold to keep going), ▲ ▼
 * show the controls. With the OSD up, the D-pad moves between the bar and the buttons, and on
 * the bar ◀ ▶ seek. In a disc menu, the D-pad and OK drive the menu. Back closes a panel,
 * then hides the OSD, then leaves.
 */
@Composable
fun PlayerScreen(item: PlayItem) {
    val context = LocalContext.current
    val app = LookeeApp.from(context)
    val nav = LocalNavigator.current
    val log: (String, Boolean) -> Unit = { msg, warn -> app.appScope.launch { app.core.logEvent(msg, warn) } }

    var current by remember { mutableStateOf(item) }
    var session by remember { mutableStateOf<Session?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    var subtitleSize by remember { mutableStateOf(SubtitleSize.Medium) }
    val noState = remember { MutableStateFlow(EngineState()) }
    val ui by (session?.engine?.state ?: noState).collectAsState()

    /** Saves the position; [leaving] also stores the last grabbed frame for Continue Watching. */
    fun save(s: Session? = session, finished: Boolean = false, leaving: Boolean = false): Job? {
        if (s == null) return null
        val st = s.engine.state.value
        if (st.durationMs <= 0) return null
        // A disc: only its feature, not its menus or a short extra (resuming plays the longest title).
        if (s.source.discMenu && (st.inMenu || st.durationMs < 20 * 60_000)) return null
        val position = if (finished) st.durationMs else st.positionMs
        if (position <= 0) return null
        val frame = s.frame.takeIf { leaving && !finished && !s.item.stream }
        return app.appScope.launch(Dispatchers.IO) {
            runCatching { app.core.saveProgress(s.item.path, s.item.titleId, position, st.durationMs) }
            if (frame != null) runCatching { VideoFrames.saveResume(context, s.item.path, position, frame) }
        }
    }

    // A frame every so often while playing, on pause and on the way out (see FrameGrab).
    suspend fun grab(s: Session? = session) {
        if (s == null) return
        val st = s.engine.state.value
        if (st.inMenu || st.durationMs <= 0 || st.error != null) return
        FrameGrab.capture(s.engine.videoView)?.let { s.frame = it }
    }

    fun start(target: PlayItem, source: Source, vlc: Boolean, startMs: Long) {
        val engine = if (vlc) VlcEngine(context, log) else ExoEngine(context, log)
        engine.setSubtitleSize(subtitleSize)
        engine.load(source, startMs)
        session = Session(engine, source, target)
        log("play ${source.label} with ${engine.name}" + if (startMs > 0) " from ${clock(startMs)}" else "", false)
    }

    // Work out what to play and with which engine, then start it.
    LaunchedEffect(current) {
        openError = null
        val target = current
        try {
            val (source, vlc) = resolveSource(target, app)
            start(target, source, vlc, target.startMs)
        } catch (e: LookeeException) {
            openError = if (e.code == Codes.UNSUPPORTED_DISC) discProblem(e) else (e.message ?: "This couldn't be opened.")
            log("open ${File(target.path).name}: ${e.message}", true)
        }
    }

    var panel by remember { mutableStateOf<Panel?>(null) }

    // ---- subtitles the screen draws itself (files next to the video, downloads) ----
    var externals by remember(current) { mutableStateOf<List<ExternalSub>>(emptyList()) }
    var activeExternal by remember(current) { mutableStateOf<String?>(null) }
    var cues by remember(current) { mutableStateOf<List<Cue>>(emptyList()) }
    var subOffset by remember(current) { mutableLongStateOf(0L) }
    var search by remember(current) { mutableStateOf<SearchState>(SearchState.Loading) }
    var searched by remember(current) { mutableStateOf(false) }
    var downloading by remember(current) { mutableStateOf<Long?>(null) }
    val subScope = rememberCoroutineScope()

    fun activate(sub: ExternalSub, quiet: Boolean = false) {
        subScope.launch {
            try {
                val loaded = app.core.subtitleCues(sub.path)
                cues = loaded
                activeExternal = sub.id
                subOffset = 0
                session?.engine?.apply {
                    selectText(null)
                    setSubtitleDelay(0)
                }
                log("subtitles ${sub.label} (${sub.caption}): ${loaded.size} cues", false)
            } catch (e: LookeeException) {
                log("subtitles ${sub.path}: ${e.message}", true)
                if (!quiet) nav.toast("This subtitle file couldn't be read", ToastKind.Problem)
            }
        }
    }

    fun pickSubtitle(id: String?) {
        val eng = session?.engine
        subOffset = 0
        eng?.setSubtitleDelay(0)
        when {
            id == null -> {
                activeExternal = null
                cues = emptyList()
                eng?.selectText(null)
            }
            id.startsWith(EXTERNAL) -> externals.firstOrNull { EXTERNAL + it.id == id }?.let { activate(it) }
            else -> {
                activeExternal = null
                cues = emptyList()
                eng?.selectText(id)
            }
        }
    }

    fun runSearch() {
        searched = true
        search = SearchState.Loading
        val item = current
        val local = !item.stream && item.disc == null
        val request = SubtitleRequest(
            videoPath = item.path.takeIf { local },
            titleId = item.titleId.takeIf { it > 0 },
            season = item.season.takeIf { it > 0 },
            episode = item.episode.takeIf { it > 0 },
            tmdbId = item.tmdbId.takeIf { it > 0 },
            kind = item.kind.ifEmpty { null },
            fileName = item.streamFile.ifEmpty { null },
            // Unmatched discs (and streams without a TMDB id): the title is all there is.
            query = item.title.takeIf { item.titleId <= 0 && item.tmdbId <= 0 && !local },
        )
        subScope.launch {
            search = try {
                val found = app.core.searchSubtitles(request)
                if (found.isEmpty()) {
                    SearchState.Failed("Nothing found in your subtitle languages. You can change them in Settings.", canRetry = true)
                } else {
                    SearchState.Results(found)
                }
            } catch (e: LookeeException) {
                log("subtitle search ${item.title}: ${e.message}", true)
                subtitleProblem(e)
            }
        }
    }

    fun download(r: SubtitleResult) {
        if (downloading != null) return
        downloading = r.fileId
        subScope.launch {
            try {
                val path = app.core.downloadSubtitle(r.fileId).path
                val sub = ExternalSub("os${r.fileId}", languageName(r.language.ifEmpty { "und" }), "OpenSubtitles", path)
                externals = externals.filterNot { it.id == sub.id } + sub
                activate(sub)
                panel = Panel.Subtitles
            } catch (e: LookeeException) {
                log("subtitle download ${r.fileId}: ${e.message}", true)
                val problem = subtitleProblem(e)
                nav.toast(if (problem is SearchState.Failed) problem.message else "Couldn't download those subtitles", ToastKind.Problem)
            } finally {
                downloading = null
            }
        }
    }

    // Files next to the video are listed with the tracks; one in the preferred language comes on.
    LaunchedEffect(session?.source) {
        val source = session?.source ?: return@LaunchedEffect
        if (externals.isNotEmpty()) return@LaunchedEffect // an engine switch: keep what's on
        externals = source.sidecars.map { sc ->
            ExternalSub("side:${sc.name}", sc.language.takeIf { it.isNotEmpty() }?.let(::languageName) ?: sc.name.substringBeforeLast('.'), "Next to the video", sc.path)
        }
        val preferred = source.preferredText ?: return@LaunchedEffect
        val match = source.sidecars.indexOfFirst { sc ->
            sc.language.isNotEmpty() && (sc.language.equals(preferred, true) || preferred.startsWith(sc.language + "-", true))
        }
        if (match >= 0) activate(externals[match], quiet = true)
    }
    // An external subtitle is on: the engine's own stay off (a default-flagged track would
    // otherwise come on underneath when it is found).
    LaunchedEffect(ui.text, activeExternal) {
        if (activeExternal != null && ui.text.any { it.selected }) session?.engine?.selectText(null)
    }

    // ExoPlayer couldn't play it: carry on with VLC from the same spot.
    LaunchedEffect(ui.fallback) {
        val reason = ui.fallback ?: return@LaunchedEffect
        val old = session ?: return@LaunchedEffect
        if (old.engine is VlcEngine) return@LaunchedEffect
        val at = old.engine.state.value.positionMs.coerceAtLeast(old.item.startMs)
        log("switching ${old.source.label} to vlc: $reason", true)
        nav.toast("Switching player for this file's format", ToastKind.Info)
        start(old.item, old.source, vlc = true, startMs = at)
    }

    // The end of an item: the next in the queue, or back out.
    LaunchedEffect(ui.ended) {
        if (!ui.ended) return@LaunchedEffect
        save(finished = true)
        val next = current.next
        if (next != null) current = next.copy(startMs = 0) else nav.pop()
    }

    // Each session's engine is released when it's replaced or the screen closes.
    val s = session
    DisposableEffect(s) {
        onDispose {
            if (s != null) {
                if (s !== session) save(s, leaving = true) // replaced (next episode, engine switch); leaving saves below
                s.engine.release()
            }
        }
    }
    // Disposed before the effect above (effects are disposed in reverse order), so the last
    // position is read while the engine is still alive.
    DisposableEffect(Unit) {
        onDispose {
            val saved = save(leaving = true)
            app.appScope.launch {
                saved?.join()
                app.library.changed() // only now: Continue Watching and progress bars reload with it
                if (item.stream) runCatching { app.core.stopStream() }
                app.core.stopDisc()
            }
        }
    }

    // The TV follows the video's frame rate while the player is open (see RefreshRate).
    val activity = context as? android.app.Activity
    LaunchedEffect(ui.frameRate) {
        if (activity != null && ui.frameRate > 0f) RefreshRate.match(activity, ui.frameRate)?.let { log(it, false) }
    }
    DisposableEffect(activity) { onDispose { activity?.let(RefreshRate::restore) } }

    // Leaving the app (Home button) pauses.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session?.engine?.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Position and menus four times a second; a save every few seconds while playing.
    LaunchedEffect(Unit) {
        var sinceSave = 0L
        var sinceGrab = 0L
        while (true) {
            session?.engine?.poll()
            delay(250)
            sinceSave += 250
            sinceGrab += 250
            if (sinceGrab >= GRAB_EVERY_MS && session?.engine?.state?.value?.playing == true) {
                sinceGrab = 0
                grab()
            }
            if (sinceSave >= SAVE_EVERY_MS && session?.engine?.state?.value?.playing == true) {
                sinceSave = 0
                save()
            }
        }
    }
    var wasPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(ui.playing) {
        if (wasPlaying && !ui.playing) {
            save()
            grab()
        }
        wasPlaying = ui.playing
    }

    val engine = session?.engine
    val error = openError ?: ui.error

    // ---- OSD visibility and focus ----
    var osd by remember { mutableStateOf(true) }
    var osdFocus by remember { mutableStateOf(OsdFocus.Play) }
    var lastInput by remember { mutableLongStateOf(0L) }
    val root = remember { FocusRequester() }
    val playButton = remember { FocusRequester() }
    val bar = remember { FocusRequester() }
    // A side panel hides the controls and dims the video, so a subtitle being synced reads clearly.
    val showControls = osd && panel == null && !ui.inMenu
    val dim by animateFloatAsState(if (panel != null) 0.45f else 0f, tween(220), label = "dim")
    val alpha by animateFloatAsState(if (showControls) 1f else 0f, tween(220), label = "osd")

    fun show(focus: OsdFocus) {
        osdFocus = focus
        osd = true
        lastInput = System.nanoTime()
    }
    LaunchedEffect(osd, ui.playing, lastInput, panel) {
        if (osd && ui.playing && panel == null && error == null) {
            delay(OSD_HIDE_MS)
            osd = false
        }
    }
    LaunchedEffect(showControls, osdFocus, panel, error) {
        withFrameNanos { }
        if (panel != null || error != null) return@LaunchedEffect
        runCatching {
            when {
                !showControls -> root.requestFocus()
                osdFocus == OsdFocus.Bar -> bar.requestFocus()
                else -> playButton.requestFocus()
            }
        }
    }

    // Out of the player, with the frame on screen grabbed first (it takes a few milliseconds).
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    fun leave() {
        if (leaving) return
        leaving = true
        scope.launch {
            grab()
            nav.pop()
        }
    }

    // Back is read straight from the key: the focus traps (panel, pushed screen) swallow it
    // before the activity's back dispatcher sees it.
    val back = {
        when {
            panel == Panel.Search -> panel = Panel.Subtitles
            panel != null -> panel = null
            osd && error == null && ui.playing && !ui.inMenu -> osd = false
            else -> leave()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                if (down) lastInput = System.nanoTime()
                if (e.key == Key.Back || e.key == Key.Escape) {
                    if (e.type == KeyEventType.KeyUp) back()
                    return@onPreviewKeyEvent true
                }
                val eng = engine ?: return@onPreviewKeyEvent false
                // Media keys work everywhere.
                when (e.key) {
                    Key.MediaPlayPause -> { if (down) eng.togglePlay(); return@onPreviewKeyEvent true }
                    Key.MediaPlay -> { if (down) eng.play(); return@onPreviewKeyEvent true }
                    Key.MediaPause -> { if (down) eng.pause(); return@onPreviewKeyEvent true }
                    Key.MediaFastForward -> { if (down) { eng.seekBy(SEEK_MS); show(OsdFocus.Bar) }; return@onPreviewKeyEvent true }
                    Key.MediaRewind -> { if (down) { eng.seekBy(-SEEK_MS); show(OsdFocus.Bar) }; return@onPreviewKeyEvent true }
                }
                if (panel != null || error != null) return@onPreviewKeyEvent false
                // A disc menu on screen: the D-pad moves its highlight, OK picks.
                if (ui.inMenu) {
                    val key = when (e.key) {
                        Key.DirectionUp -> MenuKey.Up
                        Key.DirectionDown -> MenuKey.Down
                        Key.DirectionLeft -> MenuKey.Left
                        Key.DirectionRight -> MenuKey.Right
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> MenuKey.Activate
                        else -> null
                    } ?: return@onPreviewKeyEvent false
                    if (down) eng.navigate(key)
                    return@onPreviewKeyEvent true
                }
                if (osd) return@onPreviewKeyEvent false
                // OSD hidden: the remote drives playback directly.
                when (e.key) {
                    Key.DirectionLeft -> { if (down) { eng.seekBy(-SEEK_MS); show(OsdFocus.Bar) }; true }
                    Key.DirectionRight -> { if (down) { eng.seekBy(SEEK_MS); show(OsdFocus.Bar) }; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (down) { eng.togglePlay(); show(OsdFocus.Play) }; true }
                    Key.DirectionUp, Key.DirectionDown -> { if (down) show(OsdFocus.Play); true }
                    else -> false
                }
            }
            .focusRequester(root)
            .focusable(),
    ) {
        engine?.VideoSurface(Modifier.fillMaxSize())

        if (dim > 0f) Box(Modifier.fillMaxSize().background(Ink.copy(alpha = dim)))

        if (engine != null && activeExternal != null && !ui.inMenu) {
            SubtitleOverlay(
                cues = cues,
                offsetMs = subOffset,
                position = { engine.positionNow() },
                size = subtitleSize,
                lifted = showControls,
                // With the panel open, centred on the picture left of it, as in the mockup.
                modifier = Modifier.padding(end = if (panel != null) 340.dp else 0.dp),
            )
        }

        if ((engine == null || ui.buffering) && error == null && !ui.inMenu) Spinner(Modifier.align(Alignment.Center))

        // ---- OSD ----
        if (error == null && engine != null) Box(Modifier.fillMaxSize().alpha(alpha)) {
            Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.85f), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(210.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.92f)))))
            Header(current, ui.summary, Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp))
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp),
            ) {
                SeekBar(ui, onSeek = { delta -> engine.seekBy(delta) }, modifier = Modifier.focusRequester(bar))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val on = externals.firstOrNull { it.id == activeExternal }?.label ?: ui.text.firstOrNull { it.selected }?.label
                        PillButton("Subtitles" + (on?.let { " · $it" } ?: ""), Glyph.Subtitles) { panel = Panel.Subtitles }
                        if (ui.audio.size > 1) PillButton("Audio", Glyph.Audio) { panel = Panel.Audio }
                        if (ui.hasMenu) PillButton("Disc menu", Glyph.Menu) { engine.showDiscMenu() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(44.dp, onClick = { engine.seekBy(-SEEK_MS) }) { Skip(back = true, it) }
                        RoundButton(64.dp, primary = true, onClick = { engine.togglePlay() }, modifier = Modifier.focusRequester(playButton)) { PlayPause(ui.playing, it) }
                        RoundButton(44.dp, onClick = { engine.seekBy(SEEK_MS) }) { Skip(back = false, it) }
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

        error?.let { message -> ErrorPanel(message, onBack = { nav.pop() }, Modifier.align(Alignment.Center)) }

        when (panel) {
            Panel.Subtitles -> {
                val embeddedOn = activeExternal == null && ui.text.any { it.selected }
                val choices = ui.text.map { if (activeExternal != null) it.copy(selected = false) else it } +
                    externals.map { Choice(EXTERNAL + it.id, it.label, it.caption, selected = it.id == activeExternal) }
                SubtitlePanel(
                    choices = choices,
                    offSelected = activeExternal == null && !embeddedOn,
                    onPick = ::pickSubtitle,
                    onSearch = {
                        if (!searched || search is SearchState.Failed) runSearch()
                        panel = Panel.Search
                    },
                    sync = when {
                        activeExternal != null || (embeddedOn && engine?.canDelaySubtitles == true) -> SyncState(subOffset, true, SYNC_NOTE)
                        embeddedOn -> SyncState(0, false, "Sync works with subtitle files and OpenSubtitles downloads, not the ones inside this video.")
                        else -> null
                    },
                    onSync = { ms ->
                        subOffset = ms
                        if (activeExternal == null) engine?.setSubtitleDelay(ms)
                    },
                    size = if (activeExternal != null || engine?.canResizeSubtitles == true) subtitleSize else null,
                    onSize = {
                        subtitleSize = it
                        engine?.setSubtitleSize(it)
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            Panel.Search -> SubtitleSearchPanel(
                lookingFor = "For " + listOf(current.subtitle.substringBefore(" · Season"), current.title).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                state = search,
                downloading = downloading,
                onPick = ::download,
                onRetry = ::runSearch,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            Panel.Audio -> TrackPanel(
                title = "Audio",
                choices = ui.audio,
                offOption = false,
                offSelected = false,
                onPick = { id -> id?.let { engine?.selectAudio(it) } },
                size = null,
                onSize = {},
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            null -> {}
        }
    }
}

/**
 * Which engine plays [item], from what. Discs open in VLC at their menu (dvdnav/libbluray);
 * a flattened Blu-ray copy has no disc structure VLC knows, so the core joins its main
 * playlist and VLC plays that. Files and streams start in ExoPlayer.
 */
private suspend fun resolveSource(item: PlayItem, app: LookeeApp): Pair<Source, Boolean> {
    val file = File(item.path)
    // "VIDEO_TS" and "BDMV" say nothing: name a disc by its folder.
    val label = if (file.name.equals("VIDEO_TS", true) || file.name.equals("BDMV", true)) file.parentFile?.name ?: file.name else file.name
    if (item.stream) return Source(Uri.parse(item.path), label) to false
    val disc = item.disc
    if (disc == null) {
        val sidecars = runCatching { app.core.sidecarSubtitles(item.path) }.getOrDefault(emptyList())
        val preferred = runCatching { app.core.settings().subtitleLanguages }.getOrDefault("").split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return Source(Uri.fromFile(file), label, sidecars, preferred) to false
    }
    fun mrl(scheme: String, f: File) = Uri.parse("$scheme://" + Uri.fromFile(f).encodedPath)
    val kind = disc.ifEmpty { guessDisc(file) }
    // The core's joined main title: the backup when VLC can't open the disc structure (a rip
    // without its .IFO files, say). Null when the core can't read it either. It also says the
    // DVD's frame rate, which VLC doesn't report for discs (the TV's refresh rate follows it).
    val main = runCatching { app.core.playDisc(item.path, if (kind == "dvd" || kind == "iso") kind else "bluray") }.getOrNull()
    val mainUri = main?.let { Uri.parse(it.url) }
    val fps = main?.frameRate?.toFloat() ?: 0f
    val dvd = main != null && (main.kind == "dvd" || main.kind == "iso") // an image the core read as a DVD
    return when {
        kind == "iso" -> Source(mrl("dvd", file), label, discMenu = true, alternates = listOfNotNull(mrl("bluray", file), mainUri), frameRateHint = fps, softwareVideo = dvd) to true
        kind == "dvd" -> Source(mrl("dvd", file), label, discMenu = true, alternates = listOfNotNull(mainUri), frameRateHint = fps, softwareVideo = dvd) to true
        file.name.equals("BDMV", ignoreCase = true) -> Source(mrl("bluray", file.parentFile ?: file), label, discMenu = true, alternates = listOfNotNull(mainUri)) to true
        File(file, "BDMV").isDirectory -> Source(mrl("bluray", file), label, discMenu = true, alternates = listOfNotNull(mainUri)) to true
        // Flattened Blu-ray copy: the core serves the main playlist as one stream.
        else -> (main ?: app.core.playDisc(item.path, "bluray")).let { play ->
            Source(Uri.parse(play.url), label, durationHintMs = (play.seconds * 1000).toLong()) to true
        }
    }
}

private fun guessDisc(file: File): String = when {
    file.name.endsWith(".iso", ignoreCase = true) -> "iso"
    file.name.equals("VIDEO_TS", ignoreCase = true) -> "dvd"
    file.list()?.any { it.equals("VIDEO_TS", true) || it.endsWith(".vob", true) } == true -> "dvd"
    else -> "bluray"
}

/** "Blu-ray disc images can't play yet." from the core's "disc: unsupported: …". */
private fun discProblem(e: LookeeException): String {
    val reason = e.message.orEmpty().substringAfterLast("unsupported: ").trim()
    return reason.replaceFirstChar { it.uppercase() }.let { if (it.endsWith('.')) it else "$it." }
}

// ---- pieces ----

@Composable
private fun Header(item: PlayItem, summary: String, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            if (item.subtitle.isNotEmpty()) Text(item.subtitle, fontSize = 13.sp, color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = LookeeColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(start = 24.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(item.source)
            summary.takeIf { it.isNotEmpty() }?.let { Chip(it) }
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
private fun SeekBar(ui: EngineState, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
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

private enum class Glyph { Subtitles, Audio, Next, Menu }

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
            Glyph.Menu -> listOf(0.22f, 0.5f, 0.78f).forEach { y ->
                drawLine(color, Offset(s * 0.12f, s * y), Offset(s * 0.88f, s * y), strokeWidth = s * 0.12f, cap = StrokeCap.Round)
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
internal fun Spinner(modifier: Modifier) {
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
