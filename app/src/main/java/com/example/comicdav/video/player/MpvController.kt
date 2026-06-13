package com.example.comicdav.video.player

import com.example.comicdav.video.VideoSubtitleOpenRequest
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
    val anime4kEnabled: Boolean = false,
    val anime4kMode: Anime4KMode = Anime4KMode.A,
    val anime4kQuality: Anime4KQuality = Anime4KQuality.FAST,
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

enum class VideoDecoderMode(val hwdec: String) {
    AUTO("mediacodec,mediacodec-copy,no"),
    SOFTWARE("no"),
    HARDWARE("mediacodec-copy"),
    HARDWARE_PLUS("mediacodec"),
}

enum class MpvProfileMode(val profile: String) {
    FAST("fast"),
    DEFAULT("default"),
    HIGH_QUALITY("high-quality"),
    GPU_HQ("gpu-hq"),
    LOW_LATENCY("low-latency"),
    SW_FAST("sw-fast"),
}

internal fun videoDecoderModeLabel(mode: VideoDecoderMode): String =
    when (mode) {
        VideoDecoderMode.AUTO -> "auto"
        VideoDecoderMode.SOFTWARE -> "SW"
        VideoDecoderMode.HARDWARE -> "HW"
        VideoDecoderMode.HARDWARE_PLUS -> "HW+"
    }

enum class VideoOutputMode(val videoOutput: String) {
    AUTO("gpu"),
    GPU_NEXT("gpu-next"),
}

enum class GpuApiMode(val gpuApi: String) {
    AUTO("auto"),
    VULKAN("vulkan"),
}

internal fun videoOutputModeLabel(mode: VideoOutputMode): String =
    when (mode) {
        VideoOutputMode.AUTO -> "auto"
        VideoOutputMode.GPU_NEXT -> "gpu-next"
    }

internal fun gpuApiModeLabel(mode: GpuApiMode): String =
    when (mode) {
        GpuApiMode.AUTO -> "auto"
        GpuApiMode.VULKAN -> "vulkan"
    }

internal fun mpvProfileModeLabel(mode: MpvProfileMode): String =
    when (mode) {
        MpvProfileMode.FAST -> "Fast"
        MpvProfileMode.DEFAULT -> "Default"
        MpvProfileMode.HIGH_QUALITY -> "High Quality"
        MpvProfileMode.GPU_HQ -> "GPU HQ"
        MpvProfileMode.LOW_LATENCY -> "Low Latency"
        MpvProfileMode.SW_FAST -> "SW Fast"
    }

internal fun playerControlAutoHideOptionsMillis(): List<Int> = listOf(0, 3_000, 5_000, 8_000, 10_000)

internal fun playerControlAutoHideLabel(millis: Int): String =
    if (millis <= 0) "不自动隐藏" else "${millis / 1_000} 秒"

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
)

interface MpvEngine {
    fun loadFile(uri: String) {
        command("loadfile", uri)
    }

    fun loadFile(uri: String, afterLoadfile: () -> Unit) {
        loadFile(uri)
        afterLoadfile()
    }

    fun command(vararg args: String)
    fun setPropertyString(name: String, value: String)
    fun setPropertyBoolean(name: String, value: Boolean)
    fun setPropertyInt(name: String, value: Int) = Unit
    fun setPropertyDouble(name: String, value: Double) = Unit
    fun setOptionString(name: String, value: String) = Unit
    fun destroy()
}

object RealMpvEngine : MpvEngine {
    override fun command(vararg args: String) {
        MPVLib.command(*args)
    }

    override fun setPropertyString(name: String, value: String) {
        MPVLib.setPropertyString(name, value)
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        MPVLib.setPropertyBoolean(name, value)
    }

    override fun setPropertyInt(name: String, value: Int) {
        MPVLib.setPropertyInt(name, value)
    }

    override fun setPropertyDouble(name: String, value: Double) {
        MPVLib.setPropertyDouble(name, value)
    }

