package com.example.comicdav.feature.filedirectory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.data.filedirectory.FileDirectorySource
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.feature.directorylisting.DirectoryListingTopBar
import com.example.comicdav.feature.directorylisting.DirectoryListingViewMode
import com.example.comicdav.feature.directorylisting.DirectoryVideoThumbnail
import com.example.comicdav.feature.directorylisting.DirectorySortField
import com.example.comicdav.feature.directorylisting.rememberDirectoryVideoArtworkModel
import com.example.comicdav.feature.directorylisting.shouldRequestDirectoryVideoThumbnail
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.ui.MuBoxDenseMediaRow
import com.example.comicdav.ui.MuBoxInlineMessage
import com.example.comicdav.ui.MuBoxMediaGridTile
import com.example.comicdav.ui.MuBoxMetrics
import com.example.comicdav.ui.MuBoxSourceRow
import com.example.comicdav.ui.muBoxAppBackground
import com.example.comicdav.ui.muBoxGlassSurface
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.webdav.decodeWebDavPathForDisplay

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

internal data class FileDirectorySourceGroups(
    val local: List<FileDirectorySource>,
    val webDav: List<FileDirectorySource>,
)

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

// 本地与 WebDAV 分组互不影响，任一组为空时另一组照常渲染（§8.5）。
internal fun groupFileDirectorySources(sources: List<FileDirectorySource>): FileDirectorySourceGroups =
    FileDirectorySourceGroups(
        local = sources.filter { it.sourceType == FileDirectorySourceType.LOCAL },
        webDav = sources.filter { it.sourceType == FileDirectorySourceType.WEBDAV },
    )

internal fun fileDirectorySourceTitle(source: FileDirectorySource): String =
    if (source.sourceType == FileDirectorySourceType.WEBDAV) {
        decodeWebDavPathForDisplay(source.displayName)
    } else {
        source.displayName
    }

internal fun fileDirectorySourceSubtitle(source: FileDirectorySource): String =
    when (source.sourceType) {
        FileDirectorySourceType.LOCAL -> fileDirectoryLocalPathSummary(source.localTreeUri)
        FileDirectorySourceType.WEBDAV -> source.webDavPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeWebDavPathForDisplay)
            ?: source.webDavBaseUrl
                ?.takeIf { it.isNotBlank() }
                ?.let(::decodeWebDavPathForDisplay)
            ?: "WebDAV 目录"
    }

