package com.example.comicdav.nativebridge

import java.io.File
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
}
