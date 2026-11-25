package com.lookee.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Each category's own look (docs/DESIGN.md): a tint used only for the ambient glow and
 * labels, plus a one-word kicker. Focus stays yellow everywhere.
 */
enum class CategoryLook(val tint: Color, val kicker: String) {
    Movies(LookeeColors.Accent, "Now showing"),
    Series(Color(0xFF6E9CF5), "Up next"),
    Concerts(Color(0xFFE8608C), "Live"),
    Tutorials(Color(0xFF4FC3D9), "Workshop"),
    Discover(Color(0xFF9B7BF0), "Stream"),
}

/** The soft corner glow behind a category screen. [corner] is in px from the top-left. */
@Composable
fun CategoryGlow(look: CategoryLook, corner: Offset, radius: Float = 1300f) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(look.tint.copy(alpha = 0.14f), look.tint.copy(alpha = 0.04f), Color.Transparent),
                    center = corner,
                    radius = radius,
                ),
            ),
    )
}
