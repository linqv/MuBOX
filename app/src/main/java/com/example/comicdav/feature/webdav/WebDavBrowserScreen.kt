package com.example.comicdav.feature.webdav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.ui.ComicDavCopy

@Composable
fun WebDavBrowserScreen(
    uiState: WebDavUiState,
    onItemClick: (WebDavItem) -> Unit,
    onSelectItem: (WebDavItem) -> Unit,
    onAddToLibrary: (WebDavItem) -> Unit,
    onSaveDirectory: () -> Unit,
    onBackToDirectories: () -> Unit,
    onProbeTail: () -> Unit,
    downloadProgress: DownloadProgressUi?,
    downloadError: String?,
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
                    selected = uiState.selectedItem == item,
                    onOpen = { onItemClick(item) },
                    onSelect = { onSelectItem(item) },
                    onAddToLibrary = { onAddToLibrary(item) },
                )
            }
        }

        WebDavDiagnosticsPanel(
            selectedItem = uiState.selectedItem,
            isLoading = uiState.isLoading,
            diagnostic = uiState.diagnostic,
            downloadProgress = downloadProgress,
            downloadError = downloadError,
            onProbeTail = onProbeTail,
            onCancelDownload = onCancelDownload,
        )
    }
}

@Composable
private fun WebDavBrowserAppBar(
    currentPath: String,
    isLoading: Boolean,
    onBackToDirectories: () -> Unit,
    onSaveDirectory: () -> Unit,
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

@Composable
private fun WebDavItemRow(
    item: WebDavItem,
    selected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onAddToLibrary: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (item.isDirectory) onOpen() else onSelect() },
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SourceBadge(item = item)
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
                Text(
                    text = if (item.isDirectory) "文件夹" else fileMetaLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.isDirectory) {
                TextButton(
                    onClick = onOpen,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(ComicDavCopy.open)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onAddToLibrary,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(ComicDavCopy.addToLibrary)
                    }
                    Button(
                        onClick = onOpen,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(ComicDavCopy.read)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(item: WebDavItem) {
    val color = if (item.isDirectory) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (item.isDirectory) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = Modifier.size(48.dp),
        shape = MaterialTheme.shapes.small,
        color = color,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (item.isDirectory) "目录" else item.extensionBadge(),
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun WebDavDiagnosticsPanel(
    selectedItem: WebDavItem?,
    isLoading: Boolean,
    diagnostic: String,
    downloadProgress: DownloadProgressUi?,
    downloadError: String?,
    onProbeTail: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedItem?.name ?: "未选择漫画",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = selectedItem?.let {
                            if (it.isDirectory) "目录可直接打开" else fileMetaLabel(it)
                        } ?: "选择漫画后可阅读、加入书架或检查远程读取能力",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(
                    onClick = onProbeTail,
                    enabled = selectedItem?.isDirectory == false && !isLoading && downloadProgress == null,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("诊断")
                }
            }

            if (downloadProgress != null) {
                HorizontalDivider()
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
                Text(
                    text = downloadError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (diagnostic.isNotBlank()) {
                HorizontalDivider()
                Text(
                    text = diagnostic,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun fileMetaLabel(item: WebDavItem): String {
    val size = item.size?.let(::formatByteSize) ?: "大小未知"
    val validator = item.etag?.takeIf { it.isNotBlank() }?.let { "ETag $it" }
        ?: item.lastModified?.let { "修改于 $it" }
        ?: "无校验信息"
    return "$size · $validator"
}

private fun pathLabel(path: String): String = "路径 ${path.ifBlank { "/" }}"

private fun WebDavItem.extensionBadge(): String {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    return extension.takeIf { it.length in 2..4 }?.uppercase() ?: "漫画"
}

private fun formatByteSize(bytes: Long): String {
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
