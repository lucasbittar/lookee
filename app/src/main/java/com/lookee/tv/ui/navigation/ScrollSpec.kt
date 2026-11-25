package com.lookee.tv.ui.navigation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * Compose 1.7's TV default scrolls a focused item to ~30% of its row, which pushes the first
 * card past the screen margin. lookee scrolls only as far as needed to show the focused
 * item whole, so rows rest at their margin and move one card at a time.
 */
@OptIn(ExperimentalFoundationApi::class)
object MinimalScroll : BringIntoViewSpec {
    @Suppress("DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 220)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Keep the focused item (and its glow) off the screen edge: ~48 dp on a 960 dp row.
        val margin = (containerSize * 0.05f).coerceAtMost((containerSize - size).coerceAtLeast(0f) / 2f)
        val end = offset + size
        return when {
            offset >= margin && end <= containerSize - margin -> 0f // already comfortably visible
            size > containerSize - 2 * margin || offset < margin -> offset - margin // align its start, with margin
            else -> end - (containerSize - margin) // align its end, with margin
        }
    }
}