    override fun setOptionString(name: String, value: String) {
        MPVLib.setOptionString(name, value)
    }

    override fun destroy() {
        MPVLib.destroy()
    }
}

class ViewBackedMpvEngine(
    private val view: MpvFileLoader,
) : MpvEngine {
    override fun loadFile(uri: String) {
        view.playFileWhenReady(uri) {}
    }

    override fun loadFile(uri: String, afterLoadfile: () -> Unit) {
        view.playFileWhenReady(uri, afterLoadfile)
    }

    override fun command(vararg args: String) {
        MPVLib.command(*args)
    }

    override fun setPropertyString(name: String, value: String) {
        MPVLib.setPropertyString(name, value)
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        MPVLib.setPropertyBoolean(name, value)
    }

    override fun setPropertyInt(name: String, value: Int) {
        MPVLib.setPropertyInt(name, value)
    }

    override fun setPropertyDouble(name: String, value: Double) {
        MPVLib.setPropertyDouble(name, value)
    }

    override fun setOptionString(name: String, value: String) {
        MPVLib.setOptionString(name, value)
    }

    override fun destroy() {
        view.destroy()
    }
}

class MpvController(
    private val engine: MpvEngine,
    private val anime4kShaderProvider: Anime4KShaderProvider = EmptyAnime4KShaderProvider,
    initialAnime4KSettings: Anime4KSettings = Anime4KSettings(),
    initialAnime4KStatusMessage: String? = null,
) {
    private val _state = MutableStateFlow(
        MpvPlayerState(
            statusMessage = initialAnime4KStatusMessage,
            anime4kEnabled = initialAnime4KSettings.enabled && initialAnime4KSettings.mode != Anime4KMode.OFF,
            anime4kMode = initialAnime4KSettings.mode,
            anime4kQuality = initialAnime4KSettings.quality,
        ),
    )
    val state: StateFlow<MpvPlayerState> = _state.asStateFlow()
    private val _progress = MutableStateFlow(VideoPlaybackProgressState())
    val progress: StateFlow<VideoPlaybackProgressState> = _progress.asStateFlow()
    @Volatile
    private var isCleaning = false
    @Volatile
    private var isDestroyed = false
    private var pendingResumeSeekMillis: Long? = null
    private var speedBeforeTemporary: Double? = null
    private var horizontalSwipeStartPositionMillis: Long? = null
    private var horizontalSwipeAccumulatedFraction: Double = 0.0

    fun load(
        uri: String,
        displayName: String,
        startPositionMillis: Long = 0L,
        subtitles: List<VideoSubtitleOpenRequest> = emptyList(),
        onFileLoaded: () -> Unit = {},
    ) {
        if (!canWriteEngine()) return
        pendingResumeSeekMillis = startPositionMillis.takeIf { it > 0L }
        _progress.value = VideoPlaybackProgressState(positionMillis = startPositionMillis.coerceAtLeast(0L))
        _state.value = _state.value.copy(
            displayName = displayName,
            durationMillis = 0L,
            positionMillis = startPositionMillis.coerceAtLeast(0L),
            errorMessage = null,
            activeHwdec = null,
            activeVideoDecoder = null,
            videoParams = VideoParams(),
            videoOutParams = VideoParams(),
        )
        engine.setPropertyString("force-media-title", displayName)
        engine.loadFile(uri) {
            onFileLoaded()
            addSubtitles(subtitles)
        }
    }

    fun addSubtitles(subtitles: List<VideoSubtitleOpenRequest>) {
        subtitles.forEachIndexed { index, subtitle ->
            addSubtitle(
                subtitle = subtitle,
                select = index == 0,
            )
        }
    }

    fun addSubtitle(subtitle: VideoSubtitleOpenRequest, select: Boolean) {
        if (!canWriteEngine()) return
        val flag = if (select) "select" else "auto"
        engine.command("sub-add", subtitle.uri, flag, subtitle.displayName)
    }

    fun setPaused(paused: Boolean) {
        if (!canWriteEngine()) return
        _state.value = _state.value.copy(isPaused = paused)
        engine.setPropertyBoolean("pause", paused)
    }

    fun togglePlayPause() {
        setPaused(!_state.value.isPaused)
    }

    fun seekTo(positionMillis: Long) {
        if (!canWriteEngine()) return
        val durationMillis = _progress.value.durationMillis
        val clampedPosition = when {
            durationMillis > 0L -> positionMillis.coerceIn(0L, durationMillis)
            else -> positionMillis.coerceAtLeast(0L)
        }
        _progress.value = _progress.value.copy(positionMillis = clampedPosition)
        _state.value = _state.value.copy(positionMillis = clampedPosition)
        engine.command("seek", (clampedPosition / 1000.0).toString(), "absolute")
    }

    fun setPlaybackSpeed(speed: Double) {
        if (!canWriteEngine()) return
        applyPlaybackSpeed(speed)
        if (!_state.value.gestureState.isTemporarySpeedActive) {
            speedBeforeTemporary = null
        }
    }

    fun beginTemporarySpeed(speed: Double) {
        if (!canWriteEngine()) return
        val clampedSpeed = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        if (speedBeforeTemporary == null) {
            speedBeforeTemporary = _state.value.playbackSpeed
        }
        _state.value = _state.value.copy(
            gestureState = _state.value.gestureState.copy(
                isTemporarySpeedActive = true,
                hudMessage = speedHudText(clampedSpeed),
            ),
        )
        applyPlaybackSpeed(clampedSpeed)
    }

    fun adjustTemporarySpeed(delta: Double) {
        if (!canWriteEngine() || !_state.value.gestureState.isTemporarySpeedActive) return
        val nextSpeed = (_state.value.playbackSpeed + delta)
            .coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        _state.value = _state.value.copy(
            gestureState = _state.value.gestureState.copy(hudMessage = speedHudText(nextSpeed)),
        )
        applyPlaybackSpeed(nextSpeed)
    }

    fun endTemporarySpeed() {
        if (!canWriteEngine()) return
        val restoreSpeed = speedBeforeTemporary ?: _state.value.playbackSpeed
        speedBeforeTemporary = null
        _state.value = _state.value.copy(
            gestureState = _state.value.gestureState.copy(
                isTemporarySpeedActive = false,
                hudMessage = null,
            ),
        )
        applyPlaybackSpeed(restoreSpeed)
    }

    fun selectAudioTrack(trackId: Int) {
        if (!canWriteEngine()) return
        engine.setPropertyInt("aid", trackId)
        _state.value = _state.value.copy(selectedAudioTrackId = trackId)
    }

    fun selectSubtitleTrack(trackId: Int) {
        if (!canWriteEngine()) return
        engine.setPropertyInt("sid", trackId)
        _state.value = _state.value.copy(selectedSubtitleTrackId = trackId)
    }

    fun disableSubtitles() {
        if (!canWriteEngine()) return
        engine.setPropertyString("sid", "no")
        _state.value = _state.value.copy(selectedSubtitleTrackId = null)
    }

    fun adjustAudioDelay(deltaMillis: Long) {
        setAudioDelay(_state.value.audioDelayMillis + deltaMillis)
    }

    fun resetAudioDelay() {
        setAudioDelay(0L)
    }

    fun setDecoderMode(mode: VideoDecoderMode) {
        if (!canWriteEngine()) return
        engine.setPropertyString("hwdec", mode.hwdec)
        _state.value = _state.value.copy(decoderMode = mode, currentHwdec = mode.hwdec)
    }

    fun setVideoOutputMode(mode: VideoOutputMode) {
        if (!canWriteEngine()) return
        engine.setOptionString("vo", mode.videoOutput)
        _state.value = _state.value.copy(
            videoOutputMode = mode,
            currentVideoOutput = mode.videoOutput,
        )
    }

    fun setGpuApiMode(mode: GpuApiMode) {
        if (!canWriteEngine()) return
        engine.setOptionString("gpu-api", mode.gpuApi)
        _state.value = _state.value.copy(
            gpuApiMode = mode,
            currentGpuApi = mode.gpuApi,
        )
    }

    fun setAnime4KEnabled(enabled: Boolean) {
        if (!canWriteEngine()) return
        val state = _state.value
        applyAnime4KSettings(
            Anime4KSettings(
                enabled = enabled,
                mode = state.anime4kMode,
                quality = state.anime4kQuality,
            ),
        )
    }

    fun setAnime4KMode(mode: Anime4KMode) {
        if (!canWriteEngine()) return
        val state = _state.value
        applyAnime4KSettings(
            Anime4KSettings(
                enabled = mode != Anime4KMode.OFF,
                mode = mode,
                quality = state.anime4kQuality,
            ),
        )
    }

    fun setAnime4KQuality(quality: Anime4KQuality) {
        if (!canWriteEngine()) return
        val state = _state.value
        applyAnime4KSettings(
            Anime4KSettings(
                enabled = state.anime4kEnabled,
                mode = state.anime4kMode,
                quality = quality,
            ),
        )
    }

    fun setStartupRendererState(
        videoOutputMode: VideoOutputMode,
        gpuApiMode: GpuApiMode,
        decoderMode: VideoDecoderMode,
    ) {
        _state.value = _state.value.copy(
            videoOutputMode = videoOutputMode,
            gpuApiMode = gpuApiMode,
            decoderMode = decoderMode,
            currentVideoOutput = videoOutputMode.videoOutput,
            currentGpuApi = gpuApiMode.gpuApi,
            currentHwdec = decoderMode.hwdec,
        )
    }

    fun setStartupStatusMessage(message: String?) {
        _state.value = _state.value.copy(statusMessage = message)
    }

    fun setScaleMode(mode: VideoScaleMode) {
        if (!canWriteEngine()) return
        when (mode) {
            VideoScaleMode.FIT -> {
                engine.setPropertyDouble("video-aspect-override", -1.0)
                engine.setPropertyDouble("panscan", 0.0)
            }
            VideoScaleMode.FILL -> {
                engine.setPropertyDouble("video-aspect-override", -1.0)
                engine.setPropertyDouble("panscan", 1.0)
            }
            VideoScaleMode.ORIGINAL -> {
                engine.setPropertyDouble("video-aspect-override", -1.0)
                engine.setPropertyDouble("panscan", 0.0)
                engine.setPropertyDouble("video-zoom", 0.0)
            }
            VideoScaleMode.RATIO_16_9 -> {
                engine.setPropertyDouble("video-aspect-override", 16.0 / 9.0)
                engine.setPropertyDouble("panscan", 0.0)
            }
            VideoScaleMode.RATIO_4_3 -> {
                engine.setPropertyDouble("video-aspect-override", 4.0 / 3.0)
                engine.setPropertyDouble("panscan", 0.0)
            }
        }
        _state.value = _state.value.copy(scaleMode = mode)
    }

    fun setControlsLocked(locked: Boolean) {
        _state.value = _state.value.copy(
            gestureState = _state.value.gestureState.copy(
                controlsLocked = locked,
                hudMessage = if (locked) "控制已锁定" else null,
            ),
        )
    }

    fun adjustGestureVolume(deltaPercent: Int) {
        if (!canHandleGesture()) return
        val nextVolume = ((_state.value.gestureState.volumePercent ?: DEFAULT_GESTURE_PERCENT) + deltaPercent)
            .coerceIn(MIN_GESTURE_PERCENT, MAX_GESTURE_PERCENT)
        engine.setPropertyDouble("volume", nextVolume.toDouble())
        _state.value = _state.value.copy(
            gestureState = _state.value.gestureState.copy(
                volumePercent = nextVolume,
                hudMessage = "音量 $nextVolume%",
            ),
        )
    }

    fun adjustGestureBrightness(deltaPercent: Int) {
        if (!canHandleGesture()) return
        val nextBrightness = ((_state.value.gestureState.brightnessPercent ?: DEFAULT_GESTURE_PERCENT) + deltaPercent)
            .coerceIn(MIN_GESTURE_PERCENT, MAX_GESTURE_PERCENT)
        _state.value = _state.value.copy(
            gestureState = _state.value.gestureState.copy(
                brightnessPercent = nextBrightness,
                hudMessage = "亮度 $nextBrightness%",
            ),
        )
    }

    fun handleDoubleTapSeek(forward: Boolean) {
        if (!canHandleGesture()) return
        val deltaMillis = if (forward) DOUBLE_TAP_SEEK_MILLIS else -DOUBLE_TAP_SEEK_MILLIS
        val hudText = if (forward) "快进 10秒" else "快退 10秒"
        seekTo(_progress.value.positionMillis + deltaMillis)
        _state.value = _state.value.copy(
            gestureState = _state.value.gestureState.copy(hudMessage = hudText),
        )
    }

    fun beginHorizontalSwipeSeek() {
        if (!canHandleGesture()) return
        horizontalSwipeStartPositionMillis = _progress.value.positionMillis
        horizontalSwipeAccumulatedFraction = 0.0
    }

    fun handleHorizontalSwipeSeek(deltaFraction: Float) {
        if (!canHandleGesture()) return
        val durationMillis = _progress.value.durationMillis
        if (durationMillis <= 0L || deltaFraction == 0f) return

        val startPosition = horizontalSwipeStartPositionMillis ?: _progress.value.positionMillis.also {
            horizontalSwipeStartPositionMillis = it
            horizontalSwipeAccumulatedFraction = 0.0
        }
        horizontalSwipeAccumulatedFraction += deltaFraction.toDouble()
        val requestedDelta = (durationMillis * horizontalSwipeAccumulatedFraction).roundToLong()

        val targetPosition = (startPosition + requestedDelta).coerceIn(0L, durationMillis)
        val currentPosition = _progress.value.positionMillis
        val actualDelta = targetPosition - startPosition
        if (targetPosition == currentPosition && actualDelta == 0L) return

        if (targetPosition != currentPosition) {
            seekTo(targetPosition)
        }
        if (actualDelta == 0L) return

        val directionText = if (actualDelta > 0L) "快进" else "快退"
        _state.value = _state.value.copy(
            gestureState = _state.value.gestureState.copy(
                hudMessage = "$directionText ${formatGestureSeekDelta(abs(actualDelta))}",
            ),
        )
    }

    fun endHorizontalSwipeSeek() {
        horizontalSwipeStartPositionMillis = null
        horizontalSwipeAccumulatedFraction = 0.0
    }

    fun adjustGestureZoom(delta: Float) {
        if (!canHandleGesture()) return
        val nextZoom = ((_state.value.gestureState.zoom + delta) * 100).roundToInt() / 100.0
        val clampedZoom = nextZoom.coerceIn(MIN_VIDEO_ZOOM.toDouble(), MAX_VIDEO_ZOOM.toDouble())
        engine.setPropertyDouble("video-zoom", clampedZoom)
        _state.value = _state.value.copy(
            gestureState = _state.value.gestureState.copy(
                zoom = clampedZoom.toFloat(),
                hudMessage = "缩放 ${(clampedZoom * 100).roundToInt()}%",
            ),
        )
    }

    fun clearGestureHud() {
        if (isDestroyed) return
        _state.value = _state.value.copy(
            gestureState = _state.value.gestureState.copy(hudMessage = null),
        )
    }

    fun onTrackListChanged(trackList: MPVNode) {
        val tracks = parseTrackList(trackList)
        val selectedAudioId = tracks.firstOrNull { it.type == MpvTrackType.AUDIO && it.isSelected }?.id
        val selectedSubtitleId = tracks.firstOrNull { it.type == MpvTrackType.SUBTITLE && it.isSelected }?.id
        val selectedVideoDecoder = tracks.firstOrNull { it.type == MpvTrackType.VIDEO && it.isSelected }
            ?.decoder
        _state.value = _state.value.copy(
            audioTracks = tracks.filter { it.type == MpvTrackType.AUDIO },
            subtitleTracks = tracks.filter { it.type == MpvTrackType.SUBTITLE },
            selectedAudioTrackId = selectedAudioId ?: _state.value.selectedAudioTrackId,
            selectedSubtitleTrackId = selectedSubtitleId ?: _state.value.selectedSubtitleTrackId,
            activeVideoDecoder = selectedVideoDecoder ?: _state.value.activeVideoDecoder,
        )
    }

    fun onSpeedChanged(speed: Double) {
        _state.value = _state.value.copy(playbackSpeed = speed.coerceAtLeast(MIN_PLAYBACK_SPEED))
    }

    fun onAudioTrackChanged(trackId: Int?) {
        _state.value = _state.value.copy(selectedAudioTrackId = trackId)
    }

    fun onSubtitleTrackChanged(trackId: Int?) {
        _state.value = _state.value.copy(selectedSubtitleTrackId = trackId)
    }

    fun onAudioDelayChanged(delaySeconds: Double) {
        _state.value = _state.value.copy(audioDelayMillis = secondsToMillisSigned(delaySeconds))
    }

    fun onHwdecChanged(value: String) {
        _state.value = _state.value.copy(currentHwdec = value)
    }

    fun onActiveHwdecChanged(value: String) {
        _state.value = _state.value.copy(activeHwdec = value.takeIf { it.isNotBlank() })
    }

    fun onActiveVideoDecoderChanged(value: String) {
        _state.value = _state.value.copy(activeVideoDecoder = value.takeIf { it.isNotBlank() })
    }

    fun onVoChanged(value: String) {
        _state.value = _state.value.copy(currentVideoOutput = value)
    }

    fun onGpuApiChanged(value: String) {
        _state.value = _state.value.copy(currentGpuApi = value)
    }

    fun onVideoParamsChanged(params: MPVNode) {
        val parsedParams = parseVideoParams(params)
        _state.value = _state.value.let { state ->
            state.copy(videoParams = parsedParams.withAspectFrom(state.videoParams))
        }
    }

    fun onVideoOutParamsChanged(params: MPVNode) {
        val parsedParams = parseVideoParams(params)
        _state.value = _state.value.let { state ->
            state.copy(videoOutParams = parsedParams.withAspectFrom(state.videoOutParams))
        }
    }

    fun onVideoAspectChanged(aspectRatio: Double) {
        _state.value = _state.value.let { state ->
            state.copy(
                videoParams = state.videoParams.copy(aspectRatio = aspectRatio.validAspectRatio()),
            )
        }
    }

    fun onVideoOutAspectChanged(aspectRatio: Double) {
        _state.value = _state.value.let { state ->
            state.copy(
                videoOutParams = state.videoOutParams.copy(aspectRatio = aspectRatio.validAspectRatio()),
            )
        }
    }

    fun onPauseChanged(paused: Boolean) {
        _state.value = _state.value.copy(isPaused = paused)
    }

    fun markPaused(paused: Boolean) {
        _state.value = _state.value.copy(isPaused = paused)
    }

    fun onPlaybackEnded() {
        val durationMillis = _progress.value.durationMillis
        val endedPositionMillis = if (durationMillis > 0L) durationMillis else 0L
        _progress.value = _progress.value.copy(positionMillis = endedPositionMillis)
        _state.value = _state.value.copy(
            isPaused = true,
            positionMillis = endedPositionMillis,
        )
    }

    fun onDurationChanged(durationSeconds: Double) {
        val durationMillis = secondsToMillis(durationSeconds)
        _progress.value = _progress.value.copy(durationMillis = durationMillis)
        _state.value = _state.value.copy(durationMillis = durationMillis)
        seekToPendingResumePosition()
    }

    fun onPositionChanged(positionSeconds: Double) {
        _progress.value = _progress.value.copy(positionMillis = secondsToMillis(positionSeconds))
    }

    fun onVolumeChanged(volume: Double) {
        _state.value = _state.value.copy(
            gestureState = _state.value.gestureState.copy(
                volumePercent = volume.roundToInt().coerceIn(MIN_GESTURE_PERCENT, MAX_GESTURE_PERCENT),
            ),
        )
    }

    fun onError(message: String) {
        _state.value = _state.value.copy(errorMessage = message)
    }

    fun destroy() {
        if (isDestroyed || isCleaning) return
        isCleaning = true
        val cleanupFailures = mutableListOf<Exception>()
        _state.value = _state.value.copy(isPaused = true)
        try {
            attemptCleanup(cleanupFailures) {
                engine.setPropertyBoolean("pause", true)
            }
            attemptCleanup(cleanupFailures) {
                engine.command("stop")
            }
            attemptCleanup(cleanupFailures) {
                engine.command("quit")
            }
        } finally {
            attemptCleanup(cleanupFailures) {
                engine.destroy()
            }
            isDestroyed = true
            isCleaning = false
            reportCleanupFailures(cleanupFailures)
        }
    }

    private fun canWriteEngine(): Boolean = !isCleaning && !isDestroyed

    private fun canHandleGesture(): Boolean =
        canWriteEngine() && !_state.value.gestureState.controlsLocked

    private fun applyPlaybackSpeed(speed: Double) {
        val clampedSpeed = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        engine.setPropertyDouble("speed", clampedSpeed)
        _state.value = _state.value.copy(playbackSpeed = clampedSpeed)
    }

    private fun applyAnime4KSettings(settings: Anime4KSettings) {
        if (!settings.enabled || settings.mode == Anime4KMode.OFF) {
            engine.setPropertyString("glsl-shaders", "")
            _state.value = _state.value.copy(
                anime4kEnabled = false,
                anime4kMode = settings.mode,
                anime4kQuality = settings.quality,
                statusMessage = null,
            )
            return
        }

        if (_state.value.videoOutputMode == VideoOutputMode.GPU_NEXT && _state.value.gpuApiMode != GpuApiMode.VULKAN) {
            engine.setPropertyString("glsl-shaders", "")
            _state.value = _state.value.copy(
                anime4kEnabled = false,
                anime4kMode = settings.mode,
                anime4kQuality = settings.quality,
                statusMessage = ANIME4K_GPU_NEXT_OPENGL_INCOMPATIBLE_STATUS,
            )
            return
        }

        val shaderChain = anime4kShaderProvider.shaderChain(settings)
        if (shaderChain.isBlank()) {
            engine.setPropertyString("glsl-shaders", "")
            _state.value = _state.value.copy(
                anime4kEnabled = false,
                anime4kMode = settings.mode,
                anime4kQuality = settings.quality,
                statusMessage = "Anime4K 着色器加载失败",
            )
            return
        }

        engine.setPropertyString("glsl-shaders", shaderChain)
        _state.value = _state.value.copy(
            anime4kEnabled = true,
            anime4kMode = settings.mode,
            anime4kQuality = settings.quality,
            statusMessage = null,
        )
    }

    private fun speedHudText(speed: Double): String {
        val roundedSpeed = (speed * 100).roundToInt() / 100.0
        val text = if (roundedSpeed % 1.0 == 0.0) {
            roundedSpeed.roundToInt().toString()
        } else {
            roundedSpeed.toString().trimEnd('0').trimEnd('.')
        }
        return "${text}x"
    }

    private fun formatGestureSeekDelta(deltaMillis: Long): String {
        val totalSeconds = (deltaMillis / 1000L).coerceAtLeast(1L)
        if (totalSeconds < 60L) return "${totalSeconds}秒"

        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (seconds == 0L) {
            "${minutes}分"
        } else {
            "${minutes}分${seconds}秒"
        }
    }

    private fun setAudioDelay(delayMillis: Long) {
        if (!canWriteEngine()) return
        engine.setPropertyDouble("audio-delay", delayMillis / 1000.0)
        _state.value = _state.value.copy(audioDelayMillis = delayMillis)
    }

    private fun parseTrackList(trackList: MPVNode): List<MpvTrack> =
        trackList.asArray().orEmpty().mapNotNull(::parseTrack)

    private fun parseTrack(node: MPVNode): MpvTrack? {
        val id = node.nodeInt("id")?.toInt() ?: return null
        val rawType = node.nodeString("type").orEmpty()
        val type = when (rawType) {
            "audio" -> MpvTrackType.AUDIO
            "sub" -> MpvTrackType.SUBTITLE
            "video" -> MpvTrackType.VIDEO
            else -> MpvTrackType.UNKNOWN
        }
        val title = node.nodeString("title")
            ?: node.nodeString("external-filename")?.substringAfterLast('/')
            ?: node.nodeString("lang")
            ?: "$rawType $id"
        return MpvTrack(
            id = id,
            type = type,
            title = title,
            language = node.nodeString("lang"),
            decoder = node.nodeString("decoder"),
            isSelected = node.nodeBoolean("selected") == true,
            isExternal = node.nodeBoolean("external") == true,
        )
    }

    private fun parseVideoParams(node: MPVNode): VideoParams =
        VideoParams(
            codec = node.nodeString("codec"),
            width = node.nodeInt("w")?.toInt() ?: node.nodeInt("dw")?.toInt(),
            height = node.nodeInt("h")?.toInt() ?: node.nodeInt("dh")?.toInt(),
            frameRate = node.nodeDouble("fps"),
            rotationDegrees = node.nodeInt("rotate")?.toInt(),
            aspectRatio = node.nodeDouble("aspect")?.validAspectRatio(),
        )

    private fun VideoParams.withAspectFrom(previous: VideoParams): VideoParams =
        if (aspectRatio == null) copy(aspectRatio = previous.aspectRatio) else this

    private fun Double.validAspectRatio(): Double? =
        takeIf { it > 0.0 && !it.isNaN() && !it.isInfinite() }

    private fun MPVNode.nodeString(key: String): String? = this[key]?.asString()

    private fun MPVNode.nodeInt(key: String): Long? = this[key]?.asInt()

    private fun MPVNode.nodeDouble(key: String): Double? = this[key]?.asDouble()

    private fun MPVNode.nodeBoolean(key: String): Boolean? = this[key]?.asBoolean()

    private fun seekToPendingResumePosition() {
        val pendingPositionMillis = pendingResumeSeekMillis ?: return
        if (_progress.value.durationMillis <= 0L || !canWriteEngine()) return
        pendingResumeSeekMillis = null
        seekTo(pendingPositionMillis)
    }

    private fun attemptCleanup(failures: MutableList<Exception>, block: () -> Unit) {
        try {
            block()
        } catch (exception: Exception) {
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            failures += exception
        }
    }

    private fun reportCleanupFailures(failures: List<Exception>) {
        if (failures.isEmpty()) return

        System.err.println("MpvController destroy cleanup completed with ${failures.size} failure(s).")
        failures.forEach { failure ->
            failure.printStackTrace()
        }
    }

    private fun secondsToMillis(seconds: Double): Long =
        (seconds.coerceAtLeast(0.0) * 1000).toLong()

    private fun secondsToMillisSigned(seconds: Double): Long =
        (seconds * 1000).roundToLong()

    private companion object {
        const val MIN_PLAYBACK_SPEED = 0.25
        const val MAX_PLAYBACK_SPEED = 4.0
        const val DEFAULT_GESTURE_PERCENT = 50
        const val MIN_GESTURE_PERCENT = 0
        const val MAX_GESTURE_PERCENT = 100
        const val DOUBLE_TAP_SEEK_MILLIS = 10_000L
        const val MIN_VIDEO_ZOOM = -1.0f
        const val MAX_VIDEO_ZOOM = 2.0f
        const val ANIME4K_GPU_NEXT_OPENGL_INCOMPATIBLE_STATUS = "Anime4K 与当前 gpu-next(OpenGL) 渲染器不兼容"
    }
}
