package com.example.comicdav.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import com.example.comicdav.data.AppColorPalette
import com.example.comicdav.data.AppSettings
import com.example.comicdav.data.displayLabel
import com.example.comicdav.data.ComicCacheAnalysis
import com.example.comicdav.data.ComicCacheCategory
import com.example.comicdav.data.ReadingDirection
import com.example.comicdav.data.ReaderLoggingMode
import com.example.comicdav.data.formatCacheSize
import com.example.comicdav.ui.MuBoxActionRow
import com.example.comicdav.ui.MuBoxBoxedList
import com.example.comicdav.ui.MuBoxHeaderBar
import com.example.comicdav.ui.MuBoxSwitchRow
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.video.player.Anime4KMode
import com.example.comicdav.video.player.Anime4KQuality
import com.example.comicdav.video.player.GpuApiMode
import com.example.comicdav.video.player.MpvProfileMode
import com.example.comicdav.video.player.VideoDecoderMode
import com.example.comicdav.video.player.VideoBackgroundMode
import com.example.comicdav.video.player.VideoOutputMode
import com.example.comicdav.video.player.VideoPlayerOrientationMode
import com.example.comicdav.video.player.gpuApiModeLabel
import com.example.comicdav.video.player.mpvProfileModeLabel
import com.example.comicdav.video.player.playerControlAutoHideLabel
import com.example.comicdav.video.player.playerControlAutoHideOptionsMillis
import com.example.comicdav.video.player.videoBackgroundModeLabel
import com.example.comicdav.video.player.videoDecoderModeLabel
import com.example.comicdav.video.player.videoOutputModeLabel
import com.example.comicdav.video.player.videoPlayerOrientationModeLabel
import com.example.comicdav.video.proxy.VideoForwardPrefetchMode
import com.example.comicdav.video.proxy.VideoProxyDiagnosticsMode
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MinAutoPageSpeedSeconds = 3
private const val MaxAutoPageSpeedSeconds = 60
private val SupportedDiskCacheLimitMb = listOf(500, 1024, 2048, 3072, 4096, 5120)
private val SupportedWebDavPrefetchPageCounts = listOf(2, 4, 6, 8, 10, 12)

