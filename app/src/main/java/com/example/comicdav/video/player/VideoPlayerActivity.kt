package com.example.comicdav.video.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.comicdav.ui.ComicDavTheme
import com.example.comicdav.video.LocalVideoOpenRequest
import com.example.comicdav.video.VideoSubtitleOpenRequest
import com.example.comicdav.video.WebDavVideoOpenRequest
import com.example.comicdav.video.proxy.MuBoxVideoProxy
import com.example.comicdav.video.proxy.VideoProxyManager
import com.example.comicdav.video.proxy.VideoProxyRuntimeStats
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.videoPlaybackStateDataStore by preferencesDataStore(name = "video_playback_state")

internal suspend fun loadVideoStartPosition(
    resumeEnabled: Boolean,
    playbackKey: String?,
    loadPosition: suspend (String?) -> Long,
    onFailure: (Throwable) -> Unit = {},
): Long {
    if (!resumeEnabled) return 0L
    return runCatching {
        loadPosition(playbackKey)
    }.getOrElse { error ->
        onFailure(error)
        0L
    }.coerceAtLeast(0L)
}

class VideoPlayerActivity : ComponentActivity() {
    private lateinit var mpvView: MuBoxMpvView
    private lateinit var controller: MpvController
    private lateinit var audioFocusController: VideoAudioFocusController
    private lateinit var playbackLifecyclePolicy: VideoPlaybackLifecyclePolicy
    private lateinit var playbackStateStore: VideoPlaybackStateStore
    private lateinit var progressSaver: VideoPlaybackProgressSaver
    private lateinit var orientationSession: VideoPlayerOrientationSession
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mpvObserverRegistered = false
    private var mpvInitialized = false
    private var isCleaningUp = false
    private var webDavStreamIds by mutableStateOf<List<String>>(emptyList())
    private var playbackKey: String? = null
    private var resumeEnabled = true
    private var loadJob: Job? = null
    private var progressSaveJob: Job? = null
    private var proxyStatistics by mutableStateOf<VideoProxyStatistics?>(null)
    private val systemBarsHandler = Handler(Looper.getMainLooper())
    private val hideStatusBarRunnable = Runnable { hidePlayerStatusBar() }

    private val mpvObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit

