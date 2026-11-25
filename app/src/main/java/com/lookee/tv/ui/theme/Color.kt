package com.lookee.tv.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Design tokens from docs/DESIGN.md (the phone setup page's palette). Screens never hard-code colors. */
object LookeeColors {
    val Bg = Color(0xFF0D0F12)
    val Surface = Color(0xFF15181D)
    val Surface2 = Color(0xFF1C2027)
    val Line = Color(0xFF262B33)
    val Text = Color(0xFFEEF0F3)
    val Muted = Color(0xFF8B93A1)
    val Accent = Color(0xFFF2B544)
    val AccentInk = Color(0xFF1A1404)
    val Accent2 = Color(0xFFE0752E)
    val Ok = Color(0xFF4CC38A)
    val Err = Color(0xFFF06A6A)

    /** Soft fills behind badges, alerts and focus glows (12–18 % alpha). */
    val OkSoft = Ok.copy(alpha = 0.12f)
    val ErrSoft = Err.copy(alpha = 0.12f)
    val AccentSoft = Accent.copy(alpha = 0.18f)

    /** The mark's gradient, 135° from accent to accent-2. */
    val MarkGradient = Brush.linearGradient(listOf(Accent, Accent2))
}
