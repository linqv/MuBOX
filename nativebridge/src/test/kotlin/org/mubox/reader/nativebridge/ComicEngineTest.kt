package org.mubox.reader.nativebridge

import org.mubox.reader.core.ports.PlannedRemoteRange
import org.mubox.reader.core.ports.RangeProvider
import org.mubox.reader.core.ports.ReconciledPrefetchPlan
import org.mubox.reader.core.ports.ReconciledPrefetchTask
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            native.cachedRemoteOpenCalls.single(),
        )
    }

    @Test
    fun openLocalFdPassesDescriptorAndSizeToNative() {
        val native = FakeComicNative(openHandle = 12, pageCount = 1)

        ComicEngine(native).openLocalFd(fd = 11, size = 2048)

        assertEquals(LocalFdOpenCall(11, 2048), native.localFdOpenCalls.single())
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
    fun legacyNativeWirePayloadsAreRejected() {
        val native = FakeComicNative(
            openHandle = 5,
            pageCount = 1,
            diagnostics = "planned_request_count=2",
            plannedRanges = "v1;10,29,1,2|3",
        )
        val session = ComicEngine(native).openRemote(
            fileId = 4,
            size = 100,
            cacheDir = temp.newFolder("remote-legacy-wire-cache"),
            comicKey = "comic-key",
            validator = "etag-1",
            webDavPrefetchPageCount = 8,
        )

        assertTrue(runCatching { session.diagnostics() }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { session.plannedRanges(pageIndex = 0, networkClass = 2) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun diagnosticsV2PreservesSemicolonDelimitedPayload() {
        val payload = "viewport_page=3;planned_request_count=2;planned_bytes=4096"
        val native = FakeComicNative(
            openHandle = 5,
            pageCount = 1,
            diagnostics = "v2;ok;$payload",
        )
        val session = ComicEngine(native).openRemote(
            fileId = 4,
            size = 100,
            cacheDir = temp.newFolder("remote-v2-diagnostics-cache"),
            comicKey = "comic-key",
            validator = "etag-1",
        )

        assertEquals(payload, session.diagnostics())
    }

    @Test
    fun diagnosticsV2DistinguishesEmptySuccessFromNativeError() {
        val successNative = FakeComicNative(openHandle = 5, pageCount = 1, diagnostics = "v2;ok")
        val successSession = ComicEngine(successNative).openLocal("/tmp/book.cbz")
        assertEquals("", successSession.diagnostics())

        val errorNative = FakeComicNative(
            openHandle = 6,
            pageCount = 1,
            diagnostics = "v2;error",
            lastError = "native handle not found",
        )
        val errorSession = ComicEngine(errorNative).openLocal("/tmp/book.cbz")

        val error = runCatching { errorSession.diagnostics() }.exceptionOrNull()

        assertTrue(error is ComicNativeException)
        assertEquals("native handle not found", error?.message)
    }

    @Test
    fun plannedRangesParsesNativePlanForOpenSession() {
        val native = FakeComicNative(
            openHandle = 5,
            pageCount = 6,
            plannedRanges = "v2;ok;10,29,1,2|3;40,49,5,4",
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
    fun plannedRangesParsesV2SuccessAndDistinguishesEmptyPlan() {
        val native = FakeComicNative(
            openHandle = 5,
            pageCount = 6,
            plannedRanges = "v2;ok",
        )
        val session = ComicEngine(native).openRemote(
            fileId = 4,
            size = 100,
            cacheDir = temp.newFolder("remote-empty-plan-cache"),
            comicKey = "comic-key",
            validator = "etag-1",
        )

        assertEquals(
            emptyList<PlannedRemoteRange>(),
            session.plannedRanges(pageIndex = 2, networkClass = 2),
        )
    }

    @Test
    fun plannedRangesMapsV2NativeErrorToNativeException() {
        val native = FakeComicNative(
            openHandle = 5,
            pageCount = 6,
            plannedRanges = "v2;error",
            lastError = "native handle not found",
        )
        val session = ComicEngine(native).openRemote(
            fileId = 4,
            size = 100,
            cacheDir = temp.newFolder("remote-plan-error-cache"),
            comicKey = "comic-key",
            validator = "etag-1",
        )

        val error = runCatching {
            session.plannedRanges(pageIndex = 2, networkClass = 2)
        }.exceptionOrNull()

        assertTrue(error is ComicNativeException)
        assertEquals("native handle not found", error?.message)
    }

    @Test
    fun reconcilePrefetchPlanPassesStateAndDecodesTaskProtection() {
        val native = FakeComicNative(
            openHandle = 5,
            pageCount = 6,
            reconcileResult = {
                longArrayOf(
                    1, 0,
                    2, 2, 3,
                    1,
                    10, 19, 1, 2, 2, 3,
                    1, 0, 9,
                )
            },
        )
        val session = ComicEngine(native).openRemote(
            fileId = 4,
            size = 100,
            cacheDir = temp.newFolder("remote-reconciled-plan-cache"),
            comicKey = "comic-key",
            validator = "etag-1",
            webDavPrefetchPageCount = 8,
        )
        val active = listOf(PlannedRemoteRange(0, 9, listOf(1), 0))
        val completed = listOf(PlannedRemoteRange(20, 29, listOf(4), 2))

        val result = session.reconcilePrefetchPlan(
            pageIndex = 2,
            networkClass = 2,
            activeRanges = active,
            completedRanges = completed,
            byteBudget = 48,
        )

        assertEquals(
            ReconciledPrefetchPlan(
                retainedPages = setOf(2, 3),
                tasks = listOf(
                    ReconciledPrefetchTask(
                        range = PlannedRemoteRange(10, 19, listOf(2, 3), 1),
                        protectedRanges = listOf(0L..9L),
                    ),
                ),
            ),
            result,
        )
        assertEquals(
            ReconcileCall(
                handle = 5,
                pageIndex = 2,
                networkClass = 2,
                forwardPrefetchPageCount = 8,
                byteBudget = 48,
                activeRanges = listOf(1L, 1L, 0L, 9L, 0L, 1L, 1L),
                completedRanges = listOf(1L, 1L, 20L, 29L, 2L, 1L, 4L),
            ),
            native.reconcileCalls.single(),
        )
    }

    @Test
    fun reconcilePrefetchPlanMapsNativeError() {
        val errorNative = FakeComicNative(
            openHandle = 5,
            pageCount = 1,
            lastError = "invalid reconciliation state",
            reconcileResult = { longArrayOf(1, 1) },
        )
        val errorSession = ComicEngine(errorNative).openLocal("/tmp/book.cbz")

        val error = runCatching {
            errorSession.reconcilePrefetchPlan(0, 2, emptyList(), emptyList(), 48)
        }.exceptionOrNull()

        assertTrue(error is ComicNativeException)
        assertEquals("invalid reconciliation state", error?.message)

    }

    @Test
    fun nativeRangeBundleOwnsPrefetchAndEncodesPerTaskProtection() {
        val native = FakeComicNative(
            openHandle = 9,
            pageCount = 1,
        )
        val provider = RecordingRangeProvider()
        val fileId = RangeProviderRegistry.register(provider)
        val cacheDir = temp.newFolder("native-range-bundle-cache")
        val session = ComicEngine(native).openRemote(
            fileId = fileId,
            size = 100,
            cacheDir = cacheDir,
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
        session.cancelPrefetches()
        session.close()

        assertEquals(
            listOf(RemoteOpenCall(fileId, 100, cacheDir.absolutePath, "comic-key", "etag-1")),
            native.cachedRemoteOpenCalls,
        )
        assertEquals(
            listOf(
                NativePrefetchCall(
                    handle = 9,
                    start = 10,
                    endInclusive = 19,
                    priority = 7,
                    protectedRanges = listOf(1L, 2L, 0L, 9L, 20L, 29L),
                ),
            ),
            native.nativePrefetchCalls,
        )
        assertEquals(listOf(9L), native.nativeCancelCalls)
        assertEquals(1, provider.closeCalls)
    }

    @Test
    fun nativeRangeBundleMapsFalseAndErrorPrefetchResults() {
        val falseNative = FakeComicNative(
            openHandle = 10,
            pageCount = 1,
            nativePrefetchResult = 0,
        )
        val falseProvider = RecordingRangeProvider()
        val falseSession = ComicEngine(falseNative).openRemote(
            RangeProviderRegistry.register(falseProvider),
            100,
            temp.newFolder("native-range-false-cache"),
            "comic-key",
            "etag-1",
        )
        assertFalse(falseSession.prefetchRange(0, 9))
        falseSession.close()

        val errorNative = FakeComicNative(
            openHandle = 11,
            pageCount = 1,
            lastError = "native prefetch failed",
            nativePrefetchResult = -1,
        )
        val errorProvider = RecordingRangeProvider()
        val errorSession = ComicEngine(errorNative).openRemote(
            RangeProviderRegistry.register(errorProvider),
            100,
            temp.newFolder("native-range-error-cache"),
            "comic-key",
            "etag-1",
        )

        val error = runCatching { errorSession.prefetchRange(0, 9) }.exceptionOrNull()

        assertTrue(error is ComicNativeException)
        assertEquals("native prefetch failed", error?.message)
        errorSession.close()
    }

    @Test
    fun nativeRangeBundleBusinessFailureClosesRegisteredProvider() {
        val native = FakeComicNative(
            openHandle = 99,
            cachedOpenHandle = 0,
            pageCount = 1,
            lastError = "native cache open failed",
        )
        val provider = RecordingRangeProvider()
        val fileId = RangeProviderRegistry.register(provider)

        val error = runCatching {
            ComicEngine(native).openRemote(
                fileId,
                100,
                temp.newFolder("native-range-open-error-cache"),
                "comic-key",
                "etag-1",
            )
        }.exceptionOrNull()

        assertTrue(error is ComicNativeException)
        assertEquals("native cache open failed", error?.message)
        assertEquals(1, native.cachedRemoteOpenCalls.size)
        assertEquals(1, provider.closeCalls)
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
        private val plannedRanges: String = "v2;ok",
        private val reconcileResult: () -> LongArray? = { longArrayOf(1, 0, 0, 0) },
        private val cachedOpenHandle: Long = openHandle,
        private val nativePrefetchResult: Int = 1,
    ) : ComicNativeFacade {
        val closedHandles = mutableListOf<Long>()
        val loadCalls = mutableListOf<LoadCall>()
        val localFdOpenCalls = mutableListOf<LocalFdOpenCall>()
        val cachedRemoteOpenCalls = mutableListOf<RemoteOpenCall>()
        val viewportCalls = mutableListOf<ViewportCall>()
        val plannedRangeCalls = mutableListOf<PlannedRangeCall>()
        val reconcileCalls = mutableListOf<ReconcileCall>()
        val nativePrefetchCalls = mutableListOf<NativePrefetchCall>()
        val nativeCancelCalls = mutableListOf<Long>()

        override fun openLocal(path: String): Long = openHandle

        override fun openLocalFd(fd: Int, size: Long): Long {
            localFdOpenCalls += LocalFdOpenCall(fd, size)
            return openHandle
        }

        override fun openRemoteCachedV1(
            fileId: Long,
            size: Long,
            cacheDir: String,
            comicKey: String,
            validator: String,
        ): Long {
            cachedRemoteOpenCalls += RemoteOpenCall(fileId, size, cacheDir, comicKey, validator)
            return cachedOpenHandle
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

        override fun reconcilePrefetchPlanV1(
            handle: Long,
            pageIndex: Int,
            networkClass: Int,
            forwardPrefetchPageCount: Int,
            byteBudget: Long,
            activeRanges: LongArray,
            completedRanges: LongArray,
        ): LongArray? {
            reconcileCalls += ReconcileCall(
                handle = handle,
                pageIndex = pageIndex,
                networkClass = networkClass,
                forwardPrefetchPageCount = forwardPrefetchPageCount,
                byteBudget = byteBudget,
                activeRanges = activeRanges.toList(),
                completedRanges = completedRanges.toList(),
            )
            return reconcileResult()
        }

        override fun prefetchRemoteRangeV1(
            handle: Long,
            start: Long,
            endInclusive: Long,
            priority: Int,
            protectedRanges: LongArray,
        ): Int {
            nativePrefetchCalls += NativePrefetchCall(
                handle = handle,
                start = start,
                endInclusive = endInclusive,
                priority = priority,
                protectedRanges = protectedRanges.toList(),
            )
            return nativePrefetchResult
        }

        override fun cancelRemoteIoV1(handle: Long) {
            nativeCancelCalls += handle
        }

        override fun lastErrorMessage(): String = lastError
    }

    private class RecordingRangeProvider : RangeProvider {
        var closeCalls = 0

        override fun fetchRangeInto(
            fileId: Long,
            requestId: Long,
            start: Long,
            endInclusive: Long,
            target: ByteBuffer,
        ): Int {
            val length = target.remaining()
            target.put(ByteArray(length))
            return length
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private data class LoadCall(
        val handle: Long,
        val pageIndex: Int,
        val outputPath: String,
    )

    private data class LocalFdOpenCall(
        val fd: Int,
        val size: Long,
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

    private data class ReconcileCall(
        val handle: Long,
        val pageIndex: Int,
        val networkClass: Int,
        val forwardPrefetchPageCount: Int,
        val byteBudget: Long,
        val activeRanges: List<Long>,
        val completedRanges: List<Long>,
    )

    private data class NativePrefetchCall(
        val handle: Long,
        val start: Long,
        val endInclusive: Long,
        val priority: Int,
        val protectedRanges: List<Long>,
    )

}
