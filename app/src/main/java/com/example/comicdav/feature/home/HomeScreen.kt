package com.example.comicdav.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.history.WatchMediaType
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.library.SourceType
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoSourceType
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.ui.HistoryEntryRow
import com.example.comicdav.ui.MuBoxEmptyState
import com.example.comicdav.ui.MuBoxHeaderBar
import com.example.comicdav.ui.MuBoxInlineMessage
import com.example.comicdav.ui.MuBoxMediaPosterCard
import com.example.comicdav.ui.MuBoxMetrics
import com.example.comicdav.ui.MuBoxPanelSection
import com.example.comicdav.ui.MuBoxPosterKind
import com.example.comicdav.ui.MuBoxPosterLayout
import com.example.comicdav.ui.MuBoxSection
import com.example.comicdav.ui.muBoxAppBackground
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

// 首页内部二级页（§5.1）：书架与影视库完整列表不再占据顶层 tab。
internal enum class HomeSubPage {
    ROOT,
    HISTORY,
    LIBRARY,
    VIDEO_LIBRARY,
}

private const val HomePreviewItemCount = 10

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

internal fun historyThumbnailStableKey(entry: WatchHistoryEntry): String =
    listOf(
        "history",
        entry.mediaType.name,
        entry.mediaKey,
        entry.sourceLocator,
        entry.accountId.orEmpty(),
        entry.size?.toString().orEmpty(),
        entry.etag.orEmpty(),
        entry.lastModified?.toString().orEmpty(),
    ).joinToString(separator = "\u001F")

internal fun historyThumbnailFile(
    cacheDir: File,
    entry: WatchHistoryEntry,
): File {
    val stableKey = historyThumbnailStableKey(entry)
    val readablePrefix = stableKey
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_', '.', '-')
        .take(48)
    val hash = stableKey.sha256Hex()
    val extension = if (entry.mediaType == WatchMediaType.VIDEO) "jpg" else "img"
    val fileName = if (readablePrefix.isBlank()) {
        "$hash.$extension"
    } else {
        "$readablePrefix-$hash.$extension"
    }
    return cacheDir.resolve("history-thumbnails").resolve(fileName)
}

internal fun libraryArtworkPathForHistory(
    entry: WatchHistoryEntry,
    comics: List<LibraryItemWithSources>,
    videos: List<VideoLibraryItemWithSources>,
): String? =
    when (entry.mediaType) {
        WatchMediaType.COMIC -> comics.firstOrNull { item ->
            item.localSource?.uri == entry.sourceLocator ||
                item.webDavSource?.remotePath == entry.sourceLocator
        }?.item?.coverPath
        WatchMediaType.VIDEO -> videos.firstOrNull { item ->
            item.localSource?.uri == entry.sourceLocator ||
                item.webDavSource?.remotePath == entry.sourceLocator
        }?.item?.thumbnailPath
    }

internal fun resolvedHistoryArtworkPath(
    entry: WatchHistoryEntry,
    comics: List<LibraryItemWithSources>,
    videos: List<VideoLibraryItemWithSources>,
    cacheDir: File? = null,
): String? {
    val libraryPath = libraryArtworkPathForHistory(entry, comics, videos)
    if (cacheDir == null) return libraryPath
    return libraryPath
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?.absolutePath
        ?: historyThumbnailFile(cacheDir, entry)
            .takeIf(File::isFile)
            ?.absolutePath
}

internal fun historyEntriesNeedingThumbnails(
    history: List<WatchHistoryEntry>,
    comics: List<LibraryItemWithSources>,
    videos: List<VideoLibraryItemWithSources>,
    cacheDir: File,
): List<WatchHistoryEntry> =
    history.filter { entry ->
        resolvedHistoryArtworkPath(
            entry = entry,
            comics = comics,
            videos = videos,
            cacheDir = cacheDir,
        ) == null
    }

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun homeHistoryCoverPath(
    entry: WatchHistoryEntry,
    comics: List<LibraryItemWithSources>,
    videos: List<VideoLibraryItemWithSources>,
): String? =
    resolvedHistoryArtworkPath(entry, comics, videos)

