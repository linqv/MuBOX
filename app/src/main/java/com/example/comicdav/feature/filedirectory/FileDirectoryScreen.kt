package com.example.comicdav.feature.filedirectory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.data.filedirectory.FileDirectorySourceType

@Composable
fun FileDirectoryScreen(
    uiState: FileDirectoryUiState,
    onAddLocalDirectory: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSource: (FileDirectorySourceEntity) -> Unit,
    onOpenDirectory: (FileDirectoryBrowserItem) -> Unit,
    onOpenComic: (FileDirectoryBrowserItem) -> Unit,
    onFavoriteComic: (FileDirectoryBrowserItem) -> Unit,
    onGoUp: () -> Unit,
    onCloseBrowser: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (uiState.currentTitle == null) {
            FileDirectoryHomeHeader(
                onAddLocalDirectory = onAddLocalDirectory,
                onOpenWebDav = onOpenWebDav,
                onOpenLibrary = onOpenLibrary,
            )
        } else {
            FileDirectoryBrowseHeader(
                title = uiState.currentTitle,
                onGoUp = onGoUp,
                onCloseBrowser = onCloseBrowser,
            )
        }

        if (uiState.message != null || uiState.error != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (uiState.error == null) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = uiState.error ?: uiState.message.orEmpty(),
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onDismissMessage) {
                        Text("Dismiss")
                    }
                }
            }
        }

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.currentTitle == null -> {
                SourceList(
                    sources = uiState.sources,
                    onOpenSource = onOpenSource,
                    modifier = Modifier.weight(1f),
                )
            }

            else -> {
                EntryList(
                    entries = uiState.entries,
                    onOpenDirectory = onOpenDirectory,
                    onOpenComic = onOpenComic,
                    onFavoriteComic = onFavoriteComic,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FileDirectoryHomeHeader(
    onAddLocalDirectory: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "File Directory",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Only directories you add manually are saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpenLibrary) {
                Text("Library")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAddLocalDirectory,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Add Folder")
            }
            OutlinedButton(
                onClick = onOpenWebDav,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("WebDAV")
            }
        }
    }
}

@Composable
private fun FileDirectoryBrowseHeader(
    title: String,
    onGoUp: () -> Unit,
    onCloseBrowser: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Tap folders to browse deeper.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onGoUp) {
                Text("Up")
            }
            TextButton(onClick = onCloseBrowser) {
                Text("Sources")
            }
        }
    }
}

@Composable
private fun SourceList(
    sources: List<FileDirectorySourceEntity>,
    onOpenSource: (FileDirectorySourceEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = "Add a local folder or WebDAV directory to start browsing.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sources, key = { it.id }) { source ->
            DirectorySourceRow(source = source, onClick = { onOpenSource(source) })
        }
    }
}

@Composable
private fun DirectorySourceRow(
    source: FileDirectorySourceEntity,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (source.sourceType) {
                    FileDirectorySourceType.LOCAL -> "Local folder"
                    FileDirectorySourceType.WEBDAV -> "WebDAV ${source.webDavPath.orEmpty()}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EntryList(
    entries: List<FileDirectoryBrowserItem>,
    onOpenDirectory: (FileDirectoryBrowserItem) -> Unit,
    onOpenComic: (FileDirectoryBrowserItem) -> Unit,
    onFavoriteComic: (FileDirectoryBrowserItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.uri }) { entry ->
            FileDirectoryEntryRow(
                entry = entry,
                onOpenDirectory = { onOpenDirectory(entry) },
                onOpenComic = { onOpenComic(entry) },
                onFavoriteComic = { onFavoriteComic(entry) },
            )
        }
    }
}

@Composable
private fun FileDirectoryEntryRow(
    entry: FileDirectoryBrowserItem,
    onOpenDirectory: () -> Unit,
    onOpenComic: () -> Unit,
    onFavoriteComic: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) "Folder" else entry.size?.let { "${it / 1024} KiB" } ?: "Comic file",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.isDirectory) {
                Button(
                    onClick = onOpenDirectory,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Open")
                }
            } else {
                OutlinedButton(
                    onClick = onFavoriteComic,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Favorite")
                }
                Button(
                    onClick = onOpenComic,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Read")
                }
            }
        }
    }
}
