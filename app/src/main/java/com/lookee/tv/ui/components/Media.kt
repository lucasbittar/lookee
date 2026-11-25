package com.lookee.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.lookee.tv.data.VideoFrames
import com.lookee.tv.ui.theme.LookeeColors

// ---- text ----

/**
 * One line, always (docs/DESIGN.md): an ellipsis at rest, and a slow marquee while [active]
 * (the card is focused) if the text doesn't fit.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = if (active) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = if (active) {
            modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200, repeatDelayMillis = 1500, velocity = 30.dp)
        } else {
            modifier
        },
    )
}

/** A big title kept on one line: shrinks step by step down to [minSize], then scrolls. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minSize: TextUnit = 36.sp,
) {
    var size by remember(text) { mutableStateOf(style.fontSize) }
    var scroll by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = style.copy(fontSize = size, lineHeight = size * 1.1f),
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = if (scroll) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = if (scroll) {
            modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 2000, velocity = 30.dp)
        } else {
            modifier
        },
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow) {
                if (size > minSize) size = maxOf(minSize.value, size.value - 4f).sp else scroll = true
            }
        },
    )
}

// ---- images ----

/** A remote image (TMDB) with a calm placeholder; blank urls show only the placeholder. */
@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    if (url.isBlank()) {
        Box(modifier.background(LookeeColors.Surface2))
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = contentScale,
            placeholder = ColorPainter(LookeeColors.Surface2),
            error = ColorPainter(LookeeColors.Surface2),
            modifier = modifier,
        )
    }
}

/**
 * TMDB artwork when there is some, otherwise a frame grabbed from [videoPath] (lessons,
 * personal videos, unmatched titles), otherwise the plain placeholder. Every card has a picture.
 */
@Composable
fun PreviewImage(url: String, videoPath: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    if (url.isNotBlank() || videoPath.isBlank()) {
        RemoteImage(url, modifier, contentScale)
        return
    }
    val context = LocalContext.current
    val frame by produceState<java.io.File?>(null, videoPath) { value = VideoFrames.frame(context, videoPath) }
    val file = frame
    if (file == null) {
        Box(modifier.background(LookeeColors.Surface2))
    } else {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = contentScale,
            placeholder = ColorPainter(LookeeColors.Surface2),
            modifier = modifier,
        )
    }
}

// ---- cards ----

/** The focusable frame every card uses: line border at rest; yellow border, glow and scale on focus. */
@Composable
fun MediaSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.06f,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LookeeColors.Surface2,
            focusedContainerColor = LookeeColors.Surface2,
            pressedContainerColor = LookeeColors.Surface2,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, LookeeColors.Line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, LookeeColors.Accent), shape = shape),
            pressedBorder = Border(BorderStroke(2.dp, LookeeColors.Accent), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(LookeeColors.AccentSoft, 10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        content = content,
    )
}

/** A 2:3 poster with a one-line caption under it. */
@Composable
fun PosterCard(
    title: String,
    caption: String,
    posterUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 110.dp,
    onFocused: () -> Unit = {},
    videoPath: String = "",
    badge: (@Composable BoxScope.() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaSurface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(width * 1.5f)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
            shape = RoundedCornerShape(11.dp),
            focusedScale = 1.08f,
        ) {
            PreviewImage(posterUrl, videoPath, Modifier.fillMaxSize())
            if (posterUrl.isBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = LookeeColors.Muted,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    maxLines = 3,
                )
            }
            badge?.invoke(this)
        }
        Column(Modifier.padding(top = if (focused) 6.dp else 0.dp)) {
            MarqueeText(title, MaterialTheme.typography.labelMedium, LookeeColors.Text, active = focused)
            if (caption.isNotEmpty()) {
                MarqueeText(caption, MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), LookeeColors.Muted, active = focused)
            }
        }
    }
}

/** A 16:9 still with an optional progress line and a two-line caption. */
@Composable
fun StillCard(
    title: String,
    caption: String,
    imageUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 224.dp,
    progress: Float? = null,
    onFocused: () -> Unit = {},
    videoPath: String = "",
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaSurface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(width * 9f / 16f)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
        ) {
            PreviewImage(imageUrl, videoPath, Modifier.fillMaxSize())
            if (progress != null) {
                ProgressLine(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth())
            }
        }
        Column(Modifier.padding(top = if (focused) 6.dp else 0.dp)) {
            MarqueeText(title, MaterialTheme.typography.titleSmall, LookeeColors.Text, active = focused)
            if (caption.isNotEmpty()) MarqueeText(caption, MaterialTheme.typography.bodySmall, LookeeColors.Muted, active = focused)
        }
    }
}

/** A thin progress bar: yellow played part over a faint track. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp, color: Color = LookeeColors.Accent) {
    Box(modifier.height(height).background(LookeeColors.Text.copy(alpha = 0.18f))) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .background(color),
        )
    }
}

/** A small rounded label over an image ("Stream", "On your drive", "Blu-ray"). */
@Composable
fun ImageBadge(text: String, background: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontSize = 11.sp),
        color = content,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** A row heading (small uppercase muted) with spacing that clears focused cards above it. */
@Composable
fun RowTitle(text: String, modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    SectionLabel(text, modifier.padding(start = inset, bottom = 12.dp))
}

/** Screen margin for rows: inside the row, so cards scroll edge to edge without clipping. */
val ScreenInset = 48.dp
val RowPadding = PaddingValues(horizontal = 48.dp)

/** A screen header: the category kicker in its tint over a big title. */
@Composable
fun ScreenHeader(kicker: String, title: String, tint: Color, titleStyle: TextStyle, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            if (kicker.isNotEmpty()) {
                Text(
                    kicker.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.24.em),
                    color = tint,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(title, style = titleStyle, color = LookeeColors.Text)
        }
        trailing()
    }
}

// ---- formatting (presentation only) ----

fun timeLeft(positionMs: Long, durationMs: Long): String {
    val seconds = ((durationMs - positionMs).coerceAtLeast(0) / 1000).toInt()
    val left = seconds / 60
    return when {
        left >= 60 -> "${left / 60}h ${left % 60}m left"
        left > 0 -> "${left}m left"
        seconds > 0 -> "${seconds}s left"
        else -> "Finished"
    }
}

fun runtimeLabel(minutes: Int): String = when {
    minutes <= 0 -> ""
    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes}m"
}

fun clock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun progressOf(positionMs: Long, durationMs: Long): Float? =
    if (durationMs > 0 && positionMs > 0) positionMs.toFloat() / durationMs else null

fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%d MB".format(n / (1L shl 20))
    n > 0 -> "%d KB".format(n / 1024)
    else -> ""
}

/** A square spacer, for readability in layouts. */
@Composable
fun Gap(size: Dp) = Box(Modifier.size(size))
