package com.example.comicdav.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.comicdav.core.model.media.MediaKind

data class MuBoxColors(
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

fun muBoxColorsFor(colorScheme: ColorScheme): MuBoxColors =
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
        raisedSurface = colorScheme.surfaceContainerHigh,
        separator = colorScheme.outlineVariant.copy(alpha = 0.5f),
        accentText = colorScheme.primary,
    )

object MuBoxMetrics {
    val MinTouchTargetDp = 44.dp
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
