package org.mubox.reader.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mubox.reader.core.model.media.MediaKind

/** Anthropic's published brand palette; semantic text colors are adjusted for contrast below. */
internal object MuBoxBrandColors {
    val Dark = Color(0xFF141413)
    val Light = Color(0xFFFAF9F5)
    val MidGray = Color(0xFFB0AEA5)
    val LightGray = Color(0xFFE8E6DC)
    val Orange = Color(0xFFD97757)
    val Blue = Color(0xFF6A9BCC)
    val Green = Color(0xFF788C5D)
}

/** 暖炭黑表面与克制陶土橙，作为深色界面和媒体播放控件的单一色值来源。 */
object MuBoxDarkTokens {
    val BackgroundDeep = Color(0xFF10100F)
    val BackgroundPrimary = MuBoxBrandColors.Dark
    val BackgroundSecondary = Color(0xFF1B1B19)
    val BackgroundElevated = Color(0xFF232320)

    val SurfacePrimary = Color(0xFF20201D)
    val SurfaceSecondary = Color(0xFF282825)
    val SurfaceHover = Color(0xFF31312D)
    val SurfaceActive = Color(0xFF393934)

    val BorderSubtle = Color(0xFF343430)
    val BorderDefault = Color(0xFF4C4B45)
    val BorderHighlight = MuBoxBrandColors.Orange

    val AccentPrimary = MuBoxBrandColors.Orange
    val AccentSecondary = MuBoxBrandColors.MidGray
    // Legacy media role names share the single brand accent to keep decoration restrained.
    val AccentBlue = AccentPrimary
    val AccentCyan = AccentPrimary

    val TextPrimary = MuBoxBrandColors.Light
    val TextSecondary = MuBoxBrandColors.MidGray
    val TextTertiary = Color(0xFFAAA89F)
    val TextDisabled = Color(0xFF66665F)

    // Lighter green retains the brand hue while remaining readable on raised dark surfaces.
    val Success = Color(0xFF94A97C)
    val Warning = Color(0xFFE9B09A)
    val Error = Color(0xFFE99A8A)
    val Info = Color(0xFF81ACD3)

    val Overlay = MuBoxBrandColors.Dark.copy(alpha = 0.80f)
    val SurfaceGlass = SurfacePrimary
    val BorderGlass = BorderSubtle
    val GlassStart = SurfacePrimary
    val GlassEnd = SurfacePrimary

    val PageAmbientGlow = Color.Transparent
    val NeonOutline = BorderDefault
    val NeonGlow = Color.Transparent
    val NeonAmbient = Color.Transparent
}

data class MuBoxColors(
    val isMuBoxDark: Boolean,
    val background: Color,
    val backgroundDeep: Color,
    val backgroundSecondary: Color,
    val backgroundElevated: Color,
    val panel: Color,
    val panelHigh: Color,
    val surfaceSecondary: Color,
    val surfaceHover: Color,
    val surfaceActive: Color,
    val row: Color,
    val rowSelected: Color,
    val border: Color,
    val borderDefault: Color,
    val selectedBorder: Color,
    val mediaAccent: Color,
    val onMediaAccent: Color,
    val accentBlue: Color,
    val accentCyan: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val posterChip: Color,
    val onPosterChip: Color,
    val comicAccent: Color,
    val statusAccent: Color,
    val success: Color,
    val warning: Color,
    val info: Color,
    val text: Color,
    val muted: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val overlayText: Color,
    val overlay: Color,
    val glassSurface: Color,
    val glassBorder: Color,
    val glassStart: Color,
    val glassEnd: Color,
    val pageAmbientGlow: Color,
    val neonOutline: Color,
    val neonGlow: Color,
    val neonAmbient: Color,
    val playerOverlay: Color,
    val playerSheet: Color,
    val playerChip: Color,
    val playerChipSelected: Color,
    val playerProgressTrack: Color,
    val playerProgress: Color,
    val playerHud: Color,
    val playerOsdBorder: Color,
    val playerOsdPressed: Color,
    val playerOsdSelected: Color,
    val playerOsdText: Color,
    val errorSurface: Color,
    val errorText: Color,
    val headerBar: Color,
    val boxedList: Color,
    val boxedListBorder: Color,
    val raisedSurface: Color,
    val separator: Color,
    val accentText: Color,
)