@Composable
internal fun HomeScreen(
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
private fun HomeRootContent(
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
    thumbnailExtractionMessage: String?,
    thumbnailExtractionMessageIsError: Boolean,
    selectedLibraryItemId: Long?,
    selectedVideoLibraryItemId: Long?,
    onOpenHistoryEntry: (WatchHistoryEntry) -> Unit,
    onOpenLibraryItem: (LibraryItemWithSources) -> Unit,
    onSelectLibraryItem: (LibraryItemWithSources) -> Unit,
    onOpenVideoLibraryItem: (VideoLibraryItemWithSources) -> Unit,
    onSelectVideoLibraryItem: (VideoLibraryItemWithSources) -> Unit,
    onDismissLibraryMessage: () -> Unit,
    onDismissVideoLibraryMessage: () -> Unit,
    onDismissThumbnailExtractionMessage: () -> Unit,
    onExtractThumbnails: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenAllHistory: () -> Unit,
    onOpenFullLibrary: () -> Unit,
    onOpenFullVideoLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    val thumbnailSnackbarHostState = remember { SnackbarHostState() }
    // rememberScrollState 内部使用 rememberSaveable，进程重建后保留首页滚动位置（§5.3）。
    val scrollState = rememberScrollState()

    LaunchedEffect(thumbnailExtractionMessage) {
        val message = thumbnailExtractionMessage ?: return@LaunchedEffect
        thumbnailSnackbarHostState.showSnackbar(message)
        onDismissThumbnailExtractionMessage()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .muBoxAppBackground(colors)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeTopBar(
                isExtractingThumbnails = isExtractingThumbnails,
                onExtractThumbnails = onExtractThumbnails,
            )
            if (libraryMessage != null) {
                MuBoxInlineMessage(
                    text = libraryMessage,
                    isError = libraryMessageIsError,
                    onDismiss = onDismissLibraryMessage,
                    modifier = Modifier.padding(
                        horizontal = MuBoxMetrics.PageHorizontalPaddingDp,
                        vertical = 4.dp,
                    ),
                )
            }
            if (videoLibraryMessage != null) {
                MuBoxInlineMessage(
                    text = videoLibraryMessage,
                    isError = videoLibraryMessageIsError,
                    onDismiss = onDismissVideoLibraryMessage,
                    modifier = Modifier.padding(
                        horizontal = MuBoxMetrics.PageHorizontalPaddingDp,
                        vertical = 4.dp,
                    ),
                )
            }

            HomeHistorySection(
                history = history,
                libraryItems = libraryItems,
                videoLibraryItems = videoLibraryItems,
                coversEnabled = coversEnabled,
                thumbnailsEnabled = thumbnailsEnabled,
                historyThumbnailArtworkRevisions = historyThumbnailArtworkRevisions,
                onOpenEntry = onOpenHistoryEntry,
                onOpenAll = onOpenAllHistory,
                onOpenSources = onOpenSources,
            )
            HomeLibrarySection(
                items = libraryItems,
                coversEnabled = coversEnabled,
                selectedItemId = selectedLibraryItemId,
                onOpenItem = onOpenLibraryItem,
                onSelectItem = onSelectLibraryItem,
                onOpenAll = onOpenFullLibrary,
                onOpenSources = onOpenSources,
            )
            HomeVideoLibrarySection(
                items = videoLibraryItems,
                thumbnailsEnabled = thumbnailsEnabled,
                videoThumbnailArtworkRevisions = videoThumbnailArtworkRevisions,
                selectedItemId = selectedVideoLibraryItemId,
                onOpenItem = onOpenVideoLibraryItem,
                onSelectItem = onSelectVideoLibraryItem,
                onOpenAll = onOpenFullVideoLibrary,
                onOpenSources = onOpenSources,
            )
        }

        SnackbarHost(
            hostState = thumbnailSnackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) { snackbarData ->
            Snackbar(
                snackbarData = snackbarData,
                containerColor = if (thumbnailExtractionMessageIsError) {
                    colors.errorSurface
                } else {
                    colors.panelHigh
                },
                contentColor = if (thumbnailExtractionMessageIsError) {
                    colors.errorText
                } else {
                    colors.text
                },
            )
        }
    }
}

