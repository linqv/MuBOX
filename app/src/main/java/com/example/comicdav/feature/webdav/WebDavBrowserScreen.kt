package com.example.comicdav.feature.webdav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.comicdav.network.WebDavItem

@Composable
fun WebDavBrowserScreen(
    uiState: WebDavUiState,
    onItemClick: (WebDavItem) -> Unit,
    onProbeTail: () -> Unit,
    downloadProgress: DownloadProgressUi?,
    downloadError: String?,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = uiState.currentPath, style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(uiState.items) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(item) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(text = if (item.isDirectory) "[Dir] ${item.name}" else item.name)
                }
                HorizontalDivider()
            }
        }
        Button(
            onClick = onProbeTail,
            enabled = uiState.selectedItem?.isDirectory == false && !uiState.isLoading && downloadProgress == null,
        ) {
            Text("Read Tail 64 KiB")
        }
        if (downloadProgress != null) {
            Text(text = downloadProgress.label)
            LinearProgressIndicator(
                progress = { downloadProgress.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onCancelDownload) {
                Text("Cancel Download")
            }
        }
        if (!downloadError.isNullOrBlank()) {
            Text(text = downloadError)
        }
        if (uiState.diagnostic.isNotBlank()) {
            Text(text = uiState.diagnostic)
        }
    }
}

data class DownloadProgressUi(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    val label: String
        get() = "Downloading ${downloadedBytes / 1024} KiB / ${totalBytes / 1024} KiB"
}
