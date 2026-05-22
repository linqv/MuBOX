package com.example.comicdav.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.comicdav.data.AppColorPalette
import com.example.comicdav.data.AppSettings
import com.example.comicdav.data.ComicCacheAnalysis
import com.example.comicdav.data.ComicCacheCategory
import com.example.comicdav.data.DownloadRecord
import com.example.comicdav.data.ReadingDirection
import com.example.comicdav.data.ReaderLoggingMode
import com.example.comicdav.data.formatCacheSize
import com.example.comicdav.video.player.GpuApiMode
import com.example.comicdav.video.player.MpvProfileMode
import com.example.comicdav.video.player.VideoDecoderMode
import com.example.comicdav.video.player.VideoOutputMode
import com.example.comicdav.video.player.VideoPlayerOrientationMode
import com.example.comicdav.video.player.gpuApiModeLabel
import com.example.comicdav.video.player.mpvProfileModeLabel
import com.example.comicdav.video.player.playerControlAutoHideLabel
import com.example.comicdav.video.player.playerControlAutoHideOptionsMillis
import com.example.comicdav.video.player.videoDecoderModeLabel
import com.example.comicdav.video.player.videoOutputModeLabel
import com.example.comicdav.video.player.videoPlayerOrientationModeLabel
import com.example.comicdav.video.proxy.VideoForwardPrefetchMode
import com.example.comicdav.video.proxy.VideoProxyDiagnosticsMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MinAutoPageSpeedSeconds = 3
private const val MaxAutoPageSpeedSeconds = 60
private val SupportedDiskCacheLimitMb = listOf(0, 500, 1024, 2048, 3072, 4096, 5120)
private val SupportedWebDavPrefetchPageCounts = listOf(2, 4, 6, 8, 10, 12)

internal data class SettingsGroupLayout(
    val title: String,
    val rows: List<String>,
)