// SAF tree URI 只展示 tree 之后的文档路径并做百分号解码，不直接展示完整 content:// 授权 URI。
internal fun fileDirectoryLocalPathSummary(treeUri: String?): String {
    val decoded = treeUri
        ?.takeIf { it.isNotBlank() }
        ?.let(::decodeWebDavPathForDisplay)
        ?: return "本地文件夹"
    val summary = decoded.substringAfter("/tree/", decoded).trim('/')
    return summary.ifBlank { "本地文件夹" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDirectoryScreen(
    uiState: FileDirectoryUiState,
    onAddLocalDirectory: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenSource: (FileDirectorySource) -> Unit,
    onOpenDirectory: (FileDirectoryBrowserItem) -> Unit,
    onOpenComic: (FileDirectoryBrowserItem) -> Unit,
    onOpenVideo: (FileDirectoryBrowserItem) -> Unit,
    onSelectComic: (FileDirectoryBrowserItem) -> Unit,
    onDismissMessage: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSortFieldChange: (DirectorySortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onToggleViewMode: () -> Unit,
    gridVideoThumbnailsEnabled: Boolean,
    onRequestVideoThumbnail: suspend (FileDirectoryBrowserItem) -> Unit,
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
            .muBoxAppBackground(colors),
    ) {
        val isBrowsing = uiState.currentTitle != null
        // 浏览态保留目录工具栏；根页不再显示应用内顶部标题，只补系统状态栏安全区。
        if (isBrowsing) {
            FileDirectoryBrowseHeader(
                breadcrumbLabels = uiState.breadcrumbLabels.ifEmpty { listOfNotNull(uiState.currentTitle) },
                searchQuery = uiState.searchQuery,
                sortField = uiState.sortField,
                sortDirection = uiState.sortDirection,
                viewMode = uiState.viewMode,
                onSearchQueryChange = onSearchQueryChange,
                onSortFieldChange = onSortFieldChange,
                onToggleSortDirection = onToggleSortDirection,
                onToggleViewMode = onToggleViewMode,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (isBrowsing) Modifier else Modifier.statusBarsPadding())
                .padding(
                    horizontal = if (isBrowsing) MuBoxMetrics.PageHorizontalPaddingDp else 0.dp,
                    vertical = if (isBrowsing) 12.dp else 4.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (isBrowsing) 12.dp else 8.dp),
        ) {
            if (uiState.message != null || uiState.error != null) {
                MuBoxInlineMessage(
                    text = uiState.error ?: uiState.message.orEmpty(),
                    isError = uiState.error != null,
                    onDismiss = onDismissMessage,
                    modifier = if (isBrowsing) {
                        Modifier
                    } else {
                        Modifier.padding(horizontal = MuBoxMetrics.PageHorizontalPaddingDp)
                    },
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (isBrowsing) {
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
                                viewMode = uiState.viewMode,
                                gridVideoThumbnailsEnabled = gridVideoThumbnailsEnabled,
                                videoThumbnails = uiState.videoThumbnails,
                                thumbnailRequestRevision = uiState.thumbnailRequestRevision,
                                onRequestVideoThumbnail = onRequestVideoThumbnail,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                } else {
                    SourceList(
                        sources = uiState.sources,
                        onAddLocalDirectory = onAddLocalDirectory,
                        onOpenWebDav = onOpenWebDav,
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
private fun FileDirectoryBrowseHeader(
    breadcrumbLabels: List<String>,
    searchQuery: String,
    sortField: DirectorySortField,
    sortDirection: com.example.comicdav.feature.directorylisting.DirectorySortDirection,
    viewMode: DirectoryListingViewMode,
    onSearchQueryChange: (String) -> Unit,
    onSortFieldChange: (DirectorySortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onToggleViewMode: () -> Unit,
) {
    DirectoryListingTopBar(
        breadcrumbLabels = breadcrumbLabels,
        searchQuery = searchQuery,
        sortField = sortField,
        sortDirection = sortDirection,
        viewMode = viewMode,
        onSearchQueryChange = onSearchQueryChange,
        onSortFieldChange = onSortFieldChange,
        onToggleSortDirection = onToggleSortDirection,
        onToggleViewMode = onToggleViewMode,
    )
}

@Composable
private fun SourceList(
    sources: List<FileDirectorySource>,
    onAddLocalDirectory: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenSource: (FileDirectorySource) -> Unit,
    onDeleteSource: (FileDirectorySource) -> Unit,
    onDeleteLocalSourceWithFiles: (FileDirectorySource) -> Unit,
    onEditWebDavSource: (FileDirectorySource) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sourceBeingManaged by remember { mutableStateOf<FileDirectorySource?>(null) }
    val groups = remember(sources) { groupFileDirectorySources(sources) }

    sourceBeingManaged?.let { source ->
        SourceManagementSheet(
            source = source,
            onDismiss = { sourceBeingManaged = null },
            onOpen = {
                sourceBeingManaged = null
                onOpenSource(source)
            },
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
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "local-sources") {
            LocalSourceSection(
                sources = groups.local,
                onAddLocalDirectory = onAddLocalDirectory,
                onOpenSource = onOpenSource,
                onManageSource = { sourceBeingManaged = it },
            )
        }
        item(key = "webdav-sources") {
            WebDavSourceSection(
                sources = groups.webDav,
                onOpenWebDav = onOpenWebDav,
                onOpenSource = onOpenSource,
                onManageSource = { sourceBeingManaged = it },
            )
        }
    }
}

@Composable
private fun LocalSourceSection(
    sources: List<FileDirectorySource>,
    onAddLocalDirectory: () -> Unit,
    onOpenSource: (FileDirectorySource) -> Unit,
    onManageSource: (FileDirectorySource) -> Unit,
) {
    SourcePanel(
        title = "本地",
        icon = Icons.Outlined.Folder,
        summary = "共 ${sources.size} 个来源",
        isEmpty = sources.isEmpty(),
        emptyText = "还没有本地来源。添加本地文件夹开始浏览漫画和视频。",
        actionLabel = ComicDavCopy.addLocalFolder,
        onAction = onAddLocalDirectory,
    ) {
        sources.forEach { source ->
            MuBoxSourceRow(
                icon = Icons.Outlined.Folder,
                name = fileDirectorySourceTitle(source),
                subtitle = fileDirectorySourceSubtitle(source),
                onClick = { onOpenSource(source) },
                onMoreClick = { onManageSource(source) },
                moreContentDescription = "管理来源：${fileDirectorySourceTitle(source)}",
            )
        }
    }
}

@Composable
private fun WebDavSourceSection(
    sources: List<FileDirectorySource>,
    onOpenWebDav: () -> Unit,
    onOpenSource: (FileDirectorySource) -> Unit,
    onManageSource: (FileDirectorySource) -> Unit,
) {
    SourcePanel(
        title = "WebDAV",
        icon = Icons.Outlined.Cloud,
        summary = "共 ${sources.size} 个来源",
        isEmpty = sources.isEmpty(),
        emptyText = "还没有 WebDAV 来源。添加 WebDAV 目录开始浏览云端漫画和视频。",
        actionLabel = "添加 WebDAV 来源",
        onAction = onOpenWebDav,
    ) {
        sources.forEach { source ->
            MuBoxSourceRow(
                icon = Icons.Outlined.Cloud,
                name = fileDirectorySourceTitle(source),
                subtitle = fileDirectorySourceSubtitle(source),
                onClick = { onOpenSource(source) },
                onMoreClick = { onManageSource(source) },
                moreContentDescription = "管理来源：${fileDirectorySourceTitle(source)}",
            )
        }
    }
}

// 分组列表面板（§8.2/§8.3）：16dp 圆角 + 1dp 边框，底部全宽添加按钮始终存在，空态时行区域换成说明文字。
@Composable
private fun SourcePanel(
    title: String,
    icon: ImageVector,
    summary: String,
    isEmpty: Boolean,
    emptyText: String,
    actionLabel: String,
    onAction: () -> Unit,
    rows: @Composable ColumnScope.() -> Unit,
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(MuBoxMetrics.RadiusLDp)
    Surface(
        modifier = Modifier
            .padding(horizontal = MuBoxMetrics.PageHorizontalPaddingDp)
            .fillMaxWidth()
            .muBoxGlassSurface(colors = colors, shape = shape),
        shape = shape,
        color = Color.Transparent,
        contentColor = colors.text,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.onAccentSoft,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.muted,
                )
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (isEmpty) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = rows,
                )
            }
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .heightIn(min = MuBoxMetrics.MinTouchTargetDp),
                shape = RoundedCornerShape(MuBoxMetrics.RadiusMDp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accentText),
                border = BorderStroke(1.dp, colors.border),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(text = actionLabel, maxLines = 1)
            }
        }
    }
}

// 来源管理面板（§8.4）：更多按钮打开，危险操作使用错误色并与普通操作空间分隔，执行前二次确认。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceManagementSheet(
    source: FileDirectorySource,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDeleteSource: () -> Unit,
    onDeleteLocalSourceWithFiles: () -> Unit,
    onEditWebDavSource: () -> Unit,
) {
    val colors = rememberMuBoxColors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isLocal = source.sourceType == FileDirectorySourceType.LOCAL
    var pendingConfirm by remember { mutableStateOf<SourceManagementAction?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = fileDirectorySourceTitle(source),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = fileDirectorySourceSubtitle(source),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(color = colors.separator)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SourceSheetAction(
                    icon = if (isLocal) Icons.Outlined.Folder else Icons.Outlined.Cloud,
                    label = ComicDavCopy.open,
                    tint = colors.text,
                    onClick = onOpen,
                )
                if (!isLocal) {
                    SourceSheetAction(
                        icon = Icons.Outlined.Edit,
                        label = "编辑连接",
                        tint = colors.text,
                        onClick = onEditWebDavSource,
                    )
                }
            }
            HorizontalDivider(color = colors.separator)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isLocal) {
                    SourceSheetAction(
                        icon = Icons.Outlined.RemoveCircleOutline,
                        label = "仅移除来源",
                        tint = colors.errorText,
                        onClick = { pendingConfirm = SourceManagementAction.RemoveSource },
                    )
                    SourceSheetAction(
                        icon = Icons.Outlined.DeleteForever,
                        label = "删除来源及本地文件",
                        tint = colors.errorText,
                        onClick = { pendingConfirm = SourceManagementAction.DeleteLocalSourceWithFiles },
                    )
                } else {
                    SourceSheetAction(
                        icon = Icons.Outlined.DeleteForever,
                        label = "删除来源",
                        tint = colors.errorText,
                        onClick = { pendingConfirm = SourceManagementAction.DeleteSource },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    pendingConfirm?.let { action ->
        SourceDeleteConfirmDialog(
            action = action,
            onConfirm = {
                pendingConfirm = null
                when (action) {
                    SourceManagementAction.RemoveSource,
                    SourceManagementAction.DeleteSource,
                    -> onDeleteSource()
                    SourceManagementAction.DeleteLocalSourceWithFiles -> onDeleteLocalSourceWithFiles()
                    SourceManagementAction.EditWebDav -> Unit
                }
                onDismiss()
            },
            onDismiss = { pendingConfirm = null },
        )
    }
}

@Composable
private fun SourceSheetAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MuBoxMetrics.MinTouchTargetDp),
    ) {
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Text(text = label, color = tint)
    }
}

@Composable
private fun SourceDeleteConfirmDialog(
    action: SourceManagementAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title: String
    val body: String
    val confirmLabel: String
    when (action) {
        SourceManagementAction.RemoveSource -> {
            title = "仅移除来源？"
            body = "只会从来源列表中移除，不会删除本地文件夹中的文件。"
            confirmLabel = "移除"
        }
        SourceManagementAction.DeleteLocalSourceWithFiles -> {
            title = "删除来源及本地文件？"
            body = "将删除该来源，并永久删除本地文件夹中的全部文件，无法恢复。"
            confirmLabel = "删除"
        }
        else -> {
            title = "删除来源？"
            body = "只会删除已保存的来源记录，不会影响 WebDAV 服务器上的文件。"
            confirmLabel = "删除"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = rememberMuBoxColors().errorText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
    viewMode: DirectoryListingViewMode,
    gridVideoThumbnailsEnabled: Boolean,
    videoThumbnails: Map<String, DirectoryVideoThumbnail>,
    thumbnailRequestRevision: Long,
    onRequestVideoThumbnail: suspend (FileDirectoryBrowserItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (viewMode) {
        DirectoryListingViewMode.LIST -> LazyColumn(
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
        DirectoryListingViewMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 144.dp),
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            gridItems(entries, key = { it.uri }) { entry ->
                val thumbnailVersion = fileDirectoryBrowserVideoThumbnailVersion(
                    item = entry,
                    requestRevision = thumbnailRequestRevision,
                )
                val thumbnail = videoThumbnails[entry.uri]
                    .takeIf { gridVideoThumbnailsEnabled }
                val artworkModel = rememberDirectoryVideoArtworkModel(
                    thumbnail = thumbnail,
                    expectedVersion = thumbnailVersion,
                    validationRevision = thumbnailRequestRevision,
                )
                if (
                    shouldRequestDirectoryVideoThumbnail(
                        enabled = gridVideoThumbnailsEnabled,
                        mediaKind = entry.mediaKind,
                        hasArtwork = artworkModel != null,
                    )
                ) {
                    LaunchedEffect(
                        entry.uri,
                        thumbnailVersion,
                        thumbnailRequestRevision,
                        gridVideoThumbnailsEnabled,
                        thumbnail?.path,
                        thumbnail?.artworkRevision,
                    ) {
                        onRequestVideoThumbnail(entry)
                    }
                }
                FileDirectoryEntryGridTile(
                    entry = entry,
                    artworkModel = artworkModel,
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
}

@Composable
private fun FileDirectoryEntryGridTile(
    entry: FileDirectoryBrowserItem,
    artworkModel: Any?,
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
    MuBoxMediaGridTile(
        title = entry.name,
        mediaKind = entry.mediaKind,
        artworkModel = artworkModel,
        subtitle = supportingLabel.ifBlank { null },
        selected = isSelected,
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
    )
}

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

    MuBoxDenseMediaRow(
        title = entry.name,
        mediaKind = entry.mediaKind,
        subtitle = supportingLabel.ifBlank { null },
        selected = isSelected,
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
    )
}

internal fun fileDirectoryEntrySupportingLabel(entry: FileDirectoryBrowserItem): String =
    if (entry.isDirectory) "" else entry.size?.let { "${it / 1024} KiB" } ?: "大小未知"

internal fun fileDirectoryEntryTypeContentDescription(mediaKind: MediaKind): String =
    com.example.comicdav.ui.muBoxMediaKindLabel(mediaKind)
