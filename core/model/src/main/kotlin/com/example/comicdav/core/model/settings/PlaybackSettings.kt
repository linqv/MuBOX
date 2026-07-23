package com.example.comicdav.core.model.settings

enum class VideoDecoderMode {
    AUTO,
    SOFTWARE,
    HARDWARE,
    HARDWARE_PLUS,
}

enum class MpvProfileMode {
    FAST,
    DEFAULT,
    HIGH_QUALITY,
    GPU_HQ,
    LOW_LATENCY,
    SW_FAST,
}

enum class VideoOutputMode {
    AUTO,
    GPU_NEXT,
}

enum class GpuApiMode {
    AUTO,
    VULKAN,
}

enum class Anime4KMode {
    OFF,
    A,
    B,
    C,
    A_PLUS,
    B_PLUS,
    C_PLUS,
}

enum class Anime4KQuality {
    FAST,
    BALANCED,
    HIGH,
}

data class Anime4KSettings(
    val enabled: Boolean = false,
    val mode: Anime4KMode = Anime4KMode.A,
    val quality: Anime4KQuality = Anime4KQuality.FAST,
)

enum class VideoPlayerOrientationMode {
    VIDEO,
    PORTRAIT,
    LANDSCAPE,
    SENSOR,
}

enum class VideoBackgroundMode {
    NONE,
    BACKGROUND_PLAY,
    RESUME_ON_RETURN,
}

fun playerControlAutoHideOptionsMillis(): List<Int> =
    listOf(0, 3_000, 5_000, 8_000, 10_000)
