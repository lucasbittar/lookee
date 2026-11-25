package com.lookee.tv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.darkColorScheme

/** Corner radii from docs/DESIGN.md. */
object LookeeShapes {
    val Card = RoundedCornerShape(14.dp)
    val Button = RoundedCornerShape(12.dp)
    val Input = RoundedCornerShape(10.dp)
    val Mark = RoundedCornerShape(28)
    val Pill = RoundedCornerShape(50)
}

private val colorScheme = darkColorScheme(
    primary = LookeeColors.Accent,
    onPrimary = LookeeColors.AccentInk,
    secondary = LookeeColors.Accent2,
    onSecondary = LookeeColors.AccentInk,
    background = LookeeColors.Bg,
    onBackground = LookeeColors.Text,
    surface = LookeeColors.Surface,
    onSurface = LookeeColors.Text,
    surfaceVariant = LookeeColors.Surface2,
    onSurfaceVariant = LookeeColors.Muted,
    border = LookeeColors.Line,
    borderVariant = LookeeColors.Line,
    error = LookeeColors.Err,
    onError = LookeeColors.Bg,
)

@Composable
fun LookeeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = LookeeTypography,
        shapes = Shapes(
            extraSmall = LookeeShapes.Input,
            small = LookeeShapes.Input,
            medium = LookeeShapes.Button,
            large = LookeeShapes.Card,
            extraLarge = LookeeShapes.Card,
        ),
        content = content,
    )
}
