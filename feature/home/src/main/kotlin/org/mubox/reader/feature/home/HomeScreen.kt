package org.mubox.reader.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.library.LibraryItemWithSources
import org.mubox.reader.core.model.videolibrary.VideoLibraryItemWithSources
import kotlin.math.roundToInt

internal fun homeHistoryProgressPercentLabel(entry: WatchHistoryEntry): String =
    if (entry.total > 0L) {
        "${(entry.progressFraction * 100f).roundToInt()}%"
    } else {
        "--%"
    }

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
    selectedHistoryKeys: Set<String>,
    selectedLibraryItemIds: Set<Long>,
    selectedVideoLibraryItemIds: Set<Long>,
    onOpenHistoryEntry: (WatchHistoryEntry) -> Unit,
    onToggleHistorySelection: (WatchHistoryEntry) -> Unit,
    onOpenLibraryItem: (LibraryItemWithSources) -> Unit,
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
        selectedHistoryKeys = selectedHistoryKeys,
        selectedLibraryItemIds = selectedLibraryItemIds,
        selectedVideoLibraryItemIds = selectedVideoLibraryItemIds,
        onOpenHistoryEntry = onOpenHistoryEntry,
        onToggleHistorySelection = onToggleHistorySelection,
        onOpenLibraryItem = onOpenLibraryItem,
        onToggleLibrarySelection = onToggleLibrarySelection,
        onOpenVideoLibraryItem = onOpenVideoLibraryItem,
        onToggleVideoLibrarySelection = onToggleVideoLibrarySelection,
        onDismissLibraryMessage = onDismissLibraryMessage,
        onDismissVideoLibraryMessage = onDismissVideoLibraryMessage,
        onDismissThumbnailExtractionMessage = onDismissThumbnailExtractionMessage,
        onExtractThumbnails = onExtractThumbnails,
        onOpenSources = onOpenSources,
        modifier = modifier,
    )
}
