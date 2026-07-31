package com.example.comicdav.feature.home

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.remember
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
import com.example.comicdav.core.model.history.resolvedHistoryArtworkPath
import com.example.comicdav.core.model.library.LibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.ui.MuBoxInlineMessage
import com.example.comicdav.ui.MuBoxMediaPosterCard
import com.example.comicdav.ui.MuBoxMetrics
import com.example.comicdav.ui.MuBoxPanelSection
import com.example.comicdav.ui.MuBoxPosterKind
import com.example.comicdav.ui.MuBoxPosterLayout
import com.example.comicdav.ui.muBoxAppBackground
import com.example.comicdav.ui.rememberMuBoxColors
import java.io.File

private const val HomePreviewItemCount = 10

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
