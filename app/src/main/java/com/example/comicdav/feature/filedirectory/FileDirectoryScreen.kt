package com.example.comicdav.feature.filedirectory

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.video.MediaKind
import com.example.comicdav.webdav.decodeWebDavPathForDisplay

private val FileDirectoryBackground = Color(0xFF070B16)
private val FileDirectoryPanel = Color(0xFF0D1424)
private val FileDirectoryPanelHigh = Color(0xFF121D31)
private val FileDirectoryRow = Color(0xFF101A2C)
private val FileDirectoryRowSelected = Color(0xFF123847)
private val FileDirectoryBorder = Color(0xFF243149)
private val FileDirectorySelectedBorder = Color(0xFF38BDF8)
private val FileDirectoryAccent = Color(0xFF38BDF8)
private val FileDirectoryAccentSoft = Color(0xFF164E63)
private val FileDirectoryPurple = Color(0xFFA78BFA)
private val FileDirectoryText = Color(0xFFE5EDF8)
private val FileDirectoryMuted = Color(0xFFAAB6CB)
private val FileDirectoryNoticeContainer = Color(0xFF172033)
private val FileDirectoryErrorContainer = Color(0xFF4A1720)
private val FileDirectoryErrorText = Color(0xFFFCA5A5)

private data class FileDirectoryIconColors(
    val container: Color,
    val content: Color,
)

internal enum class FileDirectoryEntryClickAction {
    OpenDirectory,
    OpenComic,
    OpenVideo,
    NoAction,
}

internal enum class FileDirectoryEntryMenuAction {
    AddToLibrary,
    AddToVideoLibrary,
}

internal enum class SourceManagementAction {
    EditWebDav,
    DeleteSource,
    RemoveSource,
    DeleteLocalSourceWithFiles,
}

internal fun fileDirectoryEntryClickAction(entry: FileDirectoryBrowserItem): FileDirectoryEntryClickAction =
    when (entry.mediaKind) {
        MediaKind.Directory -> FileDirectoryEntryClickAction.OpenDirectory
        MediaKind.Comic -> FileDirectoryEntryClickAction.OpenComic
        MediaKind.Video -> FileDirectoryEntryClickAction.OpenVideo
        MediaKind.Audio,
        MediaKind.Subtitle,
        MediaKind.Unknown,
        -> FileDirectoryEntryClickAction.NoAction
    }

internal fun fileDirectoryEntryLongPressActions(entry: FileDirectoryBrowserItem): List<FileDirectoryEntryMenuAction> =
    when (entry.mediaKind) {
        MediaKind.Comic -> listOf(FileDirectoryEntryMenuAction.AddToLibrary)
        MediaKind.Video -> listOf(FileDirectoryEntryMenuAction.AddToVideoLibrary)
        MediaKind.Directory,
        MediaKind.Audio,
        MediaKind.Subtitle,
        MediaKind.Unknown,
        -> emptyList()
    }

internal fun sourceManagementActions(source: FileDirectorySourceEntity): List<SourceManagementAction> {
    return when (source.sourceType) {
        FileDirectorySourceType.LOCAL -> listOf(
            SourceManagementAction.RemoveSource,
            SourceManagementAction.DeleteLocalSourceWithFiles,
        )
        FileDirectorySourceType.WEBDAV -> listOf(
            SourceManagementAction.EditWebDav,
            SourceManagementAction.DeleteSource,
        )
    }
}

internal fun fileDirectorySourceTitle(source: FileDirectorySourceEntity): String =
    if (source.sourceType == FileDirectorySourceType.WEBDAV) {
        decodeWebDavPathForDisplay(source.displayName)
    } else {
        source.displayName
    }

internal fun fileDirectorySourceSubtitle(source: FileDirectorySourceEntity): String =
    when (source.sourceType) {
        FileDirectorySourceType.LOCAL -> "本地文件夹"
        FileDirectorySourceType.WEBDAV -> source.webDavPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeWebDavPathForDisplay)
            ?: "WebDAV 目录"
    }

