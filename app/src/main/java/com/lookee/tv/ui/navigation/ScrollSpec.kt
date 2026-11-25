package com.lookee.tv.ui.navigation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * How rows and lists scroll as focus moves, the usual TV way: every move scrolls, so the
 * focused card stays put at the screen margin (the left in a row, the top in a list) and
 * the content slides under it. Compose's own TV default parks the focused item at 30% of
 * the row, which pushed the first card past the margin; anchoring at the margin doesn't.
 * Near the end of a list nothing is left to scroll, and focus simply moves on.
 * The scroll is instant, not animated: a gliding row felt unlike the native TV launcher.
 */
@OptIn(ExperimentalFoundationApi::class)
object FollowFocusScroll : BringIntoViewSpec {
    @Suppress("DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> = snap()

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // The margin is the screen inset: 48 dp of a 960 dp row, so 5% of the container.
        val margin = containerSize * 0.05f
        return offset - margin
    }
}
