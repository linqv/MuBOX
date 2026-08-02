package org.mubox.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.history.WatchMediaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 观看历史行：设置页历史子页与首页"全部观看记录"共用。
@Composable
fun HistoryEntryRow(
    entry: WatchHistoryEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .muBoxGradientBorder(colors = colors, shape = shape)
            .clickable(onClick = onOpen),
        shape = shape,
        color = colors.boxedList,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.mediaType == WatchMediaType.COMIC) {
                    Icons.Filled.Book
                } else {
                    Icons.Filled.Movie
                },
                contentDescription = null,
                tint = colors.mediaAccent,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = entry.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${historyProgressLabel(entry)} · ${formatHistoryTime(entry.lastWatchedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { entry.progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.mediaAccent,
                    trackColor = colors.panelHigh,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除 ${entry.displayTitle} 的历史记录和关联缓存",
                    tint = colors.errorText,
                )
            }
        }
    }
}

fun historyProgressLabel(entry: WatchHistoryEntry): String =
    when (entry.mediaType) {
        WatchMediaType.COMIC -> "第 ${entry.progress.coerceAtLeast(1L)} / ${entry.total.coerceAtLeast(1L)} 页"
        WatchMediaType.VIDEO -> "${formatVideoHistoryDuration(entry.progress)} / ${formatVideoHistoryDuration(entry.total)}"
    }

private fun formatVideoHistoryDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.US, minutes, seconds)
    }
}

private fun formatHistoryTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
