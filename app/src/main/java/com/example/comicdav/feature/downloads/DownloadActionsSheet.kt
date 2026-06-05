package com.example.comicdav.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.data.DownloadRecord
import com.example.comicdav.data.VideoDownloadRecord
import com.example.comicdav.data.formatCacheSize
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.video.MediaKind
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal sealed interface SheetRecord {
    data class Comic(val record: DownloadRecord) : SheetRecord
    data class Video(val record: VideoDownloadRecord) : SheetRecord
}

internal fun sheetMediaKind(record: SheetRecord): MediaKind = when (record) {
    is SheetRecord.Comic -> MediaKind.Comic
    is SheetRecord.Video -> MediaKind.Video
}

private fun sheetFileName(record: SheetRecord): String = when (record) {
    is SheetRecord.Comic -> record.record.fileName
    is SheetRecord.Video -> record.record.fileName
}

private fun sheetSizeBytes(record: SheetRecord): Long = when (record) {
    is SheetRecord.Comic -> record.record.sizeBytes
    is SheetRecord.Video -> record.record.sizeBytes
}

private fun sheetDownloadedAtMillis(record: SheetRecord): Long = when (record) {
    is SheetRecord.Comic -> record.record.downloadedAtMillis
    is SheetRecord.Video -> record.record.downloadedAtMillis
}

private fun sheetRemotePath(record: SheetRecord): String = when (record) {
    is SheetRecord.Comic -> record.record.remotePath
    is SheetRecord.Video -> record.record.remotePath
}

private fun sheetAccountId(record: SheetRecord): String? = when (record) {
    is SheetRecord.Comic -> record.record.accountId
    is SheetRecord.Video -> record.record.accountId
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadActionsSheet(
    record: SheetRecord,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShowDetails: () -> Unit,
    onRemoveRecord: () -> Unit,
    onDeleteFile: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = sheetFileName(record),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${mediaKindLabel(sheetMediaKind(record))} · ${formatCacheSize(sheetSizeBytes(record))}",
                style = MaterialTheme.typography.bodyMedium,
                color = rememberMuBoxColors().muted,
            )
            HorizontalDivider(color = rememberMuBoxColors().separator)
            MetadataRow(
                icon = Icons.Filled.Storage,
                label = "大小",
                value = formatCacheSize(sheetSizeBytes(record)),
            )
            MetadataRow(
                icon = Icons.Filled.Schedule,
                label = "下载时间",
                value = formatTime(sheetDownloadedAtMillis(record)),
            )
            sheetAccountId(record)?.let { account ->
                MetadataRow(
                    icon = Icons.Filled.AccountCircle,
                    label = "账号",
                    value = account,
                )
            }
            MetadataRow(
                icon = Icons.Filled.Link,
                label = "路径",
                value = decodeWebDavPathForDisplay(sheetRemotePath(record)),
                isSmall = true,
            )
            Spacer(modifier = Modifier.height(4.dp))
            ActionsBlock(
                onOpen = {
                    onOpen()
                    onDismiss()
                },
                onShowDetails = {
                    onShowDetails()
                    onDismiss()
                },
                onRemoveRecord = {
                    onRemoveRecord()
                    onDismiss()
                },
                onDeleteFile = { showDeleteConfirm = true },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showDeleteConfirm) {
        DeleteFileConfirmDialog(
            onConfirm = {
                showDeleteConfirm = false
                onDeleteFile()
                onDismiss()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun MetadataRow(
    icon: ImageVector,
    label: String,
    value: String,
    isSmall: Boolean = false,
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = if (isSmall) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = colors.text,
            maxLines = if (isSmall) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionsBlock(
    onOpen: () -> Unit,
    onShowDetails: () -> Unit,
    onRemoveRecord: () -> Unit,
    onDeleteFile: () -> Unit,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Button(
            onClick = onOpen,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.mediaAccent,
                contentColor = colors.onMediaAccent,
            ),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text("打开 / 播放")
        }
        TextButton(
            onClick = onShowDetails,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
        ) {
            Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = colors.text)
            }
            Spacer(modifier = Modifier.size(12.dp))
            Text("查看详情", color = colors.text)
        }
        TextButton(
            onClick = onRemoveRecord,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
        ) {
            Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.RemoveCircleOutline, contentDescription = null, tint = colors.text)
            }
            Spacer(modifier = Modifier.size(12.dp))
            Text("从列表移除", color = colors.text)
        }
        TextButton(
            onClick = onDeleteFile,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
        ) {
            Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = colors.errorText)
            }
            Spacer(modifier = Modifier.size(12.dp))
            Text("删除本地文件", color = colors.errorText)
        }
    }
}

@Composable
private fun DeleteFileConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除本地文件？") },
        text = { Text("将永久删除该文件，无法恢复。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = rememberMuBoxColors().errorText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun mediaKindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.Comic -> "漫画"
    MediaKind.Video -> "视频"
    else -> "文件"
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
