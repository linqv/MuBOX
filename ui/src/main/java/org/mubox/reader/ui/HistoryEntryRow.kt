package org.mubox.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.history.WatchMediaType
import org.mubox.reader.core.model.media.MediaKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 与文件浏览列表共用紧凑行组件，保证历史记录和文件条目的尺寸、选中态一致。
@Composable
fun HistoryEntryRow(
    entry: WatchHistoryEntry,
    onOpen: () -> Unit,
    selected: Boolean,
    selectionActive: Boolean,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MuBoxDenseMediaRow(
        title = entry.displayTitle,
        mediaKind = when (entry.mediaType) {
            WatchMediaType.COMIC -> MediaKind.Comic
            WatchMediaType.VIDEO -> MediaKind.Video
        },
        onClick = if (selectionActive) onToggleSelection else onOpen,
        modifier = modifier,
        subtitle = "${historyProgressLabel(entry)} · ${formatHistoryTime(entry.lastWatchedAt)}",
        selected = selected,
        onLongClick = onToggleSelection,
        onLongClickLabel = "选择历史记录",
    )
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
