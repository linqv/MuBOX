package org.mubox.reader.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.history.WatchMediaType
import org.mubox.reader.core.model.history.resolvedHistoryArtworkPath
import org.mubox.reader.core.model.library.LibraryItemWithSources
import org.mubox.reader.core.model.media.resolvedVideoThumbnailPath
import org.mubox.reader.core.model.videolibrary.VideoLibraryItemWithSources
import org.mubox.reader.ui.MuBoxCopy
import org.mubox.reader.ui.MuBoxInlineMessage
import org.mubox.reader.ui.MuBoxMediaPosterCard
import org.mubox.reader.ui.MuBoxMetrics
import org.mubox.reader.ui.MuBoxPanelSection
import org.mubox.reader.ui.MuBoxPosterKind
import org.mubox.reader.ui.MuBoxPosterLayout
import org.mubox.reader.ui.muBoxAppBackground
import org.mubox.reader.ui.rememberMuBoxColors
import java.io.File

private const val HomePreviewItemCount = 10
private const val HomeExpandedColumnCount = 3
// 面板内卡片列的内边距与卡片间距：折叠预览行与展开网格共用同一套几何，
// 保证两种状态下卡片大小一致（展开不被压缩）；内边距刻意缩小，
// 让容器恰好完整放下 columns 张卡片。
private val HomeGridHorizontalPadding = 4.dp
private val HomeCardSpacing = 6.dp

private val HomeFullSpan: LazyGridItemSpanScope.() -> GridItemSpan = {
    GridItemSpan(maxLineSpan)
}

internal fun homeHistoryArtworkRevision(
    entry: WatchHistoryEntry,
    entryRevision: Long,
    sharedVideoRevision: Long,
): Long = entryRevision + if (entry.mediaType == WatchMediaType.VIDEO) sharedVideoRevision else 0L

