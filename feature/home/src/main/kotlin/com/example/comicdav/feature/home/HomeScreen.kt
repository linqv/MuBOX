package com.example.comicdav.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.history.WatchMediaType
import com.example.comicdav.core.model.history.resolvedHistoryArtworkPath
import com.example.comicdav.core.model.library.LibraryItemWithSources
import com.example.comicdav.core.model.library.SourceType
import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoSourceType
import com.example.comicdav.ui.HistoryEntryRow
import com.example.comicdav.ui.MuBoxEmptyState
import com.example.comicdav.ui.MuBoxHeaderBar
import com.example.comicdav.ui.MuBoxMetrics
import com.example.comicdav.ui.MuBoxSection
import com.example.comicdav.ui.muBoxAppBackground
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.ui.decodeWebDavPathForDisplay
import kotlin.math.roundToInt

// 首页内部二级页（§5.1）：书架与影视库完整列表不再占据顶层 tab。
internal enum class HomeSubPage {
    ROOT,
    HISTORY,
    LIBRARY,
    VIDEO_LIBRARY,
}

internal data class HomeSearchResults(
    val history: List<WatchHistoryEntry>,
    val comics: List<LibraryItemWithSources>,
    val videos: List<VideoLibraryItemWithSources>,
) {
    val isEmpty: Boolean
        get() = history.isEmpty() && comics.isEmpty() && videos.isEmpty()
}

// 首页搜索只过滤应用已持有的数据（§7.2），不触发文件系统或 WebDAV 请求。
internal fun filterHomeSearchResults(
    query: String,
    history: List<WatchHistoryEntry>,
    comics: List<LibraryItemWithSources>,
    videos: List<VideoLibraryItemWithSources>,
): HomeSearchResults {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        return HomeSearchResults(emptyList(), emptyList(), emptyList())
    }
    return HomeSearchResults(
        history = history.filter { it.displayTitle.contains(trimmed, ignoreCase = true) },
        comics = comics.filter { it.item.displayName.contains(trimmed, ignoreCase = true) },
        videos = videos.filter { it.item.displayName.contains(trimmed, ignoreCase = true) },
    )
}

// 最近记录进度文案（§7.3）：总量未知时只显示当前进度，不显示百分比。
internal fun homeHistoryProgressLabel(entry: WatchHistoryEntry): String =
    when (entry.mediaType) {
        WatchMediaType.COMIC ->
            if (entry.total > 0L) {
                "第 ${entry.progress.coerceAtLeast(1L)} / ${entry.total} 页"
            } else {
                "第 ${entry.progress.coerceAtLeast(1L)} 页"
            }
        WatchMediaType.VIDEO ->
            if (entry.total > 0L) {
                "${formatHomeVideoDuration(entry.progress)} / ${formatHomeVideoDuration(entry.total)}"
            } else {
                formatHomeVideoDuration(entry.progress)
            }
    }

internal fun homeHistoryProgressPercentLabel(entry: WatchHistoryEntry): String =
    if (entry.total > 0L) {
        "${(entry.progressFraction * 100f).roundToInt()}%"
    } else {
        "--%"
    }

internal fun formatHomeVideoDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

internal fun homeLibrarySourceLabel(sourceType: SourceType): String =
    when (sourceType) {
        SourceType.LOCAL -> "本地"
        SourceType.WEBDAV -> "WebDAV"
    }

internal fun homeVideoSourceLabel(sourceType: VideoSourceType): String =
    when (sourceType) {
        VideoSourceType.LOCAL -> "本地"
        VideoSourceType.WEBDAV -> "WebDAV"
    }

// 书架预览副信息（§7.4）：优先页数，退化为来源信息。
internal fun homeLibrarySubtitle(item: LibraryItemWithSources): String {
    val pageCount = item.item.pageCount
    if (pageCount != null && pageCount > 0) {
        return "$pageCount 页"
    }
    return when (item.item.sourceType) {
        SourceType.LOCAL -> item.localSource?.fileName ?: "本地文件"
        SourceType.WEBDAV -> item.webDavSource?.remotePath?.let(::decodeWebDavPathForDisplay) ?: "WebDAV"
    }
}

internal fun homeVideoLibrarySubtitle(item: VideoLibraryItemWithSources): String =
    when (item.item.sourceType) {
        VideoSourceType.LOCAL -> item.localSource?.fileName ?: "本地视频"
        VideoSourceType.WEBDAV -> item.webDavSource?.remotePath?.let(::decodeWebDavPathForDisplay) ?: "WebDAV"
    }

