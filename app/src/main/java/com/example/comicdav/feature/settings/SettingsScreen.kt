package com.example.comicdav.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.example.comicdav.core.model.settings.Anime4KProfile
import com.example.comicdav.core.model.settings.AppColorPalette
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.MpvProfileMode
import com.example.comicdav.core.model.settings.ReaderLoggingMode
import com.example.comicdav.core.model.settings.ReadingDirection
import com.example.comicdav.core.model.settings.VideoBackgroundMode
import com.example.comicdav.core.model.settings.VideoDecoderMode
import com.example.comicdav.core.model.settings.VideoForwardPrefetchMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import com.example.comicdav.core.model.settings.VideoPlayerOrientationMode
import com.example.comicdav.core.model.settings.VideoProxyDiagnosticsMode
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.settings.playerControlAutoHideOptionsMillis
import com.example.comicdav.data.displayLabel
import com.example.comicdav.data.ComicCacheAnalysis
import com.example.comicdav.data.ComicCacheCategory
import com.example.comicdav.data.formatCacheSize
import com.example.comicdav.ui.HistoryEntryRow
import com.example.comicdav.ui.MuBoxActionRow
import com.example.comicdav.ui.MuBoxBoxedList
import com.example.comicdav.ui.MuBoxHeaderBar
import com.example.comicdav.ui.MuBoxEmptyState
import com.example.comicdav.ui.MuBoxMetrics
import com.example.comicdav.ui.MuBoxSwitchRow
import com.example.comicdav.ui.muBoxAppBackground
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.ui.settings.gpuApiModeLabel
import com.example.comicdav.ui.settings.mpvProfileModeLabel
import com.example.comicdav.ui.settings.playerControlAutoHideLabel
import com.example.comicdav.ui.settings.videoBackgroundModeLabel
import com.example.comicdav.ui.settings.videoDecoderModeLabel
import com.example.comicdav.ui.settings.videoOutputModeLabel
import com.example.comicdav.ui.settings.videoPlayerOrientationModeLabel
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MinAutoPageSpeedSeconds = 3
private const val MaxAutoPageSpeedSeconds = 60
private val SupportedDiskCacheLimitMb = listOf(500, 1024, 2048, 3072, 4096, 5120)
private val SupportedWebDavPrefetchPageCounts = listOf(2, 4, 6, 8, 10, 12)
private val SupportedHistoryRetentionDays = listOf(0, 7, 30, 90, 180, 365)
private val SupportedHistoryMaxRecords = listOf(50, 100, 200, 500, 1_000)

private enum class SettingsPage {
    ROOT,
    COMIC,
    VIDEO,
    HISTORY,
}

