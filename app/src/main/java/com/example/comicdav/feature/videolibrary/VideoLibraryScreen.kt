package com.example.comicdav.feature.videolibrary

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoSourceType
import com.example.comicdav.ui.MuBoxHeaderBar
import com.example.comicdav.ui.MU_BOX_MEDIA_GRID_COLUMN_COUNT
import com.example.comicdav.ui.MuBoxMetrics
import com.example.comicdav.ui.muBoxAppBackground
import com.example.comicdav.ui.muBoxGradientBorder
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import java.io.File

@Composable
fun VideoLibraryScreen(
    uiState: VideoLibraryUiState,
    onOpenItem: (VideoLibraryItemWithSources) -> Unit,
    onSelectItem: (VideoLibraryItemWithSources) -> Unit,
    onOpenDirectories: () -> Unit,
    onDismissMessage: () -> Unit,
    thumbnailsEnabled: Boolean = true,
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
            title = "影视库",
            navigationIcon = navigationIcon,
            actions = {
                TextButton(onClick = onOpenDirectories) { Text("来源") }
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
                text = videoLibraryCountLabel(uiState.items.size),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

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
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(MU_BOX_MEDIA_GRID_COLUMN_COUNT),
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
}

@Composable
private fun EmptyVideoLibrary(
    onOpenDirectories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = colors.mediaAccent,
            modifier = Modifier
                .padding(bottom = 22.dp)
                .size(64.dp),
        )
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
                    containerColor = colors.mediaAccent,
                    contentColor = colors.onMediaAccent,
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
    val colors = rememberMuBoxColors()
    val cardShape = RoundedCornerShape(MuBoxMetrics.PanelCornerDp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "影视库操作",
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(com.example.comicdav.ui.muBoxPosterAspectRatio(videoLibraryPosterKind()))
                .muBoxGradientBorder(
                    colors = colors,
                    shape = cardShape,
                    highlighted = isSelected,
                    width = if (isSelected) 1.5.dp else 1.dp,
                ),
            shape = cardShape,
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
                val thumbnailFile = item.item.thumbnailPath
                    ?.takeIf { thumbnailsEnabled }
                    ?.let(::File)
                    ?.takeIf { it.isFile }
                if (thumbnailFile != null) {
                    AsyncImage(
                        model = thumbnailFile,
                        contentDescription = item.item.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
        Text(
            text = item.item.displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
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
