package com.example.comicdav.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.comicdav.core.model.media.MediaKind
import kotlin.math.max

/**
 * MuBOX 深色模式的单一色值来源。
 *
 * 这些值直接来自 2026-07-24 的视觉参考，不在组件中临时混入新的深色十六进制值。
 */
object MuBoxDarkTokens {
    val BackgroundDeep = Color(0xFF000217)
    val BackgroundPrimary = Color(0xFF000626)
    val BackgroundSecondary = Color(0xFF030D2B)
    val BackgroundElevated = Color(0xFF081037)

    val SurfacePrimary = Color(0xFF0B1236)
    val SurfaceSecondary = Color(0xFF101B41)
    val SurfaceHover = Color(0xFF192147)
    val SurfaceActive = Color(0xFF202B58)

    val BorderSubtle = Color(0xFF1E2B52)
    val BorderDefault = Color(0xFF33436F)
    val BorderHighlight = Color(0xFF8178FF)

    val AccentPrimary = Color(0xFF7567FF)
    val AccentSecondary = Color(0xFF9C5CFF)
    val AccentBlue = Color(0xFF4D8DFF)
    val AccentCyan = Color(0xFF58D6FF)

    val TextPrimary = Color(0xFFF4F5FF)
    val TextSecondary = Color(0xFFADAFC4)
    val TextTertiary = Color(0xFF79769A)
    val TextDisabled = Color(0xFF59517E)

    val Success = Color(0xFF44D7A8)
    val Warning = Color(0xFFF5B95E)
    val Error = Color(0xFFFF6685)
    val Info = Color(0xFF58A6FF)

    val Overlay = Color(0xB8000217)
    val SurfaceGlass = Color(0xD0150C32)
    val BorderGlass = Color(0x5258D6FF)
    val GlassStart = Color(0xE51A103B)
    val GlassEnd = Color(0xD0090625)

    val PageAmbientGlow = Color(0x246048B8)
    val NeonOutline = Color(0x8058D6FF)
    val NeonGlow = Color(0x527567FF)
    val NeonAmbient = Color(0x389C5CFF)
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
        selectedBorder = if (isMuBoxDark) MuBoxDarkTokens.BorderHighlight else colorScheme.primary,
        mediaAccent = colorScheme.primary,
        onMediaAccent = colorScheme.onPrimary,
        accentBlue = if (isMuBoxDark) MuBoxDarkTokens.AccentBlue else colorScheme.primary,
        accentCyan = if (isMuBoxDark) MuBoxDarkTokens.AccentCyan else colorScheme.primary,
        accentSoft = colorScheme.primaryContainer,
        onAccentSoft = colorScheme.onPrimaryContainer,
        // 封面角标：浅色沿用实心强调底（对齐参考图蓝底白字），深色收敛为柔和底色防止过亮
        posterChip = if (isDark) colorScheme.primaryContainer else colorScheme.primary,
        onPosterChip = if (isDark) colorScheme.onPrimaryContainer else colorScheme.onPrimary,
        comicAccent = colorScheme.secondary,
        statusAccent = colorScheme.tertiary,
        // 成功语义色固定取自 UI 重构 §11 调色板，按底色明暗选取，保证所有主题下语义一致
        success = if (isMuBoxDark) MuBoxDarkTokens.Success else if (isDark) Color(0xFF44D7A8) else Color(0xFF287A4B),
        warning = if (isMuBoxDark) MuBoxDarkTokens.Warning else colorScheme.tertiary,
        info = if (isMuBoxDark) MuBoxDarkTokens.Info else colorScheme.primary,
        text = colorScheme.onBackground,
        muted = colorScheme.onSurfaceVariant,
        textTertiary = if (isMuBoxDark) MuBoxDarkTokens.TextTertiary else colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
        textDisabled = if (isMuBoxDark) MuBoxDarkTokens.TextDisabled else colorScheme.onSurface.copy(alpha = 0.38f),
        overlayText = Color.White,
        overlay = if (isMuBoxDark) MuBoxDarkTokens.Overlay else Color.Black.copy(alpha = 0.56f),
        glassSurface = if (isMuBoxDark) MuBoxDarkTokens.SurfaceGlass else colorScheme.surfaceContainer,
        glassBorder = if (isMuBoxDark) MuBoxDarkTokens.BorderGlass else colorScheme.outlineVariant,
        glassStart = if (isMuBoxDark) MuBoxDarkTokens.GlassStart else colorScheme.surfaceContainer,
        glassEnd = if (isMuBoxDark) MuBoxDarkTokens.GlassEnd else colorScheme.surfaceContainer,
        pageAmbientGlow = if (isMuBoxDark) MuBoxDarkTokens.PageAmbientGlow else Color.Transparent,
        neonOutline = if (isMuBoxDark) MuBoxDarkTokens.NeonOutline else colorScheme.primary.copy(alpha = 0.20f),
        neonGlow = if (isMuBoxDark) MuBoxDarkTokens.NeonGlow else Color.Transparent,
        neonAmbient = if (isMuBoxDark) MuBoxDarkTokens.NeonAmbient else Color.Transparent,
        playerOverlay = Color(0x80000000),
        playerSheet = Color(0xE6242424),
        playerChip = Color(0x33FFFFFF),
        playerChipSelected = colorScheme.primary,
        playerProgressTrack = Color(0x4DFFFFFF),
        playerProgress = colorScheme.primary,
        playerHud = Color(0xE6242424),
        playerOsdBorder = Color(0x33FFFFFF),
        playerOsdPressed = Color(0x1AFFFFFF),
        playerOsdSelected = colorScheme.primary.copy(alpha = 0.25f),
        playerOsdText = Color.White,
        errorSurface = colorScheme.errorContainer,
        errorText = colorScheme.onErrorContainer,
        headerBar = colorScheme.surfaceContainerLow,
        boxedList = colorScheme.surfaceContainer,
        boxedListBorder = colorScheme.outlineVariant,
        raisedSurface = if (isMuBoxDark) MuBoxDarkTokens.SurfaceSecondary else colorScheme.surfaceContainerHigh,
        separator = if (isMuBoxDark) MuBoxDarkTokens.BorderGlass else colorScheme.outlineVariant.copy(alpha = 0.5f),
        // 小字号操作文字使用更亮的蓝色，避免 #7567FF 在面板上略低于 AA 4.5:1。
        accentText = if (isMuBoxDark) MuBoxDarkTokens.AccentBlue else colorScheme.primary,
    )
}

