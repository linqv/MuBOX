package com.example.comicdav.feature.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.comicdav.core.model.format.formatCacheSize
import com.example.comicdav.ui.MuBoxMediaTypeIcon
import com.example.comicdav.ui.muBoxGradientBorder
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.ui.decodeWebDavPathForDisplay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DownloadItemCard(
    title: String,
    sizeBytes: Long,
    downloadedAtMillis: Long,
    remotePath: String,
    mediaKind: MediaKind,
    coverUri: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(12.dp)
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val containerColor = if (isSelected) colors.rowSelected else colors.boxedList

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .muBoxGradientBorder(
                colors = colors,
                shape = shape,
                highlighted = isSelected,
                width = borderWidth,
            )
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "下载操作",
            ),
        shape = shape,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeadingThumbnail(
                mediaKind = mediaKind,
                coverUri = coverUri,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.text,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatCacheSize(sizeBytes)} · ${formatDownloadTime(downloadedAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = decodeWebDavPathForDisplay(remotePath),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LeadingThumbnail(
    mediaKind: MediaKind,
    coverUri: String?,
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(width = 80.dp, height = 108.dp)
            .muBoxGradientBorder(colors = colors, shape = shape)
            .clip(shape)
            .background(colors.panel),
        contentAlignment = Alignment.Center,
    ) {
        if (!coverUri.isNullOrBlank()) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MuBoxMediaTypeIcon(mediaKind = mediaKind)
        }
    }
}

private fun formatDownloadTime(downloadedAtMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(downloadedAtMillis))
