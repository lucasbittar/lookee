package com.lookee.tv.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.lookee.tv.ui.player.engine.Choice
import com.lookee.tv.ui.player.engine.SubtitleSize
import com.lookee.tv.ui.theme.LookeeColors

internal val Soft = Color(0xFFC9CED6)

/**
 * The side panel from the "Subtitle panel" mockup: pick a track (or Off) and, for subtitles,
 * the size. Focus stays inside until Back. Online search and sync arrive in the next step.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TrackPanel(
    title: String,
    choices: List<Choice>,
    offOption: Boolean,
    offSelected: Boolean,
    onPick: (String?) -> Unit,
    size: SubtitleSize?,
    onSize: (SubtitleSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
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
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em, color = LookeeColors.Text)
        Heading("Track")
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (offOption) {
                item {
                    Option("Off", "", offSelected, true, Modifier.focusRequester(first)) { onPick(null) }
                }
            }
            items(choices, key = { it.id }) { c ->
                val m = if (!offOption && c == choices.first()) Modifier.focusRequester(first) else Modifier
                Option(c.label, if (c.supported) c.caption else "${c.caption} · can't play here".trimStart(' ', '·'), c.selected, c.supported, m) { if (c.supported) onPick(c.id) }
            }
            if (choices.isEmpty() && !offOption) item { Text("Only one track in this file.", fontSize = 13.sp, color = LookeeColors.Muted) }
        }
        if (size != null) {
            Heading("Size")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubtitleSize.entries.forEach { s -> SizeButton(s.label, s == size, Modifier.weight(1f)) { onSize(s) } }
            }
        }
    }
}

@Composable
internal fun Heading(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em, color = LookeeColors.Muted)
}

@Composable
internal fun Option(label: String, caption: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) LookeeColors.Surface2 else Color.Transparent,
            focusedContainerColor = LookeeColors.Surface2,
            pressedContainerColor = LookeeColors.Surface2,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) LookeeColors.Line else Color.Transparent), shape = RoundedCornerShape(10.dp)),
            focusedBorder = Border(BorderStroke(2.dp, LookeeColors.Accent), shape = RoundedCornerShape(10.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { if (selected) Check(LookeeColors.Accent) }
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (!enabled) LookeeColors.Muted else if (selected || focused) LookeeColors.Text else Soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (caption.isNotEmpty()) Text(caption, fontSize = 11.sp, color = LookeeColors.Muted, maxLines = 1)
        }
    }
}

@Composable
internal fun SizeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) LookeeColors.Text else Color.Transparent,
            contentColor = if (selected) LookeeColors.Bg else Soft,
            focusedContainerColor = LookeeColors.Accent,
            focusedContentColor = LookeeColors.AccentInk,
        ),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, if (selected) Color.Transparent else LookeeColors.Line), shape = RoundedCornerShape(9.dp))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
internal fun Check(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val p = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.52f)
            lineTo(size.width * 0.4f, size.height * 0.78f)
            lineTo(size.width * 0.9f, size.height * 0.22f)
        }
        drawPath(p, color, style = Stroke(size.width * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