fun muBoxAccentGradient(colors: MuBoxColors): Brush =
    if (colors.isMuBoxDark) {
        Brush.linearGradient(
            colorStops = arrayOf(
                0f to colors.accentBlue,
                0.48f to colors.mediaAccent,
                1f to colors.comicAccent,
            ),
        )
    } else {
        Brush.linearGradient(listOf(colors.mediaAccent, colors.mediaAccent))
    }

/**
 * 页面背景：深色模式按参考图叠加底部环境光与午夜蓝纵向渐变；其他主题保持原有纯色。
 */
fun Modifier.muBoxAppBackground(colors: MuBoxColors): Modifier =
    if (!colors.isMuBoxDark) {
        background(colors.background)
    } else {
        drawWithCache {
            val base = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to colors.backgroundDeep,
                    0.45f to colors.background,
                    1f to colors.backgroundSecondary,
                ),
            )
            val ambient = Brush.radialGradient(
                colors = listOf(colors.pageAmbientGlow, Color.Transparent),
                center = Offset(size.width * 0.5f, size.height),
                radius = max(size.width, size.height) * 0.62f,
            )
            onDrawBehind {
                drawRect(base)
                drawRect(ambient)
            }
        }
    }

/**
 * 磨砂玻璃容器：用半透明紫色玻璃、低频青紫漫反射和顶部霜化高光叠加在页面环境光上。
 *
 * Compose 没有跨版本稳定的 backdrop-filter，因此不直接模糊容器内容；低频径向渐变负责
 * 模拟背景经过大半径模糊后的色彩扩散，文字和图标仍保持锐利。
 */
