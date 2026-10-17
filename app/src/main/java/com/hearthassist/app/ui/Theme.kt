package com.hearthassist.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    primary = HaColors.Gold,
    onPrimary = Color.Black,
    secondary = HaColors.Mana,
    background = HaColors.Background,
    onBackground = HaColors.Text,
    surface = HaColors.Surface,
    onSurface = HaColors.Text,
    surfaceVariant = HaColors.SurfaceHigh,
    onSurfaceVariant = HaColors.TextDim,
)

@Composable
fun HaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
