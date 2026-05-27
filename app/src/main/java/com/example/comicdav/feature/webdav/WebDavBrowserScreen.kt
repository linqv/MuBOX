package com.example.comicdav.feature.webdav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.ui.muBoxColorsFor
import com.example.comicdav.video.MediaKind
import com.example.comicdav.webdav.webDavDisplayPathLabel

internal data class WebDavScreenColors(
    val background: Color,
    val panel: Color,
    val panelHigh: Color,
    val row: Color,
    val rowSelected: Color,
    val border: Color,
    val selectedBorder: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val purple: Color,
    val text: Color,
    val muted: Color,
    val progressTrack: Color,
    val errorText: Color,
)

internal fun webDavScreenColors(colorScheme: ColorScheme): WebDavScreenColors {
    val tokens = muBoxColorsFor(colorScheme)
    return WebDavScreenColors(
        background = tokens.background,
        panel = tokens.panel,
        panelHigh = tokens.panelHigh,
        row = tokens.row,
        rowSelected = tokens.rowSelected,
        border = tokens.border,
        selectedBorder = tokens.selectedBorder,
        accent = tokens.mediaAccent,
        onAccent = tokens.onMediaAccent,
        accentSoft = tokens.accentSoft,
        onAccentSoft = tokens.onAccentSoft,
        purple = tokens.comicAccent,
        text = tokens.text,
        muted = tokens.muted,
        progressTrack = tokens.playerProgressTrack,
        errorText = colorScheme.error,
    )
}

private data class WebDavIconColors(
    val container: Color,
    val content: Color,
)

internal enum class WebDavItemClickAction {
    OpenDirectory,
    OpenComic,
    OpenVideo,
    NoAction,
}

internal enum class WebDavFileMenuAction {
    AddToLibrary,
    AddToVideoLibrary,
    DownloadToLocal,
}

internal fun webDavItemClickAction(item: WebDavItem): WebDavItemClickAction =
    when (item.mediaKind) {
        MediaKind.Directory -> WebDavItemClickAction.OpenDirectory
        MediaKind.Comic -> WebDavItemClickAction.OpenComic
        MediaKind.Video -> WebDavItemClickAction.OpenVideo
        MediaKind.Audio,
        MediaKind.Subtitle,
        MediaKind.Unknown,
        -> WebDavItemClickAction.NoAction
    }

internal fun webDavItemLongPressActions(item: WebDavItem): List<WebDavFileMenuAction> =
    when (item.mediaKind) {
        MediaKind.Comic -> listOf(WebDavFileMenuAction.AddToLibrary, WebDavFileMenuAction.DownloadToLocal)
        MediaKind.Video -> listOf(WebDavFileMenuAction.AddToVideoLibrary, WebDavFileMenuAction.DownloadToLocal)
        MediaKind.Directory,
        MediaKind.Audio,
        MediaKind.Subtitle,
        MediaKind.Unknown,
        -> emptyList()
    }

@Composable
fun WebDavBrowserScreen(
    uiState: WebDavUiState,
    onItemClick: (WebDavItem) -> Unit,
    onAddToLibrary: (WebDavItem) -> Unit,
    onDownloadToLocal: (WebDavItem) -> Unit,
    onSelectFile: (WebDavItem) -> Unit,
    onSaveDirectory: () -> Unit,
    onBackToDirectories: () -> Unit,
    showSaveDirectoryAction: Boolean,
    downloadProgress: DownloadProgressUi?,
    downloadError: String?,
    actionMessage: String?,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
    selectedFile: WebDavItem? = null,
) {
    val colors = webDavScreenColors(MaterialTheme.colorScheme)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WebDavBrowserAppBar(
            currentPath = uiState.currentPath,
            isLoading = uiState.isLoading,
            onBackToDirectories = onBackToDirectories,
            onSaveDirectory = onSaveDirectory,
            showSaveDirectoryAction = showSaveDirectoryAction,
        )

        if (uiState.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.progressTrack,
            )
        }

        AnimatedContent(
            targetState = uiState.items,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "WebDavListContent",
        ) { items ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items) { item ->
                    WebDavItemRow(
                        item = item,
                        onOpen = { onItemClick(item) },
                        onAddToLibrary = { onAddToLibrary(item) },
                        onDownloadToLocal = { onDownloadToLocal(item) },
                        onSelectFile = { onSelectFile(item) },
                        isSelected = selectedFile?.path == item.path,
                    )
                }
            }
        }

        val panelMessage = uiState.message.ifBlank { actionMessage.orEmpty() }
        if (downloadProgress != null || !downloadError.isNullOrBlank() || panelMessage.isNotBlank()) {
            WebDavTransferPanel(
                message = panelMessage,
                downloadProgress = downloadProgress,
                downloadError = downloadError,
                onCancelDownload = onCancelDownload,
            )
        }
    }
}

