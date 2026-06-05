package com.example.comicdav.feature.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.data.DownloadRecord
import com.example.comicdav.data.VideoDownloadRecord
import com.example.comicdav.data.formatCacheSize
import com.example.comicdav.feature.webdav.DownloadProgressUi
import com.example.comicdav.ui.MuBoxBoxedList
import com.example.comicdav.ui.MuBoxHeaderBar
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadsScreen(
    comicDownloads: List<DownloadRecord>,
    videoDownloads: List<VideoDownloadRecord>,
    selectedComicDownload: DownloadRecord?,
    selectedVideoDownload: VideoDownloadRecord?,
    activeDownload: DownloadProgressUi?,
    onOpenComicDownload: (DownloadRecord) -> Unit,
    onSelectComicDownload: (DownloadRecord) -> Unit,
    onSelectVideoDownload: (VideoDownloadRecord) -> Unit,
    onPlayVideoDownload: (VideoDownloadRecord) -> Unit,
    onCancelActiveDownload: () -> Unit,
    actionMessage: String?,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    val isEmpty = comicDownloads.isEmpty() && videoDownloads.isEmpty() && activeDownload == null

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        MuBoxHeaderBar(title = "下载")

        if (!actionMessage.isNullOrBlank()) {
            Text(
                text = actionMessage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }

        if (isEmpty) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "从来源下载漫画或视频后会显示在这里\n长按条目可进行管理",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (activeDownload != null) {
                item(key = "active-download") {
                    ActiveDownloadCard(
                        progress = activeDownload,
                        onCancel = onCancelActiveDownload,
                    )
                }
            }

            if (comicDownloads.isNotEmpty()) {
                item(key = "comic-header") {
                    SectionTitle(title = "漫画下载 (${comicDownloads.size})")
                }
                items(
                    comicDownloads,
                    key = { "comic\u001F${it.accountId.orEmpty()}\u001F${it.remotePath}\u001F${it.fileName}" },
                ) { record ->
                    ComicDownloadRow(
                        record = record,
                        isSelected = selectedComicDownload.sameComicDownload(record),
                        onOpen = { onOpenComicDownload(record) },
                        onSelect = { onSelectComicDownload(record) },
                    )
                }
            }

            if (videoDownloads.isNotEmpty()) {
                item(key = "video-header") {
                    SectionTitle(title = "视频下载 (${videoDownloads.size})")
                }
                items(
                    videoDownloads,
                    key = { "video\u001F${it.accountId}\u001F${it.remotePath}" },
                ) { record ->
                    VideoDownloadRow(
                        record = record,
                        isSelected = selectedVideoDownload.sameVideoDownload(record),
                        onPlay = { onPlayVideoDownload(record) },
                        onSelect = { onSelectVideoDownload(record) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    val colors = rememberMuBoxColors()
    Text(
        text = title,
        modifier = Modifier.padding(start = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = colors.muted,
    )
}

@Composable
private fun ActiveDownloadCard(
    progress: DownloadProgressUi,
    onCancel: () -> Unit,
) {
    val colors = rememberMuBoxColors()
    val total = progress.totalBytes
    val fraction = if (total > 0L) {
        (progress.downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    MuBoxBoxedList(title = "正在下载") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (fraction != null) {
                        "下载中… ${(fraction * 100).toInt()}%"
                    } else {
                        "下载中…"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                )
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "取消下载", tint = colors.muted)
                }
            }
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = if (total > 0L) {
                    "${formatCacheSize(progress.downloadedBytes)} / ${formatCacheSize(total)}"
                } else {
                    formatCacheSize(progress.downloadedBytes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
    }
}

internal enum class DownloadEntryKind {
    COMIC,
    VIDEO,
}

internal enum class DownloadEntryPrimaryAction {
    OPEN_COMIC,
    PLAY_VIDEO,
}

internal fun primaryActionForDownloadEntry(kind: DownloadEntryKind): DownloadEntryPrimaryAction =
    when (kind) {
        DownloadEntryKind.COMIC -> DownloadEntryPrimaryAction.OPEN_COMIC
        DownloadEntryKind.VIDEO -> DownloadEntryPrimaryAction.PLAY_VIDEO
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicDownloadRow(
    record: DownloadRecord,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onClick: () -> Unit = when (primaryActionForDownloadEntry(DownloadEntryKind.COMIC)) {
        DownloadEntryPrimaryAction.OPEN_COMIC -> onOpen
        DownloadEntryPrimaryAction.PLAY_VIDEO -> ({})
    }
    DownloadEntryRow(
        title = record.fileName,
        subtitle = "${formatCacheSize(record.sizeBytes)} · ${formatDownloadTime(record.downloadedAtMillis)}\n" +
            decodeWebDavPathForDisplay(record.remotePath),
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onSelect,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoDownloadRow(
    record: VideoDownloadRecord,
    isSelected: Boolean,
    onPlay: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onClick: () -> Unit = when (primaryActionForDownloadEntry(DownloadEntryKind.VIDEO)) {
        DownloadEntryPrimaryAction.OPEN_COMIC -> ({})
        DownloadEntryPrimaryAction.PLAY_VIDEO -> onPlay
    }
    DownloadEntryRow(
        title = record.fileName,
        subtitle = "${formatCacheSize(record.sizeBytes)} · ${formatDownloadTime(record.downloadedAtMillis)}\n" +
            decodeWebDavPathForDisplay(record.remotePath),
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onSelect,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadEntryRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "下载管理",
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatDownloadTime(downloadedAtMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(downloadedAtMillis))

private fun DownloadRecord?.sameComicDownload(other: DownloadRecord): Boolean =
    this != null && fileName == other.fileName && remotePath == other.remotePath

private fun VideoDownloadRecord?.sameVideoDownload(other: VideoDownloadRecord): Boolean =
    this != null && accountId == other.accountId && remotePath == other.remotePath
