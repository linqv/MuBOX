package org.mubox.reader.feature.reader

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.mubox.reader.core.model.media.LocalArchiveFormat
import org.mubox.reader.core.model.media.LocalDocumentFormat
import org.mubox.reader.core.ports.ComicReaderSession
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun openerPassesSeekableFileDescriptorAndFormatToSessionFactory() {
        val archive = temp.newFile("book.cbt").apply {
            writeBytes(ByteArray(4096) { 7 })
        }
        val calls = mutableListOf<OpenLocalFdCall>()
        val opener = LocalComicOpener(
            context = ApplicationProvider.getApplicationContext(),
            openSession = { fd, size, format ->
                calls += OpenLocalFdCall(
                    fd = fd,
                    size = size,
                    format = format,
                )
                FakeReaderSession(pageCount = 1)
            },
        )

        val session = opener.open(Uri.fromFile(archive), archive.name)

        assertEquals(1, session.pageCount)
        assertEquals(listOf(LocalArchiveFormat.Tar), calls.map { it.format })
        assertEquals(listOf(archive.length()), calls.map { it.size })
        assertTrue(calls.single().fd > 0)
    }

    @Test
    fun openerPassesDocumentDescriptorAndFormatToDocumentFactory() {
        val document = temp.newFile("book.pdf").apply {
            writeBytes(ByteArray(1024) { 8 })
        }
        val documentCalls = mutableListOf<OpenLocalDocumentCall>()
        val archiveCalls = mutableListOf<OpenLocalFdCall>()
        val opener = LocalComicOpener(
            context = ApplicationProvider.getApplicationContext(),
            openSession = { fd, size, format ->
                archiveCalls += OpenLocalFdCall(
                    fd = fd,
                    size = size,
                    format = format,
                )
                FakeReaderSession(pageCount = 1)
            },
            openDocumentSession = { descriptor, fileName, format ->
                documentCalls += OpenLocalDocumentCall(
                    fd = descriptor.fd,
                    fileName = fileName,
                    format = format,
                )
                FakeReaderSession(pageCount = 2)
            },
        )

        val session = opener.open(Uri.fromFile(document), document.name)

        assertEquals(2, session.pageCount)
        assertTrue(archiveCalls.isEmpty())
        assertEquals(listOf("book.pdf"), documentCalls.map { it.fileName })
        assertEquals(listOf(LocalDocumentFormat.Pdf), documentCalls.map { it.format })
        assertTrue(documentCalls.single().fd > 0)
    }

    @Test
    fun openerRejectsUnsupportedLocalComicExtensionBeforeOpeningAnySession() {
        val opener = LocalComicOpener(
            context = ApplicationProvider.getApplicationContext(),
            openSession = { _, _, _ -> error("archive factory should not be called") },
            openDocumentSession = { _, _, _ -> error("document factory should not be called") },
        )

        val error = runCatching {
            opener.open(Uri.fromFile(temp.newFile("book.rar")), "book.rar")
        }.exceptionOrNull()

        assertEquals("暂不支持这个本地阅读格式", error?.message)
    }

    private data class OpenLocalFdCall(
        val fd: Int,
        val size: Long,
        val format: LocalArchiveFormat,
    )

    private data class OpenLocalDocumentCall(
        val fd: Int,
        val fileName: String,
        val format: LocalDocumentFormat,
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
