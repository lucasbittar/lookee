package com.lookee.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.lookee.tv.ui.theme.Inter
import com.lookee.tv.ui.theme.LookeeColors
import com.lookee.tv.ui.theme.LookeeShapes

// The component set from docs/DESIGN.md. Focus follows the phone page: an accent border,
// a soft accent glow and a slight scale, so the focused item reads from across the room.

/** The lookee mark: a rounded square with the yellow-to-orange gradient and a heavy "l". */
@Composable
fun LookeeMark(size: Dp = 36.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(LookeeShapes.Mark)
            .background(LookeeColors.MarkGradient),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "l",
            color = LookeeColors.AccentInk,
            fontFamily = Inter,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (size.value * 0.6f).sp,
            modifier = Modifier.offset(y = -(size * 0.03f)),
        )
    }
}

/** Mark plus the "lookee" wordmark. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, markSize: Dp = 32.dp) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LookeeMark(markSize)
        Text(
            text = "lookee",
            color = LookeeColors.Text,
            fontFamily = Inter,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (markSize.value * 0.72f).sp,
            letterSpacing = (-0.03).em,
        )
    }
}

/** A static panel: surface, 1 dp line border, 14 dp radius (the page's `.card`). */
@Composable
fun LookeeCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(LookeeShapes.Card)
            .background(LookeeColors.Surface)
            .border(1.dp, LookeeColors.Line, LookeeShapes.Card)
            .padding(padding),
        content = content,
    )
}

/** A card that takes D-pad focus: accent border, glow and scale when focused. */
@Composable
fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusedScale: Float = 1.04f,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(LookeeShapes.Card),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LookeeColors.Surface,
            contentColor = LookeeColors.Text,
            focusedContainerColor = LookeeColors.Surface2,
            focusedContentColor = LookeeColors.Text,
            pressedContainerColor = LookeeColors.Surface2,
            pressedContentColor = LookeeColors.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, LookeeColors.Line), shape = LookeeShapes.Card),
            focusedBorder = Border(BorderStroke(2.dp, LookeeColors.Accent), shape = LookeeShapes.Card),
            pressedBorder = Border(BorderStroke(2.dp, LookeeColors.Accent), shape = LookeeShapes.Card),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(LookeeColors.AccentSoft, 12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale, pressedScale = focusedScale * 0.985f),
        content = content,
    )
}

enum class ButtonStyle {
    /** Surface-2 at rest; accent fill when focused. */
    Primary,

    /** Transparent with a line border at rest; accent fill when focused. */
    Quiet,
}

/** Buttons: only the focused one is yellow, so the eye always lands on it. */
@Composable
fun LookeeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    enabled: Boolean = true,
) {
    val rest = if (style == ButtonStyle.Primary) LookeeColors.Surface2 else Color.Transparent
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(LookeeShapes.Button),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = rest,
            contentColor = LookeeColors.Text,
            focusedContainerColor = LookeeColors.Accent,
            focusedContentColor = LookeeColors.AccentInk,
            pressedContainerColor = LookeeColors.Accent,
            pressedContentColor = LookeeColors.AccentInk,
            disabledContainerColor = rest,
            disabledContentColor = LookeeColors.Muted,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (style == ButtonStyle.Quiet) {
                Border(BorderStroke(1.dp, LookeeColors.Line), shape = LookeeShapes.Button)
            } else {
                Border.None
            },
            focusedBorder = Border.None,
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(LookeeColors.AccentSoft, 10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

/** Small uppercase muted label above a group (the page's `.card h2`). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LookeeColors.Muted,
        modifier = modifier,
    )
}

enum class Tone { Ok, Muted, Accent, Err }

/** A pill badge: "Set", "Not set", "4K", "Disc"… */
@Composable
fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        Tone.Ok -> LookeeColors.OkSoft to LookeeColors.Ok
        Tone.Muted -> LookeeColors.Surface2 to LookeeColors.Muted
        Tone.Accent -> LookeeColors.AccentSoft to LookeeColors.Accent
        Tone.Err -> LookeeColors.ErrSoft to LookeeColors.Err
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = modifier
            .clip(LookeeShapes.Pill)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** An inline message box (the page's `.alert`). */
@Composable
fun Alert(text: String, tone: Tone = Tone.Err, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        Tone.Err -> LookeeColors.ErrSoft to LookeeColors.Err
        Tone.Ok -> LookeeColors.OkSoft to LookeeColors.Ok
        Tone.Accent -> LookeeColors.AccentSoft to LookeeColors.Accent
        Tone.Muted -> LookeeColors.Surface2 to LookeeColors.Muted
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = fg,
        modifier = modifier
            .clip(LookeeShapes.Button)
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** The PIN shown on the TV during phone setup: one tile per digit. */
@Composable
fun PinDisplay(pin: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        pin.forEach { digit ->
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .clip(LookeeShapes.Button)
                    .background(LookeeColors.Surface2)
                    .border(1.dp, LookeeColors.Line, LookeeShapes.Button)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = digit.toString(),
                    color = LookeeColors.Accent,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A green check in a soft circle (the page's "saved" state). */
@Composable
fun SuccessCheck(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(LookeeShapes.Pill)
            .background(LookeeColors.OkSoft),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", color = LookeeColors.Ok, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold)
    }
}

/** A calm, centred message for a screen with nothing to show yet. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LookeeMark(56.dp)
        Text(title, style = MaterialTheme.typography.headlineMedium, color = LookeeColors.Text, modifier = Modifier.padding(top = 8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = LookeeColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(520.dp),
        )
        if (action != null) {
            Box(Modifier.padding(top = 12.dp)) { action() }
        }
    }
}
