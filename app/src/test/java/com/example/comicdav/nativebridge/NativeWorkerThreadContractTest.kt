package com.example.comicdav.nativebridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level contract test: verifies that blocking native entry points carry @WorkerThread
 * and that key callers dispatch to an IO context.
 */
class NativeWorkerThreadContractTest {

    private val srcRoot = File("src/main/java/com/example/comicdav")

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

        assertEquals("Expected interface and implementation prefetchRange overloads", 4, prefetchIndexes.size)
        prefetchIndexes.forEach { idx ->
            val preceding = lines.subList(maxOf(0, idx - 3), idx).joinToString("\n")
            assertTrue(
                "@WorkerThread should appear above prefetchRange overload near line ${idx + 1}",
                preceding.contains("@WorkerThread"),
            )
        }
    }

    @Test
    fun openComicUseCase_openRemote_dispatchesToIo() {
        val source = File(srcRoot, "feature/reader/OpenComicUseCase.kt").readText()
        assertTrue(
            "OpenComicUseCase should dispatch native call via withContext(ioDispatcher)",
            source.contains("withContext(ioDispatcher)"),
        )
    }

    @Test
    fun webDavLibraryCoverExtractor_extractFirstPageCover_dispatchesToIo() {
        val source = File(srcRoot, "feature/library/WebDavLibraryCoverExtractor.kt").readText()
        assertTrue(
            "WebDavLibraryCoverExtractor should dispatch native call via withContext(ioDispatcher)",
            source.contains("withContext(ioDispatcher)"),
        )
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
        override fun openLocal(path: String, avifImagesEnabled: Boolean): Long = 1

        override fun openLocalFd(fd: Int, size: Long, format: String, avifImagesEnabled: Boolean): Long = 1

        override fun openRemote(
            fileId: Long,
            size: Long,
            cacheDir: String,
            comicKey: String,
            validator: String,
            avifImagesEnabled: Boolean,
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
        ): String = "v1"

        override fun close(handle: Long) = Unit

        override fun lastErrorMessage(): String = ""
    }
}