@Composable
fun FileDirectoryScreen(
    uiState: FileDirectoryUiState,
    onAddLocalDirectory: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSource: (FileDirectorySourceEntity) -> Unit,
    onOpenDirectory: (FileDirectoryBrowserItem) -> Unit,
    onOpenComic: (FileDirectoryBrowserItem) -> Unit,
    onOpenVideo: (FileDirectoryBrowserItem) -> Unit,
    onSelectComic: (FileDirectoryBrowserItem) -> Unit,
    onGoUp: () -> Unit,
    onCloseBrowser: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    selectedComic: FileDirectoryBrowserItem? = null,
    selectedVideo: FileDirectoryBrowserItem? = null,
    onSelectVideo: (FileDirectoryBrowserItem) -> Unit = onSelectComic,
    onDeleteSource: (FileDirectorySourceEntity) -> Unit = {},
    onDeleteLocalSourceWithFiles: (FileDirectorySourceEntity) -> Unit = {},
    onEditWebDavSource: (FileDirectorySourceEntity) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FileDirectoryBackground)
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
                    FileDirectoryNoticeContainer
                } else {
                    FileDirectoryErrorContainer
                },
                contentColor = if (uiState.error == null) FileDirectoryText else FileDirectoryErrorText,
                border = BorderStroke(
                    1.dp,
                    if (uiState.error == null) FileDirectoryBorder else FileDirectoryErrorText.copy(alpha = 0.35f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = uiState.error ?: uiState.message.orEmpty(),
                        modifier = Modifier.weight(1f),
                        color = if (uiState.error == null) FileDirectoryText else FileDirectoryErrorText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onDismissMessage) {
                        Text(
                            text = "知道了",
                            color = if (uiState.error == null) FileDirectoryAccent else FileDirectoryErrorText,
                        )
                    }
                }
            }
        }

        AnimatedContent(
            targetState = uiState.currentTitle != null,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "FileDirectoryContent",
        ) { isBrowsing ->
            if (isBrowsing) {
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = FileDirectoryAccent)
                    }
                } else {
                    EntryList(
                        entries = uiState.entries,
                        onOpenDirectory = onOpenDirectory,
                        onOpenComic = onOpenComic,
                        onOpenVideo = onOpenVideo,
                        onSelectComic = onSelectComic,
                        onSelectVideo = onSelectVideo,
                        selectedEntry = selectedVideo ?: selectedComic,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                SourceList(
                    sources = uiState.sources,
                    onOpenSource = onOpenSource,
                    onDeleteSource = onDeleteSource,
                    onDeleteLocalSourceWithFiles = onDeleteLocalSourceWithFiles,
                    onEditWebDavSource = onEditWebDavSource,
                    modifier = Modifier.fillMaxSize(),
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

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = FileDirectoryPanel,
        contentColor = FileDirectoryText,
        border = BorderStroke(1.dp, FileDirectoryBorder),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ComicDavCopy.sourcesTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = FileDirectoryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "管理本地文件夹和 WebDAV 目录,浏览后可把漫画加入书架。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FileDirectoryMuted,
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
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Text(
                        text = ComicDavCopy.libraryTitle,
                        color = FileDirectoryAccent,
                    )
                }
                Box {
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        FileDirectoryAccent,
                                        FileDirectoryPurple,
                                    ),
                                ),
                                shape = CircleShape,
                            ),
                        shape = CircleShape,
                        color = Color.Transparent,
                        shadowElevation = 4.dp,
                    ) {
                        IconButton(
                            onClick = { isAddMenuOpen = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "添加",
                                tint = Color.White,
                            )
                        }
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = FileDirectoryPanel,
            contentColor = FileDirectoryText,
            border = BorderStroke(1.dp, FileDirectoryBorder),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ComicDavCopy.sourcesTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        color = FileDirectoryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "浏览文件夹,选择漫画阅读或加入书架。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FileDirectoryMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = onGoUp,
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                color = FileDirectoryPanelHigh,
                                shape = CircleShape,
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = "上一级",
                            tint = FileDirectoryText,
                        )
                    }
                    IconButton(
                        onClick = onCloseBrowser,
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                color = FileDirectoryPanelHigh,
                                shape = CircleShape,
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = FileDirectoryText,
                        )
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = FileDirectoryPanelHigh,
            contentColor = FileDirectoryText,
            border = BorderStroke(1.dp, FileDirectoryAccent.copy(alpha = 0.32f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = FileDirectoryAccentSoft,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = FileDirectoryAccent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "当前位置",
                        style = MaterialTheme.typography.labelMedium,
                        color = FileDirectoryAccent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = FileDirectoryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    onEditWebDavSource: (FileDirectorySourceEntity) -> Unit,
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
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = FileDirectoryPanel,
                    contentColor = FileDirectoryMuted,
                    border = BorderStroke(1.dp, FileDirectoryBorder),
                ) {
                    Text(
                        text = "还没有保存来源。添加本地文件夹或 WebDAV 目录开始浏览。",
                        color = FileDirectoryMuted,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                }
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
            onEditWebDavSource = {
                sourceBeingManaged = null
                onEditWebDavSource(source)
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
                isSelected = sourceBeingManaged == source,
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
    isSelected: Boolean,
) {
    val isLocal = source.sourceType == FileDirectorySourceType.LOCAL
    val iconColors = sourceIconColors(isLocal)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "管理来源",
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) FileDirectoryRowSelected else FileDirectoryRow,
        contentColor = FileDirectoryText,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) FileDirectorySelectedBorder else FileDirectoryBorder,
        ),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = iconColors.container,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = iconColors.content,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = fileDirectorySourceTitle(source),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = FileDirectoryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    SourceBadge(
                        text = if (isLocal) "本地" else "WebDAV",
                    )
                }
                Text(
                    text = fileDirectorySourceSubtitle(source),
                    style = MaterialTheme.typography.bodySmall,
                    color = FileDirectoryMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onClick,
                modifier = Modifier.defaultMinSize(minHeight = 40.dp),
            ) {
                Text(
                    text = ComicDavCopy.open,
                    color = FileDirectoryAccent,
                )
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
    onEditWebDavSource: () -> Unit,
) {
    val actions = sourceManagementActions(source)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理来源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = source.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                actions.forEach { action ->
                    when (action) {
                        SourceManagementAction.EditWebDav -> {
                            TextButton(
                                onClick = onEditWebDavSource,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text("编辑 WebDAV")
                            }
                        }
                        SourceManagementAction.DeleteSource -> {
                            TextButton(
                                onClick = onDeleteSource,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text("删除来源")
                            }
                        }
                        SourceManagementAction.RemoveSource -> {
                            TextButton(
                                onClick = onDeleteSource,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text("仅移除来源")
                            }
                        }
                        SourceManagementAction.DeleteLocalSourceWithFiles -> {
                            TextButton(
                                onClick = onDeleteLocalSourceWithFiles,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text("同时删除源文件")
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
private fun EntryList(
    entries: List<FileDirectoryBrowserItem>,
    onOpenDirectory: (FileDirectoryBrowserItem) -> Unit,
    onOpenComic: (FileDirectoryBrowserItem) -> Unit,
    onOpenVideo: (FileDirectoryBrowserItem) -> Unit,
    onSelectComic: (FileDirectoryBrowserItem) -> Unit,
    onSelectVideo: (FileDirectoryBrowserItem) -> Unit,
    selectedEntry: FileDirectoryBrowserItem?,
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
                onOpenVideo = { onOpenVideo(entry) },
                onSelectComic = { onSelectComic(entry) },
                onSelectVideo = { onSelectVideo(entry) },
                isSelected = selectedEntry?.uri == entry.uri,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileDirectoryEntryRow(
    entry: FileDirectoryBrowserItem,
    onOpenDirectory: () -> Unit,
    onOpenComic: () -> Unit,
    onOpenVideo: () -> Unit,
    onSelectComic: () -> Unit,
    onSelectVideo: () -> Unit,
    isSelected: Boolean,
) {
    val longPressActions = fileDirectoryEntryLongPressActions(entry)
    val clickAction = fileDirectoryEntryClickAction(entry)
    val supportingLabel = fileDirectoryEntrySupportingLabel(entry)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    when (clickAction) {
                        FileDirectoryEntryClickAction.OpenDirectory -> onOpenDirectory()
                        FileDirectoryEntryClickAction.OpenComic -> onOpenComic()
                        FileDirectoryEntryClickAction.OpenVideo -> onOpenVideo()
                        FileDirectoryEntryClickAction.NoAction -> Unit
                    }
                },
                onLongClick = longPressActions.takeIf { it.isNotEmpty() }?.let {
                    {
                        when (longPressActions.first()) {
                            FileDirectoryEntryMenuAction.AddToLibrary -> onSelectComic()
                            FileDirectoryEntryMenuAction.AddToVideoLibrary -> onSelectVideo()
                        }
                    }
                },
                onLongClickLabel = if (longPressActions.isEmpty()) null else "文件操作",
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) {
            FileDirectoryRowSelected
        } else {
            FileDirectoryRow
        },
        contentColor = FileDirectoryText,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) FileDirectorySelectedBorder else FileDirectoryBorder,
        ),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EntryTypeIcon(mediaKind = entry.mediaKind)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = FileDirectoryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supportingLabel.isNotBlank()) {
                    Text(
                        text = supportingLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = FileDirectoryMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal fun fileDirectoryEntrySupportingLabel(entry: FileDirectoryBrowserItem): String =
    if (entry.isDirectory) "" else entry.size?.let { "${it / 1024} KiB" } ?: "大小未知"

@Composable
private fun EntryTypeIcon(mediaKind: MediaKind) {
    val colors = entryIconColors(mediaKind)
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
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = FileDirectoryMuted,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun SourceBadge(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = FileDirectoryAccentSoft,
        contentColor = FileDirectoryAccent,
        border = BorderStroke(1.dp, FileDirectoryAccent.copy(alpha = 0.35f)),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun sourceIconColors(isLocal: Boolean): FileDirectoryIconColors =
    if (isLocal) {
        FileDirectoryIconColors(
            container = Color(0xFF0D344B),
            content = Color(0xFF7DD3FC),
        )
    } else {
        FileDirectoryIconColors(
            container = Color(0xFF2E244F),
            content = Color(0xFFC4B5FD),
        )
    }

private fun entryIconColors(mediaKind: MediaKind): FileDirectoryIconColors =
    when (mediaKind) {
        MediaKind.Directory -> FileDirectoryIconColors(
            container = Color(0xFF0D344B),
            content = Color(0xFF7DD3FC),
        )
        MediaKind.Comic -> FileDirectoryIconColors(
            container = Color(0xFF2E244F),
            content = Color(0xFFC4B5FD),
        )
        MediaKind.Video -> FileDirectoryIconColors(
            container = Color(0xFF073B43),
            content = Color(0xFF67E8F9),
        )
        MediaKind.Subtitle -> FileDirectoryIconColors(
            container = Color(0xFF3F3215),
            content = Color(0xFFFDE68A),
        )
        MediaKind.Audio,
        MediaKind.Unknown,
        -> FileDirectoryIconColors(
            container = Color(0xFF263247),
            content = Color(0xFFCBD5E1),
        )
    }