        override fun eventProperty(property: String, value: Long) {
            runOnUiThread {
                when (property) {
                    "aid" -> controller.onAudioTrackChanged(value.toInt())
                    "sid" -> controller.onSubtitleTrackChanged(value.toInt().takeIf { it > 0 })
                }
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            if (property == "pause") {
                runOnUiThread { controller.onPauseChanged(value) }
            }
        }

        override fun eventProperty(property: String, value: String) {
            runOnUiThread {
                when (property) {
                    "aid" -> controller.onAudioTrackChanged(value.toIntOrNull())
                    "sid" -> controller.onSubtitleTrackChanged(value.toIntOrNull())
                    "hwdec" -> controller.onHwdecChanged(value)
                    "hwdec-current" -> controller.onActiveHwdecChanged(value)
                    "current-tracks/video/decoder" -> controller.onActiveVideoDecoderChanged(value)
                    "vo" -> controller.onVoChanged(value)
                    "gpu-api" -> controller.onGpuApiChanged(value)
                }
            }
        }

        override fun eventProperty(property: String, value: Double) {
            runOnUiThread {
                when (property) {
                    "duration" -> controller.onDurationChanged(value)
                    "time-pos" -> controller.onPositionChanged(value)
                    "speed" -> controller.onSpeedChanged(value)
                    "volume" -> controller.onVolumeChanged(value)
                    "audio-delay" -> controller.onAudioDelayChanged(value)
                    "video-params/aspect" -> controller.onVideoAspectChanged(value)
                    "video-out-params/aspect" -> controller.onVideoOutAspectChanged(value)
                }
            }
        }

        override fun eventProperty(property: String, value: MPVNode) {
            runOnUiThread {
                when (property) {
                    "track-list" -> controller.onTrackListChanged(value)
                    "video-params" -> controller.onVideoParamsChanged(value)
                    "video-out-params" -> controller.onVideoOutParamsChanged(value)
                }
            }
        }

        override fun event(eventId: Int, data: MPVNode) {
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
                val errorMessage = mpvEndFileErrorMessage(data)
                runOnUiThread {
                    if (errorMessage == null) {
                        controller.onPlaybackEnded()
                    } else {
                        controller.onError(errorMessage)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialPlayerOrientationMode = intent.getStringExtra(EXTRA_PLAYER_ORIENTATION_MODE)
            .toEnumOrDefault(VideoPlayerOrientationMode.VIDEO)
        orientationSession = VideoPlayerOrientationSession(initialPlayerOrientationMode)
        requestedOrientation = orientationSession.initialRequestedOrientation()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val uri = intent.getStringExtra(EXTRA_URI)
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: intent.data?.lastPathSegment ?: "视频"
        val source = intent.getStringExtra(EXTRA_SOURCE)
        val mediaContext = VideoPlayerMediaContext(
            displayName = displayName,
            source = source ?: SOURCE_LOCAL,
            remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH),
        )
        val subtitles = intent.subtitleRequests()
        playbackKey = intent.getStringExtra(EXTRA_PLAYBACK_KEY)
        resumeEnabled = intent.getBooleanExtra(EXTRA_RESUME_ENABLED, true)
        val initialVideoOutputMode = intent.getStringExtra(EXTRA_VIDEO_OUTPUT_MODE)
            .toEnumOrDefault(VideoOutputMode.AUTO)
        val initialGpuApiMode = intent.getStringExtra(EXTRA_GPU_API_MODE)
            .toEnumOrDefault(GpuApiMode.AUTO)
        val initialVideoDecoderMode = intent.getStringExtra(EXTRA_VIDEO_DECODER_MODE)
            .toEnumOrDefault(VideoDecoderMode.AUTO)
        val initialMpvProfileMode = intent.getStringExtra(EXTRA_MPV_PROFILE_MODE)
            .toEnumOrDefault(MpvProfileMode.FAST)
        val controlsAutoHideMillis = intent.getIntExtra(EXTRA_CONTROLS_AUTO_HIDE_MILLIS, 5_000)
        val proxyDebugInfoEnabled = intent.getBooleanExtra(EXTRA_PROXY_DEBUG_INFO_ENABLED, false)
        playbackStateStore = VideoPlaybackStateStore(applicationContext.videoPlaybackStateDataStore)
        progressSaver = VideoPlaybackProgressSaver(playbackPersistenceScope) { key, positionMillis, durationMillis ->
            playbackStateStore.savePosition(
                playbackKey = key,
                positionMillis = positionMillis,
                durationMillis = durationMillis,
            )
        }
        if (uri.isNullOrBlank()) {
            finish()
            return
        }
        if (source == SOURCE_WEB_DAV) {
            val explicitStreamIds = intent.getStringArrayListExtra(EXTRA_WEB_DAV_STREAM_IDS).orEmpty()
            webDavStreamIds = explicitStreamIds.ifEmpty {
                listOfNotNull(MuBoxVideoProxy.streamIdFromUrl(uri).takeIf { it.isNotBlank() })
            }
        }

        mpvView = MuBoxMpvView.create(this)
        mpvView.mpvProfileMode = initialMpvProfileMode
        controller = MpvController(ViewBackedMpvEngine(mpvView))
        audioFocusController = VideoAudioFocusController(this) {
            controller.setPaused(true)
        }
        playbackLifecyclePolicy = VideoPlaybackLifecyclePolicy(
            onPausePlayback = {
                controller.setPaused(true)
                audioFocusController.abandon()
            },
            onCleanupPlayback = ::cleanupPlayer,
            onBackgroundTimeoutAfterCleanup = {
                if (!isFinishing) {
                    finish()
                }
            },
        )
        setContent {
            ComicDavTheme {
                val state by controller.state.collectAsState()
                val progress by controller.progress.collectAsState()
                LaunchedEffect(webDavStreamIds, proxyDebugInfoEnabled) {
                    if (webDavStreamIds.isEmpty() || !proxyDebugInfoEnabled) {
                        proxyStatistics = null
                        return@LaunchedEffect
                    }
                    while (true) {
                        proxyStatistics = VideoProxyManager.statistics(webDavStreamIds.first())
                            ?.toPlayerStatistics()
                        delay(PROXY_STATISTICS_SAMPLE_INTERVAL_MILLIS)
                    }
                }
                LaunchedEffect(
                    state.videoParams.width,
                    state.videoParams.height,
                    state.videoParams.rotationDegrees,
                    state.videoParams.aspectRatio,
                    state.videoOutParams.width,
                    state.videoOutParams.height,
                    state.videoOutParams.rotationDegrees,
                    state.videoOutParams.aspectRatio,
                ) {
                    val orientationVideoParams = preferredVideoParamsForOrientation(state)
                    orientationSession.requestForVideoParams(orientationVideoParams)?.let { orientation ->
                        requestedOrientation = orientation
                    }
                }
                BackHandler {
                    closePlayer()
                }
                VideoPlayerScreen(
                    state = state,
                    progress = progress,
                    mpvView = mpvView,
                    onClose = ::closePlayer,
                    onPlayPause = controller::togglePlayPause,
                    onSeek = controller::seekTo,
                    onSpeedSelected = controller::setPlaybackSpeed,
                    onAudioTrackSelected = controller::selectAudioTrack,
                    onSubtitleTrackSelected = controller::selectSubtitleTrack,
                    onSubtitlesDisabled = controller::disableSubtitles,
                    onScaleModeSelected = controller::setScaleMode,
                    onDecoderModeSelected = controller::setDecoderMode,
                    onOrientationToggle = {
                        requestedOrientation = orientationSession.toggleFixedOrientation(
                            resources.configuration.orientation,
                        )
                    },
                    onControlsLockedChanged = controller::setControlsLocked,
                    onVolumeDelta = controller::adjustGestureVolume,
                    onBrightnessDelta = ::handleBrightnessGesture,
                    onDoubleTapSeek = controller::handleDoubleTapSeek,
                    onHorizontalSeekStarted = controller::beginHorizontalSwipeSeek,
                    onHorizontalSeekFraction = controller::handleHorizontalSwipeSeek,
                    onHorizontalSeekEnded = controller::endHorizontalSwipeSeek,
                    onZoomDelta = controller::adjustGestureZoom,
                    onTemporarySpeedStarted = { controller.beginTemporarySpeed(2.0) },
                    onTemporarySpeedDelta = controller::adjustTemporarySpeed,
                    onTemporarySpeedEnded = controller::endTemporarySpeed,
                    onClearHud = controller::clearGestureHud,
                    mediaContext = mediaContext,
                    controlsAutoHideMillis = controlsAutoHideMillis,
                    proxyStatistics = proxyStatistics,
                    proxyDebugInfoEnabled = proxyDebugInfoEnabled,
                )
            }
        }

        loadJob = activityScope.launch {
            if (!prepareMpv()) return@launch
            controller.setVideoOutputMode(initialVideoOutputMode)
            controller.setGpuApiMode(initialGpuApiMode)
            controller.setDecoderMode(initialVideoDecoderMode)
            val startPositionMillis = loadVideoStartPosition(
                resumeEnabled = resumeEnabled,
                playbackKey = playbackKey,
                loadPosition = { key ->
                    withContext(Dispatchers.IO) {
                        playbackStateStore.loadPosition(key)
                    }
                },
                onFailure = { error ->
                    System.err.println("Failed to load video resume position: ${error.message ?: error::class.java.simpleName}")
                },
            )
            if (!canLoadMpv()) return@launch
            val loaded = loadMpv(
                uri = uri,
                displayName = displayName,
                startPositionMillis = startPositionMillis,
                subtitles = subtitles,
            )
            if (loaded) startPlaybackProgressAutoSave()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::playbackLifecyclePolicy.isInitialized) {
            playbackLifecyclePolicy.returnToForeground()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::playbackLifecyclePolicy.isInitialized && !isFinishing && !isCleaningUp) {
            playbackLifecyclePolicy.moveToBackground()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configurePlayerSystemBars()
        }
    }

    override fun onDestroy() {
        if (::playbackLifecyclePolicy.isInitialized) {
            playbackLifecyclePolicy.cleanup()
        }
        activityScope.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    internal fun configurePlayerSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            val decorView = window.decorView
            decorView.windowInsetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            decorView.setOnApplyWindowInsetsListener { _, insets ->
                if (insets.isVisible(WindowInsets.Type.statusBars())) {
                    scheduleStatusBarRehide()
                }
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    scheduleStatusBarRehide()
                }
            }
        }
        hidePlayerStatusBar()
    }

