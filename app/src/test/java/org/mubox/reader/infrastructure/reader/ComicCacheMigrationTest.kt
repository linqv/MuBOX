package org.mubox.reader.infrastructure.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mubox.reader.core.crypto.sha256Hex
import org.mubox.reader.core.ports.ComicReaderSession
import org.mubox.reader.core.ports.PlannedRemoteRange
import org.mubox.reader.core.ports.ReadingProgressGateway
import org.mubox.reader.core.ports.RemoteRangeComicSessionFactory
import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.data.ComicCacheKey
import org.mubox.reader.data.ComicDownloadCache
import org.mubox.reader.feature.reader.readerPageFile
import org.mubox.reader.infrastructure.library.WebDavLibraryCoverExtractor
import java.io.File
import kotlinx.coroutines.test.runTest

class ComicCacheMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun v3ToV4MigrationUpdatesPageCountPageOrderProgressAndInvalidatesCaches() = runTest {
        val cacheDir = temporaryFolder.newFolder("comic-cache")
        val accountId = "account-1"
        val remotePath = "/books/mixed_comic.cbz"
        val info = RemoteFileInfo(
            path = remotePath,
            size = 12345L,
            etag = "\"etag-1\"",
            lastModified = null,
            supportsRange = true,
        )
        val comicCacheKey = ComicCacheKey.fromRemote(
            accountId = accountId,
            remotePath = remotePath,
            size = info.size,
            etag = info.etag,
            lastModified = info.lastModified,
        )
        val comicKey = comicCacheKey.value

        // 1. Preset old v3 index with only JPG pages: ["002.jpg", "003.jpg"]
        val indexDir = cacheDir.resolve("index")
        indexDir.mkdirs()
        val indexFile = indexDir.resolve("${comicKey.sha256Hex()}.json")
        val v3Json = """
        {
          "version": 3,
          "comic_key": "$comicKey",
          "file_size": 12345,
          "validator": "\"etag-1\"",
          "index": {
            "pages": [
              {
                "name": "002.jpg",
                "filename_len": 7,
                "local_header_offset": 0,
                "compressed_size": 100,
                "uncompressed_size": 100,
                "compression_method": 0,
                "crc32": 1
              },
              {
                "name": "003.jpg",
                "filename_len": 7,
                "local_header_offset": 100,
                "compressed_size": 100,
                "uncompressed_size": 100,
                "compression_method": 0,
                "crc32": 2
              }
            ]
          }
        }
        """.trimIndent()
        indexFile.writeText(v3Json)

        // 2. Preset old unversioned page file: page-0.img
        val safeKey = comicKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val readerPageDir = cacheDir.resolve("mubox-reader-pages/$safeKey")
        readerPageDir.mkdirs()
        val oldPage0 = readerPageDir.resolve("page-0.img")
        oldPage0.writeText("OLD_PAGE_0_FROM_002_JPG")

        // 3. Preset old unversioned cover file: library-covers/$comicKey.img
        val coversDir = cacheDir.resolve("library-covers")
        coversDir.mkdirs()
        val oldCoverFile = coversDir.resolve("$comicKey.img")
        oldCoverFile.writeText("OLD_COVER_FROM_002_JPG")

        // 4. Preset user reading progress: user was on page 0 ("002.jpg") in v3
        val progressStore = InMemoryProgressStore()
        progressStore.savePage(comicKey, 0)

