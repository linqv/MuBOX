package org.mubox.reader.video.proxy

import org.mubox.reader.core.model.media.WebDavSubtitleOpenRequest
import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import org.mubox.reader.core.model.settings.VideoForwardPrefetchMode
import org.mubox.reader.core.model.settings.VideoProxySettings
import org.mubox.reader.core.remote.ContentRange
import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavClientFactory
import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.core.remote.WebDavStreamResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProxyManagerTest {
    @Test
    fun openRegistersMainAndSubtitleUuidRoutesOnOneNativeProxy() = runTest {
        val native = FakeMediaProxyNative()
        var clientCreates = 0
        val manager = manager(native)

        val session = manager.open(
            request = request(
                subtitles = listOf(
                    WebDavSubtitleOpenRequest(
                        remotePath = "/movie.zh.srt",
                        displayName = "movie.zh.srt",
                        size = 4L,
                        etag = null,
                        lastModified = null,
                        mimeType = "application/x-subrip",
                    ),
                ),
            ),
            clientFactory = WebDavClientFactory {
                clientCreates += 1
                RecordingClient()
            },
            proxySettings = VideoProxySettings(
                seekOptimizationEnabled = false,
                forwardPrefetchMode = VideoForwardPrefetchMode.AGGRESSIVE,
            ),
        )

        assertEquals(0, clientCreates)
        assertEquals(2, session.streamIds.size)
        assertEquals(session.streamId, MuBoxVideoProxy.streamIdFromUrl(session.url))
        session.streamIds.forEach { UUID.fromString(it) }
        assertEquals(URL(session.url).port, URL(session.subtitleUrls.single()).port)
        assertTrue(session.url.endsWith("/movie.mp4"))
        assertTrue(session.subtitleUrls.single().endsWith("/movie.zh.srt"))
        assertEquals(1, native.proxyCreateCalls.size)
        assertEquals(1, native.proxyStartCalls.size)
        assertEquals(2, native.streamCreateCalls.size)
        assertEquals(listOf("video/mp4", "application/x-subrip"), native.streamCreateCalls.map { it.mime })
        assertTrue(native.streamCreateCalls.all { !it.seekEnabled && it.prefetchSegments == 2 })

        native.streamCreateCalls.forEachIndexed { index, call ->
            val requestId = index.toLong() + 1L
            call.bridge.openFetchV1(
                requestId = requestId,
                start = 0L,
                endInclusive = 0L,
                mode = MediaProxyNetworkBridge.MODE_RANGE,
            )
            call.bridge.closeFetchV1(requestId)
        }
        assertEquals(2, clientCreates)

        manager.close(session)
        assertEquals(listOf(100L, 101L), native.streamCloseCalls)
        assertEquals(listOf(10L), native.proxyCloseCalls)
    }

    @Test
    fun eachStreamKeepsTheFactorySnapshotCapturedWhenItWasOpened() = runTest {
        val native = FakeMediaProxyNative()
        val firstClient = RecordingClient()
        val secondClient = RecordingClient()
        val manager = manager(native)

        val first = manager.open(request(), WebDavClientFactory { firstClient })
        val second = manager.open(request(), WebDavClientFactory { secondClient })

        native.streamCreateCalls[0].bridge.openFetchV1(1L, 0L, 0L, MediaProxyNetworkBridge.MODE_RANGE)
        native.streamCreateCalls[0].bridge.closeFetchV1(1L)
        native.streamCreateCalls[1].bridge.openFetchV1(2L, 0L, 0L, MediaProxyNetworkBridge.MODE_RANGE)
        native.streamCreateCalls[1].bridge.closeFetchV1(2L)

        assertEquals(1, firstClient.rangeCalls)
        assertEquals(1, secondClient.rangeCalls)
        manager.close(first)
        manager.close(second)
    }

    @Test
    fun closingUnknownStreamDoesNotShutdownActiveProxy() = runTest {
        val native = FakeMediaProxyNative()
        val manager = manager(native)
        val session = manager.open(request(), factory())

        manager.close("missing-stream")

        assertEquals(emptyList<Long>(), native.streamCloseCalls)
        assertEquals(emptyList<Long>(), native.proxyCloseCalls)
        assertTrue(manager.statistics(session.streamId) == null)

        manager.close(session)
        assertEquals(listOf(10L), native.proxyCloseCalls)
    }

    @Test
    fun closingLastStreamAllowsLaterOpenToCreateFreshProxy() = runTest {
        val native = FakeMediaProxyNative()
        val manager = manager(native)

        val first = manager.open(request(), factory())
        manager.close(first)
        val second = manager.open(request(), factory())
        manager.close(second)

        assertEquals(2, native.proxyCreateCalls.size)
        assertEquals(listOf(10L, 11L), native.proxyStartCalls)
        assertEquals(listOf(10L, 11L), native.proxyCloseCalls)
    }

    @Test
    fun failedSubtitleRegistrationClosesMainStreamAndProxy() = runTest {
        val native = FakeMediaProxyNative().apply { failStreamCreateAt = 2 }
        val manager = manager(native)
        val openRequest = request(
            subtitles = listOf(
                WebDavSubtitleOpenRequest(
                    remotePath = "/movie.srt",
                    displayName = "movie.srt",
                    size = 5L,
                    etag = null,
                    lastModified = null,
                    mimeType = "application/x-subrip",
                ),
            ),
        )

        val error = runCatching { manager.open(openRequest, factory()) }.exceptionOrNull()

        assertTrue(error is MediaProxyNativeException)
        assertEquals(listOf(100L), native.streamCloseCalls)
        assertEquals(listOf(10L), native.proxyCloseCalls)
        native.failStreamCreateAt = null
        val recovered = manager.open(request(), factory())
        assertTrue(recovered.url.startsWith("http://127.0.0.1:"))
        manager.close(recovered)
    }

    @Test
    fun statisticsDelegatesToNativeStreamHandle() = runTest {
        val native = FakeMediaProxyNative()
        val manager = manager(native)
        val session = manager.open(request(), factory())
        native.statsByStream[100L] =
            """{"currentRange":"bytes=0-9","remoteHttpStatus":200,"memoryCacheHits":3,"prefetchState":null,"diagnosticMessage":"ok"}"""

        assertEquals(
            VideoProxyRuntimeStats("bytes=0-9", 200, 3L, null, "ok"),
            manager.statistics(session.streamId),
        )
        assertNull(manager.statistics("missing"))
        manager.close(session)
    }

    @Test
    fun closingManagerIsTerminal() = runTest {
        val native = FakeMediaProxyNative()
        val manager = manager(native)
        manager.close()

        val error = runCatching { manager.open(request(), factory()) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(native.proxyCreateCalls.isNotEmpty())
    }

    private fun manager(native: FakeMediaProxyNative): VideoProxyManager =
        VideoProxyManager(nativeProvider = { native })

    private fun request(
        subtitles: List<WebDavSubtitleOpenRequest> = emptyList(),
    ): WebDavVideoOpenRequest =
        WebDavVideoOpenRequest(
            accountId = "account-1",
            remotePath = "/movie.mp4",
            displayName = "movie.mp4",
            size = 10L,
            etag = null,
            lastModified = null,
            mimeType = "video/mp4",
            subtitles = subtitles,
        )

    private fun factory(): WebDavClientFactory = WebDavClientFactory { RecordingClient() }

    private class RecordingClient : WebDavClient {
        var rangeCalls = 0

        override suspend fun list(path: String): List<WebDavItem> = error("unused")

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, 10L, null, null, true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("unused")

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
        ): WebDavStreamResponse {
            rangeCalls += 1
            val end = endInclusive ?: 9L
            val bytes = ByteArray((end - start + 1L).toInt())
            return WebDavStreamResponse(
                stream = ByteArrayInputStream(bytes),
                statusCode = 206,
                contentLength = bytes.size.toLong(),
                contentRange = ContentRange(start, end, 10L),
                contentType = "video/mp4",
                totalSize = 10L,
                close = {},
            )
        }

        override suspend fun download(
            path: String,
            target: File,
            onBytesRead: (Long) -> Unit,
        ): Long = error("unused")
    }
}