internal fun homeHistoryCoverPath(
    entry: WatchHistoryEntry,
    comics: List<LibraryItemWithSources>,
    videos: List<VideoLibraryItemWithSources>,
): String? =
    resolvedHistoryArtworkPath(entry, comics, videos)

@Composable
fun HomeScreen(
    history: List<WatchHistoryEntry>,
    libraryItems: List<LibraryItemWithSources>,
    videoLibraryItems: List<VideoLibraryItemWithSources>,
    libraryMessage: String?,
    libraryMessageIsError: Boolean,
    videoLibraryMessage: String?,
    videoLibraryMessageIsError: Boolean,
    coversEnabled: Boolean,
    thumbnailsEnabled: Boolean,
    isExtractingThumbnails: Boolean,
    videoThumbnailArtworkRevisions: Map<Long, Long>,
    historyThumbnailArtworkRevisions: Map<String, Long>,
    sharedVideoThumbnailArtworkRevision: Long,
    thumbnailExtractionMessage: String?,
    thumbnailExtractionMessageIsError: Boolean,
    hasActiveSelection: Boolean,
    selectedLibraryItemId: Long?,
    selectedVideoLibraryItemId: Long?,
    onOpenHistoryEntry: (WatchHistoryEntry) -> Unit,
    onDeleteHistoryEntry: (WatchHistoryEntry) -> Unit,
    onOpenLibraryItem: (LibraryItemWithSources) -> Unit,
    onSelectLibraryItem: (LibraryItemWithSources) -> Unit,
    onOpenVideoLibraryItem: (VideoLibraryItemWithSources) -> Unit,
    onSelectVideoLibraryItem: (VideoLibraryItemWithSources) -> Unit,
    onDismissLibraryMessage: () -> Unit,
    onDismissVideoLibraryMessage: () -> Unit,
    onDismissThumbnailExtractionMessage: () -> Unit,
    onExtractThumbnails: () -> Unit,
    onOpenSources: () -> Unit,
    libraryPage: @Composable (onBack: () -> Unit, modifier: Modifier) -> Unit,
    videoLibraryPage: @Composable (onBack: () -> Unit, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var subPageName by rememberSaveable { mutableStateOf(HomeSubPage.ROOT.name) }
    val subPage = runCatching { HomeSubPage.valueOf(subPageName) }.getOrDefault(HomeSubPage.ROOT)

    // 内层 BackHandler 优先于全局 ComicDavBackHandler；有选择操作时让全局处理器先清选择。
    BackHandler(enabled = subPage != HomeSubPage.ROOT && !hasActiveSelection) {
        subPageName = HomeSubPage.ROOT.name
    }

    when (subPage) {
        HomeSubPage.HISTORY -> {
            HomeHistoryScreen(
                history = history,
                onOpenEntry = onOpenHistoryEntry,
                onDeleteEntry = onDeleteHistoryEntry,
                onBack = { subPageName = HomeSubPage.ROOT.name },
                modifier = modifier,
            )
            return
        }
        HomeSubPage.LIBRARY -> {
            libraryPage({ subPageName = HomeSubPage.ROOT.name }, modifier)
            return
        }
        HomeSubPage.VIDEO_LIBRARY -> {
            videoLibraryPage({ subPageName = HomeSubPage.ROOT.name }, modifier)
            return
        }
        HomeSubPage.ROOT -> Unit
    }

    HomeRootContent(
        history = history,
        libraryItems = libraryItems,
        videoLibraryItems = videoLibraryItems,
        libraryMessage = libraryMessage,
        libraryMessageIsError = libraryMessageIsError,
        videoLibraryMessage = videoLibraryMessage,
        videoLibraryMessageIsError = videoLibraryMessageIsError,
        coversEnabled = coversEnabled,
        thumbnailsEnabled = thumbnailsEnabled,
        isExtractingThumbnails = isExtractingThumbnails,
        videoThumbnailArtworkRevisions = videoThumbnailArtworkRevisions,
        historyThumbnailArtworkRevisions = historyThumbnailArtworkRevisions,
        sharedVideoThumbnailArtworkRevision = sharedVideoThumbnailArtworkRevision,
        thumbnailExtractionMessage = thumbnailExtractionMessage,
        thumbnailExtractionMessageIsError = thumbnailExtractionMessageIsError,
        selectedLibraryItemId = selectedLibraryItemId,
        selectedVideoLibraryItemId = selectedVideoLibraryItemId,
        onOpenHistoryEntry = onOpenHistoryEntry,
        onOpenLibraryItem = onOpenLibraryItem,
        onSelectLibraryItem = onSelectLibraryItem,
        onOpenVideoLibraryItem = onOpenVideoLibraryItem,
        onSelectVideoLibraryItem = onSelectVideoLibraryItem,
        onDismissLibraryMessage = onDismissLibraryMessage,
        onDismissVideoLibraryMessage = onDismissVideoLibraryMessage,
        onDismissThumbnailExtractionMessage = onDismissThumbnailExtractionMessage,
        onExtractThumbnails = onExtractThumbnails,
        onOpenSources = onOpenSources,
        onOpenAllHistory = { subPageName = HomeSubPage.HISTORY.name },
        onOpenFullLibrary = { subPageName = HomeSubPage.LIBRARY.name },
        onOpenFullVideoLibrary = { subPageName = HomeSubPage.VIDEO_LIBRARY.name },
        modifier = modifier,
    )
}

@Composable
private fun HomeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = colors.text,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("搜索漫画、视频和观看记录") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清除搜索",
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun HomeSearchResultContent(
    query: String,
    history: List<WatchHistoryEntry>,
    libraryItems: List<LibraryItemWithSources>,
    videoLibraryItems: List<VideoLibraryItemWithSources>,
    onOpenHistoryEntry: (WatchHistoryEntry) -> Unit,
    onOpenLibraryItem: (LibraryItemWithSources) -> Unit,
    onOpenVideoLibraryItem: (VideoLibraryItemWithSources) -> Unit,
    onCloseSearch: () -> Unit,
) {
    val colors = rememberMuBoxColors()
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) {
        return
    }
    val results = filterHomeSearchResults(
        query = trimmedQuery,
        history = history,
        comics = libraryItems,
        videos = videoLibraryItems,
    )
    if (results.isEmpty) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "没有找到与“$trimmedQuery”相关的内容",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
            TextButton(onClick = onCloseSearch) {
                Text("返回")
            }
        }
        return
    }
    if (results.history.isNotEmpty()) {
        MuBoxSection(title = "最近记录") {
            results.history.forEach { entry ->
                HomeSearchResultRow(
                    title = entry.displayTitle,
                    subtitle = homeHistoryProgressLabel(entry),
                    onClick = { onOpenHistoryEntry(entry) },
                )
            }
        }
    }
    if (results.comics.isNotEmpty()) {
        MuBoxSection(title = "漫画") {
            results.comics.forEach { item ->
                HomeSearchResultRow(
                    title = item.item.displayName,
                    subtitle = homeLibrarySubtitle(item),
                    onClick = { onOpenLibraryItem(item) },
                )
            }
        }
    }
    if (results.videos.isNotEmpty()) {
        MuBoxSection(title = "视频") {
            results.videos.forEach { item ->
                HomeSearchResultRow(
                    title = item.item.displayName,
                    subtitle = homeVideoLibrarySubtitle(item),
                    onClick = { onOpenVideoLibraryItem(item) },
                )
            }
        }
    }
}

