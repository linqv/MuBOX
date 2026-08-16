package org.mubox.reader

import android.net.Uri
import android.provider.DocumentsContract
import org.mubox.reader.core.model.media.LocalVideoOpenRequest
import org.mubox.reader.core.model.media.MediaEntry
import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.feature.filedirectory.LocalDirectoryReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppVideoDirectoryPlaybackResolverTest {
    @Test
    fun localPlaybackReadsParentDirectoryAndBuildsNaturallySortedEpisodeQueue() = runTest {
        val treeUri = Uri.parse("content://media.documents/tree/root%3Ashows")
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "root:shows")
        val episode2Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "root:shows/Show E02.mkv")
        val episode10Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "root:shows/Show E10.mkv")
        val subtitleUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "root:shows/Show E02.zh.srt")
        val reader = RecordingLocalDirectoryReader(
            entries = listOf(
                media("Show E10.mkv", episode10Uri),
                media("Show E02.zh.srt", subtitleUri),
                media("Show E02.mkv", episode2Uri),
            ),
        )

        val resolution = resolveLocalDirectoryPlayback(
            localDirectoryReader = reader,
            request = LocalVideoOpenRequest(
                uri = episode2Uri.toString(),
                displayName = "Show E02.mkv",
                size = 20L,
                lastModified = 2L,
            ),
        )

        assertEquals(parentUri.toString(), reader.requestedDocumentUri)
        assertEquals(
            listOf("Show E02.mkv", "Show E10.mkv"),
            resolution.episodeQueue?.episodes?.map { it.displayName },
        )
        assertEquals(0, resolution.episodeQueue?.currentIndex)
        assertEquals(
            listOf("Show E02.zh.srt"),
            resolution.request.subtitles.map { it.displayName },
        )
    }

    @Test
    fun webDavPlaybackReadsParentDirectoryAndBuildsNaturallySortedEpisodeQueue() = runTest {
        val client = ListingWebDavClient(
            items = listOf(
                remote("Show E10.mkv", "/shows/Show E10.mkv"),
                remote("Show E02.zh.srt", "/shows/Show E02.zh.srt"),
                remote("Show E02.mkv", "/shows/Show E02.mkv"),
            ),
        )

        val resolution = resolveWebDavDirectoryPlayback(
            client = client,
            request = WebDavVideoOpenRequest(
                accountId = "account",
                remotePath = "/shows/Show E02.mkv",
                displayName = "Show E02.mkv",
                size = 20L,
                etag = "episode-2",
                lastModified = 2L,
                mimeType = "video/x-matroska",
            ),
        )

        assertEquals("/shows/", client.requestedPath)
        assertEquals(
            listOf("Show E02.mkv", "Show E10.mkv"),
            resolution.episodeQueue?.episodes?.map { it.displayName },
        )
        assertEquals(0, resolution.episodeQueue?.currentIndex)
        assertEquals(
            listOf("Show E02.zh.srt"),
            resolution.request.subtitles.map { it.displayName },
        )
    }

    @Test
    fun directoryReadFailureFallsBackToOriginalPlaybackRequest() = runTest {
        val original = WebDavVideoOpenRequest(
            accountId = "account",
            remotePath = "/shows/episode.mkv",
            displayName = "episode.mkv",
            size = null,
            etag = null,
            lastModified = null,
            mimeType = "video/x-matroska",
        )
        var reportedFailure: Throwable? = null

        val resolution = resolveWebDavDirectoryPlayback(
            client = ListingWebDavClient(error = IllegalStateException("offline")),
            request = original,
            onDirectoryReadFailure = { reportedFailure = it },
        )

        assertEquals(original, resolution.request)
        assertNull(resolution.episodeQueue)
        assertEquals("offline", reportedFailure?.message)
    }

    private fun media(name: String, uri: Uri): MediaEntry = MediaEntry(
        name = name,
        uri = uri.toString(),
        isDirectory = false,
        size = 10L,
        lastModified = 1L,
    )

    private fun remote(name: String, path: String): WebDavItem = WebDavItem(
        name = name,
        path = path,
        isDirectory = false,
        size = 10L,
        etag = name,
        lastModified = 1L,
    )
}

private class RecordingLocalDirectoryReader(
    private val entries: List<MediaEntry>,
) : LocalDirectoryReader {
    var requestedDocumentUri: String? = null

    override fun rootDocumentUri(treeUri: String): String = treeUri

    override suspend fun listChildren(documentUri: String): List<MediaEntry> {
        requestedDocumentUri = documentUri
        return entries
    }
}

private class ListingWebDavClient(
    private val items: List<WebDavItem> = emptyList(),
    private val error: Throwable? = null,
) : WebDavClient {
    var requestedPath: String? = null

    override suspend fun list(path: String): List<WebDavItem> {
        requestedPath = path
        error?.let { throw it }
        return items
    }

    override suspend fun head(path: String): RemoteFileInfo = error("unused")

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = error("unused")

    override suspend fun download(
        path: String,
        target: java.io.File,
        onBytesRead: (Long) -> Unit,
    ): Long = error("unused")
}