internal fun settingsGroupLayout(): List<SettingsGroupLayout> =
    listOf(
        SettingsGroupLayout(
            title = "显示",
            rows = listOf("配色方案"),
        ),
        SettingsGroupLayout(
            title = "漫画",
            rows = listOf("阅读方向", "音量键翻页", "屏幕旋转锁定", "WebDAV 预取页数", "诊断日志", "书架封面"),
        ),
        SettingsGroupLayout(
            title = "视频",
            rows = listOf(
                "恢复播放位置",
                "WebDAV 视频 seek 优化",
                "向前预读",
                "视频代理诊断日志",
                "视频输出 (VO)",
                "GPU API",
                "默认解码器",
                "MPV Profile",
                "控制自动隐藏",
                "播放器方向",
                "提取加入影视库的视频缩略图作为封面",
            ),
        ),
        SettingsGroupLayout(
            title = "自动翻页",
            rows = listOf("启用自动翻页", "翻页速度"),
        ),
        SettingsGroupLayout(
            title = "下载记录",
            rows = listOf("下载记录"),
        ),
        SettingsGroupLayout(
            title = "缓存",
            rows = listOf("缓存占用", "远程整本缓存", "WebDAV 索引缓存", "页面图片缓存", "书架封面缓存", "磁盘缓存上限"),
        ),
    )

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onReadingDirectionChange: (ReadingDirection) -> Unit,
    onReaderLoggingModeChange: (ReaderLoggingMode) -> Unit,
    onColorPaletteChange: (AppColorPalette) -> Unit,
    onAutoPageEnabledChange: (Boolean) -> Unit,
    onAutoPageSpeedChange: (Int) -> Unit,
    onScreenRotationLockChange: (Boolean) -> Unit,
    onVolumeKeysTurnPagesChange: (Boolean) -> Unit,
    onDiskCacheLimitChange: (Int) -> Unit,
    onWebDavPrefetchPageCountChange: (Int) -> Unit,
    onLibraryCoversEnabledChange: (Boolean) -> Unit,
    onVideoResumeEnabledChange: (Boolean) -> Unit,
    onVideoSeekOptimizationEnabledChange: (Boolean) -> Unit = {},
    onVideoForwardPrefetchModeChange: (VideoForwardPrefetchMode) -> Unit = {},
    onVideoProxyDiagnosticsModeChange: (VideoProxyDiagnosticsMode) -> Unit = {},
    onVideoOutputModeChange: (VideoOutputMode) -> Unit = {},
    onGpuApiModeChange: (GpuApiMode) -> Unit = {},
    onVideoDecoderModeChange: (VideoDecoderMode) -> Unit = {},
    onMpvProfileModeChange: (MpvProfileMode) -> Unit = {},
    onVideoControlsAutoHideMillisChange: (Int) -> Unit = {},
    onVideoPlayerOrientationModeChange: (VideoPlayerOrientationMode) -> Unit = {},
    onVideoLibraryThumbnailsEnabledChange: (Boolean) -> Unit = {},
    downloadRecords: List<DownloadRecord> = emptyList(),
    selectedDownloadRecord: DownloadRecord? = null,
    onSelectDownloadRecord: (DownloadRecord) -> Unit = {},
    onClearSelectedDownloadRecord: () -> Unit = {},
    cacheAnalysis: ComicCacheAnalysis = ComicCacheAnalysis(),
    cacheActionMessage: String? = null,
    onClearCacheCategory: (ComicCacheCategory) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var isDownloadRecordsOpen by remember { mutableStateOf(false) }

    if (isDownloadRecordsOpen) {
        DownloadRecordsScreen(
            records = downloadRecords,
            selectedRecord = selectedDownloadRecord,
            onSelectRecord = onSelectDownloadRecord,
            onBack = {
                onClearSelectedDownloadRecord()
                isDownloadRecordsOpen = false
            },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "阅读体验和设备行为",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsGroup(title = "显示") {
            DropdownRow(
                title = "配色方案",
                selected = settings.colorPalette,
                options = AppColorPalette.entries,
                label = AppColorPalette::label,
                onSelected = onColorPaletteChange,
            )
        }

        SettingsGroup(title = "漫画") {
            ChoiceRow(
                title = "阅读方向",
                options = ReadingDirection.entries,
                selected = settings.readingDirection,
                label = ReadingDirection::label,
                onSelected = onReadingDirectionChange,
            )
            SwitchRow(
                title = "音量键翻页",
                subtitle = "使用音量键向前或向后翻页",
                checked = settings.volumeKeysTurnPagesEnabled,
                onCheckedChange = onVolumeKeysTurnPagesChange,
            )
            SwitchRow(
                title = "屏幕旋转锁定",
                subtitle = "阅读时锁定当前屏幕方向",
                checked = settings.screenRotationLockEnabled,
                onCheckedChange = onScreenRotationLockChange,
            )
            DropdownRow(
                title = "WebDAV 预取页数",
                selected = settings.webDavPrefetchPageCount,
                options = SupportedWebDavPrefetchPageCounts,
                label = ::webDavPrefetchPageCountLabel,
                onSelected = onWebDavPrefetchPageCountChange,
            )
            ChoiceRow(
                title = "诊断日志",
                options = ReaderLoggingMode.entries,
                selected = settings.readerLoggingMode,
                label = ReaderLoggingMode::label,
                onSelected = onReaderLoggingModeChange,
            )
            SwitchRow(
                title = "书架封面",
                subtitle = "从 WebDAV 漫画提取首图并显示在书架",
                checked = settings.libraryCoversEnabled,
                onCheckedChange = onLibraryCoversEnabledChange,
            )
        }

        SettingsGroup(title = "视频") {
            SwitchRow(
                title = "恢复播放位置",
                subtitle = "再次打开同一视频时从上次退出位置继续",
                checked = settings.videoResumeEnabled,
                onCheckedChange = onVideoResumeEnabledChange,
            )
            SwitchRow(
                title = "WebDAV 视频 seek 优化",
                subtitle = "缓存小段视频并合并重复 seek 请求",
                checked = settings.videoSeekOptimizationEnabled,
                onCheckedChange = onVideoSeekOptimizationEnabledChange,
            )
            DropdownRow(
                title = "向前预读",
                selected = settings.videoForwardPrefetchMode,
                options = VideoForwardPrefetchMode.entries,
                label = VideoForwardPrefetchMode::label,
                onSelected = onVideoForwardPrefetchModeChange,
            )
            DropdownRow(
                title = "视频代理诊断日志",
                selected = settings.videoProxyDiagnosticsMode,
                options = VideoProxyDiagnosticsMode.entries,
                label = VideoProxyDiagnosticsMode::label,
                onSelected = onVideoProxyDiagnosticsModeChange,
            )
            DropdownRow(
                title = "视频输出 (VO)",
                selected = settings.videoOutputMode,
                options = VideoOutputMode.entries,
                label = ::videoOutputModeLabel,
                onSelected = onVideoOutputModeChange,
            )
            DropdownRow(
                title = "GPU API",
                selected = settings.gpuApiMode,
                options = GpuApiMode.entries,
                label = ::gpuApiModeLabel,
                onSelected = onGpuApiModeChange,
            )
            DropdownRow(
                title = "默认解码器",
                selected = settings.videoDecoderMode,
                options = VideoDecoderMode.entries,
                label = ::videoDecoderModeLabel,
                onSelected = onVideoDecoderModeChange,
            )
            DropdownRow(
                title = "MPV Profile",
                selected = settings.mpvProfileMode,
                options = MpvProfileMode.entries,
                label = ::mpvProfileModeLabel,
                onSelected = onMpvProfileModeChange,
            )
            DropdownRow(
                title = "控制自动隐藏",
                selected = settings.videoControlsAutoHideMillis,
                options = playerControlAutoHideOptionsMillis(),
                label = ::playerControlAutoHideLabel,
                onSelected = onVideoControlsAutoHideMillisChange,
            )
            DropdownRow(
                title = "播放器方向",
                selected = settings.videoPlayerOrientationMode,
                options = VideoPlayerOrientationMode.entries,
                label = ::videoPlayerOrientationModeLabel,
                onSelected = onVideoPlayerOrientationModeChange,
            )
            SwitchRow(
                title = "提取加入影视库的视频缩略图作为封面",
                subtitle = "收藏视频时自动提取一帧作为影视库封面",
                checked = settings.videoLibraryThumbnailsEnabled,
                onCheckedChange = onVideoLibraryThumbnailsEnabledChange,
            )
        }

        SettingsGroup(title = "自动翻页") {
            SwitchRow(
                title = "启用自动翻页",
                subtitle = "按固定间隔前进到下一页",
                checked = settings.autoPageEnabled,
                onCheckedChange = onAutoPageEnabledChange,
            )
            AutoPageSpeedRow(
                speedMillis = settings.autoPageSpeedMillis,
                onSpeedChange = onAutoPageSpeedChange,
            )
        }

        SettingsGroup(title = "下载记录") {
            if (downloadRecords.isEmpty()) {
                StaticInfoRow(
                    title = "暂无下载记录",
                    subtitle = "从 WebDAV 下载到本地后会显示在这里",
                )
            } else {
                ClickableInfoRow(
                    title = "下载记录",
                    subtitle = "${downloadRecords.size} 本漫画，点开查看完整记录",
                    onClick = { isDownloadRecordsOpen = true },
                )
            }
        }

        SettingsGroup(title = "缓存") {
            StaticInfoRow(
                title = "缓存占用",
                subtitle = formatCacheSize(cacheAnalysis.totalBytes),
            )
            CacheActionRow(
                title = "远程整本缓存",
                subtitle = formatCacheSize(cacheAnalysis.remoteDownloadsBytes),
                enabled = cacheAnalysis.remoteDownloadsBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.REMOTE_DOWNLOADS) },
            )
            CacheActionRow(
                title = "WebDAV 索引缓存",
                subtitle = formatCacheSize(cacheAnalysis.remoteIndexBytes),
                enabled = cacheAnalysis.remoteIndexBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.REMOTE_INDEX) },
            )
            CacheActionRow(
                title = "页面图片缓存",
                subtitle = formatCacheSize(cacheAnalysis.readerPagesBytes),
                enabled = cacheAnalysis.readerPagesBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.READER_PAGES) },
            )
            CacheActionRow(
                title = "书架封面缓存",
                subtitle = formatCacheSize(cacheAnalysis.libraryCoversBytes),
                enabled = cacheAnalysis.libraryCoversBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.LIBRARY_COVERS) },
            )
            DiskCacheLimitRow(
                limitMb = settings.diskCacheLimitMb,
                onLimitChange = onDiskCacheLimitChange,
            )
            Text(
                text = cacheActionMessage ?: "清理缓存不会删除书架记录和设置",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DownloadRecordsScreen(
    records: List<DownloadRecord>,
    selectedRecord: DownloadRecord?,
    onSelectRecord: (DownloadRecord) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "下载记录",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (records.isEmpty()) "暂无下载记录" else "${records.size} 本漫画",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onBack) {
                Text("返回")
            }
        }

        if (records.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "从 WebDAV 下载到本地后会显示在这里",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(records, key = { "${it.accountId.orEmpty()}\u001F${it.remotePath}\u001F${it.fileName}" }) { record ->
                    DownloadRecordRow(
                        record = record,
                        isSelected = selectedRecord.sameDownloadRecord(record),
                        onSelect = { onSelectRecord(record) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRecordRow(
    record: DownloadRecord,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onSelect,
                onLongClickLabel = "下载记录操作",
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        StaticInfoRow(
            title = record.fileName,
            subtitle = "${formatCacheSize(record.sizeBytes)} · ${formatDownloadTime(record.downloadedAtMillis)}\n${record.remotePath}",
        )
    }
}

internal fun coerceAutoPageSpeed(speedSeconds: Int): Int =
    speedSeconds.coerceIn(MinAutoPageSpeedSeconds, MaxAutoPageSpeedSeconds)

internal fun autoPageIntervalMillisForSpeed(speedSeconds: Int): Long =
    coerceAutoPageSpeed(speedSeconds) * 1_000L

internal fun coerceAutoPageSpeedMillis(speedMillis: Int): Int =
    autoPageIntervalMillisForSpeed(speedMillis / 1_000).toInt()

internal fun coerceDiskCacheLimitMb(limitMb: Int): Int =
    SupportedDiskCacheLimitMb.minBy { abs(it - limitMb) }

internal fun coerceWebDavPrefetchPageCount(pageCount: Int): Int =
    SupportedWebDavPrefetchPageCounts.minBy { abs(it - pageCount) }

internal fun pageCacheLimitBytesForMb(limitMb: Int): Long =
    coerceDiskCacheLimitMb(limitMb) * 1024L * 1024L

internal fun diskCacheLimitLabel(limitMb: Int): String =
    when (val coercedLimit = coerceDiskCacheLimitMb(limitMb)) {
        0 -> "0 MB"
        500 -> "500 MB"
        else -> "${coercedLimit / 1024} GB"
    }

internal fun webDavPrefetchPageCountLabel(pageCount: Int): String =
    "${coerceWebDavPrefetchPageCount(pageCount)} 页"

internal fun ReadingDirection.label(): String =
    when (this) {
        ReadingDirection.LEFT_TO_RIGHT -> "从左到右"
        ReadingDirection.RIGHT_TO_LEFT -> "从右到左"
        ReadingDirection.VERTICAL -> "纵向翻页"
        ReadingDirection.VERTICAL_CONTINUOUS -> "纵向滚动（无间隙）"
    }

private fun AppColorPalette.label(): String =
    when (this) {
        AppColorPalette.DEFAULT -> "松石浅色"
        AppColorPalette.SEPIA -> "纸张护眼"
        AppColorPalette.NIGHT -> "夜间深色"
        AppColorPalette.HIGH_CONTRAST -> "高对比"
    }

private fun ReaderLoggingMode.label(): String =
    when (this) {
        ReaderLoggingMode.OFF -> "关闭"
        ReaderLoggingMode.SUMMARY -> "摘要"
        ReaderLoggingMode.DETAIL -> "详细"
    }

private fun VideoForwardPrefetchMode.label(): String =
    when (this) {
        VideoForwardPrefetchMode.OFF -> "关闭"
        VideoForwardPrefetchMode.STANDARD -> "标准"
        VideoForwardPrefetchMode.AGGRESSIVE -> "积极"
    }

private fun VideoProxyDiagnosticsMode.label(): String =
    when (this) {
        VideoProxyDiagnosticsMode.OFF -> "关闭"
        VideoProxyDiagnosticsMode.SUMMARY -> "摘要"
        VideoProxyDiagnosticsMode.DETAIL -> "详细"
    }

private fun formatDownloadTime(downloadedAtMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(downloadedAtMillis))

private fun DownloadRecord?.sameDownloadRecord(other: DownloadRecord): Boolean =
    this != null && fileName == other.fileName && remotePath == other.remotePath

@Composable
private fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ClickableInfoRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StaticInfoRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun StaticInfoRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CacheActionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(
            onClick = onClear,
            enabled = enabled,
        ) {
            Text("清理")
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = option == selected,
                        onClick = { onSelected(option) },
                    )
                    Text(
                        text = label(option),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> DropdownRow(
    title: String,
    selected: T,
    options: List<T>,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(label(selected))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(label(option)) },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoPageSpeedRow(
    speedMillis: Int,
    onSpeedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coercedSpeed = coerceAutoPageSpeed(speedMillis / 1_000)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "翻页速度",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "每 $coercedSpeed 秒",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = coercedSpeed.toFloat(),
            onValueChange = { value ->
                onSpeedChange(autoPageIntervalMillisForSpeed(value.roundToInt()).toInt())
            },
            valueRange = MinAutoPageSpeedSeconds.toFloat()..MaxAutoPageSpeedSeconds.toFloat(),
            steps = MaxAutoPageSpeedSeconds - MinAutoPageSpeedSeconds - 1,
        )
    }
}

@Composable
private fun DiskCacheLimitRow(
    limitMb: Int,
    onLimitChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coercedLimit = coerceDiskCacheLimitMb(limitMb)
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "磁盘缓存上限",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "仅限制页面图片，不含 WebDAV 整本下载和索引",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(diskCacheLimitLabel(coercedLimit))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    SupportedDiskCacheLimitMb.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(diskCacheLimitLabel(option)) },
                            onClick = {
                                expanded = false
                                onLimitChange(option)
                            },
                        )
                    }
                }
            }
        }
    }
}
