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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.data.filedirectory.FileDirectorySource
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.feature.directorylisting.DirectoryListingTopBar
import com.example.comicdav.feature.directorylisting.DirectorySortField
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.ui.MuBoxColors
import com.example.comicdav.ui.MuBoxHeaderBar
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.webdav.decodeWebDavPathForDisplay



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

internal fun sourceManagementActions(source: FileDirectorySource): List<SourceManagementAction> {
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

internal fun fileDirectorySourceTitle(source: FileDirectorySource): String =
    if (source.sourceType == FileDirectorySourceType.WEBDAV) {
        decodeWebDavPathForDisplay(source.displayName)
    } else {
        source.displayName
    }

internal fun fileDirectorySourceSubtitle(source: FileDirectorySource): String =
    when (source.sourceType) {
        FileDirectorySourceType.LOCAL -> "本地文件夹"
        FileDirectorySourceType.WEBDAV -> source.webDavPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeWebDavPathForDisplay)
            ?: "WebDAV 目录"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDirectoryScreen(
    uiState: FileDirectoryUiState,
    onAddLocalDirectory: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSource: (FileDirectorySource) -> Unit,
    onOpenDirectory: (FileDirectoryBrowserItem) -> Unit,
    onOpenComic: (FileDirectoryBrowserItem) -> Unit,
    onOpenVideo: (FileDirectoryBrowserItem) -> Unit,
    onSelectComic: (FileDirectoryBrowserItem) -> Unit,
    onDismissMessage: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSortFieldChange: (DirectorySortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    selectedComic: FileDirectoryBrowserItem? = null,
    selectedVideo: FileDirectoryBrowserItem? = null,
    onSelectVideo: (FileDirectoryBrowserItem) -> Unit = onSelectComic,
    onDeleteSource: (FileDirectorySource) -> Unit = {},
    onDeleteLocalSourceWithFiles: (FileDirectorySource) -> Unit = {},
    onEditWebDavSource: (FileDirectorySource) -> Unit = {},
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        val isBrowsing = uiState.currentTitle != null
        if (isBrowsing) {
            FileDirectoryBrowseHeader(
                breadcrumbLabels = uiState.breadcrumbLabels.ifEmpty { listOfNotNull(uiState.currentTitle) },
                searchQuery = uiState.searchQuery,
                sortField = uiState.sortField,
                sortDirection = uiState.sortDirection,
                onSearchQueryChange = onSearchQueryChange,
                onSortFieldChange = onSortFieldChange,
                onToggleSortDirection = onToggleSortDirection,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(
                    horizontal = 16.dp,
                    vertical = if (isBrowsing) 12.dp else 14.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (isBrowsing) 12.dp else 14.dp),
        ) {
            if (!isBrowsing) {
                FileDirectoryHomeHeader(
                    onAddLocalDirectory = onAddLocalDirectory,
                    onOpenWebDav = onOpenWebDav,
                    onOpenLibrary = onOpenLibrary,
                )
            }

            if (uiState.message != null || uiState.error != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (uiState.error == null) {
                        colors.panelHigh
                    } else {
                        colors.errorSurface
                    },
                    contentColor = if (uiState.error == null) colors.text else colors.errorText,
                    border = BorderStroke(
                        1.dp,
                        if (uiState.error == null) colors.border else colors.errorText.copy(alpha = 0.35f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = uiState.error ?: uiState.message.orEmpty(),
                            modifier = Modifier.weight(1f),
                            color = if (uiState.error == null) colors.text else colors.errorText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = onDismissMessage) {
                            Text(
                                text = "知道了",
                                color = if (uiState.error == null) colors.mediaAccent else colors.errorText,
                            )
                        }
                    }
                }
            }

            AnimatedContent(
                targetState = isBrowsing,
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "FileDirectoryContent",
            ) { browsing ->
                if (browsing) {
                    if (uiState.isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = colors.mediaAccent)
                        }
                    } else {
                        PullToRefreshBox(
                            isRefreshing = uiState.isRefreshing,
                            onRefresh = onRefresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
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
}

@Composable
private fun FileDirectoryHomeHeader(
    onAddLocalDirectory: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    var isAddMenuOpen by remember { mutableStateOf(false) }
    val colors = rememberMuBoxColors()

    MuBoxHeaderBar(
        title = ComicDavCopy.sourcesTitle,
        actions = {
            TextButton(
                onClick = onOpenLibrary,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Text(
                    text = ComicDavCopy.libraryTitle,
                    color = colors.mediaAccent,
                )
            }
            Box {
                IconButton(
                    onClick = { isAddMenuOpen = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "添加",
                        tint = colors.mediaAccent,
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
        },
    )
}

@Composable
private fun FileDirectoryBrowseHeader(
    breadcrumbLabels: List<String>,
    searchQuery: String,
    sortField: DirectorySortField,
    sortDirection: com.example.comicdav.feature.directorylisting.DirectorySortDirection,
    onSearchQueryChange: (String) -> Unit,
    onSortFieldChange: (DirectorySortField) -> Unit,
    onToggleSortDirection: () -> Unit,
) {
    DirectoryListingTopBar(
        breadcrumbLabels = breadcrumbLabels,
        searchQuery = searchQuery,
        sortField = sortField,
        sortDirection = sortDirection,
        onSearchQueryChange = onSearchQueryChange,
        onSortFieldChange = onSortFieldChange,
        onToggleSortDirection = onToggleSortDirection,
    )
}

@Composable
private fun SourceList(
    sources: List<FileDirectorySource>,
    onOpenSource: (FileDirectorySource) -> Unit,
    onDeleteSource: (FileDirectorySource) -> Unit,
    onDeleteLocalSourceWithFiles: (FileDirectorySource) -> Unit,
    onEditWebDavSource: (FileDirectorySource) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sourceBeingManaged by remember { mutableStateOf<FileDirectorySource?>(null) }
    val colors = rememberMuBoxColors()

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
                    color = colors.panel,
                    contentColor = colors.muted,
                    border = BorderStroke(1.dp, colors.border),
                ) {
                    Text(
                        text = "还没有保存来源。添加本地文件夹或 WebDAV 目录开始浏览。",
                        color = colors.muted,
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
    source: FileDirectorySource,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean,
) {
    val isLocal = source.sourceType == FileDirectorySourceType.LOCAL
    val colors = rememberMuBoxColors()
    val iconColors = sourceIconColors(isLocal, colors)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "管理来源",
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) colors.rowSelected else colors.row,
        contentColor = colors.text,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) colors.selectedBorder else colors.border,
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
                        color = colors.text,
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
                    color = colors.muted,
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
                    color = colors.mediaAccent,
                )
            }
        }
    }
}

@Composable
private fun SourceManagementDialog(
    source: FileDirectorySource,
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
    val colors = rememberMuBoxColors()

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
            colors.rowSelected
        } else {
            colors.row
        },
        contentColor = colors.text,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) colors.selectedBorder else colors.border,
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

internal fun fileDirectoryEntrySupportingLabel(entry: FileDirectoryBrowserItem): String =
    if (entry.isDirectory) "" else entry.size?.let { "${it / 1024} KiB" } ?: "大小未知"

@Composable
private fun EntryTypeIcon(mediaKind: MediaKind) {
    val screenColors = rememberMuBoxColors()
    val colors = entryIconColors(mediaKind, screenColors)
    val icon = when (mediaKind) {
        MediaKind.Directory -> Icons.Rounded.Folder
        MediaKind.Video -> Icons.Rounded.PlayCircle
        MediaKind.Subtitle -> Icons.Rounded.Subtitles
        else -> Icons.AutoMirrored.Rounded.MenuBook
    }
    val contentDescription = fileDirectoryEntryTypeContentDescription(mediaKind)

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
    val colors = rememberMuBoxColors()
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.muted,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun SourceBadge(text: String) {
    val colors = rememberMuBoxColors()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors.accentSoft,
        contentColor = colors.onAccentSoft,
        border = BorderStroke(1.dp, colors.mediaAccent.copy(alpha = 0.35f)),
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

private fun sourceIconColors(
    isLocal: Boolean,
    colors: MuBoxColors,
): FileDirectoryIconColors =
    if (isLocal) {
        FileDirectoryIconColors(
            container = colors.accentSoft,
            content = colors.onAccentSoft,
        )
    } else {
        FileDirectoryIconColors(
            container = colors.panelHigh,
            content = colors.comicAccent,
        )
    }

private fun entryIconColors(
    mediaKind: MediaKind,
    colors: MuBoxColors,
): FileDirectoryIconColors =
    when (mediaKind) {
        MediaKind.Directory -> FileDirectoryIconColors(
            container = colors.accentSoft,
            content = colors.onAccentSoft,
        )
        MediaKind.Comic -> FileDirectoryIconColors(
            container = colors.panelHigh,
            content = colors.comicAccent,
        )
        MediaKind.Video -> FileDirectoryIconColors(
            container = colors.accentSoft,
            content = colors.onAccentSoft,
        )
        MediaKind.Subtitle -> FileDirectoryIconColors(
            container = colors.panelHigh,
            content = colors.mediaAccent,
        )
        MediaKind.Audio,
        MediaKind.Unknown,
        -> FileDirectoryIconColors(
            container = colors.panelHigh,
            content = colors.muted,
        )
    }

internal fun fileDirectoryEntryTypeContentDescription(mediaKind: MediaKind): String =
    com.example.comicdav.ui.muBoxMediaKindLabel(mediaKind)
