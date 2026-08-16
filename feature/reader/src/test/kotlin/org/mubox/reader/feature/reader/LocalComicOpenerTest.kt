package org.mubox.reader.feature.reader

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.mubox.reader.core.ports.ComicReaderSession
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalComicOpenerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun openerPassesSeekableFileDescriptorAndSizeToSessionFactory() {
        val archive = temp.newFile("book.cbz").apply {
            writeBytes(ByteArray(4096) { 7 })
        }
        val calls = mutableListOf<OpenLocalFdCall>()
        val opener = LocalComicOpener(
            context = ApplicationProvider.getApplicationContext(),
            openSession = { fd, size ->
                calls += OpenLocalFdCall(
                    fd = fd,
                    size = size,
                )
                FakeReaderSession(pageCount = 1)
            },
        )

        val session = opener.open(Uri.fromFile(archive), archive.name)

        assertEquals(1, session.pageCount)
        assertEquals(listOf(archive.length()), calls.map { it.size })
        assertTrue(calls.single().fd > 0)
    }

    @Test
    fun openerRejectsUnsupportedLocalComicExtensionBeforeOpeningAnySession() {
        val opener = LocalComicOpener(
            context = ApplicationProvider.getApplicationContext(),
            openSession = { _, _ -> error("archive factory should not be called") },
        )

        val error = runCatching {
            opener.open(Uri.fromFile(temp.newFile("book.rar")), "book.rar")
        }.exceptionOrNull()

        assertEquals("暂不支持这个本地阅读格式", error?.message)
    }

    private data class OpenLocalFdCall(
        val fd: Int,
        val size: Long,
    )

    private class FakeReaderSession(
        override val pageCount: Int,
    ) : ComicReaderSession {
        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            outputFile.writeBytes(byteArrayOf(pageIndex.toByte()))
            return outputFile
        }

        override fun close() = Unit
    }
}
