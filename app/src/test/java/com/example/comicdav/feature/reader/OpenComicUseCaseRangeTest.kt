package com.example.comicdav.feature.reader

import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.data.ReaderLoggingMode
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavException
import com.example.comicdav.network.WebDavItem
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenComicUseCaseRangeTest {
    @get:Rule
    val temp = TemporaryFolder()

    @After
    fun tearDown() {
        ReaderDiagnosticLog.clearSink()
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
    }

    @Test
    fun rangeCapableRemoteOpensNativeRemoteSessionWithoutDownloadingWholeFile() = runTest {
        val client = FakeWebDavClient(
            info = RemoteFileInfo("/books/book.cbz", size = 9, etag = "\"v1\"", lastModified = null, supportsRange = true),
            bytes = byteArrayOf(1, 2, 3, 4),
        )
        val remoteOpens = mutableListOf<RemoteOpenCall>()
        val useCase = OpenComicUseCase(
            accountId = "account",
            cache = ComicDownloadCache(temp.root),
            progressStore = FakeProgressStore(savedPage = 1),
            openRemoteSession = { fileId, size, cacheDir, comicKey, validator ->
                remoteOpens += RemoteOpenCall(fileId, size, cacheDir.absolutePath, comicKey, validator)
                FakeReaderSession(pageCount = 3)
            },
        )

        val result = useCase.open(client, "/books/book.cbz")

        assertEquals(1, result.initialPage)
        assertEquals(3, result.session.pageCount)
        assertEquals(9L, remoteOpens.single().size)
        assertTrue(remoteOpens.single().fileId > 0)
        assertTrue(File(remoteOpens.single().cacheDir).isDirectory)
        assertEquals("\"v1\"", remoteOpens.single().validator)
        assertNull(client.downloadedPath)
    }

    @Test
    fun knownRemoteFileInfoSkipsHeadRequestOnFirstOpen() = runTest {
        val knownInfo = RemoteFileInfo(
            "/books/book.cbz",
            size = 9,
            etag = "\"v1\"",
            lastModified = 123,
            supportsRange = true,
        )
        val client = FakeWebDavClient(
            info = knownInfo,
            bytes = byteArrayOf(1, 2, 3, 4),
        )
        val useCase = OpenComicUseCase(
            accountId = "account",
            cache = ComicDownloadCache(temp.root),
            progressStore = FakeProgressStore(savedPage = 0),
            openRemoteSession = { _, _, _, _, _ -> FakeReaderSession(pageCount = 3) },
        )

        val result = useCase.open(client, "/books/book.cbz", knownInfo = knownInfo)

        assertEquals(3, result.session.pageCount)
        assertEquals(0, client.headCalls)
        assertNull(client.downloadedPath)
    }

    @Test
    fun missingAcceptRangesStillTriesRemoteSessionBeforeWholeFileDownload() = runTest {
        val client = FakeWebDavClient(
            info = RemoteFileInfo("/books/book.cbz", size = 9, etag = "\"v1\"", lastModified = null, supportsRange = false),
            bytes = byteArrayOf(1, 2, 3, 4),
        )
        val remoteOpens = mutableListOf<RemoteOpenCall>()
        val useCase = OpenComicUseCase(
            accountId = "account",
            cache = ComicDownloadCache(temp.root),
            progressStore = FakeProgressStore(savedPage = 0),
            openRemoteSession = { fileId, size, cacheDir, comicKey, validator ->
                remoteOpens += RemoteOpenCall(fileId, size, cacheDir.absolutePath, comicKey, validator)
                FakeReaderSession(pageCount = 3)
            },
        )

        val result = useCase.open(client, "/books/book.cbz")

        assertEquals(3, result.session.pageCount)
        assertEquals(9L, remoteOpens.single().size)
        assertNull(client.downloadedPath)
    }

    @Test
    fun rangeFailureIsReportedWithoutWholeFileDownloadAndLogsReason() = runTest {
        val client = FakeWebDavClient(
            info = RemoteFileInfo("/books/book.cbz", size = 4, etag = "\"v1\"", lastModified = null, supportsRange = true),
            bytes = byteArrayOf(1, 2, 3, 4),
        )
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        val useCase = OpenComicUseCase(
            accountId = "account",
            cache = ComicDownloadCache(temp.root),
            progressStore = FakeProgressStore(savedPage = 0),
            openRemoteSession = { _, _, _, _, _ -> throw WebDavException.RangeNotSupported() },
        )

        try {
            useCase.open(client, "/books/book.cbz")
        } catch (error: WebDavException.RangeNotSupported) {
            assertNull(client.downloadedPath)
            assertTrue(
                sink.lines.any { line ->
                    line.contains("open_remote_range_failed") &&
                        line.contains("RangeNotSupported")
                },
            )
            return@runTest
        }
        error("Expected range failure to be reported")
    }

    @Test
    fun remoteOpenCancellationDoesNotFallBackToWholeFileDownload() = runTest {
        val client = FakeWebDavClient(
            info = RemoteFileInfo("/books/book.cbz", size = 4, etag = "\"v1\"", lastModified = null, supportsRange = true),
            bytes = byteArrayOf(1, 2, 3, 4),
        )
        val useCase = OpenComicUseCase(
            accountId = "account",
            cache = ComicDownloadCache(temp.root),
            progressStore = FakeProgressStore(savedPage = 0),
            openRemoteSession = { _, _, _, _, _ -> throw CancellationException("reader closed") },
        )

        try {
            useCase.open(client, "/books/book.cbz")
        } catch (error: CancellationException) {
            assertEquals("reader closed", error.message)
            assertNull(client.downloadedPath)
            return@runTest
        }
        error("Expected cancellation to be rethrown")
    }

    @Test
    fun remoteNativeOpenRunsOnInjectedIoDispatcher() = runTest {
        val client = FakeWebDavClient(
            info = RemoteFileInfo("/books/book.cbz", size = 9, etag = "\"v1\"", lastModified = null, supportsRange = true),
            bytes = byteArrayOf(1, 2, 3, 4),
        )
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "comic-open-io")
        }
        val ioDispatcher = executor.asCoroutineDispatcher()
        val openThreads = mutableListOf<String>()
        try {
            val useCase = OpenComicUseCase(
                accountId = "account",
                cache = ComicDownloadCache(temp.root),
                progressStore = FakeProgressStore(savedPage = 0),
                ioDispatcher = ioDispatcher,
                openRemoteSession = { _, _, _, _, _ ->
                    openThreads += Thread.currentThread().name
                    FakeReaderSession(pageCount = 1)
                },
            )

            useCase.open(client, "/books/book.cbz")

            assertEquals(listOf("comic-open-io"), openThreads)
        } finally {
            ioDispatcher.close()
            executor.shutdownNow()
        }
    }

    private class FakeProgressStore(private val savedPage: Int) : ReadingProgressGateway {
        override suspend fun savePage(comicKey: String, pageIndex: Int) = Unit
        override suspend fun loadPage(comicKey: String): Int = savedPage
    }

    private class FakeReaderSession(override val pageCount: Int) : com.example.comicdav.nativebridge.ComicReaderSession {
        override fun loadPageToFile(pageIndex: Int, outputFile: File): File = outputFile
        override fun close() = Unit
    }

    private class FakeWebDavClient(
        private val info: RemoteFileInfo,
        private val bytes: ByteArray,
    ) : WebDavClient {
        var downloadedPath: String? = null
        var headCalls = 0

        override suspend fun list(path: String): List<WebDavItem> = emptyList()
        override suspend fun head(path: String): RemoteFileInfo {
            headCalls += 1
            return info
        }
        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = bytes
        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long {
            downloadedPath = path
            target.writeBytes(bytes)
            onBytesRead(bytes.size.toLong())
            return bytes.size.toLong()
        }
    }

    private data class RemoteOpenCall(
        val fileId: Long,
        val size: Long,
        val cacheDir: String,
        val comicKey: String,
        val validator: String,
    )

    private class CollectingReaderLogSink : ReaderLogSink {
        val lines = mutableListOf<String>()

        override fun log(line: String) {
            lines += line
        }

        override fun logBlocking(line: String) {
            lines += line
        }
    }
}
