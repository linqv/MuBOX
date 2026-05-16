package com.example.comicdav.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ComicDavLightColors = lightColorScheme(
    primary = Color(0xFF2F5D50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE9DE),
    onPrimaryContainer = Color(0xFF0B2119),
    secondary = Color(0xFF6B5E2E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E5B3),
    onSecondaryContainer = Color(0xFF231B00),
    tertiary = Color(0xFF6B4E71),
    tertiaryContainer = Color(0xFFF4D8FA),
    background = Color(0xFFFBFCF8),
    surface = Color(0xFFFBFCF8),
    surfaceContainer = Color(0xFFF0F2EC),
    outlineVariant = Color(0xFFC6CBC1),
)

@Composable
fun ComicDavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ComicDavLightColors,
        content = content,
    )
}
