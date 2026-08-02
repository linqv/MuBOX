package org.mubox.reader.feature.webdav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.mubox.reader.ui.directorylisting.DirectorySortDirection
import org.mubox.reader.ui.directorylisting.DirectorySortField
import org.mubox.reader.ui.directorylisting.DirectoryListingViewMode
import org.mubox.reader.ui.directorylisting.DirectoryVideoThumbnail
import org.mubox.reader.ui.directorylisting.filterAndSortDirectoryEntries
import org.mubox.reader.ui.directorylisting.opposite
import org.mubox.reader.ui.directorylisting.putBoundedDirectoryVideoThumbnail
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavException
import org.mubox.reader.core.remote.WebDavItem
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias WebDavClientFactory = (baseUrl: String, username: String?, password: String?) -> WebDavClient

data class WebDavUiState(
    val displayName: String = "",
    val host: String = "",
    val port: String = "443",
    val rootPath: String = "/",
    val useHttps: Boolean = true,
    val anonymousAccess: Boolean = false,
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val currentPath: String = "/",
    val items: List<WebDavItem> = emptyList(),
    val searchQuery: String = "",
    val sortField: DirectorySortField = DirectorySortField.NAME,
    val sortDirection: DirectorySortDirection = DirectorySortDirection.ASCENDING,
    val viewMode: DirectoryListingViewMode = DirectoryListingViewMode.LIST,
    val videoThumbnails: Map<String, DirectoryVideoThumbnail> = emptyMap(),
    val thumbnailRequestRevision: Long = 0L,
    val status: String = WEB_DAV_STATUS_NOT_CONNECTED,
    val message: String = "",
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
)

private data class WebDavUrlFields(
    val baseUrl: String,
    val host: String,
    val port: String,
    val rootPath: String,
    val useHttps: Boolean,
)

const val WEB_DAV_STATUS_NOT_CONNECTED = "未连接"
const val WEB_DAV_STATUS_CONNECTING = "正在连接..."
const val WEB_DAV_STATUS_CONNECTED = "已连接"

internal fun webDavConnectionFailureMessage(error: Throwable): String = when (error) {
    is WebDavException.RangeNotSupported -> "服务器不支持 Range 请求"
    is WebDavException.InvalidContentRange -> error.message ?: "Content-Range 无效"
    is WebDavException.InvalidResponse -> "服务器返回的不是有效的 WebDAV 目录列表，请检查 WebDAV 地址是否正确"
    is WebDavException.HttpStatus -> error.message ?: "HTTP ${error.statusCode}"
    else -> error.message ?: "发生未知错误"
}

