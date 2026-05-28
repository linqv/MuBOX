package com.example.comicdav.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.comicdav.data.AppColorPalette

// Adwaita Dark — neutral GNOME dark
private val AdwaitaDarkColors = darkColorScheme(
    primary = Color(0xFF3584E4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A4F8B),
    onPrimaryContainer = Color(0xFFD4E4FF),
    secondary = Color(0xFF9141AC),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF5C2D6E),
    onSecondaryContainer = Color(0xFFF0DDFF),
    tertiary = Color(0xFFE5A50A),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFE3A3),
    background = Color(0xFF1E1E1E),
    onBackground = Color.White,
    surface = Color(0xFF242424),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainerLowest = Color(0xFF1A1A1A),
    surfaceContainerLow = Color(0xFF202020),
    surfaceContainer = Color(0xFF303030),
    surfaceContainerHigh = Color(0xFF383838),
    surfaceContainerHighest = Color(0xFF424242),
    outline = Color(0xFF5E5E5E),
    outlineVariant = Color(0xFF3D3D3D),
    error = Color(0xFFC01C28),
    onError = Color.White,
    errorContainer = Color(0xFF3D1418),
    onErrorContainer = Color(0xFFFFDAD6),
)

// Adwaita Light
private val AdwaitaLightColors = lightColorScheme(
    primary = Color(0xFF1C71D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E4FF),
    onPrimaryContainer = Color(0xFF0A3066),
    secondary = Color(0xFF613583),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0DDFF),
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Color(0xFFA5790A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3A3),
    onTertiaryContainer = Color(0xFF3A2A00),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1E1E1E),
    surface = Color.White,
    onSurface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF5E5E5E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    outline = Color(0xFF5E5E5E),
    outlineVariant = Color(0xFFD0D0D0),
    error = Color(0xFFC01C28),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF3D1418),
)

// Adwaita Blue-Gray — dark base with cool blue-gray accent
private val AdwaitaBlueGrayColors = AdwaitaDarkColors.copy(
    primary = Color(0xFF78AEED),
    primaryContainer = Color(0xFF2A5A8F),
    onPrimaryContainer = Color(0xFFD8EAFF),
)

// Adwaita Purple — dark base with purple accent
private val AdwaitaPurpleColors = AdwaitaDarkColors.copy(
    primary = Color(0xFFC061CB),
    primaryContainer = Color(0xFF6E3575),
    onPrimaryContainer = Color(0xFFF8DDFF),
)

// Cinema Dark — the original cinema-style dark scheme (was DEFAULT)
private val CinemaDarkColors = darkColorScheme(
    primary = Color(0xFF5EEBFF),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF005463),
    onPrimaryContainer = Color(0xFFB8F4FF),
    secondary = Color(0xFFD6B7FF),
    onSecondary = Color(0xFF3A1768),
    secondaryContainer = Color(0xFF553285),
    onSecondaryContainer = Color(0xFFF0DDFF),
    tertiary = Color(0xFFFFC857),
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFF614100),
    onTertiaryContainer = Color(0xFFFFE3A3),
    background = Color(0xFF050A14),
    onBackground = Color(0xFFE8EEF7),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE7EDF7),
    surfaceVariant = Color(0xFF243244),
    onSurfaceVariant = Color(0xFFC3CEDB),
    surfaceContainerLowest = Color(0xFF03070F),
    surfaceContainerLow = Color(0xFF0E1726),
    surfaceContainer = Color(0xFF132033),
    surfaceContainerHigh = Color(0xFF1A2A40),
    surfaceContainerHighest = Color(0xFF23344B),
    outline = Color(0xFF7890A8),
    outlineVariant = Color(0xFF2F425A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// Sepia
private val ComicDavSepiaColors = lightColorScheme(
    primary = Color(0xFF92400E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCD9A8),
    onPrimaryContainer = Color(0xFF2D1607),
    secondary = Color(0xFF7C2D12),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFED7AA),
    onSecondaryContainer = Color(0xFF2A0F03),
    tertiary = Color(0xFF65A30D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9F99D),
    onTertiaryContainer = Color(0xFF1A2E05),
    background = Color(0xFFFAF3E0),
    onBackground = Color(0xFF1F1B12),
    surface = Color(0xFFFFF9EC),
    onSurface = Color(0xFF1F1B12),
    surfaceVariant = Color(0xFFEEDFC2),
    onSurfaceVariant = Color(0xFF6B5B3B),
    surfaceContainerLowest = Color(0xFFFFFCF2),
    surfaceContainerLow = Color(0xFFFCF6E4),
    surfaceContainer = Color(0xFFF5EBD5),
    surfaceContainerHigh = Color(0xFFEEDFC2),
    surfaceContainerHighest = Color(0xFFE2D2AE),
    outline = Color(0xFF8C7C5C),
    outlineVariant = Color(0xFFD8C9A8),
    error = Color(0xFFB91C1C),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

// Night
private val ComicDavNightColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFFC4B5FD),
    onSecondary = Color(0xFF2E1065),
    secondaryContainer = Color(0xFF4C1D95),
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFFCBD5E1),
    surfaceContainerLowest = Color(0xFF080C18),
    surfaceContainerLow = Color(0xFF111827),
    surfaceContainer = Color(0xFF1B2333),
    surfaceContainerHigh = Color(0xFF273142),
    surfaceContainerHighest = Color(0xFF334155),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF334155),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
)

