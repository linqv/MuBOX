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

data class AppSettings(
    val readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    val readerLoggingMode: ReaderLoggingMode = ReaderLoggingMode.SUMMARY,
    val colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
    val avifImagesEnabled: Boolean = false,
    val autoPageEnabled: Boolean = false,
    val autoPageSpeedMillis: Int = 5_000,
    val screenRotationLockEnabled: Boolean = false,
    val volumeKeysTurnPagesEnabled: Boolean = false,
    val readerPinchZoomEnabled: Boolean = false,
    val pageImageCacheEnabled: Boolean = true,
    val diskCacheLimitMb: Int = 1024,
    val webDavPrefetchPageCount: Int = 4,
    val libraryCoversEnabled: Boolean = true,
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
    val historyRetentionDays: Int = 90,
    val historyMaxRecords: Int = 200,
) {
    val loggingEnabled: Boolean
        get() = readerLoggingMode != ReaderLoggingMode.OFF
}
