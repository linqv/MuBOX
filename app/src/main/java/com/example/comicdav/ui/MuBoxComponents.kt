package com.example.comicdav.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.video.MediaKind

@Composable
internal fun rememberMuBoxColors(): MuBoxColors {
    val colorScheme = MaterialTheme.colorScheme
    return remember(colorScheme) { muBoxColorsFor(colorScheme) }
}

@Composable
internal fun MuBoxPageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
internal fun MuBoxMessagePanel(
    text: String,
    isError: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    val containerColor = if (isError) colors.errorSurface else colors.panelHigh
    val contentColor = if (isError) colors.errorText else colors.text
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MuBoxMetrics.PanelCornerDp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, if (isError) colors.errorText.copy(alpha = 0.28f) else colors.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                ) {
                    Text("知道了")
                }
            }
        }
    }
}

@Composable
internal fun MuBoxEmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(colors.accentSoft, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onAccentSoft,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
        )
        if (!body.isNullOrBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.mediaAccent,
                    contentColor = colors.onMediaAccent,
                ),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun MuBoxDenseMediaRow(
    title: String,
    subtitle: String? = null,
    mediaKind: MediaKind,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(MuBoxMetrics.DenseRowCornerDp)
    val containerColor = if (selected) colors.rowSelected else colors.row
    val borderColor = if (selected) colors.selectedBorder else colors.border
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MuBoxMetrics.MinTouchTargetDp)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MuBoxMediaTypeIcon(mediaKind = mediaKind)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
internal fun MuBoxMediaTypeIcon(
    mediaKind: MediaKind,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    val iconColors = muBoxMediaTypeIconColors(mediaKind, colors)
    Box(
        modifier = modifier
            .size(36.dp)
            .background(iconColors.container, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = muBoxMediaKindIcon(mediaKind),
            contentDescription = muBoxMediaKindLabel(mediaKind),
            tint = iconColors.content,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
internal fun MuBoxSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = rememberMuBoxColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MuBoxMetrics.PanelCornerDp),
        color = colors.panel,
        contentColor = colors.text,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.muted,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
internal fun MuBoxPlayerPanel(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = rememberMuBoxColors()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(MuBoxMetrics.PlayerPanelCornerDp),
        color = colors.playerSheet,
        contentColor = colors.overlayText,
        border = BorderStroke(1.dp, colors.playerProgressTrack),
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

private data class MuBoxIconColors(
    val container: Color,
    val content: Color,
)

private fun muBoxMediaTypeIconColors(mediaKind: MediaKind, colors: MuBoxColors): MuBoxIconColors =
    when (mediaKind) {
        MediaKind.Directory -> MuBoxIconColors(colors.accentSoft, colors.onAccentSoft)
        MediaKind.Comic -> MuBoxIconColors(colors.comicAccent.copy(alpha = 0.22f), colors.comicAccent)
        MediaKind.Video -> MuBoxIconColors(colors.mediaAccent.copy(alpha = 0.22f), colors.mediaAccent)
        MediaKind.Subtitle -> MuBoxIconColors(colors.statusAccent.copy(alpha = 0.20f), colors.statusAccent)
        MediaKind.Audio -> MuBoxIconColors(colors.playerChip, colors.playerProgress)
        MediaKind.Unknown -> MuBoxIconColors(colors.panelHigh, colors.muted)
    }

private fun muBoxMediaKindIcon(mediaKind: MediaKind): ImageVector =
    when (mediaKind) {
        MediaKind.Directory -> Icons.Filled.Folder
        MediaKind.Comic -> Icons.Filled.PhotoLibrary
        MediaKind.Video -> Icons.Filled.Movie
        MediaKind.Subtitle -> Icons.Filled.Subtitles
        MediaKind.Audio -> Icons.Filled.AudioFile
        MediaKind.Unknown -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