sealed interface SettingsAction {
    data class SetReadingDirection(val value: ReadingDirection) : SettingsAction
    data class SetReaderLoggingMode(val value: ReaderLoggingMode) : SettingsAction
    data class SetColorPalette(val value: AppColorPalette) : SettingsAction
    data class SetAvifImagesEnabled(val value: Boolean) : SettingsAction
    data class SetAutoPageEnabled(val value: Boolean) : SettingsAction
    data class SetAutoPageSpeedMillis(val value: Int) : SettingsAction
    data class SetScreenRotationLockEnabled(val value: Boolean) : SettingsAction
    data class SetVolumeKeysTurnPagesEnabled(val value: Boolean) : SettingsAction
    data class SetReaderPinchZoomEnabled(val value: Boolean) : SettingsAction
    data class SetPageImageCacheEnabled(val value: Boolean) : SettingsAction
    data class SetDiskCacheLimitMb(val value: Int) : SettingsAction
    data class SetWebDavPrefetchPageCount(val value: Int) : SettingsAction
    data class SetLibraryCoversEnabled(val value: Boolean) : SettingsAction
    data class SetVideoResumeEnabled(val value: Boolean) : SettingsAction
    data class SetVideoBackgroundMode(val value: VideoBackgroundMode) : SettingsAction
    data class SetVideoSeekOptimizationEnabled(val value: Boolean) : SettingsAction
    data class SetVideoForwardPrefetchMode(val value: VideoForwardPrefetchMode) : SettingsAction
    data class SetVideoProxyDiagnosticsMode(val value: VideoProxyDiagnosticsMode) : SettingsAction
    data class SetVideoPlayerProxyDebugInfoEnabled(val value: Boolean) : SettingsAction
    data class SetVideoOutputMode(val value: VideoOutputMode) : SettingsAction
    data class SetGpuApiMode(val value: GpuApiMode) : SettingsAction
    data class SetAnime4KProfile(val value: Anime4KProfile) : SettingsAction
    data class SetVideoDecoderMode(val value: VideoDecoderMode) : SettingsAction
    data class SetMpvProfileMode(val value: MpvProfileMode) : SettingsAction
    data class SetVideoControlsAutoHideMillis(val value: Int) : SettingsAction
    data class SetVideoPlayerOrientationMode(val value: VideoPlayerOrientationMode) : SettingsAction
    data class SetGridVideoThumbnailsEnabled(val value: Boolean) : SettingsAction
    data class SetVideoLibraryThumbnailsEnabled(val value: Boolean) : SettingsAction
    data class SetHistoryRetentionDays(val value: Int) : SettingsAction
    data class SetHistoryMaxRecords(val value: Int) : SettingsAction
    data class DeleteHistoryEntry(val entry: WatchHistoryEntry) : SettingsAction
    data object ClearHistory : SettingsAction
    data class ClearCacheCategory(val category: ComicCacheCategory) : SettingsAction
    data object ClearAllCache : SettingsAction
}

internal data class SettingsGroupLayout(
    val title: String,
    val rows: List<String>,
)

internal fun rootSettingsGroupLayout(): List<SettingsGroupLayout> =
    listOf(
        SettingsGroupLayout(
            title = "通用",
            rows = listOf("配色方案", "屏幕旋转锁定"),
        ),
        SettingsGroupLayout(
            title = "内容设置",
            rows = listOf("观看历史", "漫画设置", "视频设置"),
        ),
        SettingsGroupLayout(
            title = "观看历史设置",
            rows = listOf("保留时长", "最大保留记录", "清空观看历史"),
        ),
        SettingsGroupLayout(
            title = "缓存",
            rows = listOf(
                "缓存占用",
                "远程整本缓存",
                "WebDAV 索引缓存",
                "页面图片缓存",
                "页面图片缓存上限",
                "页面图片缓存占用",
                "书架封面缓存",
            ),
        ),
    )

internal fun comicSettingsGroupLayout(): List<SettingsGroupLayout> =
    listOf(
        SettingsGroupLayout(
            title = "漫画设置",
            rows = listOf(
                "阅读方向",
                "音量键翻页",
                "双指缩放",
                "WebDAV 预取页数",
                "诊断日志",
                "AVIF 图片",
                "书架封面",
                "启用自动翻页",
                "翻页速度",
            ),
        ),
    )

