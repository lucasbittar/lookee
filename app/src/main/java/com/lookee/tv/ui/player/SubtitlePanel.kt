package com.lookee.tv.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.lookee.tv.data.SubtitleResult
import com.lookee.tv.ui.player.engine.Choice
import com.lookee.tv.ui.player.engine.SubtitleSize
import com.lookee.tv.ui.theme.LookeeColors
import java.util.Locale
import kotlin.math.abs

/** A subtitle file the screen draws itself: next to the video, or from OpenSubtitles. */
internal data class ExternalSub(
    val id: String,
    val label: String,
    val caption: String,
    val path: String,
)

/** Choice ids for external subtitles carry this prefix; the others are the engine's. */
internal const val EXTERNAL = "ext:"

/** The sync control: the offset, or why it can't apply to the track that's on. */
internal data class SyncState(val offsetMs: Long, val available: Boolean, val note: String)

/**
 * The subtitles side panel from the "Subtitle panel" mockup: the tracks (in the file, next to
 * it, downloaded), a row that opens the OpenSubtitles search, the sync offset (◀ ▶, 0.1 s steps,
 * ±5 s) and the size. Focus stays inside until Back.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SubtitlePanel(
    choices: List<Choice>,
    offSelected: Boolean,
    onPick: (String?) -> Unit,
    onSearch: () -> Unit,
    sync: SyncState?,
    onSync: (Long) -> Unit,
    size: SubtitleSize?,
    onSize: (SubtitleSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    PanelColumn(modifier) {
        Text("Subtitles", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em, color = LookeeColors.Text)
        Heading("Track")
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Focus starts on the track that's on.
            val on = choices.firstOrNull { it.selected }
            item { Option("Off", "", offSelected, true, if (on == null) Modifier.focusRequester(first) else Modifier) { onPick(null) } }
            items(choices, key = { it.id }) { c ->
                val m = if (c == on) Modifier.focusRequester(first) else Modifier
                Option(c.label, if (c.supported) c.caption else "${c.caption} · can't play here".trimStart(' ', '·'), c.selected, c.supported, m) { if (c.supported) onPick(c.id) }
            }
            item { SearchRow(onSearch) }
        }
        if (sync != null) {
            Heading("Sync")
            SyncStepper(sync, onSync)
        }
        if (size != null) {
            Heading("Size")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubtitleSize.entries.forEach { s -> SizeButton(s.label, s == size, Modifier.weight(1f)) { onSize(s) } }
            }
        }
    }
}

/** What the search view shows. */
internal sealed interface SearchState {
    data object Loading : SearchState
    data class Failed(val message: String, val canRetry: Boolean) : SearchState
    data class Results(val items: List<SubtitleResult>) : SearchState
}

/**
 * The OpenSubtitles results, in the panel's place: language, release, and whether the file
 * was synced to this exact video. Picking one downloads it and turns it on (the panel goes
 * back to the track list); Back returns without picking.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SubtitleSearchPanel(
    lookingFor: String,
    state: SearchState,
    downloading: Long?,
    onPick: (SubtitleResult) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(state) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    PanelColumn(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("OpenSubtitles", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em, color = LookeeColors.Text)
            Text(lookingFor, fontSize = 13.sp, color = LookeeColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (state) {
            SearchState.Loading -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spinner(Modifier.size(22.dp))
                Text("Searching in your languages…", fontSize = 14.sp, color = Soft)
                // Something must hold focus while the panel waits, or Back has nowhere to go.
                Box(Modifier.size(1.dp).focusRequester(first).focusableBox())
            }
            is SearchState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(state.message, fontSize = 14.sp, lineHeight = 20.sp, color = Soft)
                if (state.canRetry) {
                    Option("Try again", "", false, true, Modifier.focusRequester(first), onRetry)
                } else {
                    Box(Modifier.size(1.dp).focusRequester(first).focusableBox())
                }
            }
            is SearchState.Results -> {
                Heading("${state.items.size} found")
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.items, key = { it.fileId }) { r ->
                        val m = if (r == state.items.first()) Modifier.focusRequester(first) else Modifier
                        ResultRow(r, downloading == r.fileId, downloading != null, m) { onPick(r) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PanelColumn(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxHeight()
            .width(340.dp)
            .background(LookeeColors.Surface.copy(alpha = 0.97f))
            .border(BorderStroke(1.dp, LookeeColors.Line))
            .padding(horizontal = 28.dp, vertical = 32.dp)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

private fun Modifier.focusableBox() = focusable()

/** The dashed "Search OpenSubtitles" row closing the track list (mockup). */
@Composable
private fun SearchRow(onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = LookeeColors.Surface2,
            pressedContainerColor = LookeeColors.Surface2,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, LookeeColors.Accent), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(Modifier.fillMaxWidth()) {
            // The dashed outline at rest; the focus border draws over it.
            Canvas(Modifier.matchParentSize()) {
                drawRoundRect(
                    LookeeColors.Line,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { Magnifier(LookeeColors.Accent) }
                Text("Search OpenSubtitles", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LookeeColors.Accent)
            }
        }
    }
}

