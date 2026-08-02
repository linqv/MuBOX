package org.mubox.reader.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.mubox.reader.core.model.library.LibraryItemWithSources
import org.mubox.reader.core.model.library.SourceType
import org.mubox.reader.ui.MuBoxCopy
import org.mubox.reader.ui.MuBoxHeaderBar
import org.mubox.reader.ui.MuBoxMetrics
import org.mubox.reader.ui.muBoxAppBackground
import org.mubox.reader.ui.muBoxGradientBorder
import org.mubox.reader.ui.rememberMuBoxColors
import org.mubox.reader.ui.decodeWebDavPathForDisplay
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
    navigationIcon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .muBoxAppBackground(colors),
    ) {
        MuBoxHeaderBar(
            title = MuBoxCopy.libraryTitle,
            navigationIcon = navigationIcon,
            actions = {
                TextButton(onClick = onOpenDirectories) { Text(MuBoxCopy.sourcesTitle) }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(
                    horizontal = MuBoxMetrics.PageHorizontalPaddingDp,
                    vertical = 14.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = libraryCountLabel(uiState.items.size),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            if (uiState.message != null || uiState.error != null) {
                org.mubox.reader.ui.MuBoxMessagePanel(
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
}

@Composable
private fun EmptyLibrary(
    onOpenDirectories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 22.dp)
                .size(72.dp)
                .background(colors.accentSoft, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                contentDescription = null,
                tint = colors.onAccentSoft,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = MuBoxCopy.emptyLibraryTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.text,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = MuBoxCopy.emptyLibraryBody,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
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
                Text(MuBoxCopy.sourcesTitle)
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
    val colors = rememberMuBoxColors()
    val cardShape = RoundedCornerShape(MuBoxMetrics.PanelCornerDp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .muBoxGradientBorder(
                colors = colors,
                shape = cardShape,
                highlighted = isSelected,
                width = if (isSelected) 2.dp else 1.dp,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "书架操作",
            ),
        shape = cardShape,
        color = if (isSelected) {
            colors.rowSelected.copy(alpha = 0.4f)
        } else {
            Color.Transparent
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(4.dp),
        ) {
            val posterShape = RoundedCornerShape(MuBoxMetrics.PanelCornerDp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(org.mubox.reader.ui.muBoxPosterAspectRatio(libraryPosterKind()))
                    .muBoxGradientBorder(colors = colors, shape = posterShape),
                shape = posterShape,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                color = colors.raisedSurface,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.raisedSurface),
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
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.2f),
                                            Color.Transparent,
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.3f),
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
                        color = colors.panel.copy(alpha = 0.92f),
                    ) {
                        Text(
                            text = sourceLabel(item.item.sourceType),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.mediaAccent,
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
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = sourceMeta(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FallbackCoverTitle(title: String) {
    val colors = rememberMuBoxColors()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
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

internal fun libraryPosterKind(): org.mubox.reader.ui.MuBoxPosterKind =
    org.mubox.reader.ui.MuBoxPosterKind.Comic

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