class WebDavViewModel(
    private val clientFactory: WebDavClientFactory,
    private val directoryComputationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    var uiState by mutableStateOf(WebDavUiState())
        private set

    private var client: WebDavClient? = null
    private var connectedAccountId: String? = null
    private var connectedCredentials: WebDavConnectionCredentials? = null
    private var currentDirectoryItems: List<WebDavItem> = emptyList()
    private var directoryLoadJob: Job? = null
    private var directoryLoadGeneration: Long = 0L
    private var requestedDirectoryPath: String = "/"
    private val directoryCache = WebDavDirectoryMemoryCache()
    private var directoryPresentationJob: Job? = null
    private var directoryPresentationGeneration: Long = 0L

    fun activeClient(): WebDavClient? = client

    fun activeAccountId(): String? = connectedAccountId

    fun accountId(): String = "${uiState.baseUrl.trim()}|${uiState.username}"

    fun updateBaseUrl(value: String) {
        uiState = uiState.withBaseUrl(value)
    }

    fun updateDisplayName(value: String) {
        uiState = uiState.copy(displayName = value)
    }

    fun updateHost(value: String) {
        uiState = uiState.copy(host = value).withBuiltBaseUrl()
    }

    fun updatePort(value: String) {
        uiState = uiState.copy(port = value.filter { it.isDigit() }).withBuiltBaseUrl()
    }

    fun updateRootPath(value: String) {
        uiState = uiState.copy(rootPath = value).withBuiltBaseUrl()
    }

    fun updateUseHttps(value: Boolean) {
        val defaultPort = if (value) "443" else "80"
        val nextPort = uiState.port.takeIf { it.isNotBlank() } ?: defaultPort
        uiState = uiState.copy(useHttps = value, port = nextPort).withBuiltBaseUrl()
    }

    fun updateAnonymousAccess(value: Boolean) {
        uiState = uiState.copy(
            anonymousAccess = value,
            username = if (value) "" else uiState.username,
            password = if (value) "" else uiState.password,
        )
    }

    fun updateUsername(value: String) {
        uiState = uiState.copy(username = value)
    }

    fun updatePassword(value: String) {
        uiState = uiState.copy(password = value)
    }

    fun testConnection() {
        uiState = uiState.withBuiltBaseUrl().copy(videoThumbnails = emptyMap())
        val credentials = WebDavConnectionCredentials(
            baseUrl = uiState.baseUrl.trim(),
            username = if (uiState.anonymousAccess) "" else uiState.username,
            password = if (uiState.anonymousAccess) "" else uiState.password,
        )
        val newClient = clientFactory(credentials.baseUrl, credentials.username, credentials.password)
        directoryCache.clear()
        client = newClient
        connectedAccountId = accountId()
        connectedCredentials = credentials
        loadPath(path = "/")
    }

    fun startNewConnection() {
        val nextThumbnailRequestRevision = uiState.thumbnailRequestRevision + 1L
        directoryLoadGeneration += 1
        directoryLoadJob?.cancel()
        directoryLoadJob = null
        cancelDirectoryPresentation()
        client = null
        connectedAccountId = null
        connectedCredentials = null
        currentDirectoryItems = emptyList()
        requestedDirectoryPath = "/"
        directoryCache.clear()
        uiState = WebDavUiState(
            thumbnailRequestRevision = nextThumbnailRequestRevision,
        )
    }

    fun editSavedConnection(
        displayName: String,
        baseUrl: String,
        username: String?,
        password: String?,
        path: String,
    ) {
        val nextThumbnailRequestRevision = uiState.thumbnailRequestRevision + 1L
        directoryLoadGeneration += 1
        directoryLoadJob?.cancel()
        directoryLoadJob = null
        cancelDirectoryPresentation()
        client = null
        connectedAccountId = null
        connectedCredentials = null
        currentDirectoryItems = emptyList()
        requestedDirectoryPath = path.ifBlank { "/" }
        directoryCache.clear()
        uiState = WebDavUiState(
            displayName = displayName,
            username = username.orEmpty(),
            password = password.orEmpty(),
            currentPath = path.ifBlank { "/" },
            anonymousAccess = username.isNullOrBlank() && password.isNullOrBlank(),
            thumbnailRequestRevision = nextThumbnailRequestRevision,
        ).withBaseUrl(baseUrl)
    }

    fun connectToSavedSource(baseUrl: String, username: String?, password: String?, path: String) {
        val normalizedBaseUrl = parseWebDavBaseUrl(baseUrl).baseUrl
        val credentials = WebDavConnectionCredentials(
            baseUrl = normalizedBaseUrl,
            username = username.orEmpty(),
            password = password.orEmpty(),
        )
        val shouldReuseClient = client != null && connectedCredentials == credentials
        uiState = uiState.withBaseUrl(normalizedBaseUrl).copy(
            username = username.orEmpty(),
            password = password.orEmpty(),
            anonymousAccess = username.isNullOrBlank() && password.isNullOrBlank(),
            videoThumbnails = if (shouldReuseClient) uiState.videoThumbnails else emptyMap(),
        )
        if (!shouldReuseClient) {
            directoryCache.clear()
            client = clientFactory(credentials.baseUrl, username, password)
            connectedCredentials = credentials
        }
        connectedAccountId = accountId()
        loadPath(
            path = path,
            keepConnectedStatus = shouldReuseClient,
            replaceVisibleDirectory = true,
        )
    }

    fun openDirectory(item: WebDavItem) {
        if (!item.isDirectory) return
        loadPath(item.path, keepConnectedStatus = true)
    }

    fun openPath(path: String) {
        loadPath(
            path = path,
            keepConnectedStatus = true,
            replaceVisibleDirectory = true,
        )
    }

    fun refreshCurrentDirectory() {
        if (uiState.isLoading || uiState.isRefreshing) return
        loadPath(
            path = requestedDirectoryPath,
            keepConnectedStatus = true,
            forceRefresh = true,
        )
    }

    fun updateSearchQuery(query: String) {
        uiState = uiState.copy(searchQuery = query)
        scheduleVisibleItems()
    }

    fun updateSortField(sortField: DirectorySortField) {
        uiState = uiState.copy(sortField = sortField)
        scheduleVisibleItems()
    }

    fun toggleSortDirection() {
        val direction = uiState.sortDirection.opposite()
        uiState = uiState.copy(sortDirection = direction)
        scheduleVisibleItems()
    }

    fun toggleViewMode() {
        uiState = uiState.copy(
            viewMode = when (uiState.viewMode) {
                DirectoryListingViewMode.LIST -> DirectoryListingViewMode.GRID
                DirectoryListingViewMode.GRID -> DirectoryListingViewMode.LIST
            },
        )
    }

    fun onVideoThumbnailExtracted(
        path: String,
        version: String,
        thumbnailPath: String,
    ) {
        val previousRevision = uiState.videoThumbnails[path]?.artworkRevision ?: 0L
        uiState = uiState.copy(
            videoThumbnails = putBoundedDirectoryVideoThumbnail(
                thumbnails = uiState.videoThumbnails,
                key = path,
                thumbnail = DirectoryVideoThumbnail(
                    version = version,
                    path = thumbnailPath,
                    artworkRevision = previousRevision + 1L,
                ),
            ),
        )
    }

    fun playbackDirectoryItems(): List<WebDavItem> =
        visibleItems(
            entries = currentDirectoryItems,
            query = "",
            sortField = uiState.sortField,
            sortDirection = uiState.sortDirection,
        )

    fun handleBack(): Boolean {
        val navigationPath = requestedDirectoryPath
        if (isAtMountedRoot(navigationPath)) return false
        val parentPath = parentDirectoryPath(navigationPath) ?: return false
        if (isMountedPath(navigationPath) && !isMountedPath(parentPath)) return false
        loadPath(parentPath, keepConnectedStatus = true)
        return true
    }

    private fun loadPath(
        path: String,
        keepConnectedStatus: Boolean = false,
        forceRefresh: Boolean = false,
        replaceVisibleDirectory: Boolean = false,
    ) {
        directoryLoadJob?.cancel()
        cancelDirectoryPresentation()
        val loadGeneration = ++directoryLoadGeneration
        requestedDirectoryPath = path
        val cachedItems = if (forceRefresh) null else directoryCache.get(path)
        val hadConnectedSession = client != null && connectedAccountId != null
        val activeClient = client ?: clientFactory(uiState.baseUrl.trim(), uiState.username, uiState.password)
        client = activeClient
        if (connectedAccountId == null) {
            connectedAccountId = accountId()
        }
        val keepBrowserState = keepConnectedStatus && hadConnectedSession
        val loadingStatus = if (keepBrowserState) {
            WEB_DAV_STATUS_CONNECTED
        } else {
            WEB_DAV_STATUS_CONNECTING
        }
        if (replaceVisibleDirectory) {
            currentDirectoryItems = emptyList()
        }
        uiState = if (forceRefresh) {
            uiState.copy(
                isLoading = false,
                isRefreshing = true,
                status = loadingStatus,
                message = "",
            )
        } else {
            uiState.copy(
                currentPath = if (replaceVisibleDirectory) path else uiState.currentPath,
                items = if (replaceVisibleDirectory) emptyList() else uiState.items,
                isLoading = true,
                isRefreshing = false,
                searchQuery = "",
                status = loadingStatus,
                message = "",
            )
        }
        directoryLoadJob = viewModelScope.launch {
            try {
                val listedItems = cachedItems ?: activeClient.list(path)
                var appliedPresentationGeneration = directoryPresentationGeneration
                val query = uiState.searchQuery
                val sortField = uiState.sortField
                val sortDirection = uiState.sortDirection
                val (items, initialVisibleItems) = withContext(directoryComputationDispatcher) {
                    val browsableItems = if (cachedItems == null) {
                        filterBrowsableWebDavItems(listedItems)
                    } else {
                        listedItems
                    }
                    browsableItems to visibleItems(
                        entries = browsableItems,
                        query = query,
                        sortField = sortField,
                        sortDirection = sortDirection,
                    )
                }
                if (loadGeneration != directoryLoadGeneration) return@launch
                if (cachedItems == null) {
                    directoryCache.put(path, items)
                }
                currentDirectoryItems = items
                var visibleItems = initialVisibleItems
                while (appliedPresentationGeneration != directoryPresentationGeneration) {
                    appliedPresentationGeneration = directoryPresentationGeneration
                    val latestQuery = uiState.searchQuery
                    val latestSortField = uiState.sortField
                    val latestSortDirection = uiState.sortDirection
                    visibleItems = withContext(directoryComputationDispatcher) {
                        visibleItems(
                            entries = items,
                            query = latestQuery,
                            sortField = latestSortField,
                            sortDirection = latestSortDirection,
                        )
                    }
                    if (loadGeneration != directoryLoadGeneration) return@launch
                }
                uiState = uiState.copy(
                    currentPath = path,
                    items = visibleItems,
                    status = WEB_DAV_STATUS_CONNECTED,
                    isLoading = false,
                    isRefreshing = false,
                    thumbnailRequestRevision = uiState.thumbnailRequestRevision + 1,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (loadGeneration != directoryLoadGeneration) return@launch
                requestedDirectoryPath = uiState.currentPath
                val message = webDavConnectionFailureMessage(error)
                uiState = if (keepBrowserState) {
                    uiState.copy(
                        status = WEB_DAV_STATUS_CONNECTED,
                        message = message,
                        isLoading = false,
                        isRefreshing = false,
                    )
                } else {
                    uiState.copy(
                        status = message,
                        message = message,
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
            }
        }
    }

    private fun scheduleVisibleItems() {
        directoryPresentationJob?.cancel()
        val presentationGeneration = ++directoryPresentationGeneration
        if (uiState.isLoading) {
            directoryPresentationJob = null
            return
        }
        val loadGeneration = directoryLoadGeneration
        val entries = currentDirectoryItems
        val query = uiState.searchQuery
        val sortField = uiState.sortField
        val sortDirection = uiState.sortDirection
        directoryPresentationJob = viewModelScope.launch {
            val visibleItems = withContext(directoryComputationDispatcher) {
                visibleItems(
                    entries = entries,
                    query = query,
                    sortField = sortField,
                    sortDirection = sortDirection,
                )
            }
            if (presentationGeneration != directoryPresentationGeneration) return@launch
            if (loadGeneration != directoryLoadGeneration) return@launch
            if (entries !== currentDirectoryItems) return@launch
            uiState = uiState.copy(items = visibleItems)
        }
    }

    private fun cancelDirectoryPresentation() {
        directoryPresentationGeneration += 1
        directoryPresentationJob?.cancel()
        directoryPresentationJob = null
    }

    private fun visibleItems(
        entries: List<WebDavItem>,
        query: String,
        sortField: DirectorySortField,
        sortDirection: DirectorySortDirection,
    ): List<WebDavItem> = filterAndSortDirectoryEntries(
        entries = entries,
        query = query,
        sortField = sortField,
        sortDirection = sortDirection,
        nameOf = WebDavItem::name,
        sizeOf = WebDavItem::size,
    )

    private fun parentDirectoryPath(path: String): String? {
        val normalized = path.takeIf { it.isNotBlank() } ?: "/"
        val withoutTrailingSlash = normalized.trimEnd('/')
        if (withoutTrailingSlash.isBlank()) return null
        val lastSlashIndex = withoutTrailingSlash.lastIndexOf('/')
        return if (lastSlashIndex <= 0) {
            "/"
        } else {
            withoutTrailingSlash.substring(0, lastSlashIndex + 1)
        }
    }

    private fun isAtMountedRoot(path: String): Boolean {
        val mountedRoot = mountedRootPath() ?: return false
        return mountedRoot != "/" && canonicalDirectoryPath(path) == mountedRoot
    }

    private fun isMountedPath(path: String): Boolean {
        val mountedRoot = mountedRootPath() ?: return false
        return mountedRoot != "/" && canonicalDirectoryPath(path).startsWith(mountedRoot)
    }

    private fun mountedRootPath(): String? {
        val baseUrl = connectedCredentials?.baseUrl ?: uiState.baseUrl.trim()
        return runCatching {
            val rawPath = URI(baseUrl).rawPath.orEmpty()
            canonicalDirectoryPath(rawPath)
        }.getOrNull()
    }

    private fun canonicalDirectoryPath(path: String): String {
        val withLeadingSlash = if (path.startsWith("/")) path else "/$path"
        val withTrailingSlash = if (withLeadingSlash.endsWith("/")) withLeadingSlash else "$withLeadingSlash/"
        return URLDecoder.decode(withTrailingSlash, StandardCharsets.UTF_8.name())
    }

    private fun WebDavUiState.withBuiltBaseUrl(): WebDavUiState {
        return copy(baseUrl = buildWebDavBaseUrl(useHttps, host, port, rootPath))
    }

    private fun WebDavUiState.withBaseUrl(baseUrl: String): WebDavUiState {
        val fields = parseWebDavBaseUrl(baseUrl)
        return copy(
            baseUrl = fields.baseUrl,
            host = fields.host,
            port = fields.port,
            rootPath = fields.rootPath,
            useHttps = fields.useHttps,
        )
    }

    private data class WebDavConnectionCredentials(
        val baseUrl: String,
        val username: String,
        val password: String,
    )
}

internal fun buildWebDavBaseUrl(useHttps: Boolean, host: String, port: String, rootPath: String): String {
    val cleanHost = host.trim()
    if (cleanHost.isBlank()) return ""
    val scheme = if (useHttps) "https" else "http"
    val cleanPort = port.trim()
    val shouldIncludePort = cleanPort.isNotBlank() &&
        !((useHttps && cleanPort == "443") || (!useHttps && cleanPort == "80"))
    val normalizedPath = normalizeWebDavRootPath(rootPath)
    return buildString {
        append(scheme)
        append("://")
        append(cleanHost)
        if (shouldIncludePort) {
            append(":")
            append(cleanPort)
        }
        append(normalizedPath)
    }
}

private fun parseWebDavBaseUrl(baseUrl: String): WebDavUrlFields {
    val trimmed = baseUrl.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull()
    if (uri == null || uri.host.isNullOrBlank()) {
        return WebDavUrlFields(
            baseUrl = trimmed,
            host = "",
            port = "443",
            rootPath = "/",
            useHttps = true,
        )
    }
    val useHttps = !uri.scheme.equals("http", ignoreCase = true)
    val defaultPort = if (useHttps) "443" else "80"
    val port = uri.port.takeIf { it > 0 }?.toString() ?: defaultPort
    val rootPath = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
    return WebDavUrlFields(
        baseUrl = buildWebDavBaseUrl(useHttps = useHttps, host = uri.host, port = port, rootPath = rootPath),
        host = uri.host,
        port = port,
        rootPath = rootPath,
        useHttps = useHttps,
    )
}

private fun normalizeWebDavRootPath(path: String): String {
    val cleanPath = path.trim()
    if (cleanPath.isBlank()) return "/"
    return if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
}
