package com.example.comicdav.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.library.SourceType
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import java.io.File

@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onOpenItem: (LibraryItemWithSources) -> Unit,
    onSelectItem: (LibraryItemWithSources) -> Unit,
    onOpenDirectories: () -> Unit,
    onDismissMessage: () -> Unit,
    coversEnabled: Boolean = true,
    selectedItemId: Long? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ComicDavCopy.libraryTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = libraryCountLabel(uiState.items.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.TextButton(
                onClick = onOpenDirectories,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Text(ComicDavCopy.sourcesTitle)
            }
        }

        if (uiState.message != null || uiState.error != null) {
            com.example.comicdav.ui.MuBoxMessagePanel(
                text = uiState.error ?: uiState.message.orEmpty(),
                isError = uiState.error != null,
                onDismiss = onDismissMessage,
            )
        }

        AnimatedContent(
            targetState = uiState,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "LibraryContent",
        ) { state ->
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.items.isEmpty() -> {
                    EmptyLibrary(
                        onOpenDirectories = onOpenDirectories,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(state.items, key = { it.item.id }) { item ->
                            LibraryCard(
                                item = item,
                                onClick = { onOpenItem(item) },
                                onLongClick = { onSelectItem(item) },
                                coversEnabled = coversEnabled,
                                isSelected = selectedItemId == item.item.id,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    onOpenDirectories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 22.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                        ),
                    ),
                    shape = CircleShape,
                )
                .padding(20.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = ComicDavCopy.emptyLibraryTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = ComicDavCopy.emptyLibraryBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onOpenDirectories,
                modifier = Modifier.defaultMinSize(minWidth = 140.dp, minHeight = 48.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(ComicDavCopy.sourcesTitle)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCard(
    item: LibraryItemWithSources,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    coversEnabled: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val borderModifier = if (isSelected) {
        Modifier.border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(14.dp),
        )
    } else {
        Modifier
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(borderModifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "书架操作",
            ),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        } else {
            Color.Transparent
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(4.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(com.example.comicdav.ui.muBoxPosterAspectRatio(libraryPosterKind())),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val coverFile = item.item.coverPath
                        ?.takeIf { coversEnabled }
                        ?.let(::File)
                        ?.takeIf { it.isFile }
                    if (coverFile != null) {
                        AsyncImage(
                            model = coverFile,
                            contentDescription = item.item.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        // 顶部渐变以确保徽章可读
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.32f),
                                            Color.Transparent,
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.4f),
                                        ),
                                    ),
                                ),
                        )
                    } else {
                        FallbackCoverTitle(item.item.displayName)
                    }
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ) {
                        Text(
                            text = sourceLabel(item.item.sourceType),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(
                    text = item.item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = sourceMeta(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FallbackCoverTitle(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private fun libraryCountLabel(count: Int): String {
    return if (count == 0) "还没有漫画" else "$count 本漫画"
}

internal fun libraryPosterKind(): com.example.comicdav.ui.MuBoxPosterKind =
    com.example.comicdav.ui.MuBoxPosterKind.Comic

private fun sourceLabel(sourceType: SourceType): String {
    return when (sourceType) {
        SourceType.LOCAL -> "本地"
        SourceType.WEBDAV -> "WebDAV"
    }
}

private fun sourceMeta(item: LibraryItemWithSources): String {
    return when (item.item.sourceType) {
        SourceType.LOCAL -> item.localSource?.fileName ?: "本地文件"
        SourceType.WEBDAV -> item.webDavSource?.remotePath?.let(::decodeWebDavPathForDisplay) ?: "WebDAV"
    }
}
