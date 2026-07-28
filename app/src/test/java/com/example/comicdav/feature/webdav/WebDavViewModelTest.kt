package com.example.comicdav.feature.webdav

import com.example.comicdav.MainDispatcherRule
import com.example.comicdav.feature.directorylisting.DirectoryListingViewMode
import com.example.comicdav.feature.directorylisting.DirectorySortField
import com.example.comicdav.core.remote.RemoteFileInfo
import com.example.comicdav.core.remote.WebDavClient
import com.example.comicdav.core.remote.WebDavItem
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class WebDavViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        mainDispatcher.set(dispatcher)
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
        val viewModel = testViewModel { _, _, _ -> client }
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.testConnection()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("已连接", viewModel.uiState.status)
        assertEquals(listOf("book.cbz", "book.zip", "Series"), viewModel.uiState.items.map { it.name })
    }

    @Test
    fun gridModeAndExtractedVideoThumbnailAreKeptInBrowserState() = runTest(dispatcher) {
        val viewModel = testViewModel { _, _, _ -> FakeWebDavClient() }

        viewModel.toggleViewMode()
        viewModel.onVideoThumbnailExtracted(
            path = "/movie.mp4",
            version = "webdav:/movie.mp4:20:etag:30",
            thumbnailPath = "/cache/movie.jpg",
        )

        assertEquals(DirectoryListingViewMode.GRID, viewModel.uiState.viewMode)
        assertEquals("/cache/movie.jpg", viewModel.uiState.videoThumbnails["/movie.mp4"]?.path)
        assertEquals(
            "webdav:/movie.mp4:20:etag:30",
            viewModel.uiState.videoThumbnails["/movie.mp4"]?.version,
        )

        viewModel.onVideoThumbnailExtracted(
            path = "/movie.mp4",
            version = "webdav:/movie.mp4:20:etag:30",
            thumbnailPath = "/cache/movie.jpg",
        )

        assertEquals(
            2L,
            viewModel.uiState.videoThumbnails["/movie.mp4"]?.artworkRevision,
        )
    }

    @Test
    fun directoryFilteringAndSortingUseComputationDispatcher() = runTest(dispatcher) {
        val computationDispatcher = QueuedCoroutineDispatcher()
        val client = FakeWebDavClient(
            items = listOf(
                WebDavItem("book.cbz", "/book.cbz", false, 10, null, null),
                directoryItem("Series", "/Series/"),
                WebDavItem("notes.txt", "/notes.txt", false, 2, null, null),
            ),
        )
        val viewModel = WebDavViewModel(
            clientFactory = { _, _, _ -> client },
            directoryComputationDispatcher = computationDispatcher,
        )
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.testConnection()
        runCurrent()

        assertTrue(viewModel.uiState.isLoading)
        assertTrue(viewModel.uiState.items.isEmpty())
        assertTrue(computationDispatcher.hasTasks())

        computationDispatcher.runAll()
        runCurrent()

        assertFalse(viewModel.uiState.isLoading)
        assertEquals(listOf("book.cbz", "Series"), viewModel.uiState.items.map { it.name })
    }

    @Test
    fun searchFilteringUsesComputationDispatcher() = runTest(dispatcher) {
        val computationDispatcher = QueuedCoroutineDispatcher()
        val client = FakeWebDavClient(
            items = listOf(
                directoryItem("Other", "/Other/"),
                directoryItem("Volume", "/Volume/"),
            ),
        )
        val viewModel = WebDavViewModel(
            clientFactory = { _, _, _ -> client },
            directoryComputationDispatcher = computationDispatcher,
        )
        viewModel.updateBaseUrl("https://example.test/dav/")
        viewModel.testConnection()
        runCurrent()
        computationDispatcher.runAll()
        runCurrent()

        viewModel.updateSearchQuery("Volume")
        runCurrent()

        assertEquals("Volume", viewModel.uiState.searchQuery)
        assertEquals(listOf("Other", "Volume"), viewModel.uiState.items.map { it.name })
        assertTrue(computationDispatcher.hasTasks())

        computationDispatcher.runAll()
        runCurrent()

        assertEquals(listOf("Volume"), viewModel.uiState.items.map { it.name })
    }

    @Test
    fun playbackDirectoryItemsIgnoreSearchButPreserveDirectorySort() = runTest(dispatcher) {
        val client = FakeWebDavClient(
            items = listOf(
                WebDavItem("Show E02.mkv", "/Show E02.mkv", false, 2L, null, null),
                WebDavItem("Show E01.mkv", "/Show E01.mkv", false, 1L, null, null),
            ),
        )
        val viewModel = testViewModel { _, _, _ -> client }

        viewModel.connectToSavedSource("https://example.test/dav/", null, null, "/")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.updateSearchQuery("E02")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Show E02.mkv"), viewModel.uiState.items.map { it.name })
        assertEquals(
            listOf("Show E01.mkv", "Show E02.mkv"),
            viewModel.playbackDirectoryItems().map { it.name },
        )
    }

    @Test
    fun changingSortFieldUsesComputationDispatcher() = runTest(dispatcher) {
        val computationDispatcher = QueuedCoroutineDispatcher()
        val client = FakeWebDavClient(
            items = listOf(
                WebDavItem("A-large.cbz", "/A-large.cbz", false, 20, null, null),
                WebDavItem("B-small.cbz", "/B-small.cbz", false, 1, null, null),
            ),
        )
        val viewModel = WebDavViewModel(
            clientFactory = { _, _, _ -> client },
            directoryComputationDispatcher = computationDispatcher,
        )
        viewModel.updateBaseUrl("https://example.test/dav/")
        viewModel.testConnection()
        runCurrent()
        computationDispatcher.runAll()
        runCurrent()

        viewModel.updateSortField(DirectorySortField.SIZE)
        runCurrent()

        assertEquals(listOf("A-large.cbz", "B-small.cbz"), viewModel.uiState.items.map { it.name })
        assertTrue(computationDispatcher.hasTasks())

        computationDispatcher.runAll()
        runCurrent()

        assertEquals(listOf("B-small.cbz", "A-large.cbz"), viewModel.uiState.items.map { it.name })
    }

    @Test
    fun togglingSortDirectionUsesComputationDispatcher() = runTest(dispatcher) {
        val computationDispatcher = QueuedCoroutineDispatcher()
        val client = FakeWebDavClient(
            items = listOf(
                directoryItem("Alpha", "/Alpha/"),
                directoryItem("Beta", "/Beta/"),
            ),
        )
        val viewModel = WebDavViewModel(
            clientFactory = { _, _, _ -> client },
            directoryComputationDispatcher = computationDispatcher,
        )
        viewModel.updateBaseUrl("https://example.test/dav/")
        viewModel.testConnection()
        runCurrent()
        computationDispatcher.runAll()
        runCurrent()

        viewModel.toggleSortDirection()
        runCurrent()

        assertEquals(listOf("Alpha", "Beta"), viewModel.uiState.items.map { it.name })
        assertTrue(computationDispatcher.hasTasks())

        computationDispatcher.runAll()
        runCurrent()

        assertEquals(listOf("Beta", "Alpha"), viewModel.uiState.items.map { it.name })
    }

    @Test
    fun sortChangeDuringDirectoryComputationAppliesToLoadedItems() = runTest(dispatcher) {
        val computationDispatcher = QueuedCoroutineDispatcher()
        val client = FakeWebDavClient(
            items = listOf(
                WebDavItem("A-large.cbz", "/A-large.cbz", false, 20, null, null),
                WebDavItem("B-small.cbz", "/B-small.cbz", false, 1, null, null),
            ),
        )
        val viewModel = WebDavViewModel(
            clientFactory = { _, _, _ -> client },
            directoryComputationDispatcher = computationDispatcher,
        )
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.testConnection()
        runCurrent()
        viewModel.updateSortField(DirectorySortField.SIZE)
        runCurrent()
        computationDispatcher.runAll()
        runCurrent()
        computationDispatcher.runAll()
        runCurrent()

        assertEquals(DirectorySortField.SIZE, viewModel.uiState.sortField)
        assertEquals(listOf("B-small.cbz", "A-large.cbz"), viewModel.uiState.items.map { it.name })
    }

    @Test
    fun activeAccountIdUsesConnectedCredentialsEvenIfFormChangesLater() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        val viewModel = testViewModel { _, _, _ -> client }
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
            directoryComputationDispatcher = dispatcher,
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
        assertEquals("https://example.test/dav/", viewModel.uiState.baseUrl)
        assertEquals("lin", viewModel.uiState.username)
        assertEquals("secret", viewModel.uiState.password)
        assertEquals("/Comics/", viewModel.uiState.currentPath)
        assertEquals(listOf("book.cbz", "Volume 1"), viewModel.uiState.items.map { it.name })
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
            directoryComputationDispatcher = dispatcher,
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
    fun connectingToSavedSourceImmediatelyClearsPreviouslyDisplayedDirectory() = runTest(dispatcher) {
        val firstClient = FakeWebDavClient(
            items = listOf(directoryItem("Old", "/Old/")),
        )
        val secondClient = BlockingDirectoryWebDavClient()
        val viewModel = WebDavViewModel(
            clientFactory = { _, username, _ ->
                if (username == "first") firstClient else secondClient
            },
            directoryComputationDispatcher = dispatcher,
        )

        viewModel.connectToSavedSource(
            baseUrl = "https://example.test/dav/",
            username = "first",
            password = "secret",
            path = "/Old/",
        )
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.connectToSavedSource(
            baseUrl = "https://example.test/dav/",
            username = "second",
            password = "secret",
            path = "/New/",
        )

        assertEquals("/New/", viewModel.uiState.currentPath)
        assertTrue(viewModel.uiState.items.isEmpty())
        assertTrue(viewModel.uiState.isLoading)
        assertEquals(WEB_DAV_STATUS_CONNECTING, viewModel.uiState.status)
    }

    @Test
    fun openingSavedPathImmediatelyClearsPreviouslyDisplayedDirectory() = runTest(dispatcher) {
        val client = BlockingDirectoryWebDavClient()
        val viewModel = testViewModel { _, _, _ -> client }
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.openPath("/Old/")
        runCurrent()
        client.complete("/Old/", listOf(directoryItem("Old", "/Old/Series/")))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.openPath("/New/")

        assertEquals("/New/", viewModel.uiState.currentPath)
        assertTrue(viewModel.uiState.items.isEmpty())
        assertTrue(viewModel.uiState.isLoading)
    }

    @Test
    fun splitConnectionFieldsBuildWebDavBaseUrl() {
        val viewModel = testViewModel { _, _, _ -> FakeWebDavClient() }

        viewModel.updateHost("cloud.example.test")
        viewModel.updatePort("8443")
        viewModel.updateRootPath("dav/books")

        assertEquals("https://cloud.example.test:8443/dav/books", viewModel.uiState.baseUrl)
    }

    @Test
    fun splitConnectionFieldsOmitStandardHttpsPort() {
        val viewModel = testViewModel { _, _, _ -> FakeWebDavClient() }

        viewModel.updateHost("webdav.123pan.cn")
        viewModel.updatePort("443")
        viewModel.updateRootPath("/webdav")

        assertEquals("https://webdav.123pan.cn/webdav", viewModel.uiState.baseUrl)
    }

    @Test
    fun connectToSavedSourceOmitStandardHttpsPort() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        var createdBaseUrl: String? = null
        val viewModel = WebDavViewModel(
            clientFactory = { baseUrl, _, _ ->
                createdBaseUrl = baseUrl
                client
            },
            directoryComputationDispatcher = dispatcher,
        )

        viewModel.connectToSavedSource(
            baseUrl = "https://webdav.123pan.cn:443/webdav",
            username = "lin",
            password = "secret",
            path = "/",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("https://webdav.123pan.cn/webdav", createdBaseUrl)
        assertEquals("https://webdav.123pan.cn/webdav", viewModel.uiState.baseUrl)
    }

    @Test
    fun editingSavedConnectionPopulatesConnectionFields() {
        val viewModel = testViewModel { _, _, _ -> FakeWebDavClient() }

        viewModel.editSavedConnection(
            displayName = "漫画库",
            baseUrl = "http://nas.example.test:8080/webdav/",
            username = "lin",
            password = "secret",
            path = "/manga/",
        )

        assertEquals("漫画库", viewModel.uiState.displayName)
        assertEquals("nas.example.test", viewModel.uiState.host)
        assertEquals("8080", viewModel.uiState.port)
        assertEquals("/webdav/", viewModel.uiState.rootPath)
        assertFalse(viewModel.uiState.useHttps)
        assertEquals("lin", viewModel.uiState.username)
        assertEquals("secret", viewModel.uiState.password)
        assertEquals("/manga/", viewModel.uiState.currentPath)
        assertEquals(WEB_DAV_STATUS_NOT_CONNECTED, viewModel.uiState.status)
    }

    @Test
    fun openingDirectoryKeepsConnectedStatusWhileLoading() = runTest(dispatcher) {
        val directory = WebDavItem("Series", "/Series/", isDirectory = true, size = null, etag = null, lastModified = null)
        val client = FakeWebDavClient(items = listOf(directory))
        val viewModel = testViewModel { _, _, _ -> client }
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.testConnection()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.openDirectory(directory)

        assertEquals(WEB_DAV_STATUS_CONNECTED, viewModel.uiState.status)
        assertTrue(viewModel.uiState.isLoading)
    }

    @Test
    fun openingNewPathCancelsPreviousDirectoryLoad() = runTest(dispatcher) {
        val client = BlockingDirectoryWebDavClient()
        val viewModel = testViewModel { _, _, _ -> client }
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.openPath("/slow/")
        runCurrent()
        viewModel.openPath("/fast/")
        runCurrent()
        val cancelledBeforeCompletion = client.cancelledPaths.toList()

        client.complete("/fast/")
        client.complete("/slow/")
        runCurrent()

        assertEquals(listOf("/slow/"), cancelledBeforeCompletion)
    }

    @Test
    fun staleResultCannotReplaceNewerDirectoryWhenClientIgnoresCancellation() = runTest(dispatcher) {
        val client = NonCancellableDirectoryWebDavClient()
        val viewModel = testViewModel { _, _, _ -> client }
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.openPath("/slow/")
        runCurrent()
        viewModel.openPath("/fast/")
        runCurrent()
        client.complete("/fast/", listOf(directoryItem("Fast", "/fast/Fast/")))
        runCurrent()
        client.complete("/slow/", listOf(directoryItem("Slow", "/slow/Slow/")))
        runCurrent()

        assertEquals("/fast/", viewModel.uiState.currentPath)
        assertEquals(listOf("Fast"), viewModel.uiState.items.map { it.name })
    }

    @Test
    fun backDuringChildLoadTargetsDisplayedParentDirectory() = runTest(dispatcher) {
        val child = directoryItem("Series", "/Comics/Series/")
        val client = BlockingDirectoryWebDavClient()
        val viewModel = testViewModel { _, _, _ -> client }
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.openPath("/Comics/")
        runCurrent()
        client.complete("/Comics/", listOf(child))
        runCurrent()
        viewModel.openDirectory(child)
        runCurrent()

        assertTrue(viewModel.handleBack())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("/Comics/", viewModel.uiState.currentPath)
        assertEquals(listOf("/Comics/", "/Comics/Series/"), client.listedPaths)
    }

    @Test
    fun directoryLoadFailureKeepsConnectedBrowserState() = runTest(dispatcher) {
        val directory = WebDavItem("Broken", "/Broken/", isDirectory = true, size = null, etag = null, lastModified = null)
        val client = FakeWebDavClient(
            itemsByPath = mapOf("/" to listOf(directory)),
            failuresByPath = mapOf("/Broken/" to IllegalStateException("目录不可用")),
        )
        val viewModel = testViewModel { _, _, _ -> client }
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.testConnection()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.openDirectory(directory)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(WEB_DAV_STATUS_CONNECTED, viewModel.uiState.status)
        assertEquals("/", viewModel.uiState.currentPath)
        assertEquals(listOf("Broken"), viewModel.uiState.items.map { it.name })
        assertEquals("目录不可用", viewModel.uiState.message)
        assertFalse(viewModel.uiState.isLoading)
    }

    @Test
    fun startNewConnectionClearsPreviousWebDavSessionAndForm() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        val viewModel = testViewModel { _, _, _ -> client }
        viewModel.updateBaseUrl("https://example.test/dav/")
        viewModel.updateUsername("lin")

        viewModel.testConnection()
        dispatcher.scheduler.advanceUntilIdle()
        val previousThumbnailRequestRevision = viewModel.uiState.thumbnailRequestRevision

        viewModel.startNewConnection()

        assertEquals(null, viewModel.activeClient())
        assertEquals(null, viewModel.activeAccountId())
        assertEquals(
            WebDavUiState(
                thumbnailRequestRevision = previousThumbnailRequestRevision + 1L,
            ),
            viewModel.uiState,
        )
    }

    @Test
    fun thumbnailRequestRevisionRemainsMonotonicAcrossReconnects() = runTest(dispatcher) {
        val viewModel = testViewModel { _, _, _ -> FakeWebDavClient() }
        viewModel.updateBaseUrl("https://example.test/dav/")
        viewModel.testConnection()
        dispatcher.scheduler.advanceUntilIdle()
        val firstConnectionRevision = viewModel.uiState.thumbnailRequestRevision

        viewModel.startNewConnection()
        val resetRevision = viewModel.uiState.thumbnailRequestRevision
        viewModel.updateBaseUrl("https://example.test/dav/")
        viewModel.testConnection()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(resetRevision > firstConnectionRevision)
        assertTrue(viewModel.uiState.thumbnailRequestRevision > resetRevision)
    }

    @Test
    fun handleBackFromNestedWebDavPathOpensParentDirectory() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        val viewModel = testViewModel { _, _, _ -> client }
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.openPath("/Comics/Series/")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.handleBack())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("/Comics/", viewModel.uiState.currentPath)
        assertEquals(listOf("/Comics/Series/", "/Comics/"), client.listedPaths)
    }

    @Test
    fun returningToRecentlyLoadedParentUsesMemoryCache() = runTest(dispatcher) {
        val child = directoryItem("Series", "/Comics/Series/")
        val client = FakeWebDavClient(
            itemsByPath = mapOf(
                "/Comics/" to listOf(child),
                "/Comics/Series/" to listOf(directoryItem("Volume", "/Comics/Series/Volume/")),
            ),
        )
        val viewModel = testViewModel { _, _, _ -> client }
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.openPath("/Comics/")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.openDirectory(child)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.handleBack())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("/Comics/", "/Comics/Series/"), client.listedPaths)
        assertEquals("/Comics/", viewModel.uiState.currentPath)
        assertEquals(listOf("Series"), viewModel.uiState.items.map { it.name })
    }

    @Test
    fun refreshCurrentWebDavDirectoryBypassesCacheAndPreservesSearch() = runTest(dispatcher) {
        val client = FakeWebDavClient(
            items = listOf(directoryItem("Alpha", "/Alpha/")),
        )
        val viewModel = testViewModel { _, _, _ -> client }

        viewModel.connectToSavedSource("https://example.test/dav/", null, null, "/Comics/")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.updateSearchQuery("Alpha")
        dispatcher.scheduler.advanceUntilIdle()
        val initialThumbnailRequestRevision = viewModel.uiState.thumbnailRequestRevision
        client.items = listOf(
            directoryItem("Alpha 2", "/Alpha 2/"),
            directoryItem("Beta", "/Beta/"),
        )

        viewModel.refreshCurrentDirectory()

        assertTrue(viewModel.uiState.isRefreshing)
        assertEquals(listOf("Alpha"), viewModel.uiState.items.map { it.name })
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.isRefreshing)
        assertEquals("Alpha", viewModel.uiState.searchQuery)
        assertEquals(listOf("Alpha 2"), viewModel.uiState.items.map { it.name })
        assertEquals(listOf("/Comics/", "/Comics/"), client.listedPaths)
        assertTrue(viewModel.uiState.thumbnailRequestRevision > initialThumbnailRequestRevision)
    }

    @Test
    fun changingCredentialsDoesNotReuseAnotherAccountsDirectoryCache() = runTest(dispatcher) {
        val firstClient = FakeWebDavClient(items = listOf(directoryItem("First", "/First/")))
        val secondClient = FakeWebDavClient(items = listOf(directoryItem("Second", "/Second/")))
        val viewModel = WebDavViewModel(
            clientFactory = { _, username, _ ->
                if (username == "first") firstClient else secondClient
            },
            directoryComputationDispatcher = dispatcher,
        )

        viewModel.connectToSavedSource("https://example.test/dav/", "first", "secret", "/Comics/")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.connectToSavedSource("https://example.test/dav/", "second", "secret", "/Comics/")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("/Comics/"), secondClient.listedPaths)
        assertEquals(listOf("Second"), viewModel.uiState.items.map { it.name })
    }

    @Test
    fun handleBackFromMountedChineseWebDavRootIsNotHandled() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        val viewModel = testViewModel { _, _, _ -> client }

        viewModel.connectToSavedSource(
            baseUrl = "https://example.test/webdav/漫画/",
            username = null,
            password = null,
            path = "webdav/%E6%BC%AB%E7%94%BB/",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.handleBack())

        assertEquals("webdav/%E6%BC%AB%E7%94%BB/", viewModel.uiState.currentPath)
        assertEquals(listOf("webdav/%E6%BC%AB%E7%94%BB/"), client.listedPaths)
    }

    @Test
    fun handleBackFromChildOfMountedChineseWebDavRootOpensMountedRoot() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        val viewModel = testViewModel { _, _, _ -> client }

        viewModel.connectToSavedSource(
            baseUrl = "https://example.test/webdav/漫画/",
            username = null,
            password = null,
            path = "webdav/%E6%BC%AB%E7%94%BB/%E6%A8%A1%E5%9B%A0/",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.handleBack())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("webdav/%E6%BC%AB%E7%94%BB/", viewModel.uiState.currentPath)
        assertEquals(
            listOf(
                "webdav/%E6%BC%AB%E7%94%BB/%E6%A8%A1%E5%9B%A0/",
                "webdav/%E6%BC%AB%E7%94%BB/",
            ),
            client.listedPaths,
        )
    }

    @Test
    fun handleBackFromWebDavRootIsNotHandled() = runTest(dispatcher) {
        val client = FakeWebDavClient()
        val viewModel = testViewModel { _, _, _ -> client }
        viewModel.updateBaseUrl("https://example.test/dav/")

        viewModel.openPath("/")
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.handleBack())
        assertEquals(listOf("/"), client.listedPaths)
    }

    private fun testViewModel(clientFactory: WebDavClientFactory): WebDavViewModel =
        WebDavViewModel(
            clientFactory = clientFactory,
            directoryComputationDispatcher = dispatcher,
        )

    private class FakeWebDavClient(
        var items: List<WebDavItem> = emptyList(),
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

    private class BlockingDirectoryWebDavClient : WebDavClient {
        private val responses = mutableMapOf<String, CompletableDeferred<List<WebDavItem>>>()
        val cancelledPaths = mutableListOf<String>()
        val listedPaths = mutableListOf<String>()

        override suspend fun list(path: String): List<WebDavItem> {
            listedPaths += path
            val response = CompletableDeferred<List<WebDavItem>>()
            responses[path] = response
            return try {
                response.await()
            } catch (error: CancellationException) {
                cancelledPaths += path
                throw error
            }
        }

        fun complete(path: String, items: List<WebDavItem> = emptyList()) {
            responses.getValue(path).complete(items)
        }

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, 0, null, null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = byteArrayOf()

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long = 0L
    }

    private class NonCancellableDirectoryWebDavClient : WebDavClient {
        private val responses = mutableMapOf<String, CompletableDeferred<List<WebDavItem>>>()

        override suspend fun list(path: String): List<WebDavItem> {
            val response = CompletableDeferred<List<WebDavItem>>()
            responses[path] = response
            return withContext(NonCancellable) { response.await() }
        }

        fun complete(path: String, items: List<WebDavItem>) {
            responses.getValue(path).complete(items)
        }

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, 0, null, null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = byteArrayOf()

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long = 0L
    }

    private fun directoryItem(name: String, path: String): WebDavItem =
        WebDavItem(name, path, isDirectory = true, size = null, etag = null, lastModified = null)

    private class QueuedCoroutineDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun hasTasks(): Boolean = tasks.isNotEmpty()

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
            }
        }
    }
}
