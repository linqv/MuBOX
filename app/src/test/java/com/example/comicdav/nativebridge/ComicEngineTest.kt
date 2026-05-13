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
        val session = ComicEngine(native).openLocal("/tmp/book.cbz")

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
        )

        assertEquals(
            RemoteOpenCall(4, 100, cacheDir.absolutePath, "comic-key", "etag-1"),
            native.remoteOpenCalls.single(),
        )
    }

    @Test
    fun updateViewportCallsNativeForOpenSession() {
        val native = FakeComicNative(openHandle = 5, pageCount = 1)
        val session = ComicEngine(native).openLocal("/tmp/book.cbz")

        session.updateViewport(pageIndex = 3, networkClass = 2)

        assertEquals(ViewportCall(5, 3, 2), native.viewportCalls.single())
    }

    @Test
    fun diagnosticsReadsNativeSessionDiagnostics() {
        val native = FakeComicNative(openHandle = 5, pageCount = 1, diagnostics = "planned_request_count=2")
        val session = ComicEngine(native).openLocal("/tmp/book.cbz")

        assertEquals("planned_request_count=2", session.diagnostics())
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
    ) : ComicNativeFacade {
        val closedHandles = mutableListOf<Long>()
        val loadCalls = mutableListOf<LoadCall>()
        val remoteOpenCalls = mutableListOf<RemoteOpenCall>()
        val viewportCalls = mutableListOf<ViewportCall>()

        override fun openLocal(path: String): Long = openHandle

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

        override fun updateViewport(handle: Long, pageIndex: Int, networkClass: Int): Int {
            viewportCalls += ViewportCall(handle, pageIndex, networkClass)
            return 0
        }

        override fun diagnostics(handle: Long): String = diagnostics

        override fun lastErrorMessage(): String = lastError
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
    )
}
