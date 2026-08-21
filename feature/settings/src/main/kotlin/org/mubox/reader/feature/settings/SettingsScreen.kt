package org.mubox.reader.feature.settings

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mubox.reader.core.model.settings.Anime4KProfile
import org.mubox.reader.core.model.settings.AppColorPalette
import org.mubox.reader.core.model.settings.AppSettings
import org.mubox.reader.core.model.settings.DiagnosticLogLevel
import org.mubox.reader.core.model.settings.GpuApiMode
import org.mubox.reader.core.model.settings.MpvProfileMode
import org.mubox.reader.core.model.settings.ReadingDirection
import org.mubox.reader.core.model.settings.VideoBackgroundMode
import org.mubox.reader.core.model.settings.VideoDecoderMode
import org.mubox.reader.core.model.settings.VideoForwardPrefetchMode
import org.mubox.reader.core.model.settings.VideoOutputMode
import org.mubox.reader.core.model.settings.VideoPlayerOrientationMode
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.settings.playerControlAutoHideOptionsMillis
import org.mubox.reader.core.model.cache.ComicCacheAnalysis
import org.mubox.reader.core.model.cache.ComicCacheCategory
import org.mubox.reader.core.model.format.formatCacheSize
import org.mubox.reader.ui.HistoryEntryRow
import org.mubox.reader.ui.MuBoxActionRow
import org.mubox.reader.ui.MuBoxBoxedList
import org.mubox.reader.ui.MuBoxHeaderBar
import org.mubox.reader.ui.MuBoxEmptyState
import org.mubox.reader.ui.MuBoxMetrics
import org.mubox.reader.ui.MuBoxSwitchRow
import org.mubox.reader.ui.muBoxAppBackground
import org.mubox.reader.ui.rememberMuBoxColors
import org.mubox.reader.ui.settings.gpuApiModeLabel
import org.mubox.reader.ui.settings.mpvProfileModeLabel
import org.mubox.reader.ui.settings.playerControlAutoHideLabel
import org.mubox.reader.ui.settings.videoBackgroundModeLabel
import org.mubox.reader.ui.settings.videoDecoderModeLabel
import org.mubox.reader.ui.settings.videoOutputModeLabel
import org.mubox.reader.ui.settings.videoPlayerOrientationModeLabel
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

internal data class SettingsGroupLayout(
    val title: String,
    val rows: List<String>,
)