    private fun scheduleStatusBarRehide() {
        systemBarsHandler.removeCallbacks(hideStatusBarRunnable)
        systemBarsHandler.postDelayed(hideStatusBarRunnable, PLAYER_STATUS_BAR_REHIDE_MILLIS)
    }

    private fun hidePlayerStatusBar() {
        systemBarsHandler.removeCallbacks(hideStatusBarRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val decorView = window.decorView
            val controller = decorView.windowInsetsController
            if (controller == null) {
                decorView.post {
                    if (!isFinishing && !isDestroyed) {
                        hidePlayerStatusBar()
                    }
                }
                return
            }
            controller.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    internal fun restorePlayerSystemBars() {
        systemBarsHandler.removeCallbacks(hideStatusBarRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val decorView = window.decorView
            decorView.setOnApplyWindowInsetsListener(null)
            decorView.windowInsetsController?.show(WindowInsets.Type.statusBars())
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(true)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener(null)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and
                    View.SYSTEM_UI_FLAG_FULLSCREEN.inv() and
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv() and
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
        }
    }

    private fun closePlayer() {
        cancelPendingLoad()
        playbackLifecyclePolicy.cleanup()
        finish()
    }

    private fun cleanupPlayer() {
        if (isCleaningUp) return
        cancelPendingLoad()
        stopPlaybackProgressAutoSave()
        isCleaningUp = true
        try {
            savePlaybackPositionAsync()
            runCatching {
                if (::audioFocusController.isInitialized) {
                    audioFocusController.abandon()
                }
            }
            if (mpvObserverRegistered) {
                MPVLib.removeObserver(mpvObserver)
                mpvObserverRegistered = false
            }
            if (mpvInitialized) {
                controller.destroy()
                mpvInitialized = false
            }
            webDavStreamIds.forEach { streamId ->
                VideoProxyManager.close(streamId)
            }
            webDavStreamIds = emptyList()
        } finally {
            isCleaningUp = false
        }
    }

    private suspend fun prepareMpv(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                Utils.copyAssets(this@VideoPlayerActivity)
            }
            if (isFinishing) return false
            mpvView.initialize(filesDir.path, cacheDir.path)
            mpvView.attachExistingSurfaceIfReady()
            mpvInitialized = true
            MPVLib.addObserver(mpvObserver)
            mpvObserverRegistered = true
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            controller.onError(error.message ?: "视频播放器初始化失败")
            false
        }
    }

