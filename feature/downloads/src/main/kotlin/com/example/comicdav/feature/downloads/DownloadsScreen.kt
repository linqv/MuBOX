package com.example.comicdav.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.comicdav.core.model.transfer.TransferProgress
import com.example.comicdav.core.model.transfer.DownloadRecord
import com.example.comicdav.core.model.transfer.VideoDownloadRecord
import com.example.comicdav.core.model.format.formatCacheSize
import com.example.comicdav.ui.MuBoxInlineMessage
import com.example.comicdav.ui.MuBoxMetrics
import com.example.comicdav.ui.muBoxAppBackground
import com.example.comicdav.ui.muBoxGradientBorder
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.core.model.media.MediaKind

@Composable
fun DownloadsScreen(
    comicDownloads: List<DownloadRecord>,
    videoDownloads: List<VideoDownloadRecord>,
    activeDownload: TransferProgress?,
    onOpenComicDownload: (DownloadRecord) -> Unit,
    onPlayVideoDownload: (VideoDownloadRecord) -> Unit,
    onCancelActiveDownload: () -> Unit,
    onRemoveComicRecord: (DownloadRecord) -> Unit,
    onRemoveVideoRecord: (VideoDownloadRecord) -> Unit,
    onDeleteComicFile: (DownloadRecord) -> Unit,
    onDeleteVideoFile: (VideoDownloadRecord) -> Unit,
    onShowDetails: () -> Unit,
    onOpenSources: () -> Unit,
    actionMessage: String?,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    var sheetRecord by remember { mutableStateOf<SheetRecord?>(null) }
    var dismissedMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .muBoxAppBackground(colors)
            .statusBarsPadding(),
    ) {
        if (!actionMessage.isNullOrBlank() && actionMessage != dismissedMessage) {
            MuBoxInlineMessage(
                text = actionMessage,
                isError = false,
                onDismiss = { dismissedMessage = actionMessage },
                modifier = Modifier.padding(
                    horizontal = MuBoxMetrics.PageHorizontalPaddingDp,
                    vertical = 4.dp,
                ),
            )
        }

        val totalCount = comicDownloads.size + videoDownloads.size
        if (totalCount > 0) {
            Text(
                text = subtitleFor(comicDownloads, videoDownloads),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MuBoxMetrics.PageHorizontalPaddingDp,
                        vertical = 4.dp,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }

        val isEmpty = comicDownloads.isEmpty() && videoDownloads.isEmpty() && activeDownload == null
        if (isEmpty) {
            DownloadsEmptyState(onOpenSources = onOpenSources)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = MuBoxMetrics.PageHorizontalPaddingDp,
                    end = MuBoxMetrics.PageHorizontalPaddingDp,
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
                        DownloadItemCard(
                            title = record.fileName,
                            sizeBytes = record.sizeBytes,
                            downloadedAtMillis = record.downloadedAtMillis,
                            remotePath = record.remotePath,
                            mediaKind = MediaKind.Comic,
                            coverUri = null,
                            isSelected = false,
                            onClick = { onOpenComicDownload(record) },
                            onLongClick = { sheetRecord = SheetRecord.Comic(record) },
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
                        DownloadItemCard(
                            title = record.fileName,
                            sizeBytes = record.sizeBytes,
                            downloadedAtMillis = record.downloadedAtMillis,
                            remotePath = record.remotePath,
                            mediaKind = MediaKind.Video,
                            coverUri = null,
                            isSelected = false,
                            onClick = { onPlayVideoDownload(record) },
                            onLongClick = { sheetRecord = SheetRecord.Video(record) },
                        )
                    }
                }
            }
        }
    }

    sheetRecord?.let { record ->
        DownloadActionsSheet(
            record = record,
            onDismiss = { sheetRecord = null },
            onOpen = {
                when (record) {
                    is SheetRecord.Comic -> onOpenComicDownload(record.record)
                    is SheetRecord.Video -> onPlayVideoDownload(record.record)
                }
            },
            onShowDetails = onShowDetails,
            onRemoveRecord = {
                when (record) {
                    is SheetRecord.Comic -> onRemoveComicRecord(record.record)
                    is SheetRecord.Video -> onRemoveVideoRecord(record.record)
                }
            },
            onDeleteFile = {
                when (record) {
                    is SheetRecord.Comic -> onDeleteComicFile(record.record)
                    is SheetRecord.Video -> onDeleteVideoFile(record.record)
                }
            },
        )
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
    progress: TransferProgress,
    onCancel: () -> Unit,
) {
    val colors = rememberMuBoxColors()
    val total = progress.totalBytes
    val fraction = if (total > 0L) {
        (progress.downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = colors.raisedSurface,
        modifier = Modifier
            .fillMaxWidth()
            .muBoxGradientBorder(colors = colors, shape = shape, highlighted = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (fraction != null) "下载中… ${(fraction * 100).toInt()}%" else "下载中…",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "取消下载",
                        tint = colors.muted,
                    )
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

private fun subtitleFor(
    comicDownloads: List<DownloadRecord>,
    videoDownloads: List<VideoDownloadRecord>,
): String {
    val total = comicDownloads.size + videoDownloads.size
    val totalSize = comicDownloads.sumOf { it.sizeBytes } + videoDownloads.sumOf { it.sizeBytes }
    return "共 $total 项 · 占用 ${formatCacheSize(totalSize)}"
}
