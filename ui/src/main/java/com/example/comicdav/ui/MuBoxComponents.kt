package com.example.comicdav.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Subtitles
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.comicdav.core.model.media.MediaKind

@Composable
fun rememberMuBoxColors(): MuBoxColors {
    val colorScheme = MaterialTheme.colorScheme
    return remember(colorScheme) { muBoxColorsFor(colorScheme) }
}

@Composable
fun MuBoxPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
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
fun MuBoxMessagePanel(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    dismissLabel: String = "知道了",
) {
    val colors = rememberMuBoxColors()
    val containerColor = if (isError) colors.errorSurface else colors.panelHigh
    val contentColor = if (isError) colors.errorText else colors.text
    val shape = RoundedCornerShape(MuBoxMetrics.PanelCornerDp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isError) {
                    Modifier
                } else {
                    Modifier.muBoxGradientBorder(colors = colors, shape = shape)
                },
            ),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = if (isError) BorderStroke(1.dp, colors.errorText.copy(alpha = 0.28f)) else null,
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
                    Text(dismissLabel)
                }
            }
        }
    }
}

@Composable
fun MuBoxEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
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
            MuBoxGradientButton(text = actionLabel, onClick = onAction)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MuBoxDenseMediaRow(
    title: String,
    mediaKind: MediaKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(MuBoxMetrics.DenseRowCornerDp)
    val containerColor = if (selected) colors.rowSelected else colors.row
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MuBoxMetrics.MinTouchTargetDp)
            .muBoxGradientBorder(
                colors = colors,
                shape = shape,
                highlighted = selected,
                width = if (selected) 1.5.dp else 1.dp,
            )
            .clip(shape)
            .background(containerColor)
            .semantics { this.selected = selected }
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MuBoxMediaGridTile(
    title: String,
    mediaKind: MediaKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    artworkModel: Any? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(MuBoxMetrics.RadiusMDp)
    val containerColor = if (selected) colors.rowSelected else colors.row
    Column(
        modifier = modifier
            .fillMaxWidth()
            .muBoxGradientBorder(
                colors = colors,
                shape = shape,
                highlighted = selected,
                width = if (selected) 1.5.dp else 1.dp,
            )
            .clip(shape)
            .background(containerColor)
            .semantics { this.selected = selected }
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(colors.panelHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (mediaKind == MediaKind.Video) {
                // A missing or unreadable video cover intentionally leaves the artwork area
                // empty. The file browser must not replace it with generic artwork.
                if (artworkModel != null) {
                    AsyncImage(
                        model = artworkModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                MuBoxMediaTypeIcon(mediaKind = mediaKind)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.text,
                maxLines = 2,
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
    }
}

@Composable
fun MuBoxMediaTypeIcon(
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
fun MuBoxSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(MuBoxMetrics.PanelCornerDp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .muBoxGradientBorder(colors = colors, shape = shape),
        shape = shape,
        color = colors.panel,
        contentColor = colors.text,
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
fun MuBoxPlayerPanel(
    modifier: Modifier = Modifier,
    color: Color? = null,
    borderColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val colors = rememberMuBoxColors()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(MuBoxMetrics.PlayerPanelCornerDp),
        color = color ?: colors.playerSheet,
        contentColor = colors.overlayText,
        border = BorderStroke(1.dp, borderColor ?: colors.playerProgressTrack),
        content = content,
    )
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

@Composable
fun MuBoxHeaderBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = rememberMuBoxColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.headerBar,
        contentColor = colors.text,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = MuBoxMetrics.HeaderBarHeightDp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) navigationIcon()
            Text(
                text = title,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
    }
}

@Composable
fun MuBoxBoxedList(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(MuBoxMetrics.BoxedListCornerDp)
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = colors.muted,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .muBoxGlassSurface(colors = colors, shape = shape),
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun MuBoxActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MuBoxMetrics.BoxedListRowMinHeightDp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun MuBoxSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MuBoxMetrics.BoxedListRowMinHeightDp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun MuBoxPropertyRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MuBoxMetrics.BoxedListRowMinHeightDp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.text)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
    }
}
