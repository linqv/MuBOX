package com.example.comicdav.feature.filedirectory

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.ui.ComicDavCopy

@Composable
fun FileDirectoryScreen(
    uiState: FileDirectoryUiState,
    onAddLocalDirectory: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSource: (FileDirectorySourceEntity) -> Unit,
    onOpenDirectory: (FileDirectoryBrowserItem) -> Unit,
    onOpenComic: (FileDirectoryBrowserItem) -> Unit,
    onFavoriteComic: (FileDirectoryBrowserItem) -> Unit,
    onGoUp: () -> Unit,
    onCloseBrowser: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteSource: (FileDirectorySourceEntity) -> Unit = {},
    onDeleteLocalSourceWithFiles: (FileDirectorySourceEntity) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (uiState.currentTitle == null) {
            FileDirectoryHomeHeader(
                onAddLocalDirectory = onAddLocalDirectory,
                onOpenWebDav = onOpenWebDav,
                onOpenLibrary = onOpenLibrary,
            )
        } else {
            FileDirectoryBrowseHeader(
                title = uiState.currentTitle,
                onGoUp = onGoUp,
                onCloseBrowser = onCloseBrowser,
            )
        }

        if (uiState.message != null || uiState.error != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (uiState.error == null) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = uiState.error ?: uiState.message.orEmpty(),
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onDismissMessage) {
                        Text("知道了")
                    }
                }
            }
        }

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.currentTitle == null -> {
                SourceList(
                    sources = uiState.sources,
                    onOpenSource = onOpenSource,
                    onDeleteSource = onDeleteSource,
                    onDeleteLocalSourceWithFiles = onDeleteLocalSourceWithFiles,
                    modifier = Modifier.weight(1f),
                )
            }

            else -> {
                EntryList(
                    entries = uiState.entries,
                    onOpenDirectory = onOpenDirectory,
                    onOpenComic = onOpenComic,
                    onFavoriteComic = onFavoriteComic,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FileDirectoryHomeHeader(
    onAddLocalDirectory: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    var isAddMenuOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ComicDavCopy.sourcesTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "管理本地文件夹和 WebDAV 目录，浏览后可把漫画加入书架。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(ComicDavCopy.libraryTitle)
                }
                Box {
                    IconButton(
                        onClick = { isAddMenuOpen = true },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text(
                            text = "+",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    DropdownMenu(
                        expanded = isAddMenuOpen,
                        onDismissRequest = { isAddMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("添加本地文件夹") },
                            onClick = {
                                isAddMenuOpen = false
                                onAddLocalDirectory()
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        )
                        DropdownMenuItem(
                            text = { Text("添加 WebDAV") },
                            onClick = {
                                isAddMenuOpen = false
                                onOpenWebDav()
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDirectoryBrowseHeader(
    title: String,
    onGoUp: () -> Unit,
    onCloseBrowser: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ComicDavCopy.sourcesTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "浏览文件夹，选择漫画阅读或加入书架。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onGoUp,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("上一级")
                }
                TextButton(
                    onClick = onCloseBrowser,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(ComicDavCopy.sourcesTitle)
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = "当前位置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SourceList(
    sources: List<FileDirectorySourceEntity>,
    onOpenSource: (FileDirectorySourceEntity) -> Unit,
    onDeleteSource: (FileDirectorySourceEntity) -> Unit,
    onDeleteLocalSourceWithFiles: (FileDirectorySourceEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sourceBeingManaged by remember { mutableStateOf<FileDirectorySourceEntity?>(null) }

    if (sources.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(text = ComicDavCopy.savedSources)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有保存来源。添加本地文件夹或 WebDAV 目录开始浏览。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    sourceBeingManaged?.let { source ->
        SourceManagementDialog(
            source = source,
            onDismiss = { sourceBeingManaged = null },
            onDeleteSource = {
                sourceBeingManaged = null
                onDeleteSource(source)
            },
            onDeleteLocalSourceWithFiles = {
                sourceBeingManaged = null
                onDeleteLocalSourceWithFiles(source)
            },
        )
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionTitle(text = ComicDavCopy.savedSources)
        }
        items(sources, key = { it.id }) { source ->
            DirectorySourceRow(
                source = source,
                onClick = { onOpenSource(source) },
                onLongClick = { sourceBeingManaged = source },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DirectorySourceRow(
    source: FileDirectorySourceEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "删除来源",
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SourceBadge(
                text = when (source.sourceType) {
                    FileDirectorySourceType.LOCAL -> "本地"
                    FileDirectorySourceType.WEBDAV -> "WebDAV"
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (source.sourceType) {
                        FileDirectorySourceType.LOCAL -> "本地文件夹"
                        FileDirectorySourceType.WEBDAV -> source.webDavPath?.takeIf { it.isNotBlank() } ?: "WebDAV 目录"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onClick,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text(ComicDavCopy.open)
            }
        }
    }
}

@Composable
private fun SourceManagementDialog(
    source: FileDirectorySourceEntity,
    onDismiss: () -> Unit,
    onDeleteSource: () -> Unit,
    onDeleteLocalSourceWithFiles: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除来源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = source.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (source.sourceType == FileDirectorySourceType.LOCAL) {
                    TextButton(
                        onClick = onDeleteSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text("仅移除来源")
                    }
                    TextButton(
                        onClick = onDeleteLocalSourceWithFiles,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text("同时删除源文件")
                    }
                } else {
                    TextButton(
                        onClick = onDeleteSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text("删除来源")
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
private fun EntryList(
    entries: List<FileDirectoryBrowserItem>,
    onOpenDirectory: (FileDirectoryBrowserItem) -> Unit,
    onOpenComic: (FileDirectoryBrowserItem) -> Unit,
    onFavoriteComic: (FileDirectoryBrowserItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionTitle(text = "当前目录")
        }
        items(entries, key = { it.uri }) { entry ->
            FileDirectoryEntryRow(
                entry = entry,
                onOpenDirectory = { onOpenDirectory(entry) },
                onOpenComic = { onOpenComic(entry) },
                onFavoriteComic = { onFavoriteComic(entry) },
            )
        }
    }
}

@Composable
private fun FileDirectoryEntryRow(
    entry: FileDirectoryBrowserItem,
    onOpenDirectory: () -> Unit,
    onOpenComic: () -> Unit,
    onFavoriteComic: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SourceBadge(text = if (entry.isDirectory) "文件夹" else "漫画")
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) {
                        "打开后继续浏览"
                    } else {
                        entry.size?.let { "${it / 1024} KiB" } ?: "CBZ / ZIP 文件"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.isDirectory) {
                Button(
                    onClick = onOpenDirectory,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(ComicDavCopy.open)
                }
            } else {
                OutlinedButton(
                    onClick = onFavoriteComic,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .widthIn(min = 96.dp, max = 116.dp),
                ) {
                    Text(ComicDavCopy.addToLibrary)
                }
                Button(
                    onClick = onOpenComic,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(ComicDavCopy.read)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun SourceBadge(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 56.dp, minHeight = 28.dp)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
