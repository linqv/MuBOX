package org.mubox.reader.feature.downloads

import org.mubox.reader.core.model.transfer.DownloadRecord
import org.mubox.reader.core.model.transfer.VideoDownloadRecord
import org.mubox.reader.core.model.media.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsScreenUiLogicTest {
    @Test
    fun sheetRecordSealedWrapperPreservesComicAndVideo() {
        val comic = sheetMediaKind(SheetRecord.Comic(stubComic))
        val video = sheetMediaKind(SheetRecord.Video(stubVideo))
        assertEquals(MediaKind.Comic, comic)
        assertEquals(MediaKind.Video, video)
    }

    private companion object {
        val stubComic = DownloadRecord(
            accountId = "acct",
            remotePath = "/a/b.cbz",
            fileName = "b.cbz",
            sizeBytes = 1024L,
            downloadedAtMillis = 0L,
            localUri = "content://stub/0",
        )
        val stubVideo = VideoDownloadRecord(
            accountId = "acct",
            remotePath = "/v/m.mp4",
            fileName = "m.mp4",
            sizeBytes = 2048L,
            downloadedAtMillis = 0L,
            localUri = "content://stub/1",
        )
    }
}
