package com.example.comicdav.feature.videolibrary

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoSourceType
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import java.io.File

private val CinematicBackdropTop = Color(0xFF050814)
private val CinematicBackdropBottom = Color(0xFF0A1220)
private val CinematicSurface = Color(0xFF0D1626)
private val CinematicSurfaceRaised = Color(0xFF121D31)
private val CinematicPosterTop = Color(0xFF172033)
private val CinematicPosterBottom = Color(0xFF050A14)
private val CinematicAccent = Color(0xFF22D3EE)
private val CinematicAccentOn = Color(0xFF03131A)
private val CinematicText = Color(0xFFF8FAFC)
private val CinematicTextMuted = Color(0xFFAEB8C8)
private val CinematicErrorSurface = Color(0xFF2B1118)
private val CinematicErrorText = Color(0xFFFFC4C8)

@Composable
fun VideoLibraryScreen(
    uiState: VideoLibraryUiState,
    onOpenItem: (VideoLibraryItemWithSources) -> Unit,
    onSelectItem: (VideoLibraryItemWithSources) -> Unit,
    onOpenDirectories: () -> Unit,
    onDismissMessage: () -> Unit,
    thumbnailsEnabled: Boolean = true,
    selectedItemId: Long? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(CinematicBackdropTop, CinematicBackdropBottom),
                ),
            )
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
                    text = "影视库",
                    style = MaterialTheme.typography.headlineSmall,
                    color = CinematicText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = videoLibraryCountLabel(uiState.items.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinematicTextMuted,
                )
            }
            OutlinedButton(
                onClick = onOpenDirectories,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, CinematicAccent.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CinematicSurfaceRaised.copy(alpha = 0.72f),
                    contentColor = CinematicAccent,
                ),
            ) {
                Text("来源")
            }
        }

        if (uiState.message != null || uiState.error != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (uiState.error == null) {
                    CinematicSurfaceRaised
                } else {
                    CinematicErrorSurface
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (uiState.error == null) {
                        CinematicAccent.copy(alpha = 0.34f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.64f)
                    },
                ),
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = uiState.error ?: uiState.message.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.error == null) {
                            CinematicText
                        } else {
                            CinematicErrorText
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = onDismissMessage,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (uiState.error == null) {
                                CinematicAccent
                            } else {
                                CinematicErrorText
                            },
                        ),
                    ) {
                        Text("知道了")
                    }
                }
            }
        }

        AnimatedContent(
            targetState = uiState,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "VideoLibraryContent",
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
                    EmptyVideoLibrary(
                        onOpenDirectories = onOpenDirectories,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(state.items, key = { it.item.id }) { item ->
                            VideoLibraryCard(
                                item = item,
                                onClick = { onOpenItem(item) },
                                onLongClick = { onSelectItem(item) },
                                thumbnailsEnabled = thumbnailsEnabled,
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
private fun EmptyVideoLibrary(
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
                            CinematicSurfaceRaised,
                            CinematicPosterBottom,
                        ),
                    ),
                    shape = CircleShape,
                )
                .border(
                    width = 1.dp,
                    color = CinematicAccent.copy(alpha = 0.58f),
                    shape = CircleShape,
                )
                .padding(20.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = CinematicAccent,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = "还没有视频",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = CinematicText,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "从来源页长按视频加入影视库",
            style = MaterialTheme.typography.bodyMedium,
            color = CinematicTextMuted,
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = CinematicAccent,
                    contentColor = CinematicAccentOn,
                ),
            ) {
                Text("来源")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoLibraryCard(
    item: VideoLibraryItemWithSources,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    thumbnailsEnabled: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(16.dp)
    val borderModifier = if (isSelected) {
        Modifier.border(
            width = 1.5.dp,
            color = CinematicAccent,
            shape = cardShape,
        )
    } else {
        Modifier.border(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.06f),
            shape = cardShape,
        )
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(borderModifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "影视库操作",
            ),
        shape = cardShape,
        color = if (isSelected) {
            CinematicAccent.copy(alpha = 0.14f)
        } else {
            CinematicSurface.copy(alpha = 0.92f)
        },
        shadowElevation = if (isSelected) 10.dp else 3.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(6.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 0.dp,
                shadowElevation = if (isSelected) 8.dp else 5.dp,
                color = CinematicPosterBottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    CinematicPosterTop,
                                    CinematicPosterBottom,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val thumbnailFile = item.item.thumbnailPath
                        ?.takeIf { thumbnailsEnabled }
                        ?.let(::File)
                        ?.takeIf { it.isFile }
                    if (thumbnailFile != null) {
                        AsyncImage(
                            model = thumbnailFile,
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
                                            Color.Black.copy(alpha = 0.62f),
                                            Color.Black.copy(alpha = 0.1f),
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.78f),
                                        ),
                                    ),
                                ),
                        )
                    } else {
                        FallbackVideoTitle(item.item.displayName)
                    }
                    Surface(
                        modifier = Modifier
                            .size(56.dp),
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.58f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                        shadowElevation = 6.dp,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(7.dp),
                        color = Color.Black.copy(alpha = 0.62f),
                        border = BorderStroke(1.dp, CinematicAccent.copy(alpha = 0.28f)),
                    ) {
                        Text(
                            text = videoSourceLabel(item.item.sourceType),
                            style = MaterialTheme.typography.labelSmall,
                            color = CinematicAccent,
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
                    color = CinematicText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = videoSourceMeta(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = CinematicTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FallbackVideoTitle(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        CinematicPosterTop,
                        CinematicSurfaceRaised,
                        CinematicPosterBottom,
                    ),
                ),
            )
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = CinematicText,
            fontWeight = FontWeight.Bold,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun videoLibraryCountLabel(count: Int): String {
    return if (count == 0) "还没有视频" else "$count 个视频"
}

internal fun videoSourceLabel(sourceType: VideoSourceType): String {
    return when (sourceType) {
        VideoSourceType.LOCAL -> "本地"
        VideoSourceType.WEBDAV -> "WebDAV"
    }
}

internal fun videoSourceMeta(item: VideoLibraryItemWithSources): String {
    return when (item.item.sourceType) {
        VideoSourceType.LOCAL -> item.localSource?.fileName ?: "本地视频"
        VideoSourceType.WEBDAV -> item.webDavSource?.remotePath?.let(::decodeWebDavPathForDisplay) ?: "WebDAV"
    }
}