@Composable
private fun WebDavBrowserAppBar(
    currentPath: String,
    isLoading: Boolean,
    onBackToDirectories: () -> Unit,
    onSaveDirectory: () -> Unit,
    showSaveDirectoryAction: Boolean,
) {
    val colors = webDavScreenColors(MaterialTheme.colorScheme)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = colors.panel,
            contentColor = colors.text,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "WebDAV ${ComicDavCopy.sourcesTitle}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "浏览远程目录，阅读漫画或加入书架",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = onBackToDirectories,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                ) {
                    Text(ComicDavCopy.sourcesTitle)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(14.dp),
                color = colors.panelHigh,
                contentColor = colors.text,
                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.32f)),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = webDavDisplayPathLabel(currentPath),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (shouldShowSaveDirectoryAction(showSaveDirectoryAction)) {
                OutlinedButton(
                    onClick = onSaveDirectory,
                    enabled = !isLoading,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    border = BorderStroke(1.dp, if (isLoading) colors.border else colors.accent.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.accent,
                        disabledContentColor = colors.muted.copy(alpha = 0.55f),
                    ),
                ) {
                    Text(ComicDavCopy.saveCurrentDirectory)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WebDavItemRow(
    item: WebDavItem,
    onOpen: () -> Unit,
    onAddToLibrary: () -> Unit,
    onDownloadToLocal: () -> Unit,
    onSelectFile: () -> Unit,
    isSelected: Boolean,
) {
    val clickAction = webDavItemClickAction(item)
    val longPressActions = webDavItemLongPressActions(item)
    val supportingLabel = webDavItemSupportingLabel(item)
    val colors = webDavScreenColors(MaterialTheme.colorScheme)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    when (clickAction) {
                        WebDavItemClickAction.OpenDirectory -> onOpen()
                        WebDavItemClickAction.OpenComic -> onOpen()
                        WebDavItemClickAction.OpenVideo -> onOpen()
                        WebDavItemClickAction.NoAction -> Unit
                    }
                },
                onLongClick = longPressActions.takeIf { it.isNotEmpty() }?.let {
                    { onSelectFile() }
                },
                onLongClickLabel = if (longPressActions.isEmpty()) null else "文件操作",
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) colors.rowSelected else colors.row,
        contentColor = colors.text,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) colors.selectedBorder else colors.border,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WebDavItemTypeIcon(mediaKind = item.mediaKind)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supportingLabel.isNotBlank()) {
                    Text(
                        text = supportingLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun WebDavItemTypeIcon(mediaKind: MediaKind) {
    val screenColors = webDavScreenColors(MaterialTheme.colorScheme)
    val colors = webDavIconColors(mediaKind, screenColors)
    val icon = when (mediaKind) {
        MediaKind.Directory -> Icons.Rounded.Folder
        MediaKind.Video -> Icons.Rounded.PlayCircle
        MediaKind.Subtitle -> Icons.Rounded.Subtitles
        else -> Icons.AutoMirrored.Rounded.MenuBook
    }
    val contentDescription = webDavItemTypeContentDescription(mediaKind)

    Box(
        modifier = Modifier
            .size(44.dp)
            .background(color = colors.container, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = colors.content,
        )
    }
}

@Composable
private fun WebDavTransferPanel(
    message: String,
    downloadProgress: DownloadProgressUi?,
    downloadError: String?,
    onCancelDownload: () -> Unit,
) {
    val colors = webDavScreenColors(MaterialTheme.colorScheme)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colors.panel,
        contentColor = colors.text,
        border = BorderStroke(1.dp, colors.border),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (downloadProgress != null) {
                Text(
                    text = downloadProgress.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                )
                LinearProgressIndicator(
                    progress = { downloadProgress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.progressTrack,
                )
                TextButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                ) {
                    Text("取消下载")
                }
            }
            if (!downloadError.isNullOrBlank()) {
                if (downloadProgress != null) {
                    HorizontalDivider(color = colors.border)
                }
                Text(
                    text = downloadError,
                    color = colors.errorText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (message.isNotBlank()) {
                if (downloadProgress != null || !downloadError.isNullOrBlank()) {
                    HorizontalDivider(color = colors.border)
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
        }
    }
}

private fun webDavIconColors(
    mediaKind: MediaKind,
    colors: WebDavScreenColors,
): WebDavIconColors =
    when (mediaKind) {
        MediaKind.Directory -> WebDavIconColors(
            container = colors.accentSoft,
            content = colors.onAccentSoft,
        )
        MediaKind.Comic -> WebDavIconColors(
            container = colors.panelHigh,
            content = colors.purple,
        )
        MediaKind.Video -> WebDavIconColors(
            container = colors.accentSoft,
            content = colors.onAccentSoft,
        )
        MediaKind.Subtitle -> WebDavIconColors(
            container = colors.panelHigh,
            content = colors.accent,
        )
        MediaKind.Audio,
        MediaKind.Unknown,
        -> WebDavIconColors(
            container = colors.panelHigh,
            content = colors.muted,
        )
    }

internal fun webDavItemTypeContentDescription(mediaKind: MediaKind): String =
    com.example.comicdav.ui.muBoxMediaKindLabel(mediaKind)

internal fun webDavItemSupportingLabel(item: WebDavItem): String =
    if (item.isDirectory) "" else item.size?.let(::formatByteSize) ?: "大小未知"

internal fun shouldShowSaveDirectoryAction(isAddingPath: Boolean): Boolean = isAddingPath

internal fun formatByteSize(bytes: Long): String {
    val kib = 1024L
    val mib = kib * 1024L
    val gib = mib * 1024L
    return when {
        bytes >= gib -> "%.1f GiB".format(bytes.toDouble() / gib)
        bytes >= mib -> "%.1f MiB".format(bytes.toDouble() / mib)
        bytes >= kib -> "%.1f KiB".format(bytes.toDouble() / kib)
        else -> "$bytes B"
    }
}

data class DownloadProgressUi(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    val label: String
        get() = "正在下载 ${downloadedBytes / 1024} KiB / ${totalBytes / 1024} KiB"
}
