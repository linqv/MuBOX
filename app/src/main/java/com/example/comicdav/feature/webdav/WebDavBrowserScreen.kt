package com.example.comicdav.feature.webdav

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.ui.ComicDavIcons

internal enum class WebDavItemClickAction {
    OpenDirectory,
    OpenComic,
}

internal enum class WebDavFileMenuAction {
    AddToLibrary,
    DownloadToLocal,
}

internal fun webDavItemClickAction(item: WebDavItem): WebDavItemClickAction =
    if (item.isDirectory) WebDavItemClickAction.OpenDirectory else WebDavItemClickAction.OpenComic

internal fun webDavItemLongPressActions(item: WebDavItem): List<WebDavFileMenuAction> =
    if (item.isDirectory) emptyList() else listOf(WebDavFileMenuAction.AddToLibrary, WebDavFileMenuAction.DownloadToLocal)

@Composable
fun WebDavBrowserScreen(
    uiState: WebDavUiState,
    onItemClick: (WebDavItem) -> Unit,
    onAddToLibrary: (WebDavItem) -> Unit,
    onDownloadToLocal: (WebDavItem) -> Unit,
    onSaveDirectory: () -> Unit,
    onBackToDirectories: () -> Unit,
    showSaveDirectoryAction: Boolean,
    downloadProgress: DownloadProgressUi?,
    downloadError: String?,
    actionMessage: String?,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
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
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.items) { item ->
                WebDavItemRow(
                    item = item,
                    onOpen = { onItemClick(item) },
                    onAddToLibrary = { onAddToLibrary(item) },
                    onDownloadToLocal = { onDownloadToLocal(item) },
                )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                )
                Text(
                    text = "浏览远程目录，阅读漫画或加入书架",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onBackToDirectories,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text(ComicDavCopy.sourcesTitle)
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
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = pathLabel(currentPath),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
) {
    var isActionDialogOpen by remember { mutableStateOf(false) }
    val clickAction = webDavItemClickAction(item)
    val longPressActions = webDavItemLongPressActions(item)
    val supportingLabel = webDavItemSupportingLabel(item)

    if (isActionDialogOpen) {
        WebDavFileActionDialog(
            item = item,
            actions = longPressActions,
            onDismiss = { isActionDialogOpen = false },
            onAddToLibrary = {
                isActionDialogOpen = false
                onAddToLibrary()
            },
            onDownloadToLocal = {
                isActionDialogOpen = false
                onDownloadToLocal()
            },
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    when (clickAction) {
                        WebDavItemClickAction.OpenDirectory -> onOpen()
                        WebDavItemClickAction.OpenComic -> onOpen()
                    }
                },
                onLongClick = longPressActions.takeIf { it.isNotEmpty() }?.let {
                    { isActionDialogOpen = true }
                },
                onLongClickLabel = if (longPressActions.isEmpty()) null else "文件操作",
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WebDavItemTypeIcon(isDirectory = item.isDirectory)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supportingLabel.isNotBlank()) {
                    Text(
                        text = supportingLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun WebDavFileActionDialog(
    item: WebDavItem,
    actions: List<WebDavFileMenuAction>,
    onDismiss: () -> Unit,
    onAddToLibrary: () -> Unit,
    onDownloadToLocal: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件操作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                actions.forEach { action ->
                    when (action) {
                        WebDavFileMenuAction.AddToLibrary -> {
                            TextButton(
                                onClick = onAddToLibrary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text(ComicDavCopy.addToLibrary)
                            }
                        }
                        WebDavFileMenuAction.DownloadToLocal -> {
                            TextButton(
                                onClick = onDownloadToLocal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text("下载到本地")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun WebDavItemTypeIcon(isDirectory: Boolean) {
    Box(
        modifier = Modifier.size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isDirectory) ComicDavIcons.Folder else ComicDavIcons.Archive,
            contentDescription = if (isDirectory) "文件夹" else "漫画文件",
            modifier = Modifier.size(38.dp),
            tint = Color.Unspecified,
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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
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
                )
                LinearProgressIndicator(
                    progress = { downloadProgress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("取消下载")
                }
            }
            if (!downloadError.isNullOrBlank()) {
                if (downloadProgress != null) {
                    HorizontalDivider()
                }
                Text(
                    text = downloadError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (message.isNotBlank()) {
                if (downloadProgress != null || !downloadError.isNullOrBlank()) {
                    HorizontalDivider()
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun webDavItemSupportingLabel(item: WebDavItem): String =
    if (item.isDirectory) "" else item.size?.let(::formatByteSize) ?: "大小未知"

internal fun shouldShowSaveDirectoryAction(isAddingPath: Boolean): Boolean = isAddingPath

private fun pathLabel(path: String): String = "路径 ${path.ifBlank { "/" }}"

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