        // 5. Remote archive actually contains: ["001.avif", "002.jpg", "003.jpg"]
        val archivePages = listOf("001.avif", "002.jpg", "003.jpg")
        val openRemoteSession: RemoteRangeComicSessionFactory = { _, size, sessionCacheDir, key, validator, _ ->
            // Re-index creates the v4 index file with AVIF pages included
            val v4Json = """
            {
              "version": 4,
              "comic_key": "$key",
              "file_size": $size,
              "validator": "$validator",
              "index": {
                "pages": [
                  {
                    "name": "001.avif",
                    "filename_len": 8,
                    "local_header_offset": 0,
                    "compressed_size": 100,
                    "uncompressed_size": 100,
                    "compression_method": 0,
                    "crc32": 10
                  },
                  {
                    "name": "002.jpg",
                    "filename_len": 7,
                    "local_header_offset": 100,
                    "compressed_size": 100,
                    "uncompressed_size": 100,
                    "compression_method": 0,
                    "crc32": 11
                  },
                  {
                    "name": "003.jpg",
                    "filename_len": 7,
                    "local_header_offset": 200,
                    "compressed_size": 100,
                    "uncompressed_size": 100,
                    "compression_method": 0,
                    "crc32": 12
                  }
                ]
              }
            }
            """.trimIndent()
            val v4IndexFile = sessionCacheDir.resolve("index/${key.sha256Hex()}.json")
            v4IndexFile.parentFile?.mkdirs()
            v4IndexFile.writeText(v4Json)

            TestComicReaderSession(archivePages)
        }

        val client = FakeWebDavClient(info)
        val useCase = OpenComicUseCase(
            accountId = accountId,
            cache = ComicDownloadCache(cacheDir),
            progressStore = progressStore,
            openRemoteSession = openRemoteSession,
        )

        val result = useCase.open(client = client, remotePath = remotePath, knownInfo = info)

        // Verify 1: Page count is 3 (previously 2)
        assertEquals(3, result.session.pageCount)

        // Verify 2: Page 0 is 001.avif, Page 1 is 002.jpg, Page 2 is 003.jpg
        val testSession = result.session as TestComicReaderSession
        assertEquals(listOf("001.avif", "002.jpg", "003.jpg"), testSession.pages)

        val tempPage0 = temporaryFolder.newFile("temp-page-0.img")
        result.session.loadPageToFile(0, tempPage0)
        assertEquals("CONTENT_OF_001.avif", tempPage0.readText())

        val tempPage1 = temporaryFolder.newFile("temp-page-1.img")
        result.session.loadPageToFile(1, tempPage1)
        assertEquals("CONTENT_OF_002.jpg", tempPage1.readText())

        // Verify 3: Reader page file is versioned (v2-page-0.img), old page-0.img is not reused
        val page0File = readerPageFile(cacheDir, result.pageCacheKey, 0)
        assertEquals("v2-page-0.img", page0File.name)
        assertFalse("Old unversioned page-0.img must be deleted", oldPage0.exists())
        assertFalse("New versioned page file must not exist before being loaded", page0File.exists())
        result.session.loadPageToFile(0, page0File)
        assertEquals("CONTENT_OF_001.avif", page0File.readText())

        // Verify 4: Reading progress migrated from page 0 ("002.jpg") to page 1 ("002.jpg")
        assertEquals(1, result.initialPage)
        assertEquals(1, progressStore.loadPage(comicKey))

        // Verify 5: Cover is invalidated and extracted from new page 0 ("001.avif")
        val coverExtractor = WebDavLibraryCoverExtractor(
            appCacheDir = cacheDir,
            remoteCacheDir = cacheDir,
            openRemoteSession = openRemoteSession,
        )
        val coverPath = coverExtractor.extractFirstPageCover(
            client = client,
            accountId = accountId,
            remotePath = remotePath,
            knownInfo = info,
        )

