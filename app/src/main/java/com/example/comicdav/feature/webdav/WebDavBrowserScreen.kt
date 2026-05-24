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
import com.example.comicdav.video.MediaKind
import com.example.comicdav.webdav.webDavDisplayPathLabel

private val WebDavBackground = Color(0xFF070B16)
private val WebDavPanel = Color(0xFF0D1424)
private val WebDavPanelHigh = Color(0xFF121D31)
private val WebDavRow = Color(0xFF101A2C)
private val WebDavRowSelected = Color(0xFF123847)
private val WebDavBorder = Color(0xFF243149)
private val WebDavSelectedBorder = Color(0xFF38BDF8)
private val WebDavAccent = Color(0xFF38BDF8)
private val WebDavText = Color(0xFFE5EDF8)
private val WebDavMuted = Color(0xFFAAB6CB)
private val WebDavProgressTrack = Color(0xFF253349)
private val WebDavErrorText = Color(0xFFFCA5A5)

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
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WebDavBackground)
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
                color = WebDavAccent,
                trackColor = WebDavProgressTrack,
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = WebDavPanel,
            contentColor = WebDavText,
            border = BorderStroke(1.dp, WebDavBorder),
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
                        color = WebDavText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "浏览远程目录，阅读漫画或加入书架",
                        style = MaterialTheme.typography.bodySmall,
                        color = WebDavMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = onBackToDirectories,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = WebDavAccent),
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
                color = WebDavPanelHigh,
                contentColor = WebDavText,
                border = BorderStroke(1.dp, WebDavAccent.copy(alpha = 0.32f)),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = webDavDisplayPathLabel(currentPath),
                        style = MaterialTheme.typography.labelLarge,
                        color = WebDavMuted,
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
                    border = BorderStroke(1.dp, if (isLoading) WebDavBorder else WebDavAccent.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = WebDavAccent,
                        disabledContentColor = WebDavMuted.copy(alpha = 0.55f),
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
        color = if (isSelected) WebDavRowSelected else WebDavRow,
        contentColor = WebDavText,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) WebDavSelectedBorder else WebDavBorder,
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
                    color = WebDavText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supportingLabel.isNotBlank()) {
                    Text(
                        text = supportingLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = WebDavMuted,
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
    val colors = webDavIconColors(mediaKind)
    val icon = when (mediaKind) {
        MediaKind.Directory -> Icons.Rounded.Folder
        MediaKind.Video -> Icons.Rounded.PlayCircle
        MediaKind.Subtitle -> Icons.Rounded.Subtitles
        else -> Icons.AutoMirrored.Rounded.MenuBook
    }
    val contentDescription = when (mediaKind) {
        MediaKind.Directory -> "文件夹"
        MediaKind.Comic -> "漫画文件"
        MediaKind.Video -> "视频文件"
        MediaKind.Subtitle -> "字幕文件"
        MediaKind.Audio -> "音频文件"
        MediaKind.Unknown -> "文件"
    }

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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = WebDavPanel,
        contentColor = WebDavText,
        border = BorderStroke(1.dp, WebDavBorder),
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
                    color = WebDavText,
                )
                LinearProgressIndicator(
                    progress = { downloadProgress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = WebDavAccent,
                    trackColor = WebDavProgressTrack,
                )
                TextButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = WebDavAccent),
                ) {
                    Text("取消下载")
                }
            }
            if (!downloadError.isNullOrBlank()) {
                if (downloadProgress != null) {
                    HorizontalDivider(color = WebDavBorder)
                }
                Text(
                    text = downloadError,
                    color = WebDavErrorText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (message.isNotBlank()) {
                if (downloadProgress != null || !downloadError.isNullOrBlank()) {
                    HorizontalDivider(color = WebDavBorder)
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = WebDavMuted,
                )
            }
        }
    }
}

private fun webDavIconColors(mediaKind: MediaKind): WebDavIconColors =
    when (mediaKind) {
        MediaKind.Directory -> WebDavIconColors(
            container = Color(0xFF0D344B),
            content = Color(0xFF7DD3FC),
        )
        MediaKind.Comic -> WebDavIconColors(
            container = Color(0xFF2E244F),
            content = Color(0xFFC4B5FD),
        )
        MediaKind.Video -> WebDavIconColors(
            container = Color(0xFF073B43),
            content = Color(0xFF67E8F9),
        )
        MediaKind.Subtitle -> WebDavIconColors(
            container = Color(0xFF3F3215),
            content = Color(0xFFFDE68A),
        )
        MediaKind.Audio,
        MediaKind.Unknown,
        -> WebDavIconColors(
            container = Color(0xFF263247),
            content = Color(0xFFCBD5E1),
        )
    }

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