fun Modifier.muBoxGlassSurface(
    colors: MuBoxColors,
    shape: Shape,
    highlighted: Boolean = false,
): Modifier {
    return muBoxGradientBorder(
        colors = colors,
        shape = shape,
        highlighted = highlighted,
        width = if (highlighted) 1.5.dp else 1.dp,
    )
        .clip(shape)
        .drawWithCache {
            val fill = if (colors.isMuBoxDark) {
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to colors.glassStart,
                        0.52f to colors.glassSurface,
                        1f to colors.glassEnd,
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            } else {
                Brush.linearGradient(listOf(colors.panel, colors.panel))
            }
            val violetFrost = Brush.radialGradient(
                colors = listOf(
                    colors.comicAccent.copy(alpha = 0.10f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.82f, size.height * 0.12f),
                radius = max(size.width, size.height) * 0.78f,
            )
            val cyanFrost = Brush.radialGradient(
                colors = listOf(
                    colors.accentCyan.copy(alpha = 0.04f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.08f, size.height * 0.94f),
                radius = max(size.width, size.height) * 0.56f,
            )
            val frostedHighlight = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = if (colors.isMuBoxDark) 0.035f else 0f),
                    0.28f to Color.Transparent,
                    1f to Color.Transparent,
                ),
            )
            onDrawBehind {
                drawRect(fill)
                if (colors.isMuBoxDark) {
                    drawRect(violetFrost)
                    drawRect(cyanFrost)
                    drawRect(frostedHighlight)
                }
            }
        }
}

/**
 * 沿真实 Shape 轮廓绘制渐变描边，而不是用单色 BorderStroke 模拟。
 *
 * 外缘用两层低透明度宽描边模拟柔光，中间是青→蓝→紫主轮廓，最内层保留冷色高光。
 * 所有层都通过 drawWithCache 缓存，不引入持续动画或额外布局。
 */
fun Modifier.muBoxGradientBorder(
    colors: MuBoxColors,
    shape: Shape,
    highlighted: Boolean = false,
    width: Dp = 1.dp,
): Modifier {
    if (!colors.isMuBoxDark) {
        return border(
            width = width,
            color = if (highlighted) colors.selectedBorder else colors.border,
            shape = shape,
        )
    }
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val outlinePath = when (outline) {
            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
            is Outline.Generic -> outline.path
        }
        val widthPx = width.toPx() * 0.68f
        val intensity = if (highlighted) 0.68f else 0.38f
        val edgeBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to colors.accentCyan.copy(alpha = 0.82f * intensity),
                0.34f to colors.accentBlue.copy(alpha = 0.88f * intensity),
                0.68f to colors.selectedBorder.copy(alpha = 0.94f * intensity),
                1f to colors.comicAccent.copy(alpha = 0.84f * intensity),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val outerGlowBrush = Brush.linearGradient(
            colors = listOf(
                colors.accentCyan.copy(alpha = 0.10f * intensity),
                colors.neonGlow.copy(alpha = 0.18f * intensity),
                colors.neonAmbient.copy(alpha = 0.16f * intensity),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val innerHighlightBrush = Brush.linearGradient(
            colors = listOf(
                colors.accentCyan.copy(alpha = 0.22f * intensity),
                colors.selectedBorder.copy(alpha = 0.18f * intensity),
                colors.comicAccent.copy(alpha = 0.12f * intensity),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        onDrawWithContent {
            drawContent()
            drawPath(
                path = outlinePath,
                brush = outerGlowBrush,
                style = Stroke(width = widthPx * 4f),
            )
            drawPath(
                path = outlinePath,
                brush = outerGlowBrush,
                style = Stroke(width = widthPx * 2.2f),
            )
            drawPath(
                path = outlinePath,
                brush = edgeBrush,
                style = Stroke(width = widthPx),
            )
            drawPath(
                path = outlinePath,
                brush = innerHighlightBrush,
                style = Stroke(width = widthPx * 0.3f),
            )
        }
    }
}

object MuBoxMetrics {
    val MinTouchTargetDp = 48.dp
    val DenseRowCornerDp = 10.dp
    val PanelCornerDp = 12.dp
    val PlayerPanelCornerDp = 16.dp
    val PlayerPanelContentPaddingDp = 0.dp
    val PlayerCenterControlVisualDp = 64.dp
    val PlayerCenterControlTouchDp = 80.dp
    val HeaderBarHeightDp = 48.dp
    val BoxedListCornerDp = 12.dp
    val BoxedListRowMinHeightDp = 48.dp
    val SeparatorThicknessDp = 1.dp

    // UI 重构 §11.5 圆角刻度
    val RadiusXsDp = 6.dp
    val RadiusSDp = 10.dp
    val RadiusMDp = 12.dp
    val RadiusLDp = 16.dp
    val RadiusXlDp = 20.dp
}

object PlayerOsdDefaults {
    val OsdCornerDp = 12.dp
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

fun muBoxPosterAspectRatio(kind: MuBoxPosterKind): Float =
    when (kind) {
        MuBoxPosterKind.Comic -> 0.72f
        MuBoxPosterKind.Video -> 16f / 9f
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