@Composable
private fun HomeSearchResultRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MuBoxMetrics.MinTouchTargetDp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
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
    }
}

// 首页二级页：全部观看记录。打开/删除逻辑沿用设置页历史子页的交互。
@Composable
internal fun HomeHistoryScreen(
    history: List<WatchHistoryEntry>,
    onOpenEntry: (WatchHistoryEntry) -> Unit,
    onDeleteEntry: (WatchHistoryEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    var pendingDelete by remember { mutableStateOf<WatchHistoryEntry?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .muBoxAppBackground(colors),
    ) {
        MuBoxHeaderBar(
            title = "观看历史",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = MuBoxMetrics.PageHorizontalPaddingDp,
                end = MuBoxMetrics.PageHorizontalPaddingDp,
                top = 16.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (history.isEmpty()) {
                item {
                    MuBoxEmptyState(
                        icon = Icons.Filled.History,
                        title = "暂无观看历史",
                        body = "打开漫画或视频后，进度会自动显示在这里",
                    )
                }
            } else {
                items(
                    items = history,
                    key = WatchHistoryEntry::mediaKey,
                ) { entry ->
                    HistoryEntryRow(
                        entry = entry,
                        onOpen = { onOpenEntry(entry) },
                        onDelete = { pendingDelete = entry },
                    )
                }
            }
        }
    }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条历史记录？") },
            text = { Text("将同时清理《${entry.displayTitle}》的恢复位置和关联漫画缓存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteEntry(entry)
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}
