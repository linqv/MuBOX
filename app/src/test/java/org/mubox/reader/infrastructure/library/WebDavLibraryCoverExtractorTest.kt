package org.mubox.reader.infrastructure.library

import org.mubox.reader.core.ports.ComicReaderSession
import org.mubox.reader.core.ports.PlannedRemoteRange
import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.data.ComicCacheKey
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavLibraryCoverExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun extractFirstPageCoverWritesPageZeroToCoverCache() = runTest {
        val cacheDir = temporaryFolder.newFolder("cache")
        val remoteCacheDir = temporaryFolder.newFolder("remote-cache")
        val session = FakeComicReaderSession()
        var openCalls = 0
        var requestedPrefetchPageCount = -1
        val extractor = WebDavLibraryCoverExtractor(
            appCacheDir = cacheDir,
            remoteCacheDir = remoteCacheDir,
            openRemoteSession = { _, _, _, _, _, webDavPrefetchPageCount ->
                openCalls += 1
                requestedPrefetchPageCount = webDavPrefetchPageCount
                session
            },
        )

        val coverPath = extractor.extractFirstPageCover(
            client = FakeWebDavClient(),
            accountId = "account-1",
            remotePath = "/books/book.cbz",
            knownInfo = RemoteFileInfo(
                path = "/books/book.cbz",
                size = 123,
                etag = "abc",
                lastModified = null,
                supportsRange = true,
            ),
        )

        val coverFile = File(requireNotNull(coverPath))
        assertTrue(coverFile.isFile)
        assertTrue(coverFile.absolutePath.contains("library-covers/v2"))
        assertEquals("cover-0", coverFile.readText())
        assertEquals(0, requestedPrefetchPageCount)
        assertEquals(listOf(0), session.loadedPages)
        assertTrue(session.closed)

        val secondPath = extractor.extractFirstPageCover(
            client = FakeWebDavClient(),
            accountId = "account-1",
            remotePath = "/books/book.cbz",
            knownInfo = RemoteFileInfo(
                path = "/books/book.cbz",
                size = 123,
                etag = "abc",
                lastModified = null,
                supportsRange = true,
            ),
        )

        assertEquals(coverFile.absolutePath, secondPath)
        assertEquals(1, openCalls)
    }

    @Test
    fun extractFirstPageCoverDeletesLegacyUnversionedCover() = runTest {
        val cacheDir = temporaryFolder.newFolder("cache-legacy")
        val remoteCacheDir = temporaryFolder.newFolder("remote-cache-legacy")
        val info = RemoteFileInfo(
            path = "/books/book.cbz",
            size = 123,
            etag = "abc",
            lastModified = null,
            supportsRange = true,
        )
        val cacheKey = ComicCacheKey.fromRemote(
            accountId = "account-1",
            remotePath = "/books/book.cbz",
            size = info.size,
            etag = info.etag,
            lastModified = info.lastModified,
        )
        val legacyDir = cacheDir.resolve("library-covers")
        legacyDir.mkdirs()
        val legacyFile = legacyDir.resolve("${cacheKey.value}.img")
        legacyFile.writeText("old-legacy-cover")
        assertTrue(legacyFile.exists())

        val extractor = WebDavLibraryCoverExtractor(
            appCacheDir = cacheDir,
            remoteCacheDir = remoteCacheDir,
            openRemoteSession = { _, _, _, _, _, _ ->
                FakeComicReaderSession()
            },
        )

        val coverPath = extractor.extractFirstPageCover(
            client = FakeWebDavClient(),
            accountId = "account-1",
            remotePath = "/books/book.cbz",
            knownInfo = info,
        )

        assertFalse("Legacy unversioned cover should be deleted", legacyFile.exists())
        val newCoverFile = File(requireNotNull(coverPath))
        assertTrue(newCoverFile.exists())
        assertTrue(newCoverFile.absolutePath.contains("library-covers/v2"))
        assertEquals("cover-0", newCoverFile.readText())
    }

    @Test
    fun extractFirstPageCoverReturnsNullWhenArchiveHasNoPages() = runTest {
        val cacheDir = temporaryFolder.newFolder("cache")
        val remoteCacheDir = temporaryFolder.newFolder("remote-cache")
        val extractor = WebDavLibraryCoverExtractor(
            appCacheDir = cacheDir,
            remoteCacheDir = remoteCacheDir,
            openRemoteSession = { _, _, _, _, _, _ ->
                FakeComicReaderSession(pageCount = 0)
            },
        )

        val coverPath = extractor.extractFirstPageCover(
            client = FakeWebDavClient(),
            accountId = "account-1",
            remotePath = "/books/empty.cbz",
            knownInfo = RemoteFileInfo(
                path = "/books/empty.cbz",
                size = 123,
                etag = "abc",
                lastModified = null,
                supportsRange = true,
            ),
        )

        assertEquals(null, coverPath)
        assertFalse(cacheDir.resolve("library-covers").exists())
    }

    @Test
    fun failedExtractionKeepsLegacyUnversionedCover() = runTest {
        val cacheDir = temporaryFolder.newFolder("cache-failure")
        val remoteCacheDir = temporaryFolder.newFolder("remote-cache-failure")
        val cacheKey = ComicCacheKey.fromRemote(
            accountId = "account-1",
            remotePath = "/books/book.cbz",
            size = 123,
            etag = "abc",
            lastModified = null,
        )
        val legacyFile = cacheDir.resolve("library-covers/${cacheKey.value}.img")
        legacyFile.parentFile!!.mkdirs()
        legacyFile.writeText("old-legacy-cover")
        val knownInfo = RemoteFileInfo(
            path = "/books/book.cbz",
            size = 123,
            etag = "abc",
            lastModified = null,
            supportsRange = true,
        )

        val extractorWithEmptyArchive = WebDavLibraryCoverExtractor(
            appCacheDir = cacheDir,
            remoteCacheDir = remoteCacheDir,
            openRemoteSession = { _, _, _, _, _, _ ->
                FakeComicReaderSession(pageCount = 0)
            },
        )
        val extractorWithFailingOpen = WebDavLibraryCoverExtractor(
            appCacheDir = cacheDir,
            remoteCacheDir = remoteCacheDir,
            openRemoteSession = { _, _, _, _, _, _ ->
                error("native session open failed")
            },
        )

        assertEquals(
            null,
            extractorWithEmptyArchive.extractFirstPageCover(
                client = FakeWebDavClient(),
                accountId = "account-1",
                remotePath = "/books/book.cbz",
                knownInfo = knownInfo,
            ),
        )
        val failingOpen = runCatching {
            extractorWithFailingOpen.extractFirstPageCover(
                client = FakeWebDavClient(),
                accountId = "account-1",
                remotePath = "/books/book.cbz",
                knownInfo = knownInfo,
            )
        }
        assertTrue("Session open failure must propagate", failingOpen.isFailure)

        assertTrue(
            "Failed extraction must keep the legacy cover readable",
            legacyFile.isFile && legacyFile.length() > 0L,
        )
        assertFalse(cacheDir.resolve("library-covers/v2").exists())
    }

    @Test
    fun nativeCoverOpenRunsOnInjectedIoDispatcher() = runTest {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cover-extractor-io")
        }
        val ioDispatcher = executor.asCoroutineDispatcher()
        val openThreads = mutableListOf<String>()
        try {
            val extractor = WebDavLibraryCoverExtractor(
                appCacheDir = temporaryFolder.newFolder("dispatcher-cache"),
                remoteCacheDir = temporaryFolder.newFolder("dispatcher-remote-cache"),
                openRemoteSession = { _, _, _, _, _, _ ->
                    openThreads += Thread.currentThread().name
                    FakeComicReaderSession(pageCount = 1)
                },
                ioDispatcher = ioDispatcher,
            )

            extractor.extractFirstPageCover(
                client = FakeWebDavClient(),
                accountId = "account-1",
                remotePath = "/books/book.cbz",
                knownInfo = RemoteFileInfo(
                    path = "/books/book.cbz",
                    size = 123,
                    etag = "abc",
                    lastModified = null,
                    supportsRange = true,
                ),
            )

            assertEquals(listOf("cover-extractor-io"), openThreads)
        } finally {
            ioDispatcher.close()
            executor.shutdownNow()
        }
    }

    private class FakeComicReaderSession(
        override val pageCount: Int = 3,
    ) : ComicReaderSession {
        val loadedPages = mutableListOf<Int>()
        var closed = false

        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            loadedPages += pageIndex
            outputFile.parentFile?.mkdirs()
            outputFile.writeText("cover-$pageIndex")
            return outputFile
        }

        override fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> = emptyList()

        override fun close() {
            closed = true
        }
    }

    private class FakeWebDavClient : WebDavClient {
        override suspend fun list(path: String) = emptyList<org.mubox.reader.core.remote.WebDavItem>()

        override suspend fun head(path: String) = RemoteFileInfo(
            path = path,
            size = 123,
            etag = "abc",
            lastModified = null,
            supportsRange = true,
        )

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = ByteArray(0)

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long = 0
    }
}