fun muBoxColorsFor(colorScheme: ColorScheme): MuBoxColors {
    val isMuBoxDark = colorScheme.background == MuBoxDarkTokens.BackgroundPrimary
    val isDark = colorScheme.background.luminance() < 0.5f
    return MuBoxColors(
        isMuBoxDark = isMuBoxDark,
        background = colorScheme.background,
        backgroundDeep = if (isMuBoxDark) MuBoxDarkTokens.BackgroundDeep else colorScheme.surfaceContainerLowest,
        backgroundSecondary = if (isMuBoxDark) MuBoxDarkTokens.BackgroundSecondary else colorScheme.background,
        backgroundElevated = if (isMuBoxDark) MuBoxDarkTokens.BackgroundElevated else colorScheme.surfaceContainerLow,
        panel = colorScheme.surfaceContainer,
        panelHigh = colorScheme.surfaceContainerHigh,
        surfaceSecondary = if (isMuBoxDark) MuBoxDarkTokens.SurfaceSecondary else colorScheme.surfaceVariant,
        surfaceHover = if (isMuBoxDark) MuBoxDarkTokens.SurfaceHover else colorScheme.surfaceContainerHigh,
        surfaceActive = if (isMuBoxDark) MuBoxDarkTokens.SurfaceActive else colorScheme.surfaceContainerHighest,
        row = colorScheme.surfaceContainer,
        rowSelected = colorScheme.primaryContainer,
        border = colorScheme.outlineVariant,
        borderDefault = colorScheme.outline,
        selectedBorder = if (isMuBoxDark) MuBoxDarkTokens.BorderHighlight else Color(0xFF9C432A),
        mediaAccent = colorScheme.primary,
        onMediaAccent = colorScheme.onPrimary,
        accentBlue = if (isMuBoxDark) MuBoxDarkTokens.AccentBlue else colorScheme.primary,
        accentCyan = if (isMuBoxDark) MuBoxDarkTokens.AccentCyan else colorScheme.primary,
        accentSoft = colorScheme.primaryContainer,
        onAccentSoft = colorScheme.onPrimaryContainer,
        // 封面角标使用柔和强调容器，在封面上保持文字清晰。
        posterChip = colorScheme.primaryContainer,
        onPosterChip = colorScheme.onPrimaryContainer,
        comicAccent = colorScheme.secondary,
        statusAccent = colorScheme.tertiary,
        // 少量语义色根据表面明暗调整对比度，不用于大面积装饰。
        success = if (isDark) MuBoxDarkTokens.Success else Color(0xFF546641),
        warning = if (isDark) MuBoxDarkTokens.Warning else Color(0xFF9C432A),
        info = if (isDark) MuBoxDarkTokens.Info else Color(0xFF3F678B),
        text = colorScheme.onBackground,
        muted = colorScheme.onSurfaceVariant,
        textTertiary = if (isDark) MuBoxDarkTokens.TextTertiary else Color(0xFF69675F),
        textDisabled = if (isMuBoxDark) MuBoxDarkTokens.TextDisabled else colorScheme.onSurface.copy(alpha = 0.38f),
        overlayText = MuBoxDarkTokens.TextPrimary,
        overlay = MuBoxDarkTokens.Overlay,
        glassSurface = if (isMuBoxDark) MuBoxDarkTokens.SurfaceGlass else colorScheme.surfaceContainer,
        glassBorder = if (isMuBoxDark) MuBoxDarkTokens.BorderGlass else colorScheme.outlineVariant,
        glassStart = if (isMuBoxDark) MuBoxDarkTokens.GlassStart else colorScheme.surfaceContainer,
        glassEnd = if (isMuBoxDark) MuBoxDarkTokens.GlassEnd else colorScheme.surfaceContainer,
        pageAmbientGlow = if (isMuBoxDark) MuBoxDarkTokens.PageAmbientGlow else Color.Transparent,
        neonOutline = colorScheme.outline,
        neonGlow = if (isMuBoxDark) MuBoxDarkTokens.NeonGlow else Color.Transparent,
        neonAmbient = if (isMuBoxDark) MuBoxDarkTokens.NeonAmbient else Color.Transparent,
        playerOverlay = MuBoxDarkTokens.BackgroundPrimary.copy(alpha = 0.64f),
        playerSheet = MuBoxDarkTokens.SurfacePrimary,
        playerChip = MuBoxDarkTokens.SurfaceHover,
        playerChipSelected = colorScheme.primary,
        playerProgressTrack = MuBoxDarkTokens.TextPrimary.copy(alpha = 0.30f),
        playerProgress = colorScheme.primary,
        playerHud = MuBoxDarkTokens.SurfacePrimary,
        playerOsdBorder = MuBoxDarkTokens.BorderDefault,
        playerOsdPressed = MuBoxDarkTokens.SurfaceHover,
        playerOsdSelected = Color(0xFF3E2A22),
        playerOsdText = MuBoxDarkTokens.TextPrimary,
        errorSurface = colorScheme.errorContainer,
        errorText = colorScheme.onErrorContainer,
        headerBar = colorScheme.surfaceContainerLow,
        boxedList = colorScheme.surfaceContainer,
        boxedListBorder = colorScheme.outlineVariant,
        raisedSurface = if (isMuBoxDark) MuBoxDarkTokens.SurfaceSecondary else colorScheme.surfaceContainerHigh,
        separator = if (isMuBoxDark) MuBoxDarkTokens.BorderGlass else colorScheme.outlineVariant.copy(alpha = 0.5f),
        accentText = if (isDark) colorScheme.onPrimaryContainer else Color(0xFF9C432A),
    )
}

