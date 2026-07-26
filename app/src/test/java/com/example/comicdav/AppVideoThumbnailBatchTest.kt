package com.example.comicdav

import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.history.WatchMediaType
import com.example.comicdav.core.model.history.WatchSourceType
import com.example.comicdav.data.videolibrary.VideoLibraryItem
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoSourceType
import com.example.comicdav.feature.home.historyEntriesNeedingThumbnails
import com.example.comicdav.feature.home.historyThumbnailFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppVideoThumbnailBatchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun batchExtractionOnlyTargetsMissingThumbnailFiles() {
        val existingThumbnail = temporaryFolder.newFile("existing.jpg")
        val missingThumbnail = File(temporaryFolder.root, "missing.jpg")
        val withoutThumbnail = videoItem(id = 1L, thumbnailPath = null)
        val missingFile = videoItem(id = 2L, thumbnailPath = missingThumbnail.path)
        val existingFile = videoItem(id = 3L, thumbnailPath = existingThumbnail.path)

        val targets = videoLibraryItemsNeedingThumbnails(
            listOf(withoutThumbnail, missingFile, existingFile),
        )

        assertEquals(listOf(1L, 2L), targets.map { it.item.id })
    }

    @Test
    fun historyExtractionTargetsDisappearWhenStableCacheFileExists() {
        val entry = WatchHistoryEntry(
            mediaKey = "video:history",
            mediaType = WatchMediaType.VIDEO,
            title = "history.mp4",
            sourceType = WatchSourceType.LOCAL,
            sourceLocator = "content://videos/history.mp4",
            progress = 1L,
            total = 10L,
            lastWatchedAt = 100L,
        )

        assertEquals(
            listOf(entry),
            historyEntriesNeedingThumbnails(
                history = listOf(entry),
                comics = emptyList(),
                videos = emptyList(),
                cacheDir = temporaryFolder.root,
            ),
        )

        historyThumbnailFile(temporaryFolder.root, entry).apply {
            parentFile!!.mkdirs()
            writeText("thumbnail")
        }

        assertEquals(
            emptyList<WatchHistoryEntry>(),
            historyEntriesNeedingThumbnails(
                history = listOf(entry),
                comics = emptyList(),
                videos = emptyList(),
                cacheDir = temporaryFolder.root,
            ),
        )
    }

    private fun videoItem(
        id: Long,
        thumbnailPath: String?,
    ): VideoLibraryItemWithSources =
        VideoLibraryItemWithSources(
            item = VideoLibraryItem(
                id = id,
                title = "video-$id",
                displayName = "video-$id",
                sourceType = VideoSourceType.LOCAL,
                thumbnailPath = thumbnailPath,
                addedAt = 100L,
            ),
            localSource = null,
            webDavSource = null,
        )
}
