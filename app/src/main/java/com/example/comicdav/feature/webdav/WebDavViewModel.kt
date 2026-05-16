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
import kotlinx.coroutines.launch
import kotlin.math.max

typealias WebDavClientFactory = (baseUrl: String, username: String?, password: String?) -> WebDavClient

data class WebDavUiState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val currentPath: String = "/",
    val items: List<WebDavItem> = emptyList(),
    val selectedItem: WebDavItem? = null,
    val status: String = WEB_DAV_STATUS_NOT_CONNECTED,
    val diagnostic: String = "",
    val isLoading: Boolean = false,
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
        uiState = uiState.copy(baseUrl = value)
    }

    fun updateUsername(value: String) {
        uiState = uiState.copy(username = value)
    }

    fun updatePassword(value: String) {
        uiState = uiState.copy(password = value)
    }

    fun testConnection() {
        val credentials = WebDavConnectionCredentials(
            baseUrl = uiState.baseUrl.trim(),
            username = uiState.username,
            password = uiState.password,
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

    fun connectToSavedSource(baseUrl: String, username: String?, password: String?, path: String) {
        val credentials = WebDavConnectionCredentials(
            baseUrl = baseUrl.trim(),
            username = username.orEmpty(),
            password = password.orEmpty(),
        )
        val shouldReuseClient = client != null && connectedCredentials == credentials
        uiState = uiState.copy(
            baseUrl = baseUrl,
            username = username.orEmpty(),
            password = password.orEmpty(),
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
        val parentPath = parentDirectoryPath(uiState.currentPath) ?: return false
        loadPath(parentPath, keepConnectedStatus = true)
        return true
    }

    fun selectItem(item: WebDavItem) {
        if (item.isDirectory) return
        uiState = uiState.copy(selectedItem = item, diagnostic = "")
    }

    fun probeTail64KiB() {
        val item = uiState.selectedItem ?: return
        val activeClient = client ?: clientFactory(uiState.baseUrl.trim(), uiState.username, uiState.password)
        client = activeClient
        uiState = uiState.copy(isLoading = true, diagnostic = "正在读取文件尾部...")
        viewModelScope.launch {
            runCatching {
                val info = activeClient.head(item.path)
                val start = max(0L, info.size - TAIL_READ_SIZE)
                val end = info.size - 1
                val bytes = activeClient.readRange(item.path, start, end)
                "读取 ${bytes.size} 字节，范围 $start-$end"
            }.fold(
                onSuccess = { message ->
                    uiState = uiState.copy(isLoading = false, diagnostic = message)
                },
                onFailure = { error ->
                    uiState = uiState.copy(isLoading = false, diagnostic = error.userMessage())
                },
            )
        }
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
        uiState = uiState.copy(isLoading = true, status = loadingStatus, diagnostic = "")
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
                        selectedItem = null,
                        status = WEB_DAV_STATUS_CONNECTED,
                        isLoading = false,
                    )
                },
                onFailure = { error ->
                    val message = error.userMessage()
                    uiState = if (keepBrowserState) {
                        uiState.copy(
                            status = WEB_DAV_STATUS_CONNECTED,
                            diagnostic = message,
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

    companion object {
        private const val TAIL_READ_SIZE = 64L * 1024L
    }

    private data class WebDavConnectionCredentials(
        val baseUrl: String,
        val username: String,
        val password: String,
    )
}