internal fun rootSettingsGroupLayout(): List<SettingsGroupLayout> =
    listOf(
        SettingsGroupLayout(
            title = "通用",
            rows = listOf("配色方案", "屏幕旋转锁定", "异常日志等级"),
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
                onReadingDirectionChange = {
                    onAction(SettingsAction.UpdateReader { current -> current.copy(readingDirection = it) })
                },
                onAutoPageEnabledChange = {
                    onAction(SettingsAction.UpdateReader { current -> current.copy(autoPageEnabled = it) })
                },
                onAutoPageSpeedChange = {
                    onAction(SettingsAction.UpdateReader { current -> current.copy(autoPageSpeedMillis = it) })
                },
                onVolumeKeysTurnPagesChange = {
                    onAction(SettingsAction.UpdateReader { current -> current.copy(volumeKeysTurnPagesEnabled = it) })
                },
                onReaderPinchZoomEnabledChange = {
                    onAction(SettingsAction.UpdateReader { current -> current.copy(readerPinchZoomEnabled = it) })
                },
                onWebDavPrefetchPageCountChange = {
                    onAction(SettingsAction.UpdateStorage { current -> current.copy(webDavPrefetchPageCount = it) })
                },
                onLibraryCoversEnabledChange = {
                    onAction(SettingsAction.UpdateAppearance { current -> current.copy(libraryCoversEnabled = it) })
                },
                onBack = { currentPage = SettingsPage.ROOT },
                modifier = modifier,
            )
            return
        }
        SettingsPage.VIDEO -> {
            VideoSettingsPage(
                settings = settings,
                onVideoResumeEnabledChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(videoResumeEnabled = it) })
                },
                onVideoBackgroundModeChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(videoBackgroundMode = it) })
                },
                onVideoSeekOptimizationEnabledChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(videoSeekOptimizationEnabled = it) })
                },
                onVideoForwardPrefetchModeChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(videoForwardPrefetchMode = it) })
                },
                onVideoPlayerProxyDebugInfoEnabledChange = {
                    onAction(
                        SettingsAction.UpdateVideo { current ->
                            current.copy(videoPlayerProxyDebugInfoEnabled = it)
                        },
                    )
                },
                onVideoOutputModeChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(videoOutputMode = it) })
                },
                onGpuApiModeChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(gpuApiMode = it) })
                },
                onAnime4KProfileChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(anime4kProfile = it) })
                },
                onVideoDecoderModeChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(videoDecoderMode = it) })
                },
                onMpvProfileModeChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(mpvProfileMode = it) })
                },
                onVideoControlsAutoHideMillisChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(videoControlsAutoHideMillis = it) })
                },
                onVideoPlayerOrientationModeChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(videoPlayerOrientationMode = it) })
                },
                onGridVideoThumbnailsEnabledChange = {
                    onAction(SettingsAction.UpdateVideo { current -> current.copy(gridVideoThumbnailsEnabled = it) })
                },
                onVideoLibraryThumbnailsEnabledChange = {
                    onAction(
                        SettingsAction.UpdateVideo { current ->
                            current.copy(videoLibraryThumbnailsEnabled = it)
                        },
                    )
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
                onDeleteEntries = { entries ->
                    if (entries.size == history.size) {
                        onAction(SettingsAction.ClearHistory)
                    } else {
                        entries.forEach { entry ->
                            onAction(SettingsAction.DeleteHistoryEntry(entry))
                        }
                    }
                },
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
                selected = settings.appearance.colorPalette,
                options = AppColorPalette.entries,
                label = AppColorPalette::settingsLabel,
                onSelected = {
                    onAction(SettingsAction.UpdateAppearance { current -> current.copy(colorPalette = it) })
                },
            )
            MuBoxSwitchRow(
                title = "屏幕旋转锁定",
                checked = settings.appearance.screenRotationLockEnabled,
                onCheckedChange = {
                    onAction(
                        SettingsAction.UpdateAppearance { current ->
                            current.copy(screenRotationLockEnabled = it)
                        },
                    )
                },
                subtitle = "锁定当前屏幕方向",
            )
            DropdownRow(
                title = "异常日志等级",
                selected = settings.diagnostics.logLevel,
                options = DiagnosticLogLevel.entries,
                label = DiagnosticLogLevel::settingsLabel,
                onSelected = {
                    onAction(
                        SettingsAction.UpdateDiagnostics { current -> current.copy(logLevel = it) },
                    )
                },
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
                subtitle = "阅读方向、翻页、预取和封面",
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
                selected = settings.history.historyRetentionDays,
                options = SupportedHistoryRetentionDays,
                label = ::historyRetentionLabel,
                onSelected = {
                    onAction(SettingsAction.UpdateHistory { current -> current.copy(historyRetentionDays = it) })
                },
            )
            DropdownRow(
                title = "最大保留记录",
                selected = settings.history.historyMaxRecords,
                options = SupportedHistoryMaxRecords,
                label = ::historyMaxRecordsLabel,
                onSelected = {
                    onAction(SettingsAction.UpdateHistory { current -> current.copy(historyMaxRecords = it) })
                },
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
                checked = settings.storage.pageImageCacheEnabled,
                onCheckedChange = {
                    onAction(SettingsAction.UpdateStorage { current -> current.copy(pageImageCacheEnabled = it) })
                },
                subtitle = "关闭后不复用页面文件",
            )
            if (settings.storage.pageImageCacheEnabled) {
                DiskCacheLimitRow(
                    limitMb = settings.storage.diskCacheLimitMb,
                    onLimitChange = {
                        onAction(SettingsAction.UpdateStorage { current -> current.copy(diskCacheLimitMb = it) })
                    },
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
                title = "视频缩略图缓存",
                subtitle = formatCacheSize(cacheAnalysis.videoThumbnailsBytes),
                enabled = cacheAnalysis.videoThumbnailsBytes > 0L,
                onClear = { onAction(SettingsAction.ClearCacheCategory(ComicCacheCategory.VIDEO_THUMBNAILS)) },
            )
            CacheActionRow(
                title = "历史漫画封面缓存",
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
    onDeleteEntries: (List<WatchHistoryEntry>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    val availableKeys = remember(history) { history.mapTo(linkedSetOf(), WatchHistoryEntry::mediaKey) }
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    var pendingDeleteKeys by remember { mutableStateOf<Set<String>?>(null) }
    val selectionActive = selectedKeys.isNotEmpty()

    LaunchedEffect(availableKeys) {
        selectedKeys = selectedKeys.intersect(availableKeys)
    }
    BackHandler(enabled = selectionActive) {
        selectedKeys = emptySet()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .muBoxAppBackground(colors),
    ) {
        MuBoxHeaderBar(
            title = if (selectionActive) "已选择 ${selectedKeys.size} 项" else "观看历史",
            navigationIcon = {
                IconButton(onClick = {
                    if (selectionActive) selectedKeys = emptySet() else onBack()
                }) {
                    Icon(
                        imageVector = if (selectionActive) {
                            Icons.Filled.Close
                        } else {
                            Icons.AutoMirrored.Filled.ArrowBack
                        },
                        contentDescription = if (selectionActive) "取消选择" else "返回",
                    )
                }
            },
            actions = {
                if (selectionActive) {
                    TextButton(
                        onClick = { selectedKeys = availableKeys },
                        enabled = selectedKeys.size < availableKeys.size,
                    ) {
                        Icon(Icons.Filled.SelectAll, contentDescription = null)
                        Text("全选")
                    }
                    IconButton(onClick = { pendingDeleteKeys = selectedKeys }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除所选 ${selectedKeys.size} 条历史记录",
                            tint = colors.errorText,
                        )
                    }
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        text = if (selectionActive) "轻触条目可继续多选" else "长按条目可多选删除",
                        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
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
                        selected = entry.mediaKey in selectedKeys,
                        selectionActive = selectionActive,
                        onToggleSelection = {
                            selectedKeys = selectedKeys.toggle(entry.mediaKey)
                        },
                    )
                }
            }
        }
    }
    pendingDeleteKeys?.let { keys ->
        val entries = history.filter { it.mediaKey in keys }
        AlertDialog(
            onDismissRequest = { pendingDeleteKeys = null },
            title = { Text("删除所选 ${entries.size} 条历史记录？") },
            text = { Text("将同时清理对应的恢复位置和关联漫画缓存，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    enabled = entries.isNotEmpty(),
                    onClick = {
                        pendingDeleteKeys = null
                        selectedKeys = emptySet()
                        onDeleteEntries(entries)
                    },
                ) {
                    Text("删除", color = colors.errorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteKeys = null }) {
                    Text("取消")
                }
            },
        )
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> =
    if (value in this) this - value else this + value

internal fun historyRetentionLabel(days: Int): String =
    if (days <= 0) "永久" else "$days 天"

internal fun historyMaxRecordsLabel(maxRecords: Int): String = "$maxRecords 条"

internal fun DiagnosticLogLevel.settingsLabel(): String =
    when (this) {
        DiagnosticLogLevel.OFF -> "关闭全部日志"
        DiagnosticLogLevel.ERROR -> "异常与崩溃"
    }

@Composable
private fun ComicSettingsPage(
    settings: AppSettings,
    onReadingDirectionChange: (ReadingDirection) -> Unit,
    onAutoPageEnabledChange: (Boolean) -> Unit,
    onAutoPageSpeedChange: (Int) -> Unit,
    onVolumeKeysTurnPagesChange: (Boolean) -> Unit,
    onReaderPinchZoomEnabledChange: (Boolean) -> Unit,
    onWebDavPrefetchPageCountChange: (Int) -> Unit,
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
                selected = settings.reader.readingDirection,
                label = ReadingDirection::label,
                onSelected = onReadingDirectionChange,
            )
            MuBoxSwitchRow(
                title = "音量键翻页",
                checked = settings.reader.volumeKeysTurnPagesEnabled,
                onCheckedChange = onVolumeKeysTurnPagesChange,
                subtitle = "使用音量键向前或向后翻页",
            )
            MuBoxSwitchRow(
                title = "双指缩放",
                checked = settings.reader.readerPinchZoomEnabled,
                onCheckedChange = onReaderPinchZoomEnabledChange,
                subtitle = "用双指放大全屏阅读画面，并拖动查看细节",
            )
            DropdownRow(
                title = "WebDAV 预取页数",
                selected = settings.storage.webDavPrefetchPageCount,
                options = SupportedWebDavPrefetchPageCounts,
                label = ::webDavPrefetchPageCountLabel,
                onSelected = onWebDavPrefetchPageCountChange,
            )
            MuBoxSwitchRow(
                title = "书架封面",
                checked = settings.appearance.libraryCoversEnabled,
                onCheckedChange = onLibraryCoversEnabledChange,
                subtitle = "从 WebDAV 漫画提取首图并显示在书架",
            )
            MuBoxSwitchRow(
                title = "启用自动翻页",
                checked = settings.reader.autoPageEnabled,
                onCheckedChange = onAutoPageEnabledChange,
                subtitle = "按固定间隔前进到下一页",
            )
            AutoPageSpeedRow(
                speedMillis = settings.reader.autoPageSpeedMillis,
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
                checked = settings.video.videoResumeEnabled,
                onCheckedChange = onVideoResumeEnabledChange,
                subtitle = "再次打开同一视频时从上次退出位置继续",
            )
            ChoiceRow(
                title = "后台行为",
                options = VideoBackgroundMode.entries,
                selected = settings.video.videoBackgroundMode,
                label = ::videoBackgroundModeLabel,
                onSelected = onVideoBackgroundModeChange,
            )
            MuBoxSwitchRow(
                title = "WebDAV 视频 seek 优化",
                checked = settings.video.videoSeekOptimizationEnabled,
                onCheckedChange = onVideoSeekOptimizationEnabledChange,
                subtitle = "缓存小段视频并合并重复 seek 请求",
            )
            DropdownRow(
                title = "向前预读",
                selected = settings.video.videoForwardPrefetchMode,
                options = VideoForwardPrefetchMode.entries,
                label = VideoForwardPrefetchMode::label,
                onSelected = onVideoForwardPrefetchModeChange,
            )
            MuBoxSwitchRow(
                title = "播放信息显示代理/Range 调试信息",
                checked = settings.video.videoPlayerProxyDebugInfoEnabled,
                onCheckedChange = onVideoPlayerProxyDebugInfoEnabledChange,
                subtitle = "在播放器信息面板显示 WebDAV 代理、Range 和预读状态",
            )
            DropdownRow(
                title = "视频输出 (VO)",
                selected = settings.video.videoOutputMode,
                options = VideoOutputMode.entries,
                label = ::videoOutputModeLabel,
                onSelected = onVideoOutputModeChange,
            )
            DropdownRow(
                title = "GPU API",
                selected = settings.video.gpuApiMode,
                options = GpuApiMode.entries,
                label = ::gpuApiModeLabel,
                onSelected = onGpuApiModeChange,
            )
            DropdownRow(
                title = "Anime4K",
                selected = settings.video.anime4kProfile,
                options = Anime4KProfile.entries,
                label = ::anime4kProfileLabel,
                onSelected = onAnime4KProfileChange,
            )
            DropdownRow(
                title = "默认解码器",
                selected = settings.video.videoDecoderMode,
                options = VideoDecoderMode.entries,
                label = ::videoDecoderModeLabel,
                onSelected = onVideoDecoderModeChange,
            )
            DropdownRow(
                title = "MPV Profile",
                selected = settings.video.mpvProfileMode,
                options = MpvProfileMode.entries,
                label = ::mpvProfileModeLabel,
                onSelected = onMpvProfileModeChange,
            )
            DropdownRow(
                title = "控制自动隐藏",
                selected = settings.video.videoControlsAutoHideMillis,
                options = playerControlAutoHideOptionsMillis(),
                label = ::playerControlAutoHideLabel,
                onSelected = onVideoControlsAutoHideMillisChange,
            )
            DropdownRow(
                title = "播放器方向",
                selected = settings.video.videoPlayerOrientationMode,
                options = VideoPlayerOrientationMode.entries,
                label = ::videoPlayerOrientationModeLabel,
                onSelected = onVideoPlayerOrientationModeChange,
            )
            MuBoxSwitchRow(
                title = "网格视图视频缩略图",
                checked = settings.video.gridVideoThumbnailsEnabled,
                onCheckedChange = onGridVideoThumbnailsEnabledChange,
                subtitle = "在本地与 WebDAV 文件网格中自动生成并显示视频缩略图",
            )
            MuBoxSwitchRow(
                title = "提取加入影视库的视频缩略图作为封面",
                checked = settings.video.videoLibraryThumbnailsEnabled,
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

fun pageCacheLimitBytesForSettings(pageImageCacheEnabled: Boolean, limitMb: Int): Long =
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

internal fun AppColorPalette.settingsLabel(): String = when (this) {
    AppColorPalette.DEFAULT -> "跟随系统"
    AppColorPalette.MU_BOX_LIGHT -> "MuBOX 浅色"
    AppColorPalette.MU_BOX_DARK -> "MuBOX 深色"
    AppColorPalette.ADWAITA_LIGHT -> "Adwaita 浅色"
    AppColorPalette.ADWAITA_BLUE_GRAY -> "Adwaita 蓝灰"
    AppColorPalette.ADWAITA_PURPLE -> "Adwaita 紫色"
    AppColorPalette.CINEMA_DARK -> "影院深色（旧）"
    AppColorPalette.SEPIA -> "纸张护眼"
    AppColorPalette.NIGHT -> "夜间深色"
    AppColorPalette.HIGH_CONTRAST -> "高对比"
}

private fun VideoForwardPrefetchMode.label(): String =
    when (this) {
        VideoForwardPrefetchMode.OFF -> "关闭"
        VideoForwardPrefetchMode.STANDARD -> "标准"
        VideoForwardPrefetchMode.AGGRESSIVE -> "积极"
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
