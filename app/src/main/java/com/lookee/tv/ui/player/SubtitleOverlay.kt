package com.lookee.tv.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import com.lookee.tv.R
import com.lookee.tv.data.Cue
import com.lookee.tv.ui.player.engine.SubtitleSize
import kotlinx.coroutines.delay

private val SubtitleFont = FontFamily(Font(R.font.inter_semi_bold))

/**
 * Draws an external subtitle (a file next to the video, or one from OpenSubtitles) over the
 * video, under either engine. The core parses the file into [cues] once; this reads the
 * engine's clock 25 times a second and shows whatever is due at that moment, moved by
 * [offsetMs] (positive: later). Loading a new file or changing the sync needs no reload.
 * Styled like ExoPlayer's own subtitles: white Inter with a drop shadow, sized as a fraction
 * of the screen height, lifted above the controls while they show.
 */
@Composable
internal fun SubtitleOverlay(
    cues: List<Cue>,
    offsetMs: Long,
    position: () -> Long,
    size: SubtitleSize,
    lifted: Boolean,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    val clock by rememberUpdatedState(position)
    LaunchedEffect(cues, offsetMs) {
        while (true) {
            val now = cueTextAt(cues, clock() - offsetMs)
            if (now != text) text = now
            delay(40)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val bottom by animateDpAsState(if (lifted) maxHeight * 0.32f else maxHeight * 0.075f, tween(220), label = "subtitle lift")
        if (text.isNotEmpty()) {
            val fontSize = with(LocalDensity.current) { (maxHeight * size.fraction).toSp() }
            Text(
                text,
                style = TextStyle(
                    fontFamily = SubtitleFont,
                    fontSize = fontSize,
                    lineHeight = 1.25.em,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(Color.Black, Offset(0f, 3f), 10f),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottom)
                    .widthIn(max = maxWidth * 0.82f)
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

/** The text of every cue showing at [ms], in order; "" when none. Cues are sorted by start. */
internal fun cueTextAt(cues: List<Cue>, ms: Long): String {
    if (cues.isEmpty()) return ""
    // The last cue starting at or before ms, then back over any still showing with it.
    var lo = 0
    var hi = cues.size - 1
    var last = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (cues[mid].s <= ms) {
            last = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (last < 0) return ""
    val on = ArrayList<String>(2)
    var i = last
    while (i >= 0 && last - i < 8) {
        val c = cues[i]
        if (ms < c.e) on.add(c.t)
        i--
    }
    return on.asReversed().joinToString("\n")
}