// High Contrast
private val ComicDavHighContrastColors = lightColorScheme(
    primary = Color(0xFF1D1F8B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7CDFF),
    onPrimaryContainer = Color(0xFF03062E),
    secondary = Color(0xFF003B70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB3E0FF),
    onSecondaryContainer = Color(0xFF001B33),
    tertiary = Color(0xFF8C2A3D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFCED7),
    onTertiaryContainer = Color(0xFF330014),
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE7E8EF),
    onSurfaceVariant = Color(0xFF1A1C24),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FB),
    surfaceContainer = Color(0xFFF1F2F8),
    surfaceContainerHigh = Color(0xFFE7E8EF),
    surfaceContainerHighest = Color(0xFFDADCE6),
    outline = Color(0xFF1F2229),
    outlineVariant = Color(0xFF494C56),
    error = Color(0xFF8B0000),
    onError = Color.White,
    errorContainer = Color(0xFFFFD8D8),
    onErrorContainer = Color(0xFF2C0000),
)

private val ComicDavShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private val ComicDavTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 56.sp,
        lineHeight = 64.sp,
        fontWeight = FontWeight.Bold,
    ),
    displayMedium = TextStyle(
        fontSize = 44.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Bold,
    ),
    displaySmall = TextStyle(
        fontSize = 34.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Bold,
    ),
    headlineLarge = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
)

internal fun comicDavColorSchemeFor(palette: AppColorPalette): ColorScheme =
    when (palette) {
        AppColorPalette.DEFAULT -> AdwaitaDarkColors
        AppColorPalette.ADWAITA_LIGHT -> AdwaitaLightColors
        AppColorPalette.ADWAITA_BLUE_GRAY -> AdwaitaBlueGrayColors
        AppColorPalette.ADWAITA_PURPLE -> AdwaitaPurpleColors
        AppColorPalette.CINEMA_DARK -> CinemaDarkColors
        AppColorPalette.SEPIA -> ComicDavSepiaColors
        AppColorPalette.NIGHT -> ComicDavNightColors
        AppColorPalette.HIGH_CONTRAST -> ComicDavHighContrastColors
    }

internal fun comicDavTypography(): Typography = ComicDavTypography

@Composable
fun ComicDavTheme(
    palette: AppColorPalette = AppColorPalette.DEFAULT,
    content: @Composable () -> Unit,
) {
    val colorScheme = comicDavColorSchemeFor(palette)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = comicDavTypography(),
        shapes = ComicDavShapes,
        content = content,
    )
}