    private suspend fun loadMpv(
        uri: String,
        displayName: String,
        startPositionMillis: Long,
        subtitles: List<VideoSubtitleOpenRequest>,
    ): Boolean {
        if (!canLoadMpv()) return false
        val resolvedInput = try {
            withContext(Dispatchers.IO) {
                resolvePlaybackInput(
                    uri = uri,
                    subtitles = subtitles,
                    isWebDav = intent.getStringExtra(EXTRA_SOURCE) == SOURCE_WEB_DAV,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            controller.onError(error.message ?: "视频播放器初始化失败")
            return false
        }
        var consumedByMpv = false
        try {
            if (!canLoadMpv()) return false
            if (audioFocusController.request()) {
                controller.load(
                    resolvedInput.videoUri.uri,
                    displayName,
                    startPositionMillis = startPositionMillis,
                    subtitles = resolvedInput.subtitleRequests(),
                    onFileLoaded = {
                        resolvedInput.videoUri.markConsumed()
                    },
                )
                resolvedInput.markConsumed()
                consumedByMpv = true
                return true
            } else {
                controller.markPaused(true)
                controller.onError("无法获取音频焦点，已暂停播放")
                return false
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            controller.onError(error.message ?: "视频播放器初始化失败")
            return false
        } finally {
            if (!consumedByMpv) {
                resolvedInput.closeIfUnused()
            }
        }
    }

    private fun canLoadMpv(): Boolean =
        mpvInitialized && !isCleaningUp && !isFinishing

    private fun savePlaybackPositionAsync() {
        if (!resumeEnabled || !::playbackStateStore.isInitialized || !::controller.isInitialized) return
        val key = playbackKey?.takeIf { it.isNotBlank() } ?: return
        val progress = controller.progress.value
        progressSaver.saveAsync(
            playbackKey = key,
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
        )
    }

    private fun cancelPendingLoad() {
        loadJob?.cancel()
        loadJob = null
    }

    private fun startPlaybackProgressAutoSave() {
        if (!resumeEnabled || progressSaveJob != null) return
        progressSaveJob = activityScope.launch {
            while (true) {
                delay(PLAYBACK_PROGRESS_SAVE_INTERVAL_MILLIS)
                savePlaybackPositionAsync()
            }
        }
    }

    private fun stopPlaybackProgressAutoSave() {
        progressSaveJob?.cancel()
        progressSaveJob = null
    }

    private fun resolvePlaybackInput(
        uri: String,
        subtitles: List<VideoSubtitleOpenRequest>,
        isWebDav: Boolean,
    ): ResolvedPlaybackInput {
        if (isWebDav) {
            return ResolvedPlaybackInput(
                videoUri = ManagedPlaybackUri(uri),
                subtitles = subtitles.map { subtitle ->
                    ResolvedSubtitlePlaybackUri(
                        uri = ManagedPlaybackUri(subtitle.uri),
                        displayName = subtitle.displayName,
                    )
                },
            )
        }
        val localUriResolver = LocalVideoUriResolver(this)
        return ResolvedPlaybackInput(
            videoUri = localUriResolver.resolveForPlayback(uri),
            subtitles = subtitles.map { subtitle ->
                ResolvedSubtitlePlaybackUri(
                    uri = localUriResolver.resolveSubtitleForPlayback(subtitle.uri, subtitle.displayName),
                    displayName = subtitle.displayName,
                )
            },
        )
    }

    private fun handleBrightnessGesture(deltaPercent: Int) {
        controller.adjustGestureBrightness(deltaPercent)
        controller.state.value.gestureState.brightnessPercent?.let(::applyScreenBrightnessPercent)
    }

    private fun applyScreenBrightnessPercent(percent: Int) {
        val clampedPercent = percent.coerceIn(0, 100)
        window.attributes = window.attributes.apply {
            screenBrightness = (clampedPercent / 100f).coerceIn(0.01f, 1f)
        }
    }

    private fun Intent.subtitleRequests(): List<VideoSubtitleOpenRequest> {
        val uris = getStringArrayListExtra(EXTRA_SUBTITLE_URIS).orEmpty()
        val names = getStringArrayListExtra(EXTRA_SUBTITLE_NAMES).orEmpty()
        return uris.mapIndexed { index, uri ->
            VideoSubtitleOpenRequest(
                uri = uri,
                displayName = names.getOrNull(index) ?: uri.substringAfterLast('/'),
            )
        }
    }

    companion object {
        const val EXTRA_SOURCE = "com.example.comicdav.video.extra.SOURCE"
        const val EXTRA_URI = "com.example.comicdav.video.extra.URI"
        const val EXTRA_DISPLAY_NAME = "com.example.comicdav.video.extra.DISPLAY_NAME"
        const val EXTRA_SIZE = "com.example.comicdav.video.extra.SIZE"
        const val EXTRA_LAST_MODIFIED = "com.example.comicdav.video.extra.LAST_MODIFIED"
        const val EXTRA_SUBTITLE_URIS = "com.example.comicdav.video.extra.SUBTITLE_URIS"
        const val EXTRA_SUBTITLE_NAMES = "com.example.comicdav.video.extra.SUBTITLE_NAMES"
        const val EXTRA_WEB_DAV_STREAM_IDS = "com.example.comicdav.video.extra.WEB_DAV_STREAM_IDS"
        const val EXTRA_PLAYBACK_KEY = "com.example.comicdav.video.extra.PLAYBACK_KEY"
        const val EXTRA_RESUME_ENABLED = "com.example.comicdav.video.extra.RESUME_ENABLED"
        const val EXTRA_REMOTE_PATH = "com.example.comicdav.video.extra.REMOTE_PATH"
        const val EXTRA_VIDEO_OUTPUT_MODE = "com.example.comicdav.video.extra.VIDEO_OUTPUT_MODE"
        const val EXTRA_GPU_API_MODE = "com.example.comicdav.video.extra.GPU_API_MODE"
        const val EXTRA_VIDEO_DECODER_MODE = "com.example.comicdav.video.extra.VIDEO_DECODER_MODE"
        const val EXTRA_MPV_PROFILE_MODE = "com.example.comicdav.video.extra.MPV_PROFILE_MODE"
        const val EXTRA_CONTROLS_AUTO_HIDE_MILLIS = "com.example.comicdav.video.extra.CONTROLS_AUTO_HIDE_MILLIS"
        const val EXTRA_PLAYER_ORIENTATION_MODE = "com.example.comicdav.video.extra.PLAYER_ORIENTATION_MODE"
        const val EXTRA_PROXY_DEBUG_INFO_ENABLED = "com.example.comicdav.video.extra.PROXY_DEBUG_INFO_ENABLED"
        const val SOURCE_LOCAL = "local"

        fun localIntent(
            context: Context,
            request: LocalVideoOpenRequest,
            resumeEnabled: Boolean = true,
            videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO,
            gpuApiMode: GpuApiMode = GpuApiMode.AUTO,
            videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
            mpvProfileMode: MpvProfileMode = MpvProfileMode.FAST,
            controlsAutoHideMillis: Int = 5_000,
            playerOrientationMode: VideoPlayerOrientationMode = VideoPlayerOrientationMode.VIDEO,
            proxyDebugInfoEnabled: Boolean = false,
        ): Intent =
            Intent(context, VideoPlayerActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_SOURCE, SOURCE_LOCAL)
                .putExtra(EXTRA_URI, request.uri)
                .putExtra(EXTRA_DISPLAY_NAME, request.displayName)
                .putExtra(EXTRA_REMOTE_PATH, request.uri)
                .putExtra(EXTRA_SIZE, request.size ?: -1L)
                .putExtra(EXTRA_LAST_MODIFIED, request.lastModified ?: -1L)
                .putExtra(
                    EXTRA_PLAYBACK_KEY,
                    localVideoPlaybackKey(
                        uri = request.uri,
                        size = request.size,
                        lastModified = request.lastModified,
                    ),
                )
                .putExtra(EXTRA_RESUME_ENABLED, resumeEnabled)
                .putExtra(EXTRA_VIDEO_OUTPUT_MODE, videoOutputMode.name)
                .putExtra(EXTRA_GPU_API_MODE, gpuApiMode.name)
                .putExtra(EXTRA_VIDEO_DECODER_MODE, videoDecoderMode.name)
                .putExtra(EXTRA_MPV_PROFILE_MODE, mpvProfileMode.name)
                .putExtra(EXTRA_CONTROLS_AUTO_HIDE_MILLIS, controlsAutoHideMillis)
                .putExtra(EXTRA_PLAYER_ORIENTATION_MODE, playerOrientationMode.name)
                .putExtra(EXTRA_PROXY_DEBUG_INFO_ENABLED, proxyDebugInfoEnabled)
                .putSubtitleExtras(request.subtitles)

        fun webDavIntent(
            context: Context,
            request: WebDavVideoOpenRequest,
            uri: String,
            subtitleUrls: List<String>,
            streamIds: List<String>,
            resumeEnabled: Boolean = true,
            videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO,
            gpuApiMode: GpuApiMode = GpuApiMode.AUTO,
            videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
            mpvProfileMode: MpvProfileMode = MpvProfileMode.FAST,
            controlsAutoHideMillis: Int = 5_000,
            playerOrientationMode: VideoPlayerOrientationMode = VideoPlayerOrientationMode.VIDEO,
            proxyDebugInfoEnabled: Boolean = false,
        ): Intent =
            request.subtitles.zip(subtitleUrls)
                .map { (subtitle, subtitleUrl) ->
                    VideoSubtitleOpenRequest(
                        uri = subtitleUrl,
                        displayName = subtitle.displayName,
                    )
                }
                .let { subtitles ->
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_SOURCE, SOURCE_WEB_DAV)
                .putExtra(EXTRA_URI, uri)
                    .putExtra(EXTRA_REMOTE_PATH, request.remotePath)
                    .putExtra(EXTRA_DISPLAY_NAME, request.displayName)
                    .putExtra(EXTRA_SIZE, request.size ?: -1L)
                    .putExtra(EXTRA_LAST_MODIFIED, request.lastModified ?: -1L)
                    .putExtra(
                        EXTRA_PLAYBACK_KEY,
                        webDavVideoPlaybackKey(
                            accountId = request.accountId,
                            remotePath = request.remotePath,
                            size = request.size,
                            etag = request.etag,
                            lastModified = request.lastModified,
                        ),
                    )
                    .putExtra(EXTRA_RESUME_ENABLED, resumeEnabled)
                    .putExtra(EXTRA_VIDEO_OUTPUT_MODE, videoOutputMode.name)
                    .putExtra(EXTRA_GPU_API_MODE, gpuApiMode.name)
                    .putExtra(EXTRA_VIDEO_DECODER_MODE, videoDecoderMode.name)
                    .putExtra(EXTRA_MPV_PROFILE_MODE, mpvProfileMode.name)
                    .putExtra(EXTRA_CONTROLS_AUTO_HIDE_MILLIS, controlsAutoHideMillis)
                    .putExtra(EXTRA_PLAYER_ORIENTATION_MODE, playerOrientationMode.name)
                    .putExtra(EXTRA_PROXY_DEBUG_INFO_ENABLED, proxyDebugInfoEnabled)
                    .putStringArrayListExtra(EXTRA_WEB_DAV_STREAM_IDS, ArrayList(streamIds))
                    .putSubtitleExtras(subtitles)
                }

        private const val SOURCE_WEB_DAV = "webdav"

        private fun Intent.putSubtitleExtras(subtitles: List<VideoSubtitleOpenRequest>): Intent =
            putStringArrayListExtra(EXTRA_SUBTITLE_URIS, ArrayList(subtitles.map { it.uri }))
                .putStringArrayListExtra(EXTRA_SUBTITLE_NAMES, ArrayList(subtitles.map { it.displayName }))
    }
}

@Composable
private fun VideoPlayerScreen(
    state: MpvPlayerState,
    progress: VideoPlaybackProgressState,
    mpvView: MuBoxMpvView,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedSelected: (Double) -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
    onScaleModeSelected: (VideoScaleMode) -> Unit,
    onDecoderModeSelected: (VideoDecoderMode) -> Unit,
    onOrientationToggle: () -> Unit,
    onControlsLockedChanged: (Boolean) -> Unit,
    onVolumeDelta: (Int) -> Unit,
    onBrightnessDelta: (Int) -> Unit,
    onDoubleTapSeek: (Boolean) -> Unit,
    onHorizontalSeekStarted: () -> Unit,
    onHorizontalSeekFraction: (Float) -> Unit,
    onHorizontalSeekEnded: () -> Unit,
    onZoomDelta: (Float) -> Unit,
    onTemporarySpeedStarted: () -> Unit,
    onTemporarySpeedDelta: (Double) -> Unit,
    onTemporarySpeedEnded: () -> Unit,
    onClearHud: () -> Unit,
    mediaContext: VideoPlayerMediaContext,
    controlsAutoHideMillis: Int,
    proxyStatistics: VideoProxyStatistics?,
    proxyDebugInfoEnabled: Boolean,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val activity = context as? VideoPlayerActivity
        activity?.configurePlayerSystemBars()
        onDispose {
            activity?.restorePlayerSystemBars()
        }
    }

    var menuVisible by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lockButtonVisible by remember { mutableStateOf(true) }
    var lockButtonRevealSignal by remember { mutableIntStateOf(0) }
    val controlsLocked = state.gestureState.controlsLocked

    LaunchedEffect(
        controlsVisible,
        menuVisible,
        controlsLocked,
        controlsAutoHideMillis,
    ) {
        if (!controlsVisible || controlsLocked || menuVisible || controlsAutoHideMillis <= 0) return@LaunchedEffect
        delay(controlsAutoHideMillis.toLong())
        menuVisible = false
        controlsVisible = false
    }

    LaunchedEffect(controlsLocked) {
        if (controlsLocked) {
            menuVisible = false
            controlsVisible = false
            lockButtonVisible = false
        } else {
            controlsVisible = true
            lockButtonVisible = true
        }
    }

    LaunchedEffect(lockButtonVisible, controlsLocked, lockButtonRevealSignal) {
        if (!controlsLocked || !lockButtonVisible) return@LaunchedEffect
        delay(PLAYER_LOCKED_BUTTON_AUTO_HIDE_MILLIS)
        lockButtonVisible = false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { mpvView },
                modifier = Modifier.fillMaxSize(),
            )

            if (controlsLocked) {
                LockedPlayerGestureOverlay(
                    onTap = {
                        lockButtonVisible = true
                        lockButtonRevealSignal += 1
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PlayerGestureOverlay(
                    onVolumeDelta = onVolumeDelta,
                    onBrightnessDelta = onBrightnessDelta,
                    onDoubleTapSeek = onDoubleTapSeek,
                    onHorizontalSeekStarted = onHorizontalSeekStarted,
                    onHorizontalSeekFraction = onHorizontalSeekFraction,
                    onHorizontalSeekEnded = onHorizontalSeekEnded,
                    onZoomDelta = onZoomDelta,
                    onTemporarySpeedStarted = onTemporarySpeedStarted,
                    onTemporarySpeedDelta = onTemporarySpeedDelta,
                    onTemporarySpeedEnded = onTemporarySpeedEnded,
                    onClearHud = onClearHud,
                    onOverlayTap = {
                        menuVisible = false
                        controlsVisible = !controlsVisible
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (!controlsLocked && controlsVisible) {
                PlayerTopBar(
                    title = state.displayName,
                    source = mediaContext.source,
                    onClose = onClose,
                    onMenuClick = {
                        menuVisible = !menuVisible
                    },
                    onOrientationToggle = onOrientationToggle,
                    modifier = Modifier
                        .align(Alignment.TopCenter),
                )
            }

            if ((controlsVisible && !controlsLocked) || (controlsLocked && lockButtonVisible)) {
                PlayerLockButton(
                    controlsLocked = controlsLocked,
                    onClick = {
                        val nextLocked = !controlsLocked
                        onControlsLockedChanged(nextLocked)
                        menuVisible = false
                        controlsVisible = !nextLocked
                        lockButtonVisible = !nextLocked
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = PLAYER_LOCK_BUTTON_START_PADDING_DP.dp),
                )
            }

            if (!controlsLocked && controlsVisible && menuVisible) {
                PlayerMenuPanel(
                    state = state,
                    mediaContext = mediaContext,
                    proxyStatistics = proxyStatistics,
                    proxyDebugInfoEnabled = proxyDebugInfoEnabled,
                    onDismiss = { menuVisible = false },
                    onSpeedSelected = onSpeedSelected,
                    onScaleModeSelected = onScaleModeSelected,
                    onDecoderModeSelected = onDecoderModeSelected,
                    onAudioTrackSelected = onAudioTrackSelected,
                    onSubtitleTrackSelected = onSubtitleTrackSelected,
                    onSubtitlesDisabled = onSubtitlesDisabled,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                )
            }

            GestureHud(
                message = state.gestureState.hudMessage,
                onTimeout = onClearHud,
                modifier = Modifier.align(Alignment.Center),
            )

            if (!controlsLocked && controlsVisible) {
                PlayerCenterControls(
                    isPaused = state.isPaused,
                    onPlayPause = {
                        controlsVisible = true
                        onPlayPause()
                    },
                    onSeekBackward = {
                        controlsVisible = true
                        onSeek((progress.positionMillis - SEEK_STEP_MILLIS).coerceAtLeast(0L))
                    },
                    onSeekForward = {
                        controlsVisible = true
                        onSeek(
                            seekForwardTargetMillis(
                                positionMillis = progress.positionMillis,
                                durationMillis = progress.durationMillis,
                            ),
                        )
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
                PlayerBottomControls(
                    state = state,
                    progress = progress,
                    onSeek = {
                        controlsVisible = true
                        onSeek(it)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

private const val PLAYER_STATUS_BAR_REHIDE_MILLIS = 3_000L
private const val PLAYBACK_PROGRESS_SAVE_INTERVAL_MILLIS = 10_000L
private const val PROXY_STATISTICS_SAMPLE_INTERVAL_MILLIS = 1_000L

private fun VideoProxyRuntimeStats.toPlayerStatistics(): VideoProxyStatistics =
    VideoProxyStatistics(
        currentRange = currentRange,
        remoteHttpStatus = remoteHttpStatus,
        downloadBytesPerSecond = null,
        memoryCacheHits = memoryCacheHits,
        prefetchState = prefetchState,
        seekFirstFrameMillis = null,
        diagnosticMessage = diagnosticMessage,
    )

private data class ResolvedPlaybackInput(
    val videoUri: ManagedPlaybackUri,
    val subtitles: List<ResolvedSubtitlePlaybackUri>,
) {
    fun subtitleRequests(): List<VideoSubtitleOpenRequest> =
        subtitles.map { subtitle ->
            VideoSubtitleOpenRequest(
                uri = subtitle.uri.uri,
                displayName = subtitle.displayName,
            )
        }

    fun markConsumed() {
        videoUri.markConsumed()
        subtitles.forEach { it.uri.markConsumed() }
    }

    fun closeIfUnused() {
        videoUri.closeIfUnused()
        subtitles.forEach { it.uri.closeIfUnused() }
    }
}

private data class ResolvedSubtitlePlaybackUri(
    val uri: ManagedPlaybackUri,
    val displayName: String,
)

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
    this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default
