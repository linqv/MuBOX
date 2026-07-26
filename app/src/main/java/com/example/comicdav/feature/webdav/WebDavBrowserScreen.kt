package com.example.comicdav.feature.webdav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.core.model.transfer.TransferProgress
import com.example.comicdav.core.remote.WebDavItem
import com.example.comicdav.feature.directorylisting.DirectoryListingTopBar
import com.example.comicdav.feature.directorylisting.DirectorySortField
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.ui.MuBoxDenseMediaRow
import com.example.comicdav.ui.MuBoxMetrics
import com.example.comicdav.ui.muBoxAppBackground
import com.example.comicdav.ui.muBoxGradientBorder
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.webdav.decodeWebDavPathForDisplay

internal fun webDavBreadcrumbLabels(path: String): List<String> =
    path.split('/')
        .filter { it.isNotBlank() }
        .map(::decodeWebDavPathForDisplay)
        .ifEmpty { listOf("根目录") }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavBrowserScreen(
    uiState: WebDavUiState,
    onItemClick: (WebDavItem) -> Unit,
    onAddToLibrary: (WebDavItem) -> Unit,
    onDownloadToLocal: (WebDavItem) -> Unit,
    onSelectFile: (WebDavItem) -> Unit,
    onSaveDirectory: () -> Unit,
    showSaveDirectoryAction: Boolean,
    downloadProgress: TransferProgress?,
    downloadError: String?,
    actionMessage: String?,
    onCancelDownload: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSortFieldChange: (DirectorySortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    selectedFile: WebDavItem? = null,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .muBoxAppBackground(colors),
    ) {
        DirectoryListingTopBar(
            breadcrumbLabels = webDavBreadcrumbLabels(uiState.currentPath),
            searchQuery = uiState.searchQuery,
            sortField = uiState.sortField,
            sortDirection = uiState.sortDirection,
            onSearchQueryChange = onSearchQueryChange,
            onSortFieldChange = onSortFieldChange,
            onToggleSortDirection = onToggleSortDirection,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (shouldShowSaveDirectoryAction(showSaveDirectoryAction)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = onSaveDirectory,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        border = BorderStroke(1.dp, if (uiState.isLoading) colors.border else colors.mediaAccent.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.mediaAccent,
                            disabledContentColor = colors.muted.copy(alpha = 0.55f),
                        ),
                    ) {
                        Text(ComicDavCopy.saveCurrentDirectory)
                    }
                }
            }

            if (uiState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.mediaAccent,
                    trackColor = colors.playerProgressTrack,
                )
            }

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f),
            ) {
                AnimatedContent(
                    targetState = uiState.items,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "WebDavListContent",
                ) { items ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items) { item ->
                            val clickAction = webDavItemClickAction(item)
                            val longPressActions = webDavItemLongPressActions(item)
                            val supportingLabel = webDavItemSupportingLabel(item)
                            val isSelected = selectedFile?.path == item.path
                            MuBoxDenseMediaRow(
                                title = item.name,
                                mediaKind = item.mediaKind,
                                onClick = {
                                    when (clickAction) {
                                        WebDavItemClickAction.OpenDirectory -> onItemClick(item)
                                        WebDavItemClickAction.OpenComic -> onItemClick(item)
                                        WebDavItemClickAction.OpenVideo -> onItemClick(item)
                                        WebDavItemClickAction.NoAction -> Unit
                                    }
                                },
                                subtitle = supportingLabel.ifBlank { null },
                                selected = isSelected,
                                onLongClick = longPressActions.takeIf { it.isNotEmpty() }?.let { { onSelectFile(item) } },
                                onLongClickLabel = if (longPressActions.isEmpty()) null else "文件操作",
                            )
                        }
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
}

@Composable
private fun WebDavTransferPanel(
    message: String,
    downloadProgress: TransferProgress?,
    downloadError: String?,
    onCancelDownload: () -> Unit,
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(MuBoxMetrics.BoxedListCornerDp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .muBoxGradientBorder(colors = colors, shape = shape),
        shape = shape,
        color = colors.panel,
        contentColor = colors.text,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (downloadProgress != null) {
                Text(
                    text = downloadProgress.downloadLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                )
                LinearProgressIndicator(
                    progress = { downloadProgress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.mediaAccent,
                    trackColor = colors.playerProgressTrack,
                )
                TextButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.mediaAccent),
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

private val TransferProgress.downloadLabel: String
    get() = "正在下载 ${downloadedBytes / 1024} KiB / ${totalBytes / 1024} KiB"
