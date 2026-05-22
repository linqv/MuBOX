package com.example.comicdav.feature.videolibrary

import com.example.comicdav.data.videolibrary.LocalVideoSourceEntity
import com.example.comicdav.data.videolibrary.VideoLibraryItemEntity
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoSourceType
import com.example.comicdav.data.videolibrary.WebDavVideoSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLibraryScreenTest {
    @Test
    fun countLabelShowsEmptyAndNonEmptyCounts() {
        assertEquals("还没有视频", videoLibraryCountLabel(0))
        assertEquals("2 个视频", videoLibraryCountLabel(2))
    }

    @Test
    fun sourceLabelShowsLocalAndWebDavLabels() {
        assertEquals("本地", videoSourceLabel(VideoSourceType.LOCAL))
        assertEquals("WebDAV", videoSourceLabel(VideoSourceType.WEBDAV))
    }

    @Test
    fun sourceMetaShowsLocalFileName() {
        val item = videoLibraryItem(
            sourceType = VideoSourceType.LOCAL,
            localSource = LocalVideoSourceEntity(
                videoLibraryItemId = 1L,
                uri = "content://video/1",
                fileName = "local-movie.mp4",
                size = 100L,
                lastModified = 20L,
            ),
        )

        assertEquals("local-movie.mp4", videoSourceMeta(item))
    }

    @Test
    fun sourceMetaShowsWebDavRemotePath() {
        val item = videoLibraryItem(
            sourceType = VideoSourceType.WEBDAV,
            webDavSource = WebDavVideoSourceEntity(
                videoLibraryItemId = 1L,
                accountId = "account",
                remotePath = "/视频/remote-movie.mp4",
                fileName = "remote-movie.mp4",
                size = 100L,
                etag = "etag",
                lastModified = 20L,
            ),
        )

        assertTrue(videoSourceMeta(item).contains("/视频/remote-movie.mp4"))
    }

    private fun videoLibraryItem(
        sourceType: VideoSourceType,
        localSource: LocalVideoSourceEntity? = null,
        webDavSource: WebDavVideoSourceEntity? = null,
    ): VideoLibraryItemWithSources {
        return VideoLibraryItemWithSources(
            item = VideoLibraryItemEntity(
                id = 1L,
                title = "movie",
                displayName = "movie.mp4",
                sourceType = sourceType,
                addedAt = 100L,
            ),
            localSource = localSource,
            webDavSource = webDavSource,
        )
    }
}