internal fun videoSettingsGroupLayout(): List<SettingsGroupLayout> =
    listOf(
        SettingsGroupLayout(
            title = "视频设置",
            rows = listOf(
                "恢复播放位置",
                "后台行为",
                "WebDAV 视频 seek 优化",
                "向前预读",
                "视频代理诊断日志",
                "播放信息显示代理/Range 调试信息",
                "视频输出 (VO)",
                "GPU API",
                "Anime4K",
                "默认解码器",
                "MPV Profile",
                "控制自动隐藏",
                "播放器方向",
                "网格视图视频缩略图",
                "提取加入影视库的视频缩略图作为封面",
            ),
        ),
    )

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onAction: (SettingsAction) -> Unit,
    history: List<WatchHistoryEntry> = emptyList(),
    onOpenHistoryEntry: (WatchHistoryEntry) -> Unit = {},
    cacheAnalysis: ComicCacheAnalysis = ComicCacheAnalysis(),
    cacheActionMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    var currentPage by remember { mutableStateOf(SettingsPage.ROOT) }
    var confirmingHistoryClear by remember { mutableStateOf(false) }

    BackHandler(enabled = currentPage != SettingsPage.ROOT) {
        currentPage = SettingsPage.ROOT
    }

    when (currentPage) {
        SettingsPage.COMIC -> {
            ComicSettingsPage(
                settings = settings,
                onReadingDirectionChange = { onAction(SettingsAction.SetReadingDirection(it)) },
                onReaderLoggingModeChange = { onAction(SettingsAction.SetReaderLoggingMode(it)) },
                onAutoPageEnabledChange = { onAction(SettingsAction.SetAutoPageEnabled(it)) },
                onAutoPageSpeedChange = { onAction(SettingsAction.SetAutoPageSpeedMillis(it)) },
                onVolumeKeysTurnPagesChange = { onAction(SettingsAction.SetVolumeKeysTurnPagesEnabled(it)) },
                onReaderPinchZoomEnabledChange = { onAction(SettingsAction.SetReaderPinchZoomEnabled(it)) },
                onWebDavPrefetchPageCountChange = { onAction(SettingsAction.SetWebDavPrefetchPageCount(it)) },
                onAvifImagesEnabledChange = { onAction(SettingsAction.SetAvifImagesEnabled(it)) },
                onLibraryCoversEnabledChange = { onAction(SettingsAction.SetLibraryCoversEnabled(it)) },
                onBack = { currentPage = SettingsPage.ROOT },
                modifier = modifier,
            )
            return
        }
        SettingsPage.VIDEO -> {
            VideoSettingsPage(
                settings = settings,
                onVideoResumeEnabledChange = { onAction(SettingsAction.SetVideoResumeEnabled(it)) },
                onVideoBackgroundModeChange = { onAction(SettingsAction.SetVideoBackgroundMode(it)) },
                onVideoSeekOptimizationEnabledChange = {
                    onAction(SettingsAction.SetVideoSeekOptimizationEnabled(it))
                },
                onVideoForwardPrefetchModeChange = { onAction(SettingsAction.SetVideoForwardPrefetchMode(it)) },
                onVideoProxyDiagnosticsModeChange = { onAction(SettingsAction.SetVideoProxyDiagnosticsMode(it)) },
                onVideoPlayerProxyDebugInfoEnabledChange = {
                    onAction(SettingsAction.SetVideoPlayerProxyDebugInfoEnabled(it))
                },
                onVideoOutputModeChange = { onAction(SettingsAction.SetVideoOutputMode(it)) },
                onGpuApiModeChange = { onAction(SettingsAction.SetGpuApiMode(it)) },
                onAnime4KProfileChange = { onAction(SettingsAction.SetAnime4KProfile(it)) },
                onVideoDecoderModeChange = { onAction(SettingsAction.SetVideoDecoderMode(it)) },
                onMpvProfileModeChange = { onAction(SettingsAction.SetMpvProfileMode(it)) },
                onVideoControlsAutoHideMillisChange = {
                    onAction(SettingsAction.SetVideoControlsAutoHideMillis(it))
                },
                onVideoPlayerOrientationModeChange = {
                    onAction(SettingsAction.SetVideoPlayerOrientationMode(it))
                },
                onGridVideoThumbnailsEnabledChange = {
                    onAction(SettingsAction.SetGridVideoThumbnailsEnabled(it))
                },
                onVideoLibraryThumbnailsEnabledChange = {
                    onAction(SettingsAction.SetVideoLibraryThumbnailsEnabled(it))
                },
                onBack = { currentPage = SettingsPage.ROOT },
                modifier = modifier,
            )
            return
        }
        SettingsPage.HISTORY -> {
            HistorySettingsPage(
                history = history,
                onOpenEntry = onOpenHistoryEntry,
                onDeleteEntry = { onAction(SettingsAction.DeleteHistoryEntry(it)) },
                onBack = { currentPage = SettingsPage.ROOT },
                modifier = modifier,
            )
            return
        }
        SettingsPage.ROOT -> Unit
    }

    val colors = rememberMuBoxColors()
    // 根页不再显示应用内顶部标题，内容从系统状态栏安全区后开始。
    Column(
        modifier = modifier
            .fillMaxSize()
            .muBoxAppBackground(colors)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        MuBoxBoxedList(
            title = "通用",
            modifier = Modifier.padding(horizontal = MuBoxMetrics.PageHorizontalPaddingDp),
        ) {
            DropdownRow(
                title = "配色方案",
                selected = settings.colorPalette,
                options = AppColorPalette.entries,
                label = AppColorPalette::settingsLabel,
                onSelected = { onAction(SettingsAction.SetColorPalette(it)) },
            )
            MuBoxSwitchRow(
                title = "屏幕旋转锁定",
                checked = settings.screenRotationLockEnabled,
                onCheckedChange = { onAction(SettingsAction.SetScreenRotationLockEnabled(it)) },
                subtitle = "锁定当前屏幕方向",
            )
        }

        MuBoxBoxedList(
            title = "内容设置",
            modifier = Modifier.padding(horizontal = MuBoxMetrics.PageHorizontalPaddingDp),
        ) {
            MuBoxActionRow(
                title = "观看历史",
                onClick = { currentPage = SettingsPage.HISTORY },
                subtitle = if (history.isEmpty()) "暂无记录" else "${history.size} 条记录，继续上次进度",
                leading = { Icon(Icons.Filled.History, contentDescription = null) },
                trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
            )
            MuBoxActionRow(
                title = "漫画设置",
                onClick = { currentPage = SettingsPage.COMIC },
                subtitle = "阅读方向、翻页、预取、封面和诊断",
                trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
            )
            MuBoxActionRow(
                title = "视频设置",
                onClick = { currentPage = SettingsPage.VIDEO },
                subtitle = "播放、WebDAV 流式读取、解码和封面",
                trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
            )
        }

        MuBoxBoxedList(
            title = "观看历史设置",
            modifier = Modifier.padding(horizontal = MuBoxMetrics.PageHorizontalPaddingDp),
        ) {
            DropdownRow(
                title = "保留时长",
                selected = settings.historyRetentionDays,
                options = SupportedHistoryRetentionDays,
                label = ::historyRetentionLabel,
                onSelected = { onAction(SettingsAction.SetHistoryRetentionDays(it)) },
            )
            DropdownRow(
                title = "最大保留记录",
                selected = settings.historyMaxRecords,
                options = SupportedHistoryMaxRecords,
                label = ::historyMaxRecordsLabel,
                onSelected = { onAction(SettingsAction.SetHistoryMaxRecords(it)) },
            )
            CacheActionRow(
                title = "清空观看历史",
                subtitle = "${history.size} 条记录；同时清理恢复位置和关联漫画缓存",
                enabled = history.isNotEmpty(),
                onClear = { confirmingHistoryClear = true },
            )
        }

        MuBoxBoxedList(
            title = "缓存",
            modifier = Modifier.padding(horizontal = MuBoxMetrics.PageHorizontalPaddingDp),
        ) {
            CacheActionRow(
                title = "缓存总占用",
                subtitle = formatCacheSize(cacheAnalysis.totalBytes),
                enabled = cacheAnalysis.totalBytes > 0L,
                onClear = { onAction(SettingsAction.ClearAllCache) },
            )
            CacheActionRow(
                title = "远程整本缓存",
                subtitle = formatCacheSize(cacheAnalysis.remoteDownloadsBytes),
                enabled = cacheAnalysis.remoteDownloadsBytes > 0L,
                onClear = { onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.REMOTE_DOWNLOADS)) },
            )
            CacheActionRow(
                title = "WebDAV 索引缓存",
                subtitle = formatCacheSize(cacheAnalysis.remoteIndexBytes),
                enabled = cacheAnalysis.remoteIndexBytes > 0L,
                onClear = { onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.REMOTE_INDEX)) },
            )
            MuBoxSwitchRow(
                title = "页面图片缓存",
                checked = settings.pageImageCacheEnabled,
                onCheckedChange = { onAction(SettingsAction.SetPageImageCacheEnabled(it)) },
                subtitle = "关闭后不复用页面文件",
            )
            if (settings.pageImageCacheEnabled) {
                DiskCacheLimitRow(
                    limitMb = settings.diskCacheLimitMb,
                    onLimitChange = { onAction(SettingsAction.SetDiskCacheLimitMb(it)) },
                )
            }
            CacheActionRow(
                title = "页面图片缓存占用",
                subtitle = formatCacheSize(cacheAnalysis.readerPagesBytes),
                enabled = cacheAnalysis.readerPagesBytes > 0L,
                onClear = { onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.READER_PAGES)) },
            )
            CacheActionRow(
                title = "临时页面缓存",
                subtitle = formatCacheSize(cacheAnalysis.transientReaderPagesBytes),
                enabled = cacheAnalysis.transientReaderPagesBytes > 0L,
                onClear = {
                    onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.TRANSIENT_READER_PAGES))
                },
            )
            CacheActionRow(
                title = "书架封面缓存",
                subtitle = formatCacheSize(cacheAnalysis.libraryCoversBytes),
                enabled = cacheAnalysis.libraryCoversBytes > 0L,
                onClear = { onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.LIBRARY_COVERS)) },
            )
            CacheActionRow(
                title = "影视库缩略图缓存",
                subtitle = formatCacheSize(cacheAnalysis.videoThumbnailsBytes),
                enabled = cacheAnalysis.videoThumbnailsBytes > 0L,
                onClear = { onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.VIDEO_THUMBNAILS)) },
            )
            CacheActionRow(
                title = "历史记录缩略图缓存",
                subtitle = formatCacheSize(cacheAnalysis.historyThumbnailsBytes),
                enabled = cacheAnalysis.historyThumbnailsBytes > 0L,
                onClear = { onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.HISTORY_THUMBNAILS)) },
            )
            CacheActionRow(
                title = "视频字幕缓存",
                subtitle = formatCacheSize(cacheAnalysis.videoSubtitlesBytes),
                enabled = cacheAnalysis.videoSubtitlesBytes > 0L,
                onClear = { onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.VIDEO_SUBTITLES)) },
            )
            CacheActionRow(
                title = "运行时代码缓存",
                subtitle = formatCacheSize(cacheAnalysis.codeCacheBytes),
                enabled = cacheAnalysis.codeCacheBytes > 0L,
                onClear = { onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.CODE_CACHE)) },
            )
            CacheActionRow(
                title = "外部缓存",
                subtitle = formatCacheSize(cacheAnalysis.externalCacheBytes),
                enabled = cacheAnalysis.externalCacheBytes > 0L,
                onClear = { onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.EXTERNAL_CACHE)) },
            )
            CacheActionRow(
                title = "其他缓存",
                subtitle = formatCacheSize(cacheAnalysis.otherBytes),
                enabled = cacheAnalysis.otherBytes > 0L,
                onClear = { onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.OTHER)) },
            )
        }
        Text(
            text = cacheActionMessage ?: "清理缓存不会删除书架记录和设置",
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MuBoxMetrics.PageHorizontalPaddingDp + 14.dp,
                    vertical = 8.dp,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (confirmingHistoryClear) {
        AlertDialog(
            onDismissRequest = { confirmingHistoryClear = false },
            title = { Text("清空全部观看历史？") },
            text = { Text("将删除全部恢复位置和关联漫画缓存，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingHistoryClear = false
                        onAction(SettingsAction.ClearHistory)
                    },
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingHistoryClear = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun HistorySettingsPage(
    history: List<WatchHistoryEntry>,
    onOpenEntry: (WatchHistoryEntry) -> Unit,
    onDeleteEntry: (WatchHistoryEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    var pendingDelete by remember { mutableStateOf<WatchHistoryEntry?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .muBoxAppBackground(colors),
    ) {
        MuBoxHeaderBar(
            title = "观看历史",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = MuBoxMetrics.PageHorizontalPaddingDp,
                end = MuBoxMetrics.PageHorizontalPaddingDp,
                top = 16.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (history.isEmpty()) {
                item {
                    MuBoxEmptyState(
                        icon = Icons.Filled.History,
                        title = "暂无观看历史",
                        body = "打开漫画或视频后，进度会自动显示在这里",
                    )
                }
            } else {
                item {
                    Text(
                        text = "最近观看",
                        modifier = Modifier.padding(start = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.muted,
                    )
                }
                items(
                    items = history,
                    key = WatchHistoryEntry::mediaKey,
                ) { entry ->
                    HistoryEntryRow(
                        entry = entry,
                        onOpen = { onOpenEntry(entry) },
                        onDelete = { pendingDelete = entry },
                    )
                }
            }
        }
    }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条历史记录？") },
            text = { Text("将同时清理《${entry.displayTitle}》的恢复位置和关联漫画缓存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteEntry(entry)
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

internal fun historyRetentionLabel(days: Int): String =
    if (days <= 0) "永久" else "$days 天"

internal fun historyMaxRecordsLabel(maxRecords: Int): String = "$maxRecords 条"

@Composable
private fun ComicSettingsPage(
    settings: AppSettings,
    onReadingDirectionChange: (ReadingDirection) -> Unit,
    onReaderLoggingModeChange: (ReaderLoggingMode) -> Unit,
    onAutoPageEnabledChange: (Boolean) -> Unit,
    onAutoPageSpeedChange: (Int) -> Unit,
    onVolumeKeysTurnPagesChange: (Boolean) -> Unit,
    onReaderPinchZoomEnabledChange: (Boolean) -> Unit,
    onWebDavPrefetchPageCountChange: (Int) -> Unit,
    onAvifImagesEnabledChange: (Boolean) -> Unit,
    onLibraryCoversEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .muBoxAppBackground(colors)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        MuBoxHeaderBar(
            title = "漫画设置",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        MuBoxBoxedList(
            title = "漫画设置",
            modifier = Modifier.padding(horizontal = MuBoxMetrics.PageHorizontalPaddingDp),
        ) {
            ChoiceRow(
                title = "阅读方向",
                options = ReadingDirection.entries,
                selected = settings.readingDirection,
                label = ReadingDirection::label,
                onSelected = onReadingDirectionChange,
            )
            MuBoxSwitchRow(
                title = "音量键翻页",
                checked = settings.volumeKeysTurnPagesEnabled,
                onCheckedChange = onVolumeKeysTurnPagesChange,
                subtitle = "使用音量键向前或向后翻页",
            )
            MuBoxSwitchRow(
                title = "双指缩放",
                checked = settings.readerPinchZoomEnabled,
                onCheckedChange = onReaderPinchZoomEnabledChange,
                subtitle = "用双指放大全屏阅读画面，并拖动查看细节",
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
            MuBoxSwitchRow(
                title = "AVIF 图片",
                checked = settings.avifImagesEnabled,
                onCheckedChange = onAvifImagesEnabledChange,
                subtitle = "需要 Android 14+；旧系统会忽略这个开关",
            )
            MuBoxSwitchRow(
                title = "书架封面",
                checked = settings.libraryCoversEnabled,
                onCheckedChange = onLibraryCoversEnabledChange,
                subtitle = "从 WebDAV 漫画提取首图并显示在书架",
            )
            MuBoxSwitchRow(
                title = "启用自动翻页",
                checked = settings.autoPageEnabled,
                onCheckedChange = onAutoPageEnabledChange,
                subtitle = "按固定间隔前进到下一页",
            )
            AutoPageSpeedRow(
                speedMillis = settings.autoPageSpeedMillis,
                onSpeedChange = onAutoPageSpeedChange,
            )
        }
    }
}

@Composable
private fun VideoSettingsPage(
    settings: AppSettings,
    onVideoResumeEnabledChange: (Boolean) -> Unit,
    onVideoBackgroundModeChange: (VideoBackgroundMode) -> Unit,
    onVideoSeekOptimizationEnabledChange: (Boolean) -> Unit,
    onVideoForwardPrefetchModeChange: (VideoForwardPrefetchMode) -> Unit,
    onVideoProxyDiagnosticsModeChange: (VideoProxyDiagnosticsMode) -> Unit,
    onVideoPlayerProxyDebugInfoEnabledChange: (Boolean) -> Unit,
    onVideoOutputModeChange: (VideoOutputMode) -> Unit,
    onGpuApiModeChange: (GpuApiMode) -> Unit,
    onAnime4KProfileChange: (Anime4KProfile) -> Unit,
    onVideoDecoderModeChange: (VideoDecoderMode) -> Unit,
    onMpvProfileModeChange: (MpvProfileMode) -> Unit,
    onVideoControlsAutoHideMillisChange: (Int) -> Unit,
    onVideoPlayerOrientationModeChange: (VideoPlayerOrientationMode) -> Unit,
    onGridVideoThumbnailsEnabledChange: (Boolean) -> Unit,
    onVideoLibraryThumbnailsEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .muBoxAppBackground(colors)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        MuBoxHeaderBar(
            title = "视频设置",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        MuBoxBoxedList(
            title = "视频设置",
            modifier = Modifier.padding(horizontal = MuBoxMetrics.PageHorizontalPaddingDp),
        ) {
            MuBoxSwitchRow(
                title = "恢复播放位置",
                checked = settings.videoResumeEnabled,
                onCheckedChange = onVideoResumeEnabledChange,
                subtitle = "再次打开同一视频时从上次退出位置继续",
            )
            ChoiceRow(
                title = "后台行为",
                options = VideoBackgroundMode.entries,
                selected = settings.videoBackgroundMode,
                label = ::videoBackgroundModeLabel,
                onSelected = onVideoBackgroundModeChange,
            )
            MuBoxSwitchRow(
                title = "WebDAV 视频 seek 优化",
                checked = settings.videoSeekOptimizationEnabled,
                onCheckedChange = onVideoSeekOptimizationEnabledChange,
                subtitle = "缓存小段视频并合并重复 seek 请求",
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
            MuBoxSwitchRow(
                title = "播放信息显示代理/Range 调试信息",
                checked = settings.videoPlayerProxyDebugInfoEnabled,
                onCheckedChange = onVideoPlayerProxyDebugInfoEnabledChange,
                subtitle = "在播放器信息面板显示 WebDAV 代理、Range 和预读状态",
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
                title = "Anime4K",
                selected = settings.anime4kProfile,
                options = Anime4KProfile.entries,
                label = ::anime4kProfileLabel,
                onSelected = onAnime4KProfileChange,
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
            MuBoxSwitchRow(
                title = "网格视图视频缩略图",
                checked = settings.gridVideoThumbnailsEnabled,
                onCheckedChange = onGridVideoThumbnailsEnabledChange,
                subtitle = "在本地与 WebDAV 文件网格中自动生成并显示视频缩略图",
            )
            MuBoxSwitchRow(
                title = "提取加入影视库的视频缩略图作为封面",
                checked = settings.videoLibraryThumbnailsEnabled,
                onCheckedChange = onVideoLibraryThumbnailsEnabledChange,
                subtitle = "收藏视频时自动提取一帧作为影视库封面",
            )
        }
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

internal fun pageCacheLimitBytesForSettings(pageImageCacheEnabled: Boolean, limitMb: Int): Long =
    if (pageImageCacheEnabled) pageCacheLimitBytesForMb(limitMb) else 0L

internal fun diskCacheLimitLabel(limitMb: Int): String =
    when (val coercedLimit = coerceDiskCacheLimitMb(limitMb)) {
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

internal fun AppColorPalette.settingsLabel(): String = displayLabel()

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

private fun anime4kProfileLabel(profile: Anime4KProfile): String =
    when (profile) {
        Anime4KProfile.OFF -> "关闭"
        Anime4KProfile.AUTO -> "自动"
        Anime4KProfile.EFFICIENCY -> "效率"
        Anime4KProfile.EXTREME -> "极致"
    }

internal fun settingsControlRowMinHeightDp(): Int = 64

@Composable
private fun CacheActionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = settingsControlRowMinHeightDp().dp)
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
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
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
            .heightIn(min = settingsControlRowMinHeightDp().dp)
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
                    text = "页面图片缓存上限",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "仅限制阅读器页面图片，不含 WebDAV 整本下载和索引",
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
