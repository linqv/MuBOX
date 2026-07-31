package com.example.comicdav.video.player

import com.example.comicdav.core.model.media.VideoSubtitleOpenRequest
import com.example.comicdav.core.model.settings.Anime4KProfile
import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.VideoDecoderMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MpvController(
    private val engine: MpvEngine,
    private val anime4kShaderProvider: Anime4KShaderProvider = EmptyAnime4KShaderProvider,
    initialAnime4KProfile: Anime4KProfile = Anime4KProfile.OFF,
    initialAnime4KStatusMessage: String? = null,
    private val monotonicTimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val _state = MutableStateFlow(
        MpvPlayerState(
            statusMessage = initialAnime4KStatusMessage,
            anime4kProfile = initialAnime4KProfile,
            anime4kPipeline = anime4kPipelineForProfile(initialAnime4KProfile),
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
    private var lastAutoVideoParams: VideoParams? = null
    private var autoForceFast = false
    private var autoDisabledByDrops = false
    private var autoDropBaseline: Long? = null
    private var autoDropWindowStartMillis: Long? = null
    private var autoDropSuppressedUntilMillis: Long = 0L
    private var displayFrameRate: Double? = null

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
            decoderDroppedFrames = null,
            outputDroppedFrames = null,
            videoParams = VideoParams(),
            videoOutParams = VideoParams(),
        )
        if (_state.value.anime4kProfile == Anime4KProfile.AUTO) {
            resetAutoAnime4KResolution()
            engine.setPropertyString("glsl-shaders", "")
            _state.value = _state.value.copy(
                anime4kPipeline = null,
                statusMessage = null,
            )
        }
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
        updatePausedState(paused)
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
        suppressAutomaticAnime4KDropEvaluation()
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

    fun setAnime4KProfile(profile: Anime4KProfile) {
        if (!canWriteEngine()) return
        applyAnime4KProfile(profile)
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
        val parsedTracks = MpvTrackParser.parse(trackList)
        _state.value = _state.value.copy(
            audioTracks = parsedTracks.audioTracks,
            subtitleTracks = parsedTracks.subtitleTracks,
            selectedAudioTrackId = parsedTracks.selectedAudioTrackId ?: _state.value.selectedAudioTrackId,
            selectedSubtitleTrackId = parsedTracks.selectedSubtitleTrackId ?: _state.value.selectedSubtitleTrackId,
            activeVideoDecoder = parsedTracks.selectedVideoDecoder ?: _state.value.activeVideoDecoder,
        )
    }

    fun onSpeedChanged(speed: Double) {
        val clampedSpeed = speed.coerceAtLeast(MIN_PLAYBACK_SPEED)
        if (clampedSpeed == _state.value.playbackSpeed) return
        _state.value = _state.value.copy(playbackSpeed = clampedSpeed)
        suppressAutomaticAnime4KDropEvaluation()
    }

    fun onContainerFrameRateChanged(frameRate: Double) {
        val validFrameRate = frameRate.validFrameRate()
        if (validFrameRate == _state.value.videoParams.frameRate) return
        _state.value = _state.value.copy(
            videoParams = _state.value.videoParams.copy(frameRate = validFrameRate),
        )
        applyAutomaticAnime4KIfVideoParamsChanged()
    }

    fun onDisplayFrameRateChanged(frameRate: Double) {
        val validFrameRate = frameRate.validFrameRate()
        if (validFrameRate == displayFrameRate) return
        displayFrameRate = validFrameRate
        suppressAutomaticAnime4KDropEvaluation()
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

    fun onGpuContextChanged(value: String) {
        _state.value = _state.value.copy(currentGpuContext = value.takeIf { it.isNotBlank() })
    }

    fun onDecoderDroppedFramesChanged(value: Long) {
        _state.value = _state.value.copy(decoderDroppedFrames = value.coerceAtLeast(0L))
    }

    fun onOutputDroppedFramesChanged(value: Long) {
        val droppedFrames = value.coerceAtLeast(0L)
        val previousDroppedFrames = _state.value.outputDroppedFrames ?: 0L
        _state.value = _state.value.copy(outputDroppedFrames = droppedFrames)
        maybeDowngradeAutomaticAnime4K(
            droppedFrames = droppedFrames,
            previousDroppedFrames = previousDroppedFrames,
        )
    }

    fun onVideoParamsChanged(params: MPVNode) {
        val parsedParams = parseVideoParams(params)
        _state.value = _state.value.let { state ->
            state.copy(videoParams = parsedParams.withObservedValuesFrom(state.videoParams))
        }
        applyAutomaticAnime4KIfVideoParamsChanged()
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
        updatePausedState(paused)
    }

    fun markPaused(paused: Boolean) {
        updatePausedState(paused)
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
        val speedChanged = clampedSpeed != _state.value.playbackSpeed
        engine.setPropertyDouble("speed", clampedSpeed)
        _state.value = _state.value.copy(playbackSpeed = clampedSpeed)
        if (speedChanged) {
            suppressAutomaticAnime4KDropEvaluation()
        }
    }

    private fun applyAnime4KProfile(profile: Anime4KProfile) {
        if (profile == Anime4KProfile.OFF) {
            resetAutoAnime4KResolution()
            engine.setPropertyString("glsl-shaders", "")
            _state.value = _state.value.copy(
                anime4kProfile = Anime4KProfile.OFF,
                anime4kPipeline = null,
                statusMessage = null,
            )
            return
        }

        if (profile == Anime4KProfile.AUTO) {
            resetAutoAnime4KResolution()
            lastAutoVideoParams = _state.value.videoParams
            applyAutomaticAnime4K()
            return
        }

        resetAutoAnime4KResolution()
        val pipeline = checkNotNull(anime4kPipelineForProfile(profile))
        val shaderChain = anime4kShaderProvider.shaderChain(pipeline)
        if (shaderChain.isBlank()) {
            engine.setPropertyString("glsl-shaders", "")
            _state.value = _state.value.copy(
                anime4kProfile = Anime4KProfile.OFF,
                anime4kPipeline = null,
                statusMessage = "Anime4K 着色器加载失败",
            )
            return
        }

        engine.setPropertyString("glsl-shaders", shaderChain)
        _state.value = _state.value.copy(
            anime4kProfile = profile,
            anime4kPipeline = pipeline,
            statusMessage = null,
        )
    }

    private fun applyAutomaticAnime4K(statusOverride: String? = null) {
        if (autoDisabledByDrops) {
            engine.setPropertyString("glsl-shaders", "")
            _state.value = _state.value.copy(
                anime4kProfile = Anime4KProfile.AUTO,
                anime4kPipeline = null,
                statusMessage = statusOverride ?: "Anime4K 自动：持续丢帧，已暂停增强",
            )
            autoDropBaseline = null
            autoDropWindowStartMillis = null
            autoDropSuppressedUntilMillis = 0L
            return
        }

        val selection = selectAnime4KPipeline(
            videoParams = _state.value.videoParams,
            forceFast = autoForceFast,
        )
        val pipeline = selection.pipeline
        if (pipeline == null) {
            engine.setPropertyString("glsl-shaders", "")
            _state.value = _state.value.copy(
                anime4kProfile = Anime4KProfile.AUTO,
                anime4kPipeline = null,
                statusMessage = selection.statusMessage,
            )
            autoDropBaseline = null
            autoDropWindowStartMillis = null
            autoDropSuppressedUntilMillis = 0L
            return
        }

        val shaderChain = anime4kShaderProvider.shaderChain(pipeline)
        if (shaderChain.isBlank()) {
            engine.setPropertyString("glsl-shaders", "")
            _state.value = _state.value.copy(
                anime4kProfile = Anime4KProfile.AUTO,
                anime4kPipeline = null,
                statusMessage = "Anime4K 自动：着色器加载失败",
            )
            autoDropBaseline = null
            autoDropWindowStartMillis = null
            autoDropSuppressedUntilMillis = 0L
            return
        }

        engine.setPropertyString("glsl-shaders", shaderChain)
        _state.value = _state.value.copy(
            anime4kProfile = Anime4KProfile.AUTO,
            anime4kPipeline = pipeline,
            statusMessage = statusOverride,
        )
        suppressAutomaticAnime4KDropEvaluation()
    }

    private fun maybeDowngradeAutomaticAnime4K(
        droppedFrames: Long,
        previousDroppedFrames: Long,
    ) {
        if (_state.value.anime4kProfile != Anime4KProfile.AUTO) return
        val pipeline = _state.value.anime4kPipeline ?: return
        val nowMillis = monotonicTimeMillis()
        if (
            _state.value.isPaused ||
            !_state.value.playbackSpeed.isNormalPlaybackSpeed() ||
            sourceFrameRateExceedsDisplayRate() ||
            nowMillis < autoDropSuppressedUntilMillis
        ) {
            autoDropBaseline = droppedFrames
            autoDropWindowStartMillis = null
            return
        }
        autoDropSuppressedUntilMillis = 0L
        val windowStartMillis = autoDropWindowStartMillis
        if (windowStartMillis == null) {
            autoDropBaseline = droppedFrames
            autoDropWindowStartMillis = nowMillis
            return
        }
        if (nowMillis - windowStartMillis > AUTO_ANIME4K_DROP_WINDOW_MILLIS) {
            autoDropBaseline = previousDroppedFrames.coerceAtMost(droppedFrames)
            autoDropWindowStartMillis = nowMillis
        } else if (droppedFrames < (autoDropBaseline ?: 0L)) {
            autoDropBaseline = droppedFrames
            autoDropWindowStartMillis = nowMillis
        }
        val baseline = autoDropBaseline ?: droppedFrames
        val droppedSinceApply = droppedFrames - baseline
        when {
            pipeline.isBalancedAutoPipeline &&
                droppedSinceApply >= AUTO_ANIME4K_BALANCED_DROP_THRESHOLD -> {
                autoForceFast = true
                applyAutomaticAnime4K("Anime4K 自动：检测到丢帧，已降为快速模式")
            }
            pipeline.isFastAutoPipeline &&
                droppedSinceApply >= AUTO_ANIME4K_FAST_DISABLE_DROP_THRESHOLD -> {
                autoDisabledByDrops = true
                applyAutomaticAnime4K()
            }
        }
    }

    private fun applyAutomaticAnime4KIfVideoParamsChanged() {
        val currentParams = _state.value.videoParams
        if (
            _state.value.anime4kProfile != Anime4KProfile.AUTO ||
            currentParams == lastAutoVideoParams
        ) {
            return
        }
        lastAutoVideoParams = currentParams
        applyAutomaticAnime4K()
    }

    private fun updatePausedState(paused: Boolean) {
        if (paused == _state.value.isPaused) return
        _state.value = _state.value.copy(isPaused = paused)
        suppressAutomaticAnime4KDropEvaluation()
    }

    private fun suppressAutomaticAnime4KDropEvaluation() {
        if (_state.value.anime4kProfile != Anime4KProfile.AUTO) return
        val nowMillis = monotonicTimeMillis()
        autoDropBaseline = _state.value.outputDroppedFrames
        autoDropWindowStartMillis = null
        autoDropSuppressedUntilMillis = maxOf(
            autoDropSuppressedUntilMillis,
            nowMillis + AUTO_ANIME4K_DROP_SUPPRESSION_MILLIS,
        )
    }

    private fun sourceFrameRateExceedsDisplayRate(): Boolean {
        val sourceFrameRate = _state.value.videoParams.frameRate ?: return false
        val currentDisplayFrameRate = displayFrameRate ?: return false
        return sourceFrameRate * _state.value.playbackSpeed >
            currentDisplayFrameRate * AUTO_ANIME4K_DISPLAY_RATE_TOLERANCE
    }

    private fun resetAutoAnime4KResolution() {
        lastAutoVideoParams = null
        autoForceFast = false
        autoDisabledByDrops = false
        autoDropBaseline = null
        autoDropWindowStartMillis = null
        autoDropSuppressedUntilMillis = 0L
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

    private fun parseVideoParams(node: MPVNode): VideoParams =
        VideoParams(
            codec = node.nodeString("codec"),
            width = node.nodeInt("w")?.toInt() ?: node.nodeInt("dw")?.toInt(),
            height = node.nodeInt("h")?.toInt() ?: node.nodeInt("dh")?.toInt(),
            rotationDegrees = node.nodeInt("rotate")?.toInt(),
            aspectRatio = node.nodeDouble("aspect")?.validAspectRatio(),
            primaries = node.nodeString("primaries"),
            gamma = node.nodeString("gamma"),
        )

    private fun VideoParams.withObservedValuesFrom(previous: VideoParams): VideoParams =
        copy(
            frameRate = frameRate ?: previous.frameRate,
            aspectRatio = aspectRatio ?: previous.aspectRatio,
        )

    private fun VideoParams.withAspectFrom(previous: VideoParams): VideoParams =
        if (aspectRatio == null) copy(aspectRatio = previous.aspectRatio) else this

    private fun Double.validAspectRatio(): Double? =
        takeIf { it > 0.0 && !it.isNaN() && !it.isInfinite() }

    private fun Double.validFrameRate(): Double? =
        takeIf { it > 0.0 && !it.isNaN() && !it.isInfinite() }

    private fun Double.isNormalPlaybackSpeed(): Boolean =
        abs(this - 1.0) < NORMAL_PLAYBACK_SPEED_EPSILON

    private fun MPVNode.nodeString(key: String): String? = this[key]?.asString()

    private fun MPVNode.nodeInt(key: String): Long? = this[key]?.asInt()

    private fun MPVNode.nodeDouble(key: String): Double? = this[key]?.asDouble()

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
        const val AUTO_ANIME4K_BALANCED_DROP_THRESHOLD = 3L
        const val AUTO_ANIME4K_FAST_DISABLE_DROP_THRESHOLD = 12L
        const val AUTO_ANIME4K_DROP_WINDOW_MILLIS = 5_000L
        const val AUTO_ANIME4K_DROP_SUPPRESSION_MILLIS = 2_000L
        const val AUTO_ANIME4K_DISPLAY_RATE_TOLERANCE = 1.02
        const val NORMAL_PLAYBACK_SPEED_EPSILON = 0.001
    }
}
