package org.mubox.reader.nativebridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narrow source-level JNI contract: blocking native entry points must carry @WorkerThread.
 * Dispatcher behavior is covered through injected-dispatcher tests at each caller boundary.
 */
class NativeWorkerThreadContractTest {

    private val srcRoot = File("src/main/kotlin/org/mubox/reader")

    @Test
    fun comicEngine_openLocal_hasWorkerThread() {
        assertAnnotationAboveFunction(
            File(srcRoot, "nativebridge/ComicEngine.kt"),
            "fun openLocal",
        )
    }

    @Test
    fun comicEngine_openLocalFd_hasWorkerThread() {
        assertAnnotationAboveFunction(
            File(srcRoot, "nativebridge/ComicEngine.kt"),
            "fun openLocalFd",
        )
    }

    @Test
    fun comicEngine_openRemote_hasWorkerThread() {
        assertAnnotationAboveFunction(
            File(srcRoot, "nativebridge/ComicEngine.kt"),
            "fun openRemote",
        )
    }

    @Test
    fun comicReaderSession_loadPageToFile_hasWorkerThread() {
        assertAnnotationAboveFunction(
            File(srcRoot, "nativebridge/ComicEngine.kt"),
            "fun loadPageToFile",
        )
    }

    @Test
    fun comicReaderSession_updateViewport_hasWorkerThread() {
        assertAnnotationAboveFunction(
            File(srcRoot, "nativebridge/ComicEngine.kt"),
            "fun updateViewport",
        )
    }

    @Test
    fun comicReaderSession_plannedRanges_hasWorkerThread() {
        assertAnnotationAboveFunction(
            File(srcRoot, "nativebridge/ComicEngine.kt"),
            "fun plannedRanges",
        )
    }

    @Test
    fun comicReaderSession_reconcilePrefetchPlan_hasWorkerThread() {
        assertAnnotationAboveFunction(
            File(srcRoot, "nativebridge/ComicEngine.kt"),
            "fun reconcilePrefetchPlan",
        )
    }

    @Test
    fun comicReaderSession_prefetchRange_hasWorkerThread() {
        assertAnnotationAboveFunction(
            File(srcRoot, "nativebridge/ComicEngine.kt"),
            "fun prefetchRange",
        )
    }

    @Test
    fun comicReaderSession_allPrefetchRangeOverloadsHaveWorkerThread() {
        val file = File(srcRoot, "nativebridge/ComicEngine.kt")
        val lines = file.readLines()
        val prefetchIndexes = lines.withIndex()
            .filter { it.value.contains("fun prefetchRange") }
            .map { it.index }

        assertEquals("Expected ComicSession prefetchRange overloads", 2, prefetchIndexes.size)
        prefetchIndexes.forEach { idx ->
            val preceding = lines.subList(maxOf(0, idx - 3), idx).joinToString("\n")
            assertTrue(
                "@WorkerThread should appear above prefetchRange overload near line ${idx + 1}",
                preceding.contains("@WorkerThread"),
            )
        }
    }

    @Test
    fun remoteComicSessionAdvancesPrefetchOnPageDemand() {
        val session = ComicSession(
            native = NoopComicNative,
            handle = 1,
            pageCount = 1,
            rangeProviderFileId = 7,
        )

        assertTrue(session.advancePrefetchOnPageDemand)
    }

    @Test
    fun localComicSessionDoesNotAdvancePrefetchOnPageDemand() {
        val session = ComicSession(
            native = NoopComicNative,
            handle = 1,
            pageCount = 1,
        )

        assertFalse(session.advancePrefetchOnPageDemand)
    }

    private fun assertAnnotationAboveFunction(file: File, funSignature: String) {
        val lines = file.readLines()
        val idx = lines.indexOfFirst { it.contains(funSignature) }
        assertTrue("Could not find '$funSignature' in ${file.name}", idx > 0)
        val preceding = lines.subList(maxOf(0, idx - 3), idx).joinToString("\n")
        assertTrue(
            "@WorkerThread should appear above '$funSignature' in ${file.name}",
            preceding.contains("@WorkerThread"),
        )
    }

    private object NoopComicNative : ComicNativeFacade {
        override fun openLocal(path: String): Long = 1

        override fun openLocalFd(fd: Int, size: Long, format: String): Long = 1

        override fun openRemoteCachedV1(
            fileId: Long,
            size: Long,
            cacheDir: String,
            comicKey: String,
            validator: String,
        ): Long = 1

        override fun pageCount(handle: Long): Int = 1

        override fun loadPageToFile(handle: Long, pageIndex: Int, outputPath: String): Int = 0

        override fun updateViewport(
            handle: Long,
            pageIndex: Int,
            networkClass: Int,
            forwardPrefetchPageCount: Int,
        ): Int = 0

        override fun diagnostics(handle: Long): String = ""

        override fun plannedRanges(
            handle: Long,
            pageIndex: Int,
            networkClass: Int,
            forwardPrefetchPageCount: Int,
        ): String = "v2;ok"

        override fun reconcilePrefetchPlanV1(
            handle: Long,
            pageIndex: Int,
            networkClass: Int,
            forwardPrefetchPageCount: Int,
            byteBudget: Long,
            activeRanges: LongArray,
            completedRanges: LongArray,
        ): LongArray = longArrayOf(1, 0, 0, 0)

        override fun prefetchRemoteRangeV1(
            handle: Long,
            start: Long,
            endInclusive: Long,
            priority: Int,
            protectedRanges: LongArray,
        ): Int = 0

        override fun cancelRemoteIoV1(handle: Long) = Unit

        override fun close(handle: Long) = Unit

        override fun lastErrorMessage(): String = ""
    }
}