/** ◀ value ▶. Left and right move it while focused; it keeps focus (the panel doesn't scroll). */
@Composable
private fun SyncStepper(sync: SyncState, onSync: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = {},
            enabled = sync.available,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    if (!sync.available) return@onKeyEvent false
                    val step = when (e.key) {
                        Key.DirectionLeft -> -SYNC_STEP_MS
                        Key.DirectionRight -> SYNC_STEP_MS
                        else -> return@onKeyEvent false
                    }
                    if (e.type == KeyEventType.KeyDown) onSync((sync.offsetMs + step).coerceIn(-SYNC_MAX_MS, SYNC_MAX_MS))
                    true
                },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = LookeeColors.Surface2,
                focusedContainerColor = LookeeColors.Surface2,
                disabledContainerColor = LookeeColors.Surface2,
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, LookeeColors.Line), shape = shape),
                focusedBorder = Border(BorderStroke(2.dp, LookeeColors.Accent), shape = shape),
            ),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(LookeeColors.AccentSoft, 8.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Row(Modifier.padding(7.dp).alpha(if (sync.available) 1f else 0.45f), verticalAlignment = Alignment.CenterVertically) {
                StepKey(left = true)
                Text(
                    offsetLabel(sync.offsetMs),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (focused) LookeeColors.Accent else LookeeColors.Text,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                StepKey(left = false)
            }
        }
        Text(sync.note, fontSize = 11.sp, lineHeight = 15.sp, color = LookeeColors.Muted)
    }
}

@Composable
private fun StepKey(left: Boolean) {
    Box(Modifier.size(32.dp).background(LookeeColors.Line, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(14.dp)) {
            val w = size.width
            val x0 = if (left) w * 0.65f else w * 0.35f
            val x1 = if (left) w * 0.35f else w * 0.65f
            val stroke = w * 0.14f
            drawLine(LookeeColors.Text, androidx.compose.ui.geometry.Offset(x0, w * 0.2f), androidx.compose.ui.geometry.Offset(x1, w * 0.5f), stroke, StrokeCap.Round)
            drawLine(LookeeColors.Text, androidx.compose.ui.geometry.Offset(x1, w * 0.5f), androidx.compose.ui.geometry.Offset(x0, w * 0.8f), stroke, StrokeCap.Round)
        }
    }
}

/** "+0.4 s", "−1.2 s", "0.0 s" (a real minus sign). */
internal fun offsetLabel(ms: Long): String {
    val sign = when {
        ms > 0 -> "+"
        ms < 0 -> "−"
        else -> ""
    }
    return sign + String.format(Locale.US, "%.1f s", abs(ms) / 1000.0)
}

/** One search result: language and badges, then the release it was made for. */
@Composable
private fun ResultRow(r: SubtitleResult, downloading: Boolean, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = { if (!busy) onClick() },
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = LookeeColors.Surface2,
            pressedContainerColor = LookeeColors.Surface2,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, LookeeColors.Accent), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    languageName(r.language.ifEmpty { "und" }),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (focused) LookeeColors.Text else Soft,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (r.hashMatch) Badge("Fits this file", LookeeColors.Ok)
                if (r.hearingImpaired) Badge("HI", LookeeColors.Muted)
                Box(Modifier.weight(1f))
                if (downloading) Spinner(Modifier.size(16.dp))
                else if (r.downloads > 0) Text(downloadsLabel(r.downloads), fontSize = 11.sp, color = LookeeColors.Muted, maxLines = 1)
            }
            Text(
                r.release.ifEmpty { r.fileName },
                fontSize = 12.sp,
                color = LookeeColors.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** "870 downloads", "12k downloads". */
internal fun downloadsLabel(n: Int): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}M downloads"
    n >= 10_000 -> "${n / 1000}k downloads"
    n >= 1_000 -> String.format(Locale.US, "%.1fk downloads", n / 1000.0)
    else -> "$n downloads"
}

@Composable
private fun Magnifier(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        drawCircle(color, radius = w * 0.3f, center = androidx.compose.ui.geometry.Offset(w * 0.44f, w * 0.44f), style = Stroke(w * 0.13f))
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.66f, w * 0.66f), androidx.compose.ui.geometry.Offset(w * 0.92f, w * 0.92f), w * 0.13f, StrokeCap.Round)
    }
}

internal const val SYNC_STEP_MS = 100L
internal const val SYNC_MAX_MS = 5_000L