private enum class SettingsPage {
    ROOT,
    COMIC,
    VIDEO,
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
            rows = listOf("漫画设置", "视频设置"),
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
                "Anime4K 预设",
                "Anime4K 质量",
                "默认解码器",
                "MPV Profile",
                "控制自动隐藏",
                "播放器方向",
                "提取加入影视库的视频缩略图作为封面",
            ),
        ),
    )

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onReadingDirectionChange: (ReadingDirection) -> Unit,
    onReaderLoggingModeChange: (ReaderLoggingMode) -> Unit,
    onColorPaletteChange: (AppColorPalette) -> Unit,
    onAvifImagesEnabledChange: (Boolean) -> Unit = {},
    onAutoPageEnabledChange: (Boolean) -> Unit,
    onAutoPageSpeedChange: (Int) -> Unit,
    onScreenRotationLockChange: (Boolean) -> Unit,
    onVolumeKeysTurnPagesChange: (Boolean) -> Unit,
    onReaderPinchZoomEnabledChange: (Boolean) -> Unit = {},
    onPageImageCacheEnabledChange: (Boolean) -> Unit = {},
    onDiskCacheLimitChange: (Int) -> Unit,
    onWebDavPrefetchPageCountChange: (Int) -> Unit,
    onLibraryCoversEnabledChange: (Boolean) -> Unit,
    onVideoResumeEnabledChange: (Boolean) -> Unit,
    onVideoBackgroundModeChange: (VideoBackgroundMode) -> Unit = {},
    onVideoSeekOptimizationEnabledChange: (Boolean) -> Unit = {},
    onVideoForwardPrefetchModeChange: (VideoForwardPrefetchMode) -> Unit = {},
    onVideoProxyDiagnosticsModeChange: (VideoProxyDiagnosticsMode) -> Unit = {},
    onVideoPlayerProxyDebugInfoEnabledChange: (Boolean) -> Unit = {},
    onVideoOutputModeChange: (VideoOutputMode) -> Unit = {},
    onGpuApiModeChange: (GpuApiMode) -> Unit = {},
    onAnime4KEnabledChange: (Boolean) -> Unit = {},
    onAnime4KModeChange: (Anime4KMode) -> Unit = {},
    onAnime4KQualityChange: (Anime4KQuality) -> Unit = {},
    onVideoDecoderModeChange: (VideoDecoderMode) -> Unit = {},
    onMpvProfileModeChange: (MpvProfileMode) -> Unit = {},
    onVideoControlsAutoHideMillisChange: (Int) -> Unit = {},
    onVideoPlayerOrientationModeChange: (VideoPlayerOrientationMode) -> Unit = {},
    onVideoLibraryThumbnailsEnabledChange: (Boolean) -> Unit = {},
    cacheAnalysis: ComicCacheAnalysis = ComicCacheAnalysis(),
    cacheActionMessage: String? = null,
    onClearCacheCategory: (ComicCacheCategory) -> Unit = {},
    onClearAllCache: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var currentPage by remember { mutableStateOf(SettingsPage.ROOT) }

    BackHandler(enabled = currentPage != SettingsPage.ROOT) {
        currentPage = SettingsPage.ROOT
    }

    when (currentPage) {
        SettingsPage.COMIC -> {
            ComicSettingsPage(
                settings = settings,
                onReadingDirectionChange = onReadingDirectionChange,
                onReaderLoggingModeChange = onReaderLoggingModeChange,
                onAutoPageEnabledChange = onAutoPageEnabledChange,
                onAutoPageSpeedChange = onAutoPageSpeedChange,
                onVolumeKeysTurnPagesChange = onVolumeKeysTurnPagesChange,
                onReaderPinchZoomEnabledChange = onReaderPinchZoomEnabledChange,
                onWebDavPrefetchPageCountChange = onWebDavPrefetchPageCountChange,
                onAvifImagesEnabledChange = onAvifImagesEnabledChange,
                onLibraryCoversEnabledChange = onLibraryCoversEnabledChange,
                onBack = { currentPage = SettingsPage.ROOT },
                modifier = modifier,
            )
            return
        }
        SettingsPage.VIDEO -> {
            VideoSettingsPage(
                settings = settings,
                onVideoResumeEnabledChange = onVideoResumeEnabledChange,
                onVideoBackgroundModeChange = onVideoBackgroundModeChange,
                onVideoSeekOptimizationEnabledChange = onVideoSeekOptimizationEnabledChange,
                onVideoForwardPrefetchModeChange = onVideoForwardPrefetchModeChange,
                onVideoProxyDiagnosticsModeChange = onVideoProxyDiagnosticsModeChange,
                onVideoPlayerProxyDebugInfoEnabledChange = onVideoPlayerProxyDebugInfoEnabledChange,
                onVideoOutputModeChange = onVideoOutputModeChange,
                onGpuApiModeChange = onGpuApiModeChange,
                onAnime4KEnabledChange = onAnime4KEnabledChange,
                onAnime4KModeChange = onAnime4KModeChange,
                onAnime4KQualityChange = onAnime4KQualityChange,
                onVideoDecoderModeChange = onVideoDecoderModeChange,
                onMpvProfileModeChange = onMpvProfileModeChange,
                onVideoControlsAutoHideMillisChange = onVideoControlsAutoHideMillisChange,
                onVideoPlayerOrientationModeChange = onVideoPlayerOrientationModeChange,
                onVideoLibraryThumbnailsEnabledChange = onVideoLibraryThumbnailsEnabledChange,
                onBack = { currentPage = SettingsPage.ROOT },
                modifier = modifier,
            )
            return
        }
        SettingsPage.ROOT -> Unit
    }

    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 0.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        MuBoxHeaderBar(title = "设置")

        MuBoxBoxedList(title = "通用") {
            DropdownRow(
                title = "配色方案",
                selected = settings.colorPalette,
                options = AppColorPalette.entries,
                label = AppColorPalette::settingsLabel,
                onSelected = onColorPaletteChange,
            )
            MuBoxSwitchRow(
                title = "屏幕旋转锁定",
                checked = settings.screenRotationLockEnabled,
                onCheckedChange = onScreenRotationLockChange,
                subtitle = "锁定当前屏幕方向",
            )
        }

        MuBoxBoxedList(title = "内容设置") {
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

        MuBoxBoxedList(title = "缓存") {
            CacheActionRow(
                title = "缓存总占用",
                subtitle = formatCacheSize(cacheAnalysis.totalBytes),
                enabled = cacheAnalysis.totalBytes > 0L,
                onClear = onClearAllCache,
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
            MuBoxSwitchRow(
                title = "页面图片缓存",
                checked = settings.pageImageCacheEnabled,
                onCheckedChange = onPageImageCacheEnabledChange,
                subtitle = "关闭后不复用页面文件",
            )
            if (settings.pageImageCacheEnabled) {
                DiskCacheLimitRow(
                    limitMb = settings.diskCacheLimitMb,
                    onLimitChange = onDiskCacheLimitChange,
                )
            }
            CacheActionRow(
                title = "页面图片缓存占用",
                subtitle = formatCacheSize(cacheAnalysis.readerPagesBytes),
                enabled = cacheAnalysis.readerPagesBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.READER_PAGES) },
            )
            CacheActionRow(
                title = "临时页面缓存",
                subtitle = formatCacheSize(cacheAnalysis.transientReaderPagesBytes),
                enabled = cacheAnalysis.transientReaderPagesBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.TRANSIENT_READER_PAGES) },
            )
            CacheActionRow(
                title = "书架封面缓存",
                subtitle = formatCacheSize(cacheAnalysis.libraryCoversBytes),
                enabled = cacheAnalysis.libraryCoversBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.LIBRARY_COVERS) },
            )
            CacheActionRow(
                title = "影视库缩略图缓存",
                subtitle = formatCacheSize(cacheAnalysis.videoThumbnailsBytes),
                enabled = cacheAnalysis.videoThumbnailsBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.VIDEO_THUMBNAILS) },
            )
            CacheActionRow(
                title = "视频字幕缓存",
                subtitle = formatCacheSize(cacheAnalysis.videoSubtitlesBytes),
                enabled = cacheAnalysis.videoSubtitlesBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.VIDEO_SUBTITLES) },
            )
            CacheActionRow(
                title = "运行时代码缓存",
                subtitle = formatCacheSize(cacheAnalysis.codeCacheBytes),
                enabled = cacheAnalysis.codeCacheBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.CODE_CACHE) },
            )
            CacheActionRow(
                title = "外部缓存",
                subtitle = formatCacheSize(cacheAnalysis.externalCacheBytes),
                enabled = cacheAnalysis.externalCacheBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.EXTERNAL_CACHE) },
            )
            CacheActionRow(
                title = "其他缓存",
                subtitle = formatCacheSize(cacheAnalysis.otherBytes),
                enabled = cacheAnalysis.otherBytes > 0L,
                onClear = { onClearCacheCategory(ComicCacheCategory.OTHER) },
            )
        }
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
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 0.dp),
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
        MuBoxBoxedList(title = "漫画设置") {
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
                subtitle = "在阅读时用双指放大并拖动查看细节",
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
    onAnime4KEnabledChange: (Boolean) -> Unit,
    onAnime4KModeChange: (Anime4KMode) -> Unit,
    onAnime4KQualityChange: (Anime4KQuality) -> Unit,
    onVideoDecoderModeChange: (VideoDecoderMode) -> Unit,
    onMpvProfileModeChange: (MpvProfileMode) -> Unit,
    onVideoControlsAutoHideMillisChange: (Int) -> Unit,
    onVideoPlayerOrientationModeChange: (VideoPlayerOrientationMode) -> Unit,
    onVideoLibraryThumbnailsEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 0.dp),
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
        MuBoxBoxedList(title = "视频设置") {
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
            MuBoxSwitchRow(
                title = "Anime4K",
                checked = settings.anime4kEnabled,
                onCheckedChange = onAnime4KEnabledChange,
                subtitle = "启用 Anime4K 动画画面实时放大；不兼容时播放器会自动关闭",
            )
            DropdownRow(
                title = "Anime4K 预设",
                selected = settings.anime4kMode,
                options = Anime4KMode.entries.filterNot { it == Anime4KMode.OFF },
                label = ::anime4kModeLabel,
                onSelected = onAnime4KModeChange,
            )
            DropdownRow(
                title = "Anime4K 质量",
                selected = settings.anime4kQuality,
                options = Anime4KQuality.entries,
                label = ::anime4kQualityLabel,
                onSelected = onAnime4KQualityChange,
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

private fun anime4kModeLabel(mode: Anime4KMode): String = mode.label

private fun anime4kQualityLabel(quality: Anime4KQuality): String = quality.label

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