        assertFalse("Old derived cover library-covers/$comicKey.img must be deleted", oldCoverFile.exists())
        val newCoverFile = File(requireNotNull(coverPath))
        assertTrue(newCoverFile.exists())
        assertEquals("library-covers/v2/$comicKey.img", newCoverFile.toRelativeString(cacheDir).replace('\\', '/'))
        assertEquals("CONTENT_OF_001.avif", newCoverFile.readText())
    }

    @Test
    fun readingProgressNotMigratedWhenTargetPageRemainsAtSameIndex() = runTest {
        val cacheDir = temporaryFolder.newFolder("comic-cache-same")
        val accountId = "account-1"
        val remotePath = "/books/same_comic.cbz"
        val info = RemoteFileInfo(
            path = remotePath,
            size = 5000L,
            etag = "\"etag-2\"",
            lastModified = null,
            supportsRange = true,
        )
        val comicCacheKey = ComicCacheKey.fromRemote(
            accountId = accountId,
            remotePath = remotePath,
            size = info.size,
            etag = info.etag,
            lastModified = info.lastModified,
        )
        val comicKey = comicCacheKey.value

        // Preset v3 index where pages are ["001.jpg", "002.jpg"]
        val indexDir = cacheDir.resolve("index")
        indexDir.mkdirs()
        val indexFile = indexDir.resolve("${comicKey.sha256Hex()}.json")
        val v3Json = """
        {
          "version": 3,
          "comic_key": "$comicKey",
          "file_size": 5000,
          "validator": "\"etag-2\"",
          "index": {
            "pages": [
              { "name": "001.jpg", "filename_len": 7, "local_header_offset": 0, "compressed_size": 100, "uncompressed_size": 100, "compression_method": 0, "crc32": 1 },
              { "name": "002.jpg", "filename_len": 7, "local_header_offset": 100, "compressed_size": 100, "uncompressed_size": 100, "compression_method": 0, "crc32": 2 }
            ]
          }
        }
        """.trimIndent()
        indexFile.writeText(v3Json)

        val progressStore = InMemoryProgressStore()
        progressStore.savePage(comicKey, 0)

        // In v4, an AVIF was added AFTER page 0, e.g. ["001.jpg", "001_extra.avif", "002.jpg"]
        val archivePages = listOf("001.jpg", "001_extra.avif", "002.jpg")
        val openRemoteSession: RemoteRangeComicSessionFactory = { _, size, sessionCacheDir, key, validator, _ ->
            val v4Json = """
            {
              "version": 4,
              "comic_key": "$key",
              "file_size": $size,
              "validator": "$validator",
              "index": {
                "pages": [
                  { "name": "001.jpg", "filename_len": 7, "local_header_offset": 0, "compressed_size": 100, "uncompressed_size": 100, "compression_method": 0, "crc32": 1 },
                  { "name": "001_extra.avif", "filename_len": 14, "local_header_offset": 100, "compressed_size": 100, "uncompressed_size": 100, "compression_method": 0, "crc32": 2 },
                  { "name": "002.jpg", "filename_len": 7, "local_header_offset": 200, "compressed_size": 100, "uncompressed_size": 100, "compression_method": 0, "crc32": 3 }
                ]
              }
            }
            """.trimIndent()
            val v4IndexFile = sessionCacheDir.resolve("index/${key.sha256Hex()}.json")
            v4IndexFile.parentFile?.mkdirs()
            v4IndexFile.writeText(v4Json)

            TestComicReaderSession(archivePages)
        }

        val client = FakeWebDavClient(info)
        val useCase = OpenComicUseCase(
            accountId = accountId,
            cache = ComicDownloadCache(cacheDir),
            progressStore = progressStore,
            openRemoteSession = openRemoteSession,
        )

        val result = useCase.open(client = client, remotePath = remotePath, knownInfo = info)

        // Target page "001.jpg" is still at index 0
        assertEquals(0, result.initialPage)
        assertEquals(0, progressStore.loadPage(comicKey))
    }

    private class InMemoryProgressStore : ReadingProgressGateway {
        private val pages = mutableMapOf<String, Int>()

        override suspend fun savePage(comicKey: String, pageIndex: Int) {
            pages[comicKey] = pageIndex
        }

        override suspend fun loadPage(comicKey: String): Int =
            pages[comicKey] ?: 0
    }

    private class TestComicReaderSession(
        val pages: List<String>,
    ) : ComicReaderSession {
        override val pageCount: Int = pages.size

        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            outputFile.parentFile?.mkdirs()
            outputFile.writeText("CONTENT_OF_${pages[pageIndex]}")
            return outputFile
        }

        override fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> = emptyList()

        override fun close() = Unit
    }

    private class FakeWebDavClient(
        private val info: RemoteFileInfo,
    ) : WebDavClient {
        override suspend fun list(path: String): List<WebDavItem> = emptyList()
        override suspend fun head(path: String): RemoteFileInfo = info
        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = ByteArray(0)
        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long = 0L
    }
}