@Composable
internal fun HomeRootContent(
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
    selectedHistoryKeys: Set<String>,
    selectedLibraryItemIds: Set<Long>,
    selectedVideoLibraryItemIds: Set<Long>,
    onOpenHistoryEntry: (WatchHistoryEntry) -> Unit,
    onOpenLibraryItem: (LibraryItemWithSources) -> Unit,
    onToggleHistorySelection: (WatchHistoryEntry) -> Unit,
    onToggleLibrarySelection: (LibraryItemWithSources) -> Unit,
    onOpenVideoLibraryItem: (VideoLibraryItemWithSources) -> Unit,
    onToggleVideoLibrarySelection: (VideoLibraryItemWithSources) -> Unit,
    onDismissLibraryMessage: () -> Unit,
    onDismissVideoLibraryMessage: () -> Unit,
    onDismissThumbnailExtractionMessage: () -> Unit,
    onExtractThumbnails: () -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    val thumbnailSnackbarHostState = remember { SnackbarHostState() }
    // LazyVerticalGrid 内部使用 rememberSaveable，进程重建后保留首页滚动位置（§5.3）。
    val gridState = rememberLazyGridState()
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    var libraryExpanded by rememberSaveable { mutableStateOf(false) }
    var videoLibraryExpanded by rememberSaveable { mutableStateOf(false) }

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
        LazyVerticalGrid(
            columns = GridCells.Fixed(HomeExpandedColumnCount),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = HomeFullSpan, key = "topbar") {
                HomeTopBar(
                    isExtractingThumbnails = isExtractingThumbnails,
                    onExtractThumbnails = onExtractThumbnails,
                )
            }
            if (libraryMessage != null) {
                item(span = HomeFullSpan, key = "library-message") {
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
            }
            if (videoLibraryMessage != null) {
                item(span = HomeFullSpan, key = "video-library-message") {
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
            }

            homeHistorySection(
                history = history,
                libraryItems = libraryItems,
                videoLibraryItems = videoLibraryItems,
                coversEnabled = coversEnabled,
                thumbnailsEnabled = thumbnailsEnabled,
                historyThumbnailArtworkRevisions = historyThumbnailArtworkRevisions,
                sharedVideoThumbnailArtworkRevision = sharedVideoThumbnailArtworkRevision,
                onOpenEntry = onOpenHistoryEntry,
                selectedKeys = selectedHistoryKeys,
                selectionActive = selectedHistoryKeys.isNotEmpty() ||
                    selectedLibraryItemIds.isNotEmpty() || selectedVideoLibraryItemIds.isNotEmpty(),
                onToggleSelection = onToggleHistorySelection,
                expanded = historyExpanded,
                onToggleExpanded = { historyExpanded = !historyExpanded },
                onOpenSources = onOpenSources,
            )
            homeLibrarySection(
                items = libraryItems,
                coversEnabled = coversEnabled,
                selectedItemIds = selectedLibraryItemIds,
                selectionActive = selectedHistoryKeys.isNotEmpty() ||
                    selectedLibraryItemIds.isNotEmpty() || selectedVideoLibraryItemIds.isNotEmpty(),
                onOpenItem = onOpenLibraryItem,
                onToggleSelection = onToggleLibrarySelection,
                expanded = libraryExpanded,
                onToggleExpanded = { libraryExpanded = !libraryExpanded },
                onOpenSources = onOpenSources,
            )
            homeVideoLibrarySection(
                items = videoLibraryItems,
                thumbnailsEnabled = thumbnailsEnabled,
                videoThumbnailArtworkRevisions = videoThumbnailArtworkRevisions,
                selectedItemIds = selectedVideoLibraryItemIds,
                selectionActive = selectedHistoryKeys.isNotEmpty() ||
                    selectedLibraryItemIds.isNotEmpty() || selectedVideoLibraryItemIds.isNotEmpty(),
                onOpenItem = onOpenVideoLibraryItem,
                onToggleSelection = onToggleVideoLibrarySelection,
                expanded = videoLibraryExpanded,
                onToggleExpanded = { videoLibraryExpanded = !videoLibraryExpanded },
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

private fun LazyGridScope.homeHistorySection(
    history: List<WatchHistoryEntry>,
    libraryItems: List<LibraryItemWithSources>,
    videoLibraryItems: List<VideoLibraryItemWithSources>,
    coversEnabled: Boolean,
    thumbnailsEnabled: Boolean,
    historyThumbnailArtworkRevisions: Map<String, Long>,
    sharedVideoThumbnailArtworkRevision: Long,
    onOpenEntry: (WatchHistoryEntry) -> Unit,
    selectedKeys: Set<String>,
    selectionActive: Boolean,
    onToggleSelection: (WatchHistoryEntry) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenSources: () -> Unit,
) {
    if (history.isEmpty()) {
        item(span = HomeFullSpan, key = "history") {
            MuBoxPanelSection(title = "最近记录") {
                HomeSectionEmpty(
                    text = "还没有观看记录",
                    actionText = "浏览来源",
                    onAction = onOpenSources,
                )
            }
        }
        return
    }
    if (!expanded) {
        item(span = HomeFullSpan, key = "history") {
            val cacheDir = LocalContext.current.cacheDir
            MuBoxPanelSection(
                title = "最近记录",
                actionText = "展开",
                actionIcon = Icons.Filled.ExpandMore,
                onAction = onToggleExpanded,
            ) {
                HomePreviewCardRow(columns = HomeExpandedColumnCount) { cardWidth ->
                    items(
                        items = history.take(HomePreviewItemCount),
                        key = WatchHistoryEntry::mediaKey,
                    ) { entry ->
                        HomeHistoryCard(
                            entry = entry,
                            libraryItems = libraryItems,
                            videoLibraryItems = videoLibraryItems,
                            coversEnabled = coversEnabled,
                            thumbnailsEnabled = thumbnailsEnabled,
                            historyThumbnailArtworkRevisions = historyThumbnailArtworkRevisions,
                            sharedVideoThumbnailArtworkRevision = sharedVideoThumbnailArtworkRevision,
                            selected = entry.mediaKey in selectedKeys,
                            selectionActive = selectionActive,
                            onOpen = onOpenEntry,
                            onToggleSelection = onToggleSelection,
                            cacheDir = cacheDir,
                            modifier = Modifier.width(cardWidth),
                        )
                    }
                }
            }
        }
        return
    }
    item(span = HomeFullSpan, key = "history") {
        MuBoxPanelSection(
            title = "最近记录",
            actionText = "收起",
            actionIcon = Icons.Filled.ExpandLess,
            onAction = onToggleExpanded,
        ) {
            val cacheDir = LocalContext.current.cacheDir
            HomeExpandedCardGrid(items = history, columns = HomeExpandedColumnCount) { entry, modifier ->
                HomeHistoryCard(
                    entry = entry,
                    libraryItems = libraryItems,
                    videoLibraryItems = videoLibraryItems,
                    coversEnabled = coversEnabled,
                    thumbnailsEnabled = thumbnailsEnabled,
                    historyThumbnailArtworkRevisions = historyThumbnailArtworkRevisions,
                    sharedVideoThumbnailArtworkRevision = sharedVideoThumbnailArtworkRevision,
                    selected = entry.mediaKey in selectedKeys,
                    selectionActive = selectionActive,
                    onOpen = onOpenEntry,
                    onToggleSelection = onToggleSelection,
                    cacheDir = cacheDir,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun HomeHistoryCard(
    entry: WatchHistoryEntry,
    libraryItems: List<LibraryItemWithSources>,
    videoLibraryItems: List<VideoLibraryItemWithSources>,
    coversEnabled: Boolean,
    thumbnailsEnabled: Boolean,
    historyThumbnailArtworkRevisions: Map<String, Long>,
    sharedVideoThumbnailArtworkRevision: Long,
    selected: Boolean,
    selectionActive: Boolean,
    onOpen: (WatchHistoryEntry) -> Unit,
    onToggleSelection: (WatchHistoryEntry) -> Unit,
    cacheDir: File,
    modifier: Modifier,
) {
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
        onClick = { if (selectionActive) onToggleSelection(entry) else onOpen(entry) },
        modifier = modifier,
        subtitle = homeHistoryProgressPercentLabel(entry),
        coverModel = rememberHomeArtworkModel(
            path = coverPath,
            enabled = when (entry.mediaType) {
                WatchMediaType.COMIC -> coversEnabled
                WatchMediaType.VIDEO -> thumbnailsEnabled
            },
            artworkRevision = homeHistoryArtworkRevision(
                entry = entry,
                entryRevision = historyThumbnailArtworkRevisions[entry.mediaKey] ?: 0L,
                sharedVideoRevision = sharedVideoThumbnailArtworkRevision,
            ),
        ),
        progress = entry.progressFraction,
        selected = selected,
        layout = MuBoxPosterLayout.Recent,
        coverAspectRatio = 0.75f,
        onLongClick = { onToggleSelection(entry) },
        onLongClickLabel = "选择记录",
    )
}

private fun LazyGridScope.homeLibrarySection(
    items: List<LibraryItemWithSources>,
    coversEnabled: Boolean,
    selectedItemIds: Set<Long>,
    selectionActive: Boolean,
    onOpenItem: (LibraryItemWithSources) -> Unit,
    onToggleSelection: (LibraryItemWithSources) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenSources: () -> Unit,
) {
    if (items.isEmpty()) {
        item(span = HomeFullSpan, key = "library") {
            MuBoxPanelSection(title = "漫画书架") {
                HomeSectionEmpty(
                    text = MuBoxCopy.emptyLibraryTitle,
                    actionText = "从来源添加",
                    onAction = onOpenSources,
                )
            }
        }
        return
    }
    if (!expanded) {
        item(span = HomeFullSpan, key = "library") {
            MuBoxPanelSection(
                title = "漫画书架",
                actionText = "展开",
                actionIcon = Icons.Filled.ExpandMore,
                onAction = onToggleExpanded,
            ) {
                HomePreviewCardRow(columns = HomeExpandedColumnCount) { cardWidth ->
                    items(items = items.take(HomePreviewItemCount), key = { it.item.id }) { item ->
                        HomeLibraryCard(
                            item = item,
                            coversEnabled = coversEnabled,
                            selected = item.item.id in selectedItemIds,
                            selectionActive = selectionActive,
                            onOpenItem = onOpenItem,
                            onToggleSelection = onToggleSelection,
                            modifier = Modifier.width(cardWidth),
                        )
                    }
                }
            }
        }
        return
    }
    item(span = HomeFullSpan, key = "library") {
        MuBoxPanelSection(
            title = "漫画书架",
            actionText = "收起",
            actionIcon = Icons.Filled.ExpandLess,
            onAction = onToggleExpanded,
        ) {
            HomeExpandedCardGrid(items = items, columns = HomeExpandedColumnCount) { libraryItem, modifier ->
                HomeLibraryCard(
                    item = libraryItem,
                    coversEnabled = coversEnabled,
                    selected = libraryItem.item.id in selectedItemIds,
                    selectionActive = selectionActive,
                    onOpenItem = onOpenItem,
                    onToggleSelection = onToggleSelection,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun HomeLibraryCard(
    item: LibraryItemWithSources,
    coversEnabled: Boolean,
    selected: Boolean,
    selectionActive: Boolean,
    onOpenItem: (LibraryItemWithSources) -> Unit,
    onToggleSelection: (LibraryItemWithSources) -> Unit,
    modifier: Modifier,
) {
    MuBoxMediaPosterCard(
        title = item.item.displayName,
        mediaKind = MuBoxPosterKind.Comic,
        onClick = { if (selectionActive) onToggleSelection(item) else onOpenItem(item) },
        modifier = modifier,
        coverModel = item.item.coverPath
            ?.takeIf { coversEnabled }
            ?.let(::File)
            ?.takeIf { it.isFile },
        selected = selected,
        layout = MuBoxPosterLayout.Cover,
        coverAspectRatio = 2f / 3f,
        showKindLabel = false,
        onLongClick = { onToggleSelection(item) },
        onLongClickLabel = "选择书架项目",
    )
}

private fun LazyGridScope.homeVideoLibrarySection(
    items: List<VideoLibraryItemWithSources>,
    thumbnailsEnabled: Boolean,
    videoThumbnailArtworkRevisions: Map<Long, Long>,
    selectedItemIds: Set<Long>,
    selectionActive: Boolean,
    onOpenItem: (VideoLibraryItemWithSources) -> Unit,
    onToggleSelection: (VideoLibraryItemWithSources) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenSources: () -> Unit,
) {
    if (items.isEmpty()) {
        item(span = HomeFullSpan, key = "video-library") {
            MuBoxPanelSection(title = "影视库") {
                HomeSectionEmpty(
                    text = "影视库还是空的",
                    actionText = "从来源添加",
                    onAction = onOpenSources,
                )
            }
        }
        return
    }
    if (!expanded) {
        item(span = HomeFullSpan, key = "video-library") {
            val cacheDir = LocalContext.current.cacheDir
            MuBoxPanelSection(
                title = "影视库",
                actionText = "展开",
                actionIcon = Icons.Filled.ExpandMore,
                onAction = onToggleExpanded,
            ) {
                HomePreviewCardRow(columns = HomeExpandedColumnCount) { cardWidth ->
                    items(items = items.take(HomePreviewItemCount), key = { it.item.id }) { item ->
                        HomeVideoLibraryCard(
                            item = item,
                            thumbnailsEnabled = thumbnailsEnabled,
                            videoThumbnailArtworkRevisions = videoThumbnailArtworkRevisions,
                            cacheDir = cacheDir,
                            selected = item.item.id in selectedItemIds,
                            selectionActive = selectionActive,
                            onOpenItem = onOpenItem,
                            onToggleSelection = onToggleSelection,
                            modifier = Modifier.width(cardWidth),
                        )
                    }
                }
            }
        }
        return
    }
    item(span = HomeFullSpan, key = "video-library") {
        MuBoxPanelSection(
            title = "影视库",
            actionText = "收起",
            actionIcon = Icons.Filled.ExpandLess,
            onAction = onToggleExpanded,
        ) {
            val cacheDir = LocalContext.current.cacheDir
            HomeExpandedCardGrid(items = items, columns = HomeExpandedColumnCount) { videoItem, modifier ->
                HomeVideoLibraryCard(
                    item = videoItem,
                    thumbnailsEnabled = thumbnailsEnabled,
                    videoThumbnailArtworkRevisions = videoThumbnailArtworkRevisions,
                    cacheDir = cacheDir,
                    selected = videoItem.item.id in selectedItemIds,
                    selectionActive = selectionActive,
                    onOpenItem = onOpenItem,
                    onToggleSelection = onToggleSelection,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun HomeVideoLibraryCard(
    item: VideoLibraryItemWithSources,
    thumbnailsEnabled: Boolean,
    videoThumbnailArtworkRevisions: Map<Long, Long>,
    cacheDir: File,
    selected: Boolean,
    selectionActive: Boolean,
    onOpenItem: (VideoLibraryItemWithSources) -> Unit,
    onToggleSelection: (VideoLibraryItemWithSources) -> Unit,
    modifier: Modifier,
) {
    MuBoxMediaPosterCard(
        title = item.item.displayName,
        mediaKind = MuBoxPosterKind.Video,
        onClick = { if (selectionActive) onToggleSelection(item) else onOpenItem(item) },
        modifier = modifier,
        coverModel = rememberHomeArtworkModel(
            path = resolvedVideoThumbnailPath(item, cacheDir),
            enabled = thumbnailsEnabled,
            artworkRevision = videoThumbnailArtworkRevisions[item.item.id] ?: 0L,
        ),
        selected = selected,
        layout = MuBoxPosterLayout.Cover,
        coverAspectRatio = 2f / 3f,
        showKindLabel = false,
        onLongClick = { onToggleSelection(item) },
        onLongClickLabel = "选择影视项目",
    )
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

// 折叠态预览行：与展开网格共用同一套内边距/间距几何计算卡片宽度，
// 保证展开时卡片大小不被压缩；内边距刻意缩小，容器恰好完整放下 columns 张卡片。
@Composable
private fun HomePreviewCardRow(
    columns: Int,
    content: LazyListScope.(cardWidth: Dp) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth =
            (maxWidth - HomeGridHorizontalPadding * 2 - HomeCardSpacing * (columns - 1)) / columns
        LazyRow(
            contentPadding = PaddingValues(horizontal = HomeGridHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
        ) {
            content(cardWidth)
        }
    }
}

// 展开态仍收在原面板矩形框内：整组卡片在 MuBoxPanelSection 面板内部按列等宽排布，
// 末行不足 columns 张时用占位补齐，保证各行卡片宽度一致。
@Composable
private fun <T> HomeExpandedCardGrid(
    items: List<T>,
    columns: Int,
    content: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HomeGridHorizontalPadding,
                end = HomeGridHorizontalPadding,
                bottom = 8.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
            ) {
                rowItems.forEach { item -> content(item, Modifier.weight(1f)) }
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
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
