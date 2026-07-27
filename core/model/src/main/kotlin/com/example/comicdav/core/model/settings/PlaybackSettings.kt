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

enum class Anime4KProfile {
    OFF,
    AUTO,
    EFFICIENCY,
    EXTREME,
}

fun anime4KProfileFromLegacy(
    enabled: Boolean,
    mode: String?,
    quality: String?,
): Anime4KProfile =
    when {
        !enabled || mode == "OFF" -> Anime4KProfile.OFF
        mode == "C_PLUS" && quality == "HIGH" -> Anime4KProfile.EXTREME
        else -> Anime4KProfile.EFFICIENCY
    }

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
