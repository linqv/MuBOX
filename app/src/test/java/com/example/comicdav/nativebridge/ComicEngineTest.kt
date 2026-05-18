package com.example.comicdav.nativebridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicEngineTest {
    private val temp = TemporaryFolder().apply { create() }

    @Test
    fun openLocalMapsZeroHandleToNativeException() {
        val native = FakeComicNative(openHandle = 0, lastError = "invalid zip")
        val engine = ComicEngine(native)

        val error = runCatching { engine.openLocal("/tmp/book.cbz") }.exceptionOrNull()

        assertTrue(error is ComicNativeException)
        assertEquals("invalid zip", error?.message)
    }

    @Test
    fun openLocalMapsNegativePageCountToNativeExceptionAndClosesHandle() {
        val native = FakeComicNative(openHandle = 7, pageCount = -1, lastError = "bad handle")
        val engine = ComicEngine(native)

        val error = runCatching { engine.openLocal("/tmp/book.cbz") }.exceptionOrNull()

        assertTrue(error is ComicNativeException)
        assertEquals("bad handle", error?.message)
        assertEquals(listOf(7L), native.closedHandles)
    }

    @Test
    fun loadPageReturnsCacheFilePath() {
        val output = temp.newFile("page-0.bin")
        val native = FakeComicNative(openHandle = 3, pageCount = 2)
        val session = ComicEngine(native).openRemote(
            fileId = 4,
            size = 100,
            cacheDir = temp.newFolder("remote-planned-cache"),
            comicKey = "comic-key",
            validator = "etag-1",
            webDavPrefetchPageCount = 8,
        )

        val pageFile = session.loadPageToFile(0, output)

        assertEquals(output, pageFile)
        assertEquals(LoadCall(3L, 0, output.absolutePath), native.loadCalls.single())
    }

    @Test
    fun openRemotePassesCacheIdentityToNative() {
        val native = FakeComicNative(openHandle = 9, pageCount = 1)
        val cacheDir = temp.newFolder("remote-cache")

        ComicEngine(native).openRemote(
            fileId = 4,
            size = 100,
            cacheDir = cacheDir,
            comicKey = "comic-key",
            validator = "etag-1",
            webDavPrefetchPageCount = 6,
        )

        assertEquals(
            RemoteOpenCall(4, 100, cacheDir.absolutePath, "comic-key", "etag-1"),
            native.remoteOpenCalls.single(),
        )
    }

    @Test
    fun openLocalFdReportsNativeOpenAndPageCountTiming() {
        val native = FakeComicNative(openHandle = 12, pageCount = 5)
        val elapsedTimes = mutableListOf(10L, 35L, 42L)
        val diagnosticLines = mutableListOf<String>()
        val engine = ComicEngine(
            native = native,
            logDiagnostic = { event -> diagnosticLines += event() },
            elapsedRealtimeMs = { elapsedTimes.removeAt(0) },
        )

        val session = engine.openLocalFd(fd = 11, size = 2048, format = "zip")

        assertEquals(5, session.pageCount)
        assertEquals(
            listOf("native_open_local_fd_done format=zip sizeBytes=2048 nativeOpenMs=25 pageCountMs=7 pageCount=5"),
            diagnosticLines,
        )
    }

    @Test
    fun updateViewportCallsNativeForOpenSession() {
        val native = FakeComicNative(openHandle = 5, pageCount = 1)
        val session = ComicEngine(native).openRemote(
            fileId = 4,
            size = 100,
            cacheDir = temp.newFolder("remote-viewport-cache"),
            comicKey = "comic-key",
            validator = "etag-1",
            webDavPrefetchPageCount = 6,
        )

        session.updateViewport(pageIndex = 3, networkClass = 2)

        assertEquals(ViewportCall(5, 3, 2, 6), native.viewportCalls.single())
        assertEquals(6, session.forwardPrefetchPageCount)
    }

    @Test
    fun diagnosticsReadsNativeSessionDiagnostics() {
        val native = FakeComicNative(openHandle = 5, pageCount = 1, diagnostics = "planned_request_count=2")
        val session = ComicEngine(native).openRemote(
            fileId = 4,
            size = 100,
            cacheDir = temp.newFolder("remote-planned-cache"),
            comicKey = "comic-key",
            validator = "etag-1",
            webDavPrefetchPageCount = 8,
        )

        assertEquals("planned_request_count=2", session.diagnostics())
    }

    @Test
    fun plannedRangesParsesNativePlanForOpenSession() {
        val native = FakeComicNative(
            openHandle = 5,
            pageCount = 6,
            plannedRanges = "v1;10,29,1,2|3;40,49,5,4",
        )
        val session = ComicEngine(native).openRemote(
            fileId = 4,
            size = 100,
            cacheDir = temp.newFolder("remote-planned-cache"),
            comicKey = "comic-key",
            validator = "etag-1",
            webDavPrefetchPageCount = 8,
        )

        assertEquals(
            listOf(
                PlannedRemoteRange(start = 10, endInclusive = 29, pages = listOf(2, 3), priority = 1),
                PlannedRemoteRange(start = 40, endInclusive = 49, pages = listOf(4), priority = 5),
            ),
            session.plannedRanges(pageIndex = 2, networkClass = 2),
        )
        assertEquals(PlannedRangeCall(5, 2, 2, 8), native.plannedRangeCalls.single())
    }

    @Test
    fun remoteSessionPrefetchesRangesThroughRegisteredProvider() {
        val native = FakeComicNative(openHandle = 9, pageCount = 1)
        val provider = RecordingRangeProvider(size = 100)
        val fileId = RangeProviderRegistry.register(provider)
        val session = ComicEngine(native).openRemote(
            fileId = fileId,
            size = 100,
            cacheDir = temp.newFolder("range-prefetch-cache"),
            comicKey = "comic-key",
            validator = "etag-1",
        )

        assertTrue(session.prefetchRange(start = 10, endInclusive = 19))

        assertEquals(listOf(10L to 19L), provider.prefetchCalls)
        session.close()
    }

    @Test
    fun remoteSessionPrefetchPassesPriorityAndProtectedRangesThroughRegisteredProvider() {
        val native = FakeComicNative(openHandle = 9, pageCount = 1)
        val provider = RecordingRangeProvider(size = 100)
        val fileId = RangeProviderRegistry.register(provider)
        val session = ComicEngine(native).openRemote(
            fileId = fileId,
            size = 100,
            cacheDir = temp.newFolder("range-prefetch-priority-cache"),
            comicKey = "comic-key",
            validator = "etag-1",
        )

        assertTrue(
            session.prefetchRange(
                start = 10,
                endInclusive = 19,
                priority = 7,
                protectedRanges = listOf(0L..9L, 20L..29L),
            ),
        )

        assertEquals(
            listOf(PriorityPrefetchCall(10, 19, 7, listOf(0L..9L, 20L..29L))),
            provider.priorityPrefetchCalls,
        )
        session.close()
    }

    @Test
    fun closeReleasesNativeHandleOnce() {
        val native = FakeComicNative(openHandle = 5, pageCount = 1)
        val session = ComicEngine(native).openLocal("/tmp/book.cbz")

        session.close()
        session.close()

        assertEquals(listOf(5L), native.closedHandles)
    }

    private class FakeComicNative(
        private val openHandle: Long,
        private val pageCount: Int = 0,
        private val lastError: String = "",
        private val diagnostics: String = "",
        private val plannedRanges: String = "v1",
    ) : ComicNativeFacade {
        val closedHandles = mutableListOf<Long>()
        val loadCalls = mutableListOf<LoadCall>()
        val remoteOpenCalls = mutableListOf<RemoteOpenCall>()
        val viewportCalls = mutableListOf<ViewportCall>()
        val plannedRangeCalls = mutableListOf<PlannedRangeCall>()

        override fun openLocal(path: String): Long = openHandle

        override fun openLocalFd(fd: Int, size: Long, format: String): Long = openHandle

        override fun openRemote(
            fileId: Long,
            size: Long,
            cacheDir: String,
            comicKey: String,
            validator: String,
        ): Long {
            remoteOpenCalls += RemoteOpenCall(fileId, size, cacheDir, comicKey, validator)
            return openHandle
        }

        override fun pageCount(handle: Long): Int = pageCount

        override fun loadPageToFile(handle: Long, pageIndex: Int, outputPath: String): Int {
            loadCalls += LoadCall(handle, pageIndex, outputPath)
            return 0
        }

        override fun close(handle: Long) {
            closedHandles += handle
        }

        override fun updateViewport(
            handle: Long,
            pageIndex: Int,
            networkClass: Int,
            forwardPrefetchPageCount: Int,
        ): Int {
            viewportCalls += ViewportCall(handle, pageIndex, networkClass, forwardPrefetchPageCount)
            return 0
        }

        override fun diagnostics(handle: Long): String = diagnostics

        override fun plannedRanges(
            handle: Long,
            pageIndex: Int,
            networkClass: Int,
            forwardPrefetchPageCount: Int,
        ): String {
            plannedRangeCalls += PlannedRangeCall(handle, pageIndex, networkClass, forwardPrefetchPageCount)
            return plannedRanges
        }

        override fun lastErrorMessage(): String = lastError
    }

    private class RecordingRangeProvider(private val size: Long) : RangeProvider {
        val prefetchCalls = mutableListOf<Pair<Long, Long>>()
        val priorityPrefetchCalls = mutableListOf<PriorityPrefetchCall>()

        override fun size(fileId: Long): Long = size

        override fun readRange(fileId: Long, start: Long, endInclusive: Long): ByteArray =
            ByteArray((endInclusive - start + 1).toInt())

        override fun prefetchRange(start: Long, endInclusive: Long): Boolean {
            prefetchCalls += start to endInclusive
            return true
        }

        override fun prefetchRange(
            start: Long,
            endInclusive: Long,
            priority: Int,
            protectedRanges: List<LongRange>,
        ): Boolean {
            priorityPrefetchCalls += PriorityPrefetchCall(start, endInclusive, priority, protectedRanges)
            return true
        }
    }

    private data class LoadCall(
        val handle: Long,
        val pageIndex: Int,
        val outputPath: String,
    )

    private data class RemoteOpenCall(
        val fileId: Long,
        val size: Long,
        val cacheDir: String,
        val comicKey: String,
        val validator: String,
    )

    private data class ViewportCall(
        val handle: Long,
        val pageIndex: Int,
        val networkClass: Int,
        val forwardPrefetchPageCount: Int,
    )

    private data class PlannedRangeCall(
        val handle: Long,
        val pageIndex: Int,
        val networkClass: Int,
        val forwardPrefetchPageCount: Int,
    )

    private data class PriorityPrefetchCall(
        val start: Long,
        val endInclusive: Long,
        val priority: Int,
        val protectedRanges: List<LongRange>,
    )
}