@Composable
private fun HomeTopBar(
    isExtractingThumbnails: Boolean,
    onExtractThumbnails: () -> Unit,
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "MuBOX",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.text,
            maxLines = 1,
        )
        TextButton(
            onClick = onExtractThumbnails,
            enabled = !isExtractingThumbnails,
        ) {
            if (isExtractingThumbnails) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.accentText,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.ImageSearch,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = if (isExtractingThumbnails) "正在提取" else "一键提取缩略图",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
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

@Composable
private fun HomeHistorySection(
    history: List<WatchHistoryEntry>,
    libraryItems: List<LibraryItemWithSources>,
    videoLibraryItems: List<VideoLibraryItemWithSources>,
    coversEnabled: Boolean,
    thumbnailsEnabled: Boolean,
    historyThumbnailArtworkRevisions: Map<String, Long>,
    onOpenEntry: (WatchHistoryEntry) -> Unit,
    onOpenAll: () -> Unit,
    onOpenSources: () -> Unit,
) {
    val cacheDir = LocalContext.current.cacheDir
    MuBoxPanelSection(
        title = "最近记录",
        actionText = if (history.isEmpty()) null else "查看全部",
        onAction = if (history.isEmpty()) null else onOpenAll,
    ) {
        if (history.isEmpty()) {
            HomeSectionEmpty(
                text = "还没有观看记录",
                actionText = "浏览来源",
                onAction = onOpenSources,
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = history.take(HomePreviewItemCount),
                    key = WatchHistoryEntry::mediaKey,
                ) { entry ->
                    val coverPath = resolvedHistoryArtworkPath(
                        entry = entry,
                        comics = libraryItems,
                        videos = videoLibraryItems,
                        cacheDir = cacheDir,
                    )
                    MuBoxMediaPosterCard(
                        title = entry.displayTitle,
                        mediaKind = when (entry.mediaType) {
                            WatchMediaType.COMIC -> MuBoxPosterKind.Comic
                            WatchMediaType.VIDEO -> MuBoxPosterKind.Video
                        },
                        onClick = { onOpenEntry(entry) },
                        modifier = Modifier.width(108.dp),
                        subtitle = homeHistoryProgressPercentLabel(entry),
                        coverModel = rememberHomeArtworkModel(
                            path = coverPath,
                            enabled = when (entry.mediaType) {
                                WatchMediaType.COMIC -> coversEnabled
                                WatchMediaType.VIDEO -> thumbnailsEnabled
                            },
                            artworkRevision =
                                historyThumbnailArtworkRevisions[entry.mediaKey] ?: 0L,
                        ),
                        progress = entry.progressFraction,
                        layout = MuBoxPosterLayout.Recent,
                        coverAspectRatio = 0.75f,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeLibrarySection(
    items: List<LibraryItemWithSources>,
    coversEnabled: Boolean,
    selectedItemId: Long?,
    onOpenItem: (LibraryItemWithSources) -> Unit,
    onSelectItem: (LibraryItemWithSources) -> Unit,
    onOpenAll: () -> Unit,
    onOpenSources: () -> Unit,
) {
    MuBoxPanelSection(
        title = "漫画书架",
        actionText = if (items.isEmpty()) null else "查看全部",
        onAction = if (items.isEmpty()) null else onOpenAll,
    ) {
        if (items.isEmpty()) {
            HomeSectionEmpty(
                text = ComicDavCopy.emptyLibraryTitle,
                actionText = "从来源添加",
                onAction = onOpenSources,
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = items.take(HomePreviewItemCount),
                    key = { it.item.id },
                ) { item ->
                    MuBoxMediaPosterCard(
                        title = item.item.displayName,
                        mediaKind = MuBoxPosterKind.Comic,
                        onClick = { onOpenItem(item) },
                        modifier = Modifier.width(96.dp),
                        coverModel = item.item.coverPath
                            ?.takeIf { coversEnabled }
                            ?.let(::File)
                            ?.takeIf { it.isFile },
                        selected = selectedItemId == item.item.id,
                        layout = MuBoxPosterLayout.Cover,
                        coverAspectRatio = 2f / 3f,
                        showKindLabel = false,
                        onLongClick = { onSelectItem(item) },
                        onLongClickLabel = "书架操作",
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeVideoLibrarySection(
    items: List<VideoLibraryItemWithSources>,
    thumbnailsEnabled: Boolean,
    videoThumbnailArtworkRevisions: Map<Long, Long>,
    selectedItemId: Long?,
    onOpenItem: (VideoLibraryItemWithSources) -> Unit,
    onSelectItem: (VideoLibraryItemWithSources) -> Unit,
    onOpenAll: () -> Unit,
    onOpenSources: () -> Unit,
) {
    MuBoxPanelSection(
        title = "影视库",
        actionText = if (items.isEmpty()) null else "查看全部",
        onAction = if (items.isEmpty()) null else onOpenAll,
    ) {
        if (items.isEmpty()) {
            HomeSectionEmpty(
                text = "影视库还是空的",
                actionText = "从来源添加",
                onAction = onOpenSources,
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = items.take(HomePreviewItemCount),
                    key = { it.item.id },
                ) { item ->
                    MuBoxMediaPosterCard(
                        title = item.item.displayName,
                        mediaKind = MuBoxPosterKind.Video,
                        onClick = { onOpenItem(item) },
                        modifier = Modifier.width(96.dp),
                        coverModel = rememberHomeArtworkModel(
                            path = item.item.thumbnailPath,
                            enabled = thumbnailsEnabled,
                            artworkRevision =
                                videoThumbnailArtworkRevisions[item.item.id] ?: 0L,
                        ),
                        selected = selectedItemId == item.item.id,
                        layout = MuBoxPosterLayout.Cover,
                        coverAspectRatio = 2f / 3f,
                        showKindLabel = false,
                        onLongClick = { onSelectItem(item) },
                        onLongClickLabel = "影视库操作",
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberHomeArtworkModel(
    path: String?,
    enabled: Boolean,
    artworkRevision: Long,
): Any? {
    val context = LocalContext.current
    return remember(context, path, enabled, artworkRevision) {
        val file = path
            ?.takeIf { enabled }
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?: return@remember null
        if (artworkRevision == 0L) {
            file
        } else {
            ImageRequest.Builder(context)
                .data(file)
                .memoryCacheKey("${file.absolutePath}#extraction-$artworkRevision")
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        }
    }
}

@Composable
private fun HomeSectionEmpty(
    text: String,
    actionText: String,
    onAction: () -> Unit,
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MuBoxMetrics.MinTouchTargetDp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onAction) {
            Text(text = actionText, maxLines = 1)
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
