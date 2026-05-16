package com.example.comicdav.feature.webdav

import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertEquals("已连接", viewModel.uiState.status)
        assertEquals(listOf("Series", "book.cbz", "book.zip"), viewModel.uiState.items.map { it.name })
    }

    @Test
    fun activeAccountIdUsesConnectedCredentialsEvenIfFormChangesLater() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        val viewModel = WebDavViewModel(clientFactory = { _, _, _ -> client })
        viewModel.updateBaseUrl("https://example.test/dav/")
        viewModel.updateUsername("lin")

        viewModel.testConnection()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.updateUsername("other")

        assertEquals("https://example.test/dav/|lin", viewModel.activeAccountId())
    }

    @Test
    fun connectToSavedSourceUsesSavedCredentialsAndOpensSavedPath() = runTest(dispatcher) {
        val client = FakeWebDavClient(
            items = listOf(
                WebDavItem("Volume 1", "/Comics/Volume 1/", isDirectory = true, size = null, etag = null, lastModified = null),
                WebDavItem("book.cbz", "/Comics/book.cbz", isDirectory = false, size = 10, etag = "a", lastModified = null),
            ),
        )
        var createdWith: Triple<String, String?, String?>? = null
        val viewModel = WebDavViewModel(
            clientFactory = { baseUrl, username, password ->
                createdWith = Triple(baseUrl, username, password)
                client
            },
        )

        viewModel.connectToSavedSource(
            baseUrl = "https://example.test/dav/ ",
            username = "lin",
            password = "secret",
            path = "/Comics/",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Triple("https://example.test/dav/", "lin", "secret"), createdWith)
        assertEquals(viewModel.accountId(), viewModel.activeAccountId())
        assertEquals("https://example.test/dav/ ", viewModel.uiState.baseUrl)
        assertEquals("lin", viewModel.uiState.username)
        assertEquals("secret", viewModel.uiState.password)
        assertEquals("/Comics/", viewModel.uiState.currentPath)
        assertEquals(listOf("Volume 1", "book.cbz"), viewModel.uiState.items.map { it.name })
        assertEquals("已连接", viewModel.uiState.status)
    }

    @Test
    fun connectToSavedSourceReusesActiveClientForSameCredentials() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        var clientCreations = 0
        val viewModel = WebDavViewModel(
            clientFactory = { _, _, _ ->
                clientCreations += 1
                client
            },
        )

        viewModel.connectToSavedSource(
            baseUrl = "https://example.test/dav/",
            username = "lin",
            password = "secret",
            path = "/Comics/",
        )
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.connectToSavedSource(
            baseUrl = "https://example.test/dav/",
            username = "lin",
            password = "secret",
            path = "/Comics/Series/",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, clientCreations)
        assertEquals(listOf("/Comics/", "/Comics/Series/"), client.listedPaths)
        assertEquals("/Comics/Series/", viewModel.uiState.currentPath)
    }

    @Test
    fun openingDirectoryKeepsConnectedStatusWhileLoading() = runTest(dispatcher) {
        val directory = WebDavItem("Series", "/Series/", isDirectory = true, size = null, etag = null, lastModified = null)
        val client = FakeWebDavClient(items = listOf(directory))
        val viewModel = WebDavViewModel(clientFactory = { _, _, _ -> client })
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.testConnection()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.openDirectory(directory)

        assertEquals(WEB_DAV_STATUS_CONNECTED, viewModel.uiState.status)
        assertTrue(viewModel.uiState.isLoading)
    }

    @Test
    fun directoryLoadFailureKeepsConnectedBrowserState() = runTest(dispatcher) {
        val directory = WebDavItem("Broken", "/Broken/", isDirectory = true, size = null, etag = null, lastModified = null)
        val client = FakeWebDavClient(
            itemsByPath = mapOf("/" to listOf(directory)),
            failuresByPath = mapOf("/Broken/" to IllegalStateException("目录不可用")),
        )
        val viewModel = WebDavViewModel(clientFactory = { _, _, _ -> client })
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.testConnection()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.openDirectory(directory)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(WEB_DAV_STATUS_CONNECTED, viewModel.uiState.status)
        assertEquals("/", viewModel.uiState.currentPath)
        assertEquals(listOf("Broken"), viewModel.uiState.items.map { it.name })
        assertEquals("目录不可用", viewModel.uiState.diagnostic)
        assertFalse(viewModel.uiState.isLoading)
    }

    @Test
    fun startNewConnectionClearsPreviousWebDavSessionAndForm() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        val viewModel = WebDavViewModel(clientFactory = { _, _, _ -> client })
        viewModel.updateBaseUrl("https://example.test/dav/")
        viewModel.updateUsername("lin")

        viewModel.testConnection()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startNewConnection()

        assertEquals(null, viewModel.activeClient())
        assertEquals(null, viewModel.activeAccountId())
        assertEquals(WebDavUiState(), viewModel.uiState)
    }

    @Test
    fun handleBackFromNestedWebDavPathOpensParentDirectory() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        val viewModel = WebDavViewModel(clientFactory = { _, _, _ -> client })
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.openPath("/Comics/Series/")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.handleBack())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("/Comics/", viewModel.uiState.currentPath)
        assertEquals(listOf("/Comics/Series/", "/Comics/"), client.listedPaths)
    }

    @Test
    fun handleBackFromWebDavRootIsNotHandled() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        val viewModel = WebDavViewModel(clientFactory = { _, _, _ -> client })
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.openPath("/")
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.handleBack())
        assertEquals(listOf("/"), client.listedPaths)
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
        assertTrue(viewModel.uiState.diagnostic.contains("读取 3 字节"))
    }

    @Test
    fun selectItemKeepsFilesButIgnoresDirectories() {
        val viewModel = WebDavViewModel(clientFactory = { _, _, _ -> FakeWebDavClient() })
        val file = WebDavItem("book.cbz", "/book.cbz", isDirectory = false, size = 100_000, etag = "a", lastModified = null)
        val directory = WebDavItem("Series", "/Series/", isDirectory = true, size = null, etag = null, lastModified = null)

        viewModel.selectItem(file)
        viewModel.selectItem(directory)

        assertEquals(file, viewModel.uiState.selectedItem)
    }

    private class FakeWebDavClient(
        private val items: List<WebDavItem> = emptyList(),
        private val itemsByPath: Map<String, List<WebDavItem>> = emptyMap(),
        private val failuresByPath: Map<String, Throwable> = emptyMap(),
        private val head: RemoteFileInfo = RemoteFileInfo("/", 0, null, null, supportsRange = true),
        private val rangeBytes: ByteArray = byteArrayOf(),
    ) : WebDavClient {
        var lastRange: Pair<Long, Long>? = null
        val listedPaths = mutableListOf<String>()

        override suspend fun list(path: String): List<WebDavItem> {
            listedPaths += path
            failuresByPath[path]?.let { throw it }
            return itemsByPath[path] ?: items
        }

        override suspend fun head(path: String): RemoteFileInfo = head

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
            lastRange = start to endInclusive
            return rangeBytes
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long {
            target.writeBytes(rangeBytes)
            onBytesRead(rangeBytes.size.toLong())
            return rangeBytes.size.toLong()
        }
    }
}
