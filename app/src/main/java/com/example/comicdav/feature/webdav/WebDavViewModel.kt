package com.example.comicdav.feature.webdav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comicdav.network.OkHttpWebDavClient
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavException
import com.example.comicdav.network.WebDavItem
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

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
    val status: String = WEB_DAV_STATUS_NOT_CONNECTED,
    val message: String = "",
    val isLoading: Boolean = false,
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

class WebDavViewModel(
    private val clientFactory: WebDavClientFactory = { baseUrl, username, password ->
        OkHttpWebDavClient(
            baseUrl = baseUrl,
            username = username?.takeIf { it.isNotBlank() },
            password = password?.takeIf { it.isNotBlank() },
        )
    },
) : ViewModel() {
    var uiState by mutableStateOf(WebDavUiState())
        private set

    private var client: WebDavClient? = null
    private var connectedAccountId: String? = null
    private var connectedCredentials: WebDavConnectionCredentials? = null

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
        uiState = uiState.withBuiltBaseUrl()
        val credentials = WebDavConnectionCredentials(
            baseUrl = uiState.baseUrl.trim(),
            username = if (uiState.anonymousAccess) "" else uiState.username,
            password = if (uiState.anonymousAccess) "" else uiState.password,
        )
        val newClient = clientFactory(credentials.baseUrl, credentials.username, credentials.password)
        client = newClient
        connectedAccountId = accountId()
        connectedCredentials = credentials
        loadPath(path = "/")
    }

    fun startNewConnection() {
        client = null
        connectedAccountId = null
        connectedCredentials = null
        uiState = WebDavUiState()
    }

    fun editSavedConnection(
        displayName: String,
        baseUrl: String,
        username: String?,
        password: String?,
        path: String,
    ) {
        client = null
        connectedAccountId = null
        connectedCredentials = null
        uiState = WebDavUiState(
            displayName = displayName,
            username = username.orEmpty(),
            password = password.orEmpty(),
            currentPath = path.ifBlank { "/" },
            anonymousAccess = username.isNullOrBlank() && password.isNullOrBlank(),
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
        )
        if (!shouldReuseClient) {
            client = clientFactory(credentials.baseUrl, username, password)
            connectedCredentials = credentials
        }
        connectedAccountId = accountId()
        loadPath(path = path, keepConnectedStatus = shouldReuseClient)
    }

    fun openDirectory(item: WebDavItem) {
        if (!item.isDirectory) return
        loadPath(item.path, keepConnectedStatus = true)
    }

    fun openPath(path: String) {
        loadPath(path, keepConnectedStatus = true)
    }

    fun handleBack(): Boolean {
        if (isAtMountedRoot(uiState.currentPath)) return false
        val parentPath = parentDirectoryPath(uiState.currentPath) ?: return false
        if (isMountedPath(uiState.currentPath) && !isMountedPath(parentPath)) return false
        loadPath(parentPath, keepConnectedStatus = true)
        return true
    }

    private fun loadPath(path: String, keepConnectedStatus: Boolean = false) {
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
        uiState = uiState.copy(isLoading = true, status = loadingStatus, message = "")
        viewModelScope.launch {
            runCatching {
                activeClient.list(path)
                    .filter { it.isDirectory || it.name.endsWith(".cbz", true) || it.name.endsWith(".zip", true) }
                    .sortedWith(compareBy<WebDavItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
            }.fold(
                onSuccess = { items ->
                    uiState = uiState.copy(
                        currentPath = path,
                        items = items,
                        status = WEB_DAV_STATUS_CONNECTED,
                        isLoading = false,
                    )
                },
                onFailure = { error ->
                    val message = error.userMessage()
                    uiState = if (keepBrowserState) {
                        uiState.copy(
                            status = WEB_DAV_STATUS_CONNECTED,
                            message = message,
                            isLoading = false,
                        )
                    } else {
                        uiState.copy(status = message, isLoading = false)
                    }
                },
            )
        }
    }

    private fun Throwable.userMessage(): String = when (this) {
        is WebDavException.RangeNotSupported -> "服务器不支持 Range 请求"
        is WebDavException.InvalidContentRange -> message ?: "Content-Range 无效"
        is WebDavException.HttpStatus -> message ?: "HTTP $statusCode"
        else -> message ?: "发生未知错误"
    }

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
