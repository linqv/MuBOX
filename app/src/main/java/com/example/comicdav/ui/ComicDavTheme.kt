package com.example.comicdav.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ComicDavLightColors = lightColorScheme(
    primary = Color(0xFF1F5B49),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBE2),
    onPrimaryContainer = Color(0xFF0B2118),
    secondary = Color(0xFF6D5419),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E5C5),
    onSecondaryContainer = Color(0xFF231B00),
    tertiary = Color(0xFF5A4765),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8DDF0),
    onTertiaryContainer = Color(0xFF21132C),
    background = Color(0xFFF7F7F1),
    onBackground = Color(0xFF151916),
    surface = Color(0xFFFFFEFA),
    onSurface = Color(0xFF151916),
    surfaceVariant = Color(0xFFE7ECE6),
    onSurfaceVariant = Color(0xFF58645F),
    surfaceContainer = Color(0xFFEFF2EC),
    outline = Color(0xFF7D8982),
    outlineVariant = Color(0xFFD7DDD4),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val ComicDavShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val ComicDavTypography = Typography()

@Composable
fun ComicDavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ComicDavLightColors,
        typography = ComicDavTypography,
        shapes = ComicDavShapes,
        content = content,
    )
}
