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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.comicdav.ui.muBoxColorsFor
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import java.io.File

internal data class VideoLibraryScreenColors(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val posterTop: Color,
    val posterBottom: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val muted: Color,
    val errorSurface: Color,
    val errorText: Color,
    val border: Color,
    val thumbnailScrim: Color,
    val onThumbnailScrim: Color,
)

internal fun videoLibraryScreenColors(colorScheme: ColorScheme): VideoLibraryScreenColors {
    val tokens = muBoxColorsFor(colorScheme)
    return VideoLibraryScreenColors(
        backgroundTop = tokens.background,
        backgroundBottom = colorScheme.surfaceContainerLowest,
        surface = tokens.panel,
        surfaceRaised = tokens.panelHigh,
        posterTop = colorScheme.surfaceVariant,
        posterBottom = colorScheme.surfaceContainerLowest,
        accent = tokens.mediaAccent,
        onAccent = tokens.onMediaAccent,
        text = tokens.text,
        muted = tokens.muted,
        errorSurface = tokens.errorSurface,
        errorText = tokens.errorText,
        border = tokens.border,
        thumbnailScrim = colorScheme.scrim,
        onThumbnailScrim = colorScheme.inverseOnSurface,
    )
}

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
    val colors = videoLibraryScreenColors(MaterialTheme.colorScheme)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(colors.backgroundTop, colors.backgroundBottom),
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
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = videoLibraryCountLabel(uiState.items.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }
            OutlinedButton(
                onClick = onOpenDirectories,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surfaceRaised.copy(alpha = 0.72f),
                    contentColor = colors.accent,
                ),
            ) {
                Text("来源")
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
                        colors = colors,
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
                                colors = colors,
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
    colors: VideoLibraryScreenColors,
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
                            colors.surfaceRaised,
                            colors.posterBottom,
                        ),
                    ),
                    shape = CircleShape,
                )
                .border(
                    width = 1.dp,
                    color = colors.accent.copy(alpha = 0.58f),
                    shape = CircleShape,
                )
                .padding(20.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = "还没有视频",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.text,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "从来源页长按视频加入影视库",
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
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
    colors: VideoLibraryScreenColors,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(16.dp)
    val borderModifier = if (isSelected) {
        Modifier.border(
            width = 1.5.dp,
            color = colors.accent,
            shape = cardShape,
        )
    } else {
        Modifier.border(
            width = 1.dp,
            color = colors.border.copy(alpha = 0.45f),
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
            colors.accent.copy(alpha = 0.14f)
        } else {
            colors.surface.copy(alpha = 0.92f)
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
                    .aspectRatio(com.example.comicdav.ui.muBoxPosterAspectRatio(videoLibraryPosterKind())),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 0.dp,
                shadowElevation = if (isSelected) 8.dp else 5.dp,
                color = colors.posterBottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    colors.posterTop,
                                    colors.posterBottom,
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
                                            colors.thumbnailScrim.copy(alpha = 0.62f),
                                            colors.thumbnailScrim.copy(alpha = 0.1f),
                                            colors.thumbnailScrim.copy(alpha = 0f),
                                            colors.thumbnailScrim.copy(alpha = 0.78f),
                                        ),
                                    ),
                                ),
                        )
                    } else {
                        FallbackVideoTitle(item.item.displayName, colors = colors)
                    }
                    Surface(
                        modifier = Modifier
                            .size(56.dp),
                        shape = CircleShape,
                        color = colors.thumbnailScrim.copy(alpha = 0.58f),
                        border = BorderStroke(1.dp, colors.onThumbnailScrim.copy(alpha = 0.28f)),
                        shadowElevation = 6.dp,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = colors.onThumbnailScrim,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(7.dp),
                        color = colors.thumbnailScrim.copy(alpha = 0.62f),
                        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.28f)),
                    ) {
                        Text(
                            text = videoSourceLabel(item.item.sourceType),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent,
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
                    text = videoSourceMeta(item),
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
private fun FallbackVideoTitle(
    title: String,
    colors: VideoLibraryScreenColors,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        colors.posterTop,
                        colors.surfaceRaised,
                        colors.posterBottom,
                    ),
                ),
            )
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

internal fun videoLibraryCountLabel(count: Int): String {
    return if (count == 0) "还没有视频" else "$count 个视频"
}

internal fun videoLibraryPosterKind(): com.example.comicdav.ui.MuBoxPosterKind =
    com.example.comicdav.ui.MuBoxPosterKind.Video

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
