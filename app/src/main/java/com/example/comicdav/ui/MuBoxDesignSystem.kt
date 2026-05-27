package com.example.comicdav.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.comicdav.video.MediaKind

internal data class MuBoxColors(
    val background: Color,
    val panel: Color,
    val panelHigh: Color,
    val row: Color,
    val rowSelected: Color,
    val border: Color,
    val selectedBorder: Color,
    val mediaAccent: Color,
    val onMediaAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val comicAccent: Color,
    val statusAccent: Color,
    val text: Color,
    val muted: Color,
    val overlayText: Color,
    val playerOverlay: Color,
    val playerSheet: Color,
    val playerChip: Color,
    val playerChipSelected: Color,
    val playerProgressTrack: Color,
    val playerProgress: Color,
    val playerHud: Color,
    val errorSurface: Color,
    val errorText: Color,
)

internal fun muBoxColorsFor(colorScheme: ColorScheme): MuBoxColors =
    MuBoxColors(
        background = colorScheme.background,
        panel = colorScheme.surfaceContainer,
        panelHigh = colorScheme.surfaceContainerHigh,
        row = colorScheme.surfaceContainer,
        rowSelected = colorScheme.primaryContainer,
        border = colorScheme.outlineVariant,
        selectedBorder = colorScheme.primary,
        mediaAccent = colorScheme.primary,
        onMediaAccent = colorScheme.onPrimary,
        accentSoft = colorScheme.primaryContainer,
        onAccentSoft = colorScheme.onPrimaryContainer,
        comicAccent = colorScheme.secondary,
        statusAccent = colorScheme.tertiary,
        text = colorScheme.onBackground,
        muted = colorScheme.onSurfaceVariant,
        overlayText = Color.White,
        playerOverlay = Color(0x66000000),
        playerSheet = Color(0xCC0F172A),
        playerChip = Color(0x33FFFFFF),
        playerChipSelected = Color(0xFFEC4899),
        playerProgressTrack = Color(0x4DFFFFFF),
        playerProgress = Color(0xFFEC4899),
        playerHud = Color(0xCC000000),
        errorSurface = colorScheme.errorContainer,
        errorText = colorScheme.onErrorContainer,
    )

internal object MuBoxMetrics {
    val MinTouchTargetDp = 44.dp
    val DenseRowCornerDp = 14.dp
    val PanelCornerDp = 20.dp
    val PlayerPanelCornerDp = 22.dp
    val PlayerPanelContentPaddingDp = 0.dp
    val PlayerCenterControlVisualDp = 64.dp
    val PlayerCenterControlTouchDp = 80.dp
}

internal enum class MuBoxPosterKind {
    Comic,
    Video,
}

internal fun muBoxPosterAspectRatio(kind: MuBoxPosterKind): Float =
    when (kind) {
        MuBoxPosterKind.Comic -> 0.72f
        MuBoxPosterKind.Video -> 16f / 9f
    }

internal fun muBoxMediaKindLabel(mediaKind: MediaKind): String =
    when (mediaKind) {
        MediaKind.Directory -> "文件夹"
        MediaKind.Comic -> "漫画文件"
        MediaKind.Video -> "视频文件"
        MediaKind.Subtitle -> "字幕文件"
        MediaKind.Audio -> "音频文件"
        MediaKind.Unknown -> "文件"
    }
