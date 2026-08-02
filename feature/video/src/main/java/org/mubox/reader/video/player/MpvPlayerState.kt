package org.mubox.reader.video.player

import org.mubox.reader.core.model.settings.Anime4KProfile
import org.mubox.reader.core.model.settings.GpuApiMode
import org.mubox.reader.core.model.settings.MpvProfileMode
import org.mubox.reader.core.model.settings.VideoDecoderMode
import org.mubox.reader.core.model.settings.VideoOutputMode

data class MpvPlayerState(
    val displayName: String = "",
    val isPaused: Boolean = false,
    val durationMillis: Long = 0L,
    val positionMillis: Long = 0L,
    val errorMessage: String? = null,
    val playbackSpeed: Double = 1.0,
    val decoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    val videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO,
    val gpuApiMode: GpuApiMode = GpuApiMode.AUTO,
    val statusMessage: String? = null,
    val anime4kProfile: Anime4KProfile = Anime4KProfile.OFF,
    val anime4kPipeline: Anime4KPipeline? = null,
    val scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    val gestureState: VideoGestureState = VideoGestureState(),
    val audioTracks: List<MpvTrack> = emptyList(),
    val subtitleTracks: List<MpvTrack> = emptyList(),
    val selectedAudioTrackId: Int? = null,
    val selectedSubtitleTrackId: Int? = null,
    val audioDelayMillis: Long = 0L,
    val currentHwdec: String? = null,
    val activeHwdec: String? = null,
    val activeVideoDecoder: String? = null,
    val currentVideoOutput: String? = null,
    val currentGpuApi: String? = null,
    val currentGpuContext: String? = null,
    val decoderDroppedFrames: Long? = null,
    val outputDroppedFrames: Long? = null,
    val videoParams: VideoParams = VideoParams(),
    val videoOutParams: VideoParams = VideoParams(),
    val statisticsVisible: Boolean = false,
) {
    val hasMultipleSubtitleChoices: Boolean
        get() = subtitleTracks.size > 1
}

data class VideoPlaybackProgressState(
    val durationMillis: Long = 0L,
    val positionMillis: Long = 0L,
)

data class VideoGestureState(
    val controlsLocked: Boolean = false,
    val isTemporarySpeedActive: Boolean = false,
    val hudMessage: String? = null,
    val volumePercent: Int? = null,
    val brightnessPercent: Int? = null,
    val zoom: Float = 0f,
)

data class MpvTrack(
    val id: Int,
    val type: MpvTrackType,
    val title: String,
    val language: String? = null,
    val decoder: String? = null,
    val isSelected: Boolean = false,
    val isExternal: Boolean = false,
)

enum class MpvTrackType {
    AUDIO,
    SUBTITLE,
    VIDEO,
    UNKNOWN,
}

internal val VideoDecoderMode.hwdec: String
    get() = when (this) {
        VideoDecoderMode.AUTO -> "mediacodec,mediacodec-copy,no"
        VideoDecoderMode.SOFTWARE -> "no"
        VideoDecoderMode.HARDWARE -> "mediacodec-copy"
        VideoDecoderMode.HARDWARE_PLUS -> "mediacodec"
    }

internal val MpvProfileMode.profile: String
    get() = when (this) {
        MpvProfileMode.FAST -> "fast"
        MpvProfileMode.DEFAULT -> "default"
        MpvProfileMode.HIGH_QUALITY -> "high-quality"
        MpvProfileMode.GPU_HQ -> "gpu-hq"
        MpvProfileMode.LOW_LATENCY -> "low-latency"
        MpvProfileMode.SW_FAST -> "sw-fast"
    }

internal val VideoOutputMode.videoOutput: String
    get() = when (this) {
        VideoOutputMode.AUTO -> "gpu"
        VideoOutputMode.GPU_NEXT -> "gpu-next"
    }

internal val GpuApiMode.gpuApi: String
    get() = when (this) {
        GpuApiMode.AUTO -> "auto"
        GpuApiMode.VULKAN -> "vulkan"
    }

enum class VideoScaleMode {
    FIT,
    FILL,
    ORIGINAL,
    RATIO_16_9,
    RATIO_4_3,
}

data class VideoParams(
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val rotationDegrees: Int? = null,
    val aspectRatio: Double? = null,
    val primaries: String? = null,
    val gamma: String? = null,
)
