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
    ) : ComicNativeFacade {
        val closedHandles = mutableListOf<Long>()
        val loadCalls = mutableListOf<LoadCall>()

        override fun openLocal(path: String): Long = openHandle

        override fun pageCount(handle: Long): Int = pageCount

        override fun loadPageToFile(handle: Long, pageIndex: Int, outputPath: String): Int {
            loadCalls += LoadCall(handle, pageIndex, outputPath)
            return 0
        }

        override fun close(handle: Long) {
            closedHandles += handle
        }

        override fun lastErrorMessage(): String = lastError
    }

    private data class LoadCall(
        val handle: Long,
        val pageIndex: Int,
        val outputPath: String,
    )
}
