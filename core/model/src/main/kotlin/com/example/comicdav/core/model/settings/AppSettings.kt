package com.example.comicdav.core.model.settings

enum class ReadingDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    VERTICAL,
    VERTICAL_CONTINUOUS,
}

enum class AppColorPalette {
    DEFAULT,
    MU_BOX_LIGHT,
    MU_BOX_DARK,
    ADWAITA_LIGHT,
    ADWAITA_BLUE_GRAY,
    ADWAITA_PURPLE,
    CINEMA_DARK,
    SEPIA,
    NIGHT,
    HIGH_CONTRAST,
}

enum class ReaderLoggingMode {
    OFF,
    SUMMARY,
    DETAIL,
}

data class ReaderSettings(
    val readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    val readerLoggingMode: ReaderLoggingMode = ReaderLoggingMode.SUMMARY,
    val avifImagesEnabled: Boolean = false,
    val autoPageEnabled: Boolean = false,
    val autoPageSpeedMillis: Int = 5_000,
    val volumeKeysTurnPagesEnabled: Boolean = false,
    val readerPinchZoomEnabled: Boolean = false,
)

data class AppearanceSettings(
    val colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
    val screenRotationLockEnabled: Boolean = false,
    val libraryCoversEnabled: Boolean = true,
)

data class StorageSettings(
    val pageImageCacheEnabled: Boolean = true,
    val diskCacheLimitMb: Int = 1024,
    val webDavPrefetchPageCount: Int = 4,
)

data class VideoSettings(
    val videoResumeEnabled: Boolean = true,
    val videoSeekOptimizationEnabled: Boolean = true,
    val videoForwardPrefetchMode: VideoForwardPrefetchMode = VideoForwardPrefetchMode.STANDARD,
    val videoProxyDiagnosticsMode: VideoProxyDiagnosticsMode = VideoProxyDiagnosticsMode.OFF,
    val videoPlayerProxyDebugInfoEnabled: Boolean = false,
    val videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO,
    val gpuApiMode: GpuApiMode = GpuApiMode.AUTO,
    val anime4kProfile: Anime4KProfile = Anime4KProfile.OFF,
    val videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    val mpvProfileMode: MpvProfileMode = MpvProfileMode.FAST,
    val videoControlsAutoHideMillis: Int = 5_000,
    val videoPlayerOrientationMode: VideoPlayerOrientationMode = VideoPlayerOrientationMode.VIDEO,
    val videoBackgroundMode: VideoBackgroundMode = VideoBackgroundMode.NONE,
    val gridVideoThumbnailsEnabled: Boolean = true,
    val videoLibraryThumbnailsEnabled: Boolean = true,
)

data class HistorySettings(
    val historyRetentionDays: Int = 90,
    val historyMaxRecords: Int = 200,
)

