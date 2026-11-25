package com.lookee.tv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.lookee.tv.R

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semi_bold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extra_bold, FontWeight.ExtraBold),
)

private fun style(size: Int, weight: FontWeight, tracking: Double = 0.0, lineHeight: Double = 1.3) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = tracking.em,
)

/**
 * 10-foot type scale (docs/DESIGN.md): the Mi Box renders 960×540 dp, so body text is
 * 16–18 sp, card titles 20 sp, screen titles 32–40 sp and the hero 48 sp; nothing under 13 sp.
 * Headings use negative tracking like the phone page.
 */
val LookeeTypography = Typography(
    displayLarge = style(48, FontWeight.ExtraBold, -0.02, 1.1),
    displayMedium = style(40, FontWeight.Bold, -0.02, 1.15),
    displaySmall = style(32, FontWeight.Bold, -0.02, 1.2),
    headlineLarge = style(28, FontWeight.Bold, -0.02, 1.2),
    headlineMedium = style(24, FontWeight.Bold, -0.01, 1.25),
    headlineSmall = style(20, FontWeight.SemiBold, -0.01),
    titleLarge = style(20, FontWeight.SemiBold, -0.01),
    titleMedium = style(18, FontWeight.SemiBold),
    titleSmall = style(16, FontWeight.SemiBold),
    bodyLarge = style(18, FontWeight.Normal, lineHeight = 1.5),
    bodyMedium = style(16, FontWeight.Normal, lineHeight = 1.5),
    bodySmall = style(14, FontWeight.Normal, lineHeight = 1.45),
    labelLarge = style(16, FontWeight.Bold),
    labelMedium = style(14, FontWeight.SemiBold),
    // Section labels: small uppercase with wide tracking (the page's `.card h2`).
    labelSmall = style(13, FontWeight.SemiBold, 0.08),
)

// Category faces (docs/DESIGN.md): Movies' title in Fraunces, artist names in Bebas Neue,
// Tutorials' numbers and Discover's stats in JetBrains Mono. Fraunces and JetBrains Mono are
// variable fonts; Android 8+ picks the weight from the variation settings.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Fraunces = FontFamily(
    Font(
        R.font.fraunces_variable,
        weight = FontWeight.Bold,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
            androidx.compose.ui.text.font.FontVariation.weight(700),
        ),
    ),
)

val BebasNeue = FontFamily(Font(R.font.bebas_neue_regular))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val JetBrainsMono = FontFamily(
    Font(
        R.font.jetbrains_mono_variable,
        weight = FontWeight.Medium,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
            androidx.compose.ui.text.font.FontVariation.weight(500),
        ),
    ),
    Font(
        R.font.jetbrains_mono_variable,
        weight = FontWeight.Bold,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
            androidx.compose.ui.text.font.FontVariation.weight(700),
        ),
    ),
)
