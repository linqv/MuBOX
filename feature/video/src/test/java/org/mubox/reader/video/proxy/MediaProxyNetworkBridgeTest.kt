package org.mubox.reader.video.proxy

import org.mubox.reader.core.remote.ContentRange
import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.core.remote.WebDavStreamResponse
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProxyNetworkBridgeTest {
    @Test
    fun knownHeadMetadataDoesNotCreateWebDavClient() {
        var clientCreates = 0
        val bridge = bridge(
            knownSize = 123L,
            knownLastModified = 456L,
            openClient = {
                clientCreates += 1
                error("client should stay lazy")
            },
        )

        assertArrayEquals(longArrayOf(123L, 456L), bridge.headV1())
        assertEquals(0, clientCreates)

        bridge.close()
    }

    @Test
    fun callbacksReuseOneLazyClientSnapshotAndReadIntoDirectBuffer() {
        val client = RecordingWebDavClient()
        var clientCreates = 0
        val bridge = bridge(
            knownSize = null,
            knownLastModified = null,
            openClient = {
                clientCreates += 1
                client
            },
        )

        assertArrayEquals(longArrayOf(10L, 99L), bridge.headV1())
        val metadata = bridge.openFetchV1(
            requestId = 7L,
            start = 2L,
            endInclusive = 4L,
            mode = MediaProxyNetworkBridge.MODE_RANGE,
        )
        assertArrayEquals(longArrayOf(206L, 3L, 2L, 4L, 10L), metadata)

        val target = ByteBuffer.allocateDirect(3)
        assertEquals(3, bridge.readFetchIntoV1(7L, target))
        target.flip()
        val bytes = ByteArray(target.remaining())
        target.get(bytes)
        assertArrayEquals(byteArrayOf(2, 3, 4), bytes)

        bridge.closeFetchV1(7L)
        assertEquals(1, client.rangeCalls)
        assertEquals(listOf(2L to 4L), client.ranges)
        assertEquals(1, clientCreates)
        assertEquals(1, client.responseCloses.get())
        assertEquals(1, client.transportCancels.get())

        bridge.close()
    }

    @Test
    fun fullFetchUsesFullWebDavOperationAndUnknownRangeEndStaysOpenEnded() {
        val client = RecordingWebDavClient()
        val bridge = bridge(openClient = { client })

        bridge.openFetchV1(1L, 0L, -1L, MediaProxyNetworkBridge.MODE_RANGE)
        bridge.closeFetchV1(1L)
        val fullMetadata = bridge.openFetchV1(2L, 999L, 1_000L, MediaProxyNetworkBridge.MODE_FULL)

        assertEquals(listOf(0L to null), client.ranges)
        assertEquals(1, client.fullCalls)
        assertArrayEquals(longArrayOf(200L, 10L, -1L, -1L, 10L), fullMetadata)

        bridge.close()
    }

    @Test
    fun cancelAndCloseAreIdempotentAndReleaseResponseAndTransport() {
        val client = RecordingWebDavClient()
        val bridge = bridge(openClient = { client })
        bridge.openFetchV1(8L, 0L, 2L, MediaProxyNetworkBridge.MODE_RANGE)

        bridge.cancelFetchV1(8L)
        bridge.cancelFetchV1(8L)
        bridge.closeFetchV1(8L)
        bridge.closeFetchV1(8L)

        assertEquals(1, client.responseCloses.get())
        assertEquals(1, client.transportCancels.get())
        bridge.close()
    }

    @Test
    fun bridgeCloseCancelsEveryActiveFetchAndRejectsFurtherCallbacks() {
        val client = RecordingWebDavClient()
        val bridge = bridge(openClient = { client })
        bridge.openFetchV1(1L, 0L, 1L, MediaProxyNetworkBridge.MODE_RANGE)
        bridge.openFetchV1(2L, 2L, 3L, MediaProxyNetworkBridge.MODE_RANGE)

        bridge.close()
        bridge.close()

        assertEquals(2, client.responseCloses.get())
        assertEquals(2, client.transportCancels.get())
        assertThrows(IllegalStateException::class.java) { bridge.headV1() }
    }

    @Test
    fun readRequiresDirectByteBuffer() {
        val client = RecordingWebDavClient()
        val bridge = bridge(openClient = { client })
        bridge.openFetchV1(1L, 0L, 1L, MediaProxyNetworkBridge.MODE_RANGE)

        val error = assertThrows(IllegalArgumentException::class.java) {
            bridge.readFetchIntoV1(1L, ByteBuffer.allocate(2))
        }

        assertTrue(error.message.orEmpty().contains("direct ByteBuffer"))
        bridge.close()
    }

    private fun bridge(
        knownSize: Long? = 10L,
        knownLastModified: Long? = 99L,
        openClient: suspend () -> WebDavClient?,
    ): MediaProxyNetworkBridge =
        MediaProxyNetworkBridge(
            streamId = "stream-1",
            remotePath = "/movie.mp4",
            knownSize = knownSize,
            knownLastModified = knownLastModified,
            openClient = openClient,
        )

    private class RecordingWebDavClient : WebDavClient {
        var rangeCalls = 0
        var fullCalls = 0
        val ranges = mutableListOf<Pair<Long, Long?>>()
        val responseCloses = AtomicInteger()
        val transportCancels = AtomicInteger()

        override suspend fun list(path: String): List<WebDavItem> = error("unused")

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(
                path = path,
                size = 10L,
                etag = null,
                lastModified = 99L,
                supportsRange = true,
            )

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("unused")

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
        ): WebDavStreamResponse = rangeResponse(start, endInclusive)

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
            registerCancellation: (Closeable) -> Unit,
        ): WebDavStreamResponse {
            registerCancellation(Closeable { transportCancels.incrementAndGet() })
            return rangeResponse(start, endInclusive)
        }

        override suspend fun openFullStream(path: String): WebDavStreamResponse = fullResponse()

        override suspend fun openFullStream(
            path: String,
            registerCancellation: (Closeable) -> Unit,
        ): WebDavStreamResponse {
            registerCancellation(Closeable { transportCancels.incrementAndGet() })
            return fullResponse()
        }

        override suspend fun download(
            path: String,
            target: File,
            onBytesRead: (Long) -> Unit,
        ): Long = error("unused")

        private fun rangeResponse(start: Long, endInclusive: Long?): WebDavStreamResponse {
            rangeCalls += 1
            ranges += start to endInclusive
            val end = endInclusive ?: 9L
            val bytes = ByteArray((end - start + 1L).toInt()) { offset -> (start + offset).toByte() }
            return WebDavStreamResponse(
                stream = ByteArrayInputStream(bytes),
                statusCode = 206,
                contentLength = bytes.size.toLong(),
                contentRange = ContentRange(start, end, 10L),
                contentType = "video/mp4",
                totalSize = 10L,
                close = { responseCloses.incrementAndGet() },
            )
        }

        private fun fullResponse(): WebDavStreamResponse {
            fullCalls += 1
            val bytes = ByteArray(10) { it.toByte() }
            return WebDavStreamResponse(
                stream = ByteArrayInputStream(bytes),
                statusCode = 200,
                contentLength = bytes.size.toLong(),
                contentRange = null,
                contentType = "video/mp4",
                totalSize = 10L,
                close = { responseCloses.incrementAndGet() },
            )
        }
    }
}
