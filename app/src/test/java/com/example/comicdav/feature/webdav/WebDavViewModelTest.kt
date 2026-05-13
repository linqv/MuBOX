package com.example.comicdav.feature.webdav

import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebDavViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testConnectionListsFoldersAndComicArchivesOnly() = runTest(dispatcher) {
        val client = FakeWebDavClient(
            items = listOf(
                WebDavItem("Series", "/Series/", isDirectory = true, size = null, etag = null, lastModified = null),
                WebDavItem("book.cbz", "/book.cbz", isDirectory = false, size = 10, etag = "a", lastModified = null),
                WebDavItem("notes.txt", "/notes.txt", isDirectory = false, size = 2, etag = null, lastModified = null),
                WebDavItem("book.zip", "/book.zip", isDirectory = false, size = 11, etag = "b", lastModified = null),
            ),
        )
        val viewModel = WebDavViewModel(clientFactory = { _, _, _ -> client })
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.testConnection()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Connected", viewModel.uiState.status)
        assertEquals(listOf("Series", "book.cbz", "book.zip"), viewModel.uiState.items.map { it.name })
    }

    @Test
    fun probeTailReadsLast64KiB() = runTest(dispatcher) {
        val item = WebDavItem("book.cbz", "/book.cbz", isDirectory = false, size = 100_000, etag = "a", lastModified = null)
        val client = FakeWebDavClient(
            items = listOf(item),
            head = RemoteFileInfo(item.path, size = 100_000, etag = "a", lastModified = null, supportsRange = true),
            rangeBytes = byteArrayOf(1, 2, 3),
        )
        val viewModel = WebDavViewModel(clientFactory = { _, _, _ -> client })

        viewModel.selectItem(item)
        viewModel.probeTail64KiB()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(34_464L to 99_999L, client.lastRange)
        assertTrue(viewModel.uiState.diagnostic.contains("Read 3 bytes"))
    }

    private class FakeWebDavClient(
        private val items: List<WebDavItem> = emptyList(),
        private val head: RemoteFileInfo = RemoteFileInfo("/", 0, null, null, supportsRange = true),
        private val rangeBytes: ByteArray = byteArrayOf(),
    ) : WebDavClient {
        var lastRange: Pair<Long, Long>? = null

        override suspend fun list(path: String): List<WebDavItem> = items

        override suspend fun head(path: String): RemoteFileInfo = head

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
            lastRange = start to endInclusive
            return rangeBytes
        }
    }
}
