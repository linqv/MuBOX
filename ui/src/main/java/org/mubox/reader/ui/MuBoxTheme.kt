package org.mubox.reader.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mubox.reader.core.model.settings.AppColorPalette

// Warm paper, restrained terracotta, and neutral surfaces follow Anthropic's brand palette.
private val MuBoxLightColors = lightColorScheme(
    primary = MuBoxBrandColors.Orange,
    // Brand orange needs dark text for readable small labels and filled buttons.
    onPrimary = MuBoxBrandColors.Dark,
    primaryContainer = Color(0xFFF6E7DE),
    onPrimaryContainer = Color(0xFF7A331D),
    secondary = Color(0xFF64635C),
    onSecondary = MuBoxBrandColors.Light,
    secondaryContainer = MuBoxBrandColors.LightGray,
    onSecondaryContainer = MuBoxBrandColors.Dark,
    tertiary = Color(0xFF546641),
    onTertiary = MuBoxBrandColors.Light,
    tertiaryContainer = Color(0xFFE7EBDF),
    onTertiaryContainer = Color(0xFF334025),
    background = MuBoxBrandColors.Light,
    onBackground = MuBoxBrandColors.Dark,
    surface = MuBoxBrandColors.Light,
    onSurface = MuBoxBrandColors.Dark,
    surfaceVariant = Color(0xFFF0EFE8),
    onSurfaceVariant = Color(0xFF65645D),
    surfaceContainerLowest = Color(0xFFFFFEFA),
    surfaceContainerLow = Color(0xFFF5F4EE),
    surfaceContainer = Color(0xFFF0EFE8),
    surfaceContainerHigh = Color(0xFFECEAE2),
    surfaceContainerHighest = MuBoxBrandColors.LightGray,
    outline = Color(0xFF88867E),
    outlineVariant = Color(0xFFDEDCD2),
    inverseSurface = MuBoxBrandColors.Dark,
    inverseOnSurface = MuBoxBrandColors.Light,
    inversePrimary = MuBoxBrandColors.Orange,
    surfaceTint = MuBoxBrandColors.Orange,
    scrim = MuBoxBrandColors.Dark,
    error = Color(0xFFA53E32),
    onError = MuBoxBrandColors.Light,
    errorContainer = Color(0xFFF5E4DF),
    onErrorContainer = Color(0xFF762B21),
)

private val MuBoxDarkColors = darkColorScheme(
    primary = MuBoxDarkTokens.AccentPrimary,
    onPrimary = MuBoxBrandColors.Dark,
    primaryContainer = Color(0xFF3E2A22),
    onPrimaryContainer = Color(0xFFE9B09A),
    secondary = MuBoxDarkTokens.AccentSecondary,
    onSecondary = MuBoxBrandColors.Dark,
    secondaryContainer = MuBoxDarkTokens.SurfaceSecondary,
    onSecondaryContainer = MuBoxDarkTokens.TextPrimary,
    tertiary = MuBoxDarkTokens.Success,
    onTertiary = MuBoxBrandColors.Dark,
    tertiaryContainer = Color(0xFF2A3224),
    onTertiaryContainer = Color(0xFFC3D2AE),
    background = MuBoxDarkTokens.BackgroundPrimary,
    onBackground = MuBoxDarkTokens.TextPrimary,
    surface = MuBoxDarkTokens.SurfacePrimary,
    onSurface = MuBoxDarkTokens.TextPrimary,
    surfaceVariant = MuBoxDarkTokens.SurfaceSecondary,
    onSurfaceVariant = MuBoxDarkTokens.TextSecondary,
    surfaceContainerLowest = MuBoxDarkTokens.BackgroundDeep,
    surfaceContainerLow = MuBoxDarkTokens.BackgroundElevated,
    surfaceContainer = MuBoxDarkTokens.SurfacePrimary,
    surfaceContainerHigh = MuBoxDarkTokens.SurfaceHover,
    surfaceContainerHighest = MuBoxDarkTokens.SurfaceActive,
    outline = MuBoxDarkTokens.BorderDefault,
    outlineVariant = MuBoxDarkTokens.BorderSubtle,
    inverseSurface = MuBoxBrandColors.Light,
    inverseOnSurface = MuBoxBrandColors.Dark,
    inversePrimary = Color(0xFF9C432A),
    surfaceTint = MuBoxBrandColors.Orange,
    scrim = MuBoxBrandColors.Dark,
    error = MuBoxDarkTokens.Error,
    onError = MuBoxBrandColors.Dark,
    errorContainer = Color(0xFF3D2621),
    onErrorContainer = Color(0xFFF0B9AA),
)

private val MuBoxShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

private val MuBoxTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        fontWeight = FontWeight.Normal,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Normal,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Normal,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Normal,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Medium,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Medium,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Medium,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
)

fun muBoxColorSchemeFor(palette: AppColorPalette): ColorScheme =
    when (palette) {
        AppColorPalette.MU_BOX_LIGHT -> MuBoxLightColors
        AppColorPalette.MU_BOX_DARK -> MuBoxDarkColors
    }

fun muBoxTypography(): Typography = MuBoxTypography

@Composable
fun MuBoxTheme(
    palette: AppColorPalette = AppColorPalette.MU_BOX_LIGHT,
    content: @Composable () -> Unit,
) {
    val colorScheme = muBoxColorSchemeFor(palette)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = muBoxTypography(),
        shapes = MuBoxShapes,
        content = content,
    )
}
