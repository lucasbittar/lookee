package com.lookee.tv.ui.splash

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lookee.tv.R
import com.lookee.tv.data.BootState
import com.lookee.tv.data.BootStep
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import com.lookee.tv.ui.theme.Inter
import com.lookee.tv.ui.theme.JetBrainsMono
import com.lookee.tv.ui.theme.LookeeColors
import kotlinx.coroutines.delay
import kotlin.math.exp

private val MarkSize = 96.dp
private val BarWidth = 360.dp
private val Lift = 64.dp
private val Steps = BootStep.Done.ordinal

/**
 * The boot screen. The mark sits exactly where the launch window drew it (centred, 96 dp), so
 * the window-to-Compose hand-off doesn't jump; the name, bar and step text then fade in below.
 * The bar fills one segment per real step and creeps inside a step, so it never looks stuck.
 */
@Composable
fun BootSplash(state: BootState, onFinished: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)

    // Everything but the mark fades in, so a fast boot is just the mark and a quick fill.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(420, delayMillis = 120, easing = FastOutSlowInEasing)) }

    // Inside a step: ease toward 85 % of its segment (time constant 1.2 s), then wait for the real work.
    var creep by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.step) {
        creep = 0f
        if (state.done) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { now -> creep = 0.85f * (1f - exp(-(now - start) / 1200f)) }
        }
    }
    val target = if (state.done) 1f else (state.step.ordinal + creep) / Steps
    val progress by animateFloatAsState(target, tween(280), label = "boot")

    LaunchedEffect(state.done) {
        if (state.done) {
            delay(380) // let the bar reach the end before the splash fades
            finished()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(LookeeColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        // A soft accent glow behind the mark.
        Box(
            Modifier
                .offset(y = -Lift * reveal.value)
                .size(420.dp)
                .alpha(reveal.value * 0.9f)
                .background(
                    Brush.radialGradient(listOf(LookeeColors.Accent.copy(alpha = 0.10f), LookeeColors.Bg.copy(alpha = 0f))),
                ),
        )
        // Starts where the window drew it, then rises to make room, clear of TV overscan.
        val lift = Lift * reveal.value
        Image(
            painterResource(R.drawable.splash_mark),
            contentDescription = null,
            modifier = Modifier
                .offset(y = -lift)
                .size(MarkSize),
        )

        Column(
            Modifier
                .offset(y = MarkSize / 2 + 100.dp - lift)
                .alpha(reveal.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "lookee",
                color = LookeeColors.Text,
                fontFamily = Inter,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 40.sp,
                letterSpacing = (-0.03).em,
            )
            Spacer(Modifier.height(36.dp))
            ProgressBar(progress)
            Spacer(Modifier.height(18.dp))
            StepText(state)
        }

        // The installed version, quietly at the foot of the screen (inside TV overscan).
        val context = LocalContext.current
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }
        if (!version.isNullOrBlank()) {
            Text(
                "v$version",
                color = LookeeColors.Muted,
                fontFamily = JetBrainsMono,
                fontSize = 14.sp,
                letterSpacing = 0.04.em,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .alpha(reveal.value * 0.8f),
            )
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        Modifier
            .width(BarWidth)
            .height(4.dp)
            .clip(CircleShape)
            .background(LookeeColors.Line),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(LookeeColors.MarkGradient),
        )
    }
}

@Composable
private fun StepText(state: BootState) {
    Column(Modifier.width(BarWidth + 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = state.error?.let { "Something went wrong starting up" } ?: state.step.label,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 }) togetherWith
                    (fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 2 })
            },
            label = "step",
        ) { label ->
            Text(label, style = MaterialTheme.typography.titleMedium, color = LookeeColors.Text)
        }
        Spacer(Modifier.height(6.dp))
        val detail = state.error ?: state.detail
        val stepNo = if (state.done) Steps else state.step.ordinal + 1
        Text(
            detail ?: "Step $stepNo of $Steps",
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.error != null) LookeeColors.Accent2 else LookeeColors.Muted,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
