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
    val status: String = "Not connected",
    val diagnostic: String = "",
    val isLoading: Boolean = false,
)

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
        val newClient = clientFactory(uiState.baseUrl.trim(), uiState.username, uiState.password)
        client = newClient
        connectedAccountId = accountId()
        loadPath(path = "/")
    }

    fun openDirectory(item: WebDavItem) {
        if (!item.isDirectory) return
        loadPath(item.path)
    }

    fun selectItem(item: WebDavItem) {
        if (item.isDirectory) return
        uiState = uiState.copy(selectedItem = item, diagnostic = "")
    }

    fun probeTail64KiB() {
        val item = uiState.selectedItem ?: return
        val activeClient = client ?: clientFactory(uiState.baseUrl.trim(), uiState.username, uiState.password)
        client = activeClient
        uiState = uiState.copy(isLoading = true, diagnostic = "Reading tail...")
        viewModelScope.launch {
            runCatching {
                val info = activeClient.head(item.path)
                val start = max(0L, info.size - TAIL_READ_SIZE)
                val end = info.size - 1
                val bytes = activeClient.readRange(item.path, start, end)
                "Read ${bytes.size} bytes from $start-$end"
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

    private fun loadPath(path: String) {
        val activeClient = client ?: clientFactory(uiState.baseUrl.trim(), uiState.username, uiState.password)
        client = activeClient
        if (connectedAccountId == null) {
            connectedAccountId = accountId()
        }
        uiState = uiState.copy(isLoading = true, status = "Connecting...", diagnostic = "")
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
                        status = "Connected",
                        isLoading = false,
                    )
                },
                onFailure = { error ->
                    uiState = uiState.copy(status = error.userMessage(), isLoading = false)
                },
            )
        }
    }

    private fun Throwable.userMessage(): String = when (this) {
        is WebDavException.RangeNotSupported -> "Range not supported"
        is WebDavException.InvalidContentRange -> message ?: "Invalid Content-Range"
        is WebDavException.HttpStatus -> message ?: "HTTP $statusCode"
        else -> message ?: "Unexpected error"
    }

    companion object {
        private const val TAIL_READ_SIZE = 64L * 1024L
    }
}