data class AppSettings(
    val reader: ReaderSettings,
    val appearance: AppearanceSettings,
    val storage: StorageSettings,
    val video: VideoSettings,
    val history: HistorySettings,
) {
    /**
     * Flat construction stays source-compatible while callers migrate to grouped settings.
     * The grouped primary constructor is the canonical representation used by persistence.
     */
    constructor(
        readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
        readerLoggingMode: ReaderLoggingMode = ReaderLoggingMode.SUMMARY,
        colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
        avifImagesEnabled: Boolean = false,
        autoPageEnabled: Boolean = false,
        autoPageSpeedMillis: Int = 5_000,
        screenRotationLockEnabled: Boolean = false,
        volumeKeysTurnPagesEnabled: Boolean = false,
        readerPinchZoomEnabled: Boolean = false,
        pageImageCacheEnabled: Boolean = true,
        diskCacheLimitMb: Int = 1024,
        webDavPrefetchPageCount: Int = 4,
        libraryCoversEnabled: Boolean = true,
        videoResumeEnabled: Boolean = true,
        videoSeekOptimizationEnabled: Boolean = true,
        videoForwardPrefetchMode: VideoForwardPrefetchMode = VideoForwardPrefetchMode.STANDARD,
        videoProxyDiagnosticsMode: VideoProxyDiagnosticsMode = VideoProxyDiagnosticsMode.OFF,
        videoPlayerProxyDebugInfoEnabled: Boolean = false,
        videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO,
        gpuApiMode: GpuApiMode = GpuApiMode.AUTO,
        anime4kProfile: Anime4KProfile = Anime4KProfile.OFF,
        videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
        mpvProfileMode: MpvProfileMode = MpvProfileMode.FAST,
        videoControlsAutoHideMillis: Int = 5_000,
        videoPlayerOrientationMode: VideoPlayerOrientationMode = VideoPlayerOrientationMode.VIDEO,
        videoBackgroundMode: VideoBackgroundMode = VideoBackgroundMode.NONE,
        gridVideoThumbnailsEnabled: Boolean = true,
        videoLibraryThumbnailsEnabled: Boolean = true,
        historyRetentionDays: Int = 90,
        historyMaxRecords: Int = 200,
    ) : this(
        reader = ReaderSettings(
            readingDirection = readingDirection,
            readerLoggingMode = readerLoggingMode,
            avifImagesEnabled = avifImagesEnabled,
            autoPageEnabled = autoPageEnabled,
            autoPageSpeedMillis = autoPageSpeedMillis,
            volumeKeysTurnPagesEnabled = volumeKeysTurnPagesEnabled,
            readerPinchZoomEnabled = readerPinchZoomEnabled,
        ),
        appearance = AppearanceSettings(
            colorPalette = colorPalette,
            screenRotationLockEnabled = screenRotationLockEnabled,
            libraryCoversEnabled = libraryCoversEnabled,
        ),
        storage = StorageSettings(
            pageImageCacheEnabled = pageImageCacheEnabled,
            diskCacheLimitMb = diskCacheLimitMb,
            webDavPrefetchPageCount = webDavPrefetchPageCount,
        ),
        video = VideoSettings(
            videoResumeEnabled = videoResumeEnabled,
            videoSeekOptimizationEnabled = videoSeekOptimizationEnabled,
            videoForwardPrefetchMode = videoForwardPrefetchMode,
            videoProxyDiagnosticsMode = videoProxyDiagnosticsMode,
            videoPlayerProxyDebugInfoEnabled = videoPlayerProxyDebugInfoEnabled,
            videoOutputMode = videoOutputMode,
            gpuApiMode = gpuApiMode,
            anime4kProfile = anime4kProfile,
            videoDecoderMode = videoDecoderMode,
            mpvProfileMode = mpvProfileMode,
            videoControlsAutoHideMillis = videoControlsAutoHideMillis,
            videoPlayerOrientationMode = videoPlayerOrientationMode,
            videoBackgroundMode = videoBackgroundMode,
            gridVideoThumbnailsEnabled = gridVideoThumbnailsEnabled,
            videoLibraryThumbnailsEnabled = videoLibraryThumbnailsEnabled,
        ),
        history = HistorySettings(
            historyRetentionDays = historyRetentionDays,
            historyMaxRecords = historyMaxRecords,
        ),
    )

    val readingDirection: ReadingDirection get() = reader.readingDirection
    val readerLoggingMode: ReaderLoggingMode get() = reader.readerLoggingMode
    val colorPalette: AppColorPalette get() = appearance.colorPalette
    val avifImagesEnabled: Boolean get() = reader.avifImagesEnabled
    val autoPageEnabled: Boolean get() = reader.autoPageEnabled
    val autoPageSpeedMillis: Int get() = reader.autoPageSpeedMillis
    val screenRotationLockEnabled: Boolean get() = appearance.screenRotationLockEnabled
    val volumeKeysTurnPagesEnabled: Boolean get() = reader.volumeKeysTurnPagesEnabled
    val readerPinchZoomEnabled: Boolean get() = reader.readerPinchZoomEnabled
    val pageImageCacheEnabled: Boolean get() = storage.pageImageCacheEnabled
    val diskCacheLimitMb: Int get() = storage.diskCacheLimitMb
    val webDavPrefetchPageCount: Int get() = storage.webDavPrefetchPageCount
    val libraryCoversEnabled: Boolean get() = appearance.libraryCoversEnabled
    val videoResumeEnabled: Boolean get() = video.videoResumeEnabled
    val videoSeekOptimizationEnabled: Boolean get() = video.videoSeekOptimizationEnabled
    val videoForwardPrefetchMode: VideoForwardPrefetchMode get() = video.videoForwardPrefetchMode
    val videoProxyDiagnosticsMode: VideoProxyDiagnosticsMode get() = video.videoProxyDiagnosticsMode
    val videoPlayerProxyDebugInfoEnabled: Boolean get() = video.videoPlayerProxyDebugInfoEnabled
    val videoOutputMode: VideoOutputMode get() = video.videoOutputMode
    val gpuApiMode: GpuApiMode get() = video.gpuApiMode
    val anime4kProfile: Anime4KProfile get() = video.anime4kProfile
    val videoDecoderMode: VideoDecoderMode get() = video.videoDecoderMode
    val mpvProfileMode: MpvProfileMode get() = video.mpvProfileMode
    val videoControlsAutoHideMillis: Int get() = video.videoControlsAutoHideMillis
    val videoPlayerOrientationMode: VideoPlayerOrientationMode get() = video.videoPlayerOrientationMode
    val videoBackgroundMode: VideoBackgroundMode get() = video.videoBackgroundMode
    val gridVideoThumbnailsEnabled: Boolean get() = video.gridVideoThumbnailsEnabled
    val videoLibraryThumbnailsEnabled: Boolean get() = video.videoLibraryThumbnailsEnabled
    val historyRetentionDays: Int get() = history.historyRetentionDays
    val historyMaxRecords: Int get() = history.historyMaxRecords

    val loggingEnabled: Boolean
        get() = reader.readerLoggingMode != ReaderLoggingMode.OFF
}
