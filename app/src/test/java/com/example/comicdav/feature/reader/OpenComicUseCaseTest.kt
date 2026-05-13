package com.example.comicdav.feature.reader

import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.data.ReadingProgressStore
import com.example.comicdav.nativebridge.ComicReaderSession
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavItem
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenComicUseCaseTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun downloadsRemoteFileOpensLocalSessionAndReturnsSavedPage() = runTest {
        val client = FakeWebDavClient(
            info = RemoteFileInfo(
                path = "/books/book.cbz",
                size = 4,
                etag = "\"v1\"",
                lastModified = 123,
                supportsRange = true,
            ),
            bytes = byteArrayOf(1, 2, 3, 4),
        )
        val progress = FakeProgressStore(savedPage = 3)
        val openedPaths = mutableListOf<String>()
        val useCase = OpenComicUseCase(
            accountId = "account",
            cache = ComicDownloadCache(temp.root),
            progressStore = progress,
            openSession = { path ->
                openedPaths += path
                FakeReaderSession(pageCount = 5)
            },
        )

        val result = useCase.open(client, "/books/book.cbz")

        assertEquals(3, result.initialPage)
        assertEquals(5, result.session.pageCount)
        assertTrue(File(openedPaths.single()).isFile)
        assertEquals("/books/book.cbz", client.downloadedPath)
        assertEquals(result.comicKey, progress.loadedKey)
    }

    private class FakeProgressStore(
        private val savedPage: Int,
    ) : ReadingProgressGateway {
        var loadedKey: String? = null

        override suspend fun savePage(comicKey: String, pageIndex: Int) = Unit

        override suspend fun loadPage(comicKey: String): Int {
            loadedKey = comicKey
            return savedPage
        }
    }

    private class FakeReaderSession(
        override val pageCount: Int,
    ) : ComicReaderSession {
        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            outputFile.writeBytes(byteArrayOf(pageIndex.toByte()))
            return outputFile
        }

        override fun close() = Unit
    }

    private class FakeWebDavClient(
        private val info: RemoteFileInfo,
        private val bytes: ByteArray,
    ) : WebDavClient {
        var downloadedPath: String? = null

        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo = info

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = error("unused")

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long {
            downloadedPath = path
            target.writeBytes(bytes)
            onBytesRead(bytes.size.toLong())
            return bytes.size.toLong()
        }
    }
}
