package com.example.comicdav.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.comicdav.data.AppColorPalette

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

private val ComicDavSepiaColors = lightColorScheme(
    primary = Color(0xFF6F4E1F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3DFC0),
    onPrimaryContainer = Color(0xFF251805),
    secondary = Color(0xFF4F6254),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E7D9),
    onSecondaryContainer = Color(0xFF0D1F13),
    tertiary = Color(0xFF7B4358),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E3),
    onTertiaryContainer = Color(0xFF32101D),
    background = Color(0xFFFBF3E4),
    onBackground = Color(0xFF211A12),
    surface = Color(0xFFFFF8EB),
    onSurface = Color(0xFF211A12),
    surfaceVariant = Color(0xFFECE0CF),
    onSurfaceVariant = Color(0xFF665C4C),
    surfaceContainer = Color(0xFFF3E8D6),
    outline = Color(0xFF8F806C),
    outlineVariant = Color(0xFFD7C9B5),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val ComicDavNightColors = darkColorScheme(
    primary = Color(0xFF8FD7BE),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF14513D),
    onPrimaryContainer = Color(0xFFA9F3D8),
    secondary = Color(0xFFD8C37B),
    onSecondary = Color(0xFF3A2F00),
    secondaryContainer = Color(0xFF564600),
    onSecondaryContainer = Color(0xFFF5E09A),
    tertiary = Color(0xFFD9BDE8),
    onTertiary = Color(0xFF3A2545),
    tertiaryContainer = Color(0xFF523B5D),
    onTertiaryContainer = Color(0xFFF1D8FF),
    background = Color(0xFF101511),
    onBackground = Color(0xFFE2E8DF),
    surface = Color(0xFF171C18),
    onSurface = Color(0xFFE2E8DF),
    surfaceVariant = Color(0xFF3F4943),
    onSurfaceVariant = Color(0xFFC0CAC2),
    surfaceContainer = Color(0xFF1D231F),
    outline = Color(0xFF89938B),
    outlineVariant = Color(0xFF3F4943),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val ComicDavHighContrastColors = lightColorScheme(
    primary = Color(0xFF002B75),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A4A),
    secondary = Color(0xFF00523A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8F5D8),
    onSecondaryContainer = Color(0xFF002417),
    tertiary = Color(0xFF7A003B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E6),
    onTertiaryContainer = Color(0xFF3F001B),
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE7E8EF),
    onSurfaceVariant = Color(0xFF25272D),
    surfaceContainer = Color(0xFFF1F2F8),
    outline = Color(0xFF33363D),
    outlineVariant = Color(0xFF767980),
    error = Color(0xFF9D0000),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF300000),
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
fun ComicDavTheme(
    palette: AppColorPalette = AppColorPalette.DEFAULT,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (palette) {
        AppColorPalette.DEFAULT -> ComicDavLightColors
        AppColorPalette.SEPIA -> ComicDavSepiaColors
        AppColorPalette.NIGHT -> ComicDavNightColors
        AppColorPalette.HIGH_CONTRAST -> ComicDavHighContrastColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ComicDavTypography,
        shapes = ComicDavShapes,
        content = content,
    )
}