fun muBoxAccentGradient(colors: MuBoxColors): Brush =
    Brush.linearGradient(listOf(colors.mediaAccent, colors.mediaAccent))

/**
 * 页面背景：温暖克制的纯色表面。
 */
fun Modifier.muBoxAppBackground(colors: MuBoxColors): Modifier =
    background(colors.background)

/**
 * 面板表面：采用克制实体表面与极细微边框，去除弥散光与霜化特效。
 */
fun Modifier.muBoxGlassSurface(
    colors: MuBoxColors,
    shape: Shape,
    highlighted: Boolean = false,
): Modifier {
    val surfaceColor = if (colors.isMuBoxDark) colors.surfaceSecondary else colors.panel
    return muBoxGradientBorder(
        colors = colors,
        shape = shape,
        highlighted = highlighted,
        width = if (highlighted) 1.5.dp else 1.dp,
    )
        .clip(shape)
        .background(surfaceColor)
}

/**
 * 沿真实 Shape 轮廓绘制干净的单层微细描边，去除霓虹外发光与多层模糊通道。
 */
fun Modifier.muBoxGradientBorder(
    colors: MuBoxColors,
    shape: Shape,
    highlighted: Boolean = false,
    width: Dp = 1.dp,
): Modifier =
    border(
        width = width,
        color = if (highlighted) colors.selectedBorder else colors.border,
        shape = shape,
    )

object MuBoxMetrics {
    // 页面级容器保持紧凑边距；组件内部仍各自保留可读性所需的留白。
    val PageHorizontalPaddingDp = 12.dp
    val MinTouchTargetDp = 48.dp
    val DenseRowCornerDp = 6.dp
    val PanelCornerDp = 8.dp
    val PlayerPanelCornerDp = 8.dp
    val PlayerPanelContentPaddingDp = 0.dp
    val PlayerCenterControlVisualDp = 64.dp
    val PlayerCenterControlTouchDp = 80.dp
    val HeaderBarHeightDp = 48.dp
    val BoxedListCornerDp = 8.dp
    val BoxedListRowMinHeightDp = 48.dp
    val SeparatorThicknessDp = 1.dp

    // UI 重构圆角刻度 (对齐 Anthropic 克制圆角)
    val RadiusXsDp = 4.dp
    val RadiusSDp = 6.dp
    val RadiusMDp = 8.dp
    val RadiusLDp = 10.dp
    val RadiusXlDp = 12.dp
}

object PlayerOsdDefaults {
    val OsdCornerDp = 8.dp
    val OsdButtonSize = 44.dp
    val OsdIconSize = 22.dp
    val CenterButtonVisualDp = 64.dp
    val CenterButtonTouchDp = 80.dp
    val ProgressTrackHeight = 3.dp
    val ProgressThumbRadius = 6.dp
}

enum class MuBoxPosterKind {
    Comic,
    Video,
}

const val MU_BOX_MEDIA_GRID_COLUMN_COUNT = 3

fun muBoxPosterAspectRatio(kind: MuBoxPosterKind): Float =
    when (kind) {
        MuBoxPosterKind.Comic -> 0.72f
        MuBoxPosterKind.Video -> 3f / 4f
    }

fun muBoxMediaKindLabel(mediaKind: MediaKind): String =
    when (mediaKind) {
        MediaKind.Directory -> "文件夹"
        MediaKind.Comic -> "漫画文件"
        MediaKind.Video -> "视频文件"
        MediaKind.Subtitle -> "字幕文件"
        MediaKind.Audio -> "音频文件"
        MediaKind.Unknown -> "文件"
    }
