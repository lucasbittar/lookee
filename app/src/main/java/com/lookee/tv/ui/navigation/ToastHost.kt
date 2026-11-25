package com.lookee.tv.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lookee.tv.ui.theme.LookeeColors
import kotlinx.coroutines.delay

/** Bottom-centre toast: slides up, stays [holdMs], slides away. A new toast replaces the current one. */
@Composable
fun ToastHost(navigator: Navigator, modifier: Modifier = Modifier, holdMs: Long = 3_500) {
    val toast = navigator.message
    LaunchedEffect(toast?.id) {
        if (toast != null) {
            delay(holdMs)
            navigator.clearMessage()
        }
    }
    AnimatedContent(
        targetState = toast,
        contentKey = { it?.id },
        transitionSpec = {
            (fadeIn(tween(200)) + slideInVertically(tween(240)) { it }) togetherWith
                (fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 })
        },
        contentAlignment = Alignment.BottomCenter,
        label = "toast",
        modifier = modifier,
    ) { current ->
        if (current != null) ToastPill(current)
    }
}

@Composable
private fun ToastPill(toast: Toast) {
    Row(
        Modifier
            .padding(bottom = 36.dp)
            .widthIn(max = 720.dp)
            .clip(RoundedCornerShape(50))
            .background(LookeeColors.Surface2)
            .border(1.dp, LookeeColors.Line, RoundedCornerShape(50))
            .padding(start = 16.dp, end = 22.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (toast.kind != ToastKind.Info) ToastIcon(toast.kind)
        Text(
            toast.text,
            style = MaterialTheme.typography.bodyMedium,
            color = LookeeColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToastIcon(kind: ToastKind) {
    when (kind) {
        // A breathing accent dot: something is under way.
        ToastKind.Working -> {
            val pulse by rememberInfiniteTransition(label = "pulse")
                .animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(18.dp).alpha(pulse * 0.35f).clip(CircleShape).background(LookeeColors.Accent))
                Box(Modifier.size(8.dp).clip(CircleShape).background(LookeeColors.Accent))
            }
        }
        // An accent disc with a dark check, like the phone page's success mark.
        ToastKind.Done -> Canvas(Modifier.size(18.dp)) {
            drawCircle(LookeeColors.Accent)
            val check = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.67f)
                lineTo(size.width * 0.73f, size.height * 0.36f)
            }
            drawPath(check, LookeeColors.AccentInk, style = Stroke(width = size.width * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        ToastKind.Problem -> Canvas(Modifier.size(18.dp)) {
            drawCircle(LookeeColors.Accent2)
            drawLine(LookeeColors.AccentInk, center.copy(y = size.height * 0.27f), center.copy(y = size.height * 0.56f), strokeWidth = size.width * 0.13f, cap = StrokeCap.Round)
            drawCircle(LookeeColors.AccentInk, radius = size.width * 0.075f, center = center.copy(y = size.height * 0.73f))
        }
        ToastKind.Info -> Unit
    }
}
