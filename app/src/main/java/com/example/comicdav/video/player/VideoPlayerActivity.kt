package com.example.comicdav.video.player

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.preferencesDataStore
import com.example.comicdav.appSettingsDataStore
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.data.WebDavAccountStore
import com.example.comicdav.security.AndroidKeystoreCredentialCipher
import com.example.comicdav.ui.ComicDavTheme
import com.example.comicdav.video.LocalVideoOpenRequest
import com.example.comicdav.video.VideoSubtitleOpenRequest
import com.example.comicdav.video.WebDavVideoOpenRequest
import com.example.comicdav.video.proxy.MuBoxVideoProxy
import com.example.comicdav.video.proxy.VideoProxyManager
import com.example.comicdav.video.proxy.VideoProxySettings
import com.example.comicdav.video.proxy.VideoProxyRuntimeStats
import com.example.comicdav.webDavAccountDataStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val Context.videoPlaybackStateDataStore by preferencesDataStore(name = "video_playback_state")

internal data class VideoBackgroundPermissionDecision(
    val mode: VideoBackgroundMode,
    val shouldRequestPostNotifications: Boolean,
)

internal fun videoBackgroundPermissionDecision(
    requestedMode: VideoBackgroundMode,
    sdkInt: Int,
    postNotificationsGranted: Boolean,
): VideoBackgroundPermissionDecision =
    VideoBackgroundPermissionDecision(
        mode = requestedMode,
        shouldRequestPostNotifications = requestedMode == VideoBackgroundMode.BACKGROUND_PLAY &&
            sdkInt >= Build.VERSION_CODES.TIRAMISU &&
            !postNotificationsGranted,
    )

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
    private var videoBackgroundMode = VideoBackgroundMode.NONE
    private var loadJob: Job? = null
    private var progressSaveJob: Job? = null
    private var proxyStatistics by mutableStateOf<VideoProxyStatistics?>(null)
    private var episodeQueue: VideoEpisodeQueue? = null
    private var currentEpisodeIndex by mutableIntStateOf(0)
    private var isEpisodeSwitching by mutableStateOf(false)
    private var isActivityInForeground = false
    @Volatile
    private var ignoreNextMpvStopEndFile = false
    @Volatile
    private var playEpisodeWhenFileLoaded = false
    private var playerMediaContext by mutableStateOf(
        VideoPlayerMediaContext(displayName = "视频", source = SOURCE_LOCAL, remotePath = null),
    )
    private val playerSettingsStore by lazy {
        AppSettingsStore(applicationContext.appSettingsDataStore)
    }
    private val playerWebDavAccountStore by lazy {
        WebDavAccountStore(
            dataStore = applicationContext.webDavAccountDataStore,
            cipher = AndroidKeystoreCredentialCipher(),
        )
    }
    private val systemBarsHandler = Handler(Looper.getMainLooper())
    private val hideStatusBarRunnable = Runnable { hidePlayerStatusBar() }
    private val playbackSessionId: String = UUID.randomUUID().toString()
    private lateinit var playbackStopReceiver: BroadcastReceiver

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
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED && playEpisodeWhenFileLoaded) {
                runOnUiThread {
                    if (!playEpisodeWhenFileLoaded) return@runOnUiThread
                    playEpisodeWhenFileLoaded = false
                    if (isActivityInForeground && canLoadMpv()) {
                        controller.setPaused(false)
                    }
                }
            }
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
                if (ignoreNextMpvStopEndFile && isMpvEndFileStop(data)) {
                    ignoreNextMpvStopEndFile = false
                    return
                }
                val errorMessage = mpvEndFileErrorMessage(data)
                runOnUiThread {
                    if (errorMessage == null) {
                        controller.onPlaybackEnded()
                        playbackLifecyclePolicy.playbackEnded()
                    } else {
                        controller.onError(errorMessage)
                        playbackLifecyclePolicy.playbackInterrupted()
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
        playerMediaContext = VideoPlayerMediaContext(
            displayName = displayName,
            source = source ?: SOURCE_LOCAL,
            remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH),
        )
        val subtitles = intent.subtitleRequests()
        playbackKey = intent.getStringExtra(EXTRA_PLAYBACK_KEY)
        episodeQueue = intent.episodeQueue()?.withCurrentPlaybackKey(playbackKey)
        currentEpisodeIndex = episodeQueue?.currentIndex ?: 0
        resumeEnabled = intent.getBooleanExtra(EXTRA_RESUME_ENABLED, true)
        val initialVideoOutputMode = intent.getStringExtra(EXTRA_VIDEO_OUTPUT_MODE)
            .toEnumOrDefault(VideoOutputMode.AUTO)
        val initialGpuApiMode = intent.getStringExtra(EXTRA_GPU_API_MODE)
            .toEnumOrDefault(GpuApiMode.AUTO)
        val initialVideoDecoderMode = intent.getStringExtra(EXTRA_VIDEO_DECODER_MODE)
            .toEnumOrDefault(VideoDecoderMode.AUTO)
        val initialMpvProfileMode = intent.getStringExtra(EXTRA_MPV_PROFILE_MODE)
            .toEnumOrDefault(MpvProfileMode.FAST)
        val initialAnime4KSettings = Anime4KSettings(
            enabled = intent.getBooleanExtra(EXTRA_ANIME4K_ENABLED, false),
            mode = intent.getStringExtra(EXTRA_ANIME4K_MODE).toEnumOrDefault(Anime4KMode.A),
            quality = intent.getStringExtra(EXTRA_ANIME4K_QUALITY).toEnumOrDefault(Anime4KQuality.FAST),
        )
        val anime4kManager = Anime4KManager(applicationContext)
        val startupCompatibility = anime4kStartupCompatibility(
            settings = initialAnime4KSettings,
            requestedVideoOutputMode = initialVideoOutputMode,
            gpuApiMode = initialGpuApiMode,
        )
        val controlsAutoHideMillis = intent.getIntExtra(EXTRA_CONTROLS_AUTO_HIDE_MILLIS, 5_000)
        val proxyDebugInfoEnabled = intent.getBooleanExtra(EXTRA_PROXY_DEBUG_INFO_ENABLED, false)
        val initialVideoBackgroundMode = intent.getStringExtra(EXTRA_VIDEO_BACKGROUND_MODE)
            .toEnumOrDefault(VideoBackgroundMode.NONE)
        val postNotificationsGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val backgroundPermissionDecision = videoBackgroundPermissionDecision(
            requestedMode = initialVideoBackgroundMode,
            sdkInt = Build.VERSION.SDK_INT,
            postNotificationsGranted = postNotificationsGranted,
        )
        if (backgroundPermissionDecision.shouldRequestPostNotifications) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
        videoBackgroundMode = backgroundPermissionDecision.mode
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
        mpvView.videoOutputMode = startupCompatibility.effectiveVideoOutputMode
        mpvView.gpuApiMode = initialGpuApiMode
        mpvView.videoDecoderMode = initialVideoDecoderMode
        mpvView.anime4kSettings = initialAnime4KSettings
        mpvView.anime4kManager = anime4kManager
        controller = MpvController(
            engine = ViewBackedMpvEngine(mpvView),
            anime4kShaderProvider = anime4kManager,
            initialAnime4KSettings = initialAnime4KSettings,
            initialAnime4KStatusMessage = startupCompatibility.statusMessage,
        )
        audioFocusController = VideoAudioFocusController(this) {
            controller.setPaused(true)
            playbackLifecyclePolicy.playbackInterrupted()
        }
        playbackLifecyclePolicy = VideoPlaybackLifecyclePolicy(
            mode = videoBackgroundMode,
            isCurrentlyPlaying = { !controller.state.value.isPaused },
            onPausePlayback = {
                controller.setPaused(true)
                audioFocusController.abandon()
            },
            onResumePlayback = {
                if (audioFocusController.request()) {
                    controller.setPaused(false)
                } else {
                    controller.markPaused(true)
                    controller.onError("无法获取音频焦点，已暂停播放")
                }
            },
            onCleanupPlayback = ::cleanupPlayer,
            onBackgroundTimeoutAfterCleanup = {
                if (!isFinishing) {
                    finish()
                }
            },
            onStartForegroundPlayback = {
                runCatching {
                    VideoPlaybackService.start(this, playerMediaContext.displayName, playbackSessionId)
                    true
                }.getOrElse {
                    controller.onError("后台播放启动失败，已暂停")
                    false
                }
            },
            onStopForegroundPlayback = {
                VideoPlaybackService.stop(this)
            },
        )
        playbackStopReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (VideoPlaybackService.isPlaybackStoppedForSession(intent, playbackSessionId)) {
                    playbackLifecyclePolicy.cleanup()
                    if (!isFinishing) finish()
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            playbackStopReceiver,
            IntentFilter(VideoPlaybackService.ACTION_PLAYBACK_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
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
                    onAnime4KEnabledSelected = controller::setAnime4KEnabled,
                    onAnime4KModeSelected = controller::setAnime4KMode,
                    onAnime4KQualitySelected = controller::setAnime4KQuality,
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
                    mediaContext = playerMediaContext,
                    episodeQueue = episodeQueue,
                    currentEpisodeIndex = currentEpisodeIndex,
                    isEpisodeSwitching = isEpisodeSwitching,
                    onPreviousEpisode = { switchToEpisode(currentEpisodeIndex - 1) },
                    onNextEpisode = { switchToEpisode(currentEpisodeIndex + 1) },
                    onEpisodeSelected = ::switchToEpisode,
                    controlsAutoHideMillis = controlsAutoHideMillis,
                    proxyStatistics = proxyStatistics,
                    proxyDebugInfoEnabled = proxyDebugInfoEnabled,
                )
            }
        }

        isEpisodeSwitching = true
        loadJob = activityScope.launch {
            try {
                if (!prepareMpv()) return@launch
                controller.setStartupRendererState(
                    videoOutputMode = startupCompatibility.effectiveVideoOutputMode,
                    gpuApiMode = initialGpuApiMode,
                    decoderMode = initialVideoDecoderMode,
                )
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
                    isWebDav = source == SOURCE_WEB_DAV,
                )
                if (loaded) startPlaybackProgressAutoSave()
            } finally {
                isEpisodeSwitching = false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isActivityInForeground = true
        if (::playbackLifecyclePolicy.isInitialized) {
            playbackLifecyclePolicy.returnToForeground()
        }
    }

    override fun onStop() {
        isActivityInForeground = false
        if (::playbackLifecyclePolicy.isInitialized && !isFinishing && !isCleaningUp) {
            playbackLifecyclePolicy.moveToBackground()
        }
        super.onStop()
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
        runCatching {
            if (::playbackStopReceiver.isInitialized) {
                unregisterReceiver(playbackStopReceiver)
            }
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
        playEpisodeWhenFileLoaded = false
        isCleaningUp = true
        try {
            savePlaybackPositionAsync()
            runCatching {
                if (::audioFocusController.isInitialized) {
                    audioFocusController.abandon()
                }
            }
            runCatching {
                VideoPlaybackService.stop(this)
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
        isWebDav: Boolean,
    ): Boolean {
        if (!canLoadMpv()) return false
        val resolvedInput = try {
            withContext(Dispatchers.IO) {
                resolvePlaybackInput(
                    uri = uri,
                    subtitles = subtitles,
                    isWebDav = isWebDav,
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

    private fun switchToEpisode(targetIndex: Int) {
        val queue = episodeQueue ?: return
        val episode = queue.episodes.getOrNull(targetIndex) ?: return
        if (targetIndex == currentEpisodeIndex || isEpisodeSwitching) return

        val wasPlayingBeforeSwitch = !controller.state.value.isPaused
        controller.setPaused(true)
        savePlaybackPositionAsync()
        isEpisodeSwitching = true
        loadJob = activityScope.launch {
            var preparedEpisode: PreparedVideoEpisode? = null
            var adoptedEpisode = false
            try {
                preparedEpisode = prepareEpisode(episode)
                val startPositionMillis = loadVideoStartPosition(
                    resumeEnabled = resumeEnabled,
                    playbackKey = episode.playbackKey,
                    loadPosition = { key ->
                        withContext(Dispatchers.IO) {
                            playbackStateStore.loadPosition(key)
                        }
                    },
                    onFailure = { error ->
                        System.err.println("Failed to load episode resume position: ${error.message ?: error::class.java.simpleName}")
                    },
                )
                if (!canLoadMpv()) return@launch
                ignoreNextMpvStopEndFile = true
                playEpisodeWhenFileLoaded = true
                val loaded = loadMpv(
                    uri = preparedEpisode.uri,
                    displayName = episode.displayName,
                    startPositionMillis = startPositionMillis,
                    subtitles = preparedEpisode.subtitles,
                    isWebDav = episode.source == VideoEpisodeSource.WEB_DAV,
                )
                if (!loaded) {
                    ignoreNextMpvStopEndFile = false
                    return@launch
                }

                val previousStreamIds = webDavStreamIds
                webDavStreamIds = preparedEpisode.webDavStreamIds
                playbackKey = episode.playbackKey
                currentEpisodeIndex = targetIndex
                playerMediaContext = VideoPlayerMediaContext(
                    displayName = episode.displayName,
                    source = if (episode.source == VideoEpisodeSource.WEB_DAV) SOURCE_WEB_DAV else SOURCE_LOCAL,
                    remotePath = episode.webDavRequest?.remotePath ?: episode.localRequest?.uri,
                )
                adoptedEpisode = true
                VideoProxyManager.close(previousStreamIds)
                startPlaybackProgressAutoSave()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                controller.onError(error.message ?: "切换剧集失败，请重试")
            } finally {
                if (!adoptedEpisode) {
                    playEpisodeWhenFileLoaded = false
                    preparedEpisode?.webDavStreamIds?.let(VideoProxyManager::close)
                    if (wasPlayingBeforeSwitch && isActivityInForeground && canLoadMpv()) {
                        if (audioFocusController.request()) controller.setPaused(false)
                    }
                }
                isEpisodeSwitching = false
            }
        }
    }

    private suspend fun prepareEpisode(episode: VideoEpisode): PreparedVideoEpisode =
        when (episode.source) {
            VideoEpisodeSource.LOCAL -> {
                val request = requireNotNull(episode.localRequest)
                PreparedVideoEpisode(
                    uri = request.uri,
                    subtitles = request.subtitles,
                )
            }
            VideoEpisodeSource.WEB_DAV -> {
                val request = requireNotNull(episode.webDavRequest)
                val account = withContext(Dispatchers.IO) {
                    playerWebDavAccountStore.loadAccount(request.accountId)
                } ?: error("缺少 WebDAV 账号，请重新连接后再切换剧集")
                val proxySettings = withContext(Dispatchers.IO) {
                    playerSettingsStore.settings.first().let { settings ->
                        VideoProxySettings(
                            seekOptimizationEnabled = settings.videoSeekOptimizationEnabled,
                            forwardPrefetchMode = settings.videoForwardPrefetchMode,
                            diagnosticsMode = settings.videoProxyDiagnosticsMode,
                        )
                    }
                }
                val session = VideoProxyManager.open(
                    request = request,
                    account = account,
                    proxySettings = proxySettings,
                )
                PreparedVideoEpisode(
                    uri = session.url,
                    subtitles = request.subtitles.zip(session.subtitleUrls).map { (subtitle, subtitleUrl) ->
                        VideoSubtitleOpenRequest(
                            uri = subtitleUrl,
                            displayName = subtitle.displayName,
                        )
                    },
                    webDavStreamIds = session.streamIds,
                )
            }
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

    private fun Intent.episodeQueue(): VideoEpisodeQueue? =
        VideoEpisodeQueueRegistry.consume(getStringExtra(EXTRA_EPISODE_QUEUE_ID))

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 2001
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
        const val EXTRA_VIDEO_BACKGROUND_MODE = "com.example.comicdav.video.extra.VIDEO_BACKGROUND_MODE"
        const val EXTRA_ANIME4K_ENABLED = "com.example.comicdav.video.extra.ANIME4K_ENABLED"
        const val EXTRA_ANIME4K_MODE = "com.example.comicdav.video.extra.ANIME4K_MODE"
        const val EXTRA_ANIME4K_QUALITY = "com.example.comicdav.video.extra.ANIME4K_QUALITY"
        const val EXTRA_EPISODE_QUEUE_ID = "com.example.comicdav.video.extra.EPISODE_QUEUE_ID"
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
            videoBackgroundMode: VideoBackgroundMode = VideoBackgroundMode.NONE,
            anime4kEnabled: Boolean = false,
            anime4kMode: Anime4KMode = Anime4KMode.A,
            anime4kQuality: Anime4KQuality = Anime4KQuality.FAST,
            episodeQueue: VideoEpisodeQueue? = null,
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
                .putExtra(EXTRA_VIDEO_BACKGROUND_MODE, videoBackgroundMode.name)
                .putExtra(EXTRA_ANIME4K_ENABLED, anime4kEnabled)
                .putExtra(EXTRA_ANIME4K_MODE, anime4kMode.name)
                .putExtra(EXTRA_ANIME4K_QUALITY, anime4kQuality.name)
                .putSubtitleExtras(request.subtitles)
                .putEpisodeQueueExtra(episodeQueue)

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
            videoBackgroundMode: VideoBackgroundMode = VideoBackgroundMode.NONE,
            anime4kEnabled: Boolean = false,
            anime4kMode: Anime4KMode = Anime4KMode.A,
            anime4kQuality: Anime4KQuality = Anime4KQuality.FAST,
            episodeQueue: VideoEpisodeQueue? = null,
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
                    .putExtra(EXTRA_VIDEO_BACKGROUND_MODE, videoBackgroundMode.name)
                    .putExtra(EXTRA_ANIME4K_ENABLED, anime4kEnabled)
                    .putExtra(EXTRA_ANIME4K_MODE, anime4kMode.name)
                    .putExtra(EXTRA_ANIME4K_QUALITY, anime4kQuality.name)
                    .putStringArrayListExtra(EXTRA_WEB_DAV_STREAM_IDS, ArrayList(streamIds))
                    .putSubtitleExtras(subtitles)
                    .putEpisodeQueueExtra(episodeQueue)
                }

        private const val SOURCE_WEB_DAV = "webdav"

        private fun Intent.putSubtitleExtras(subtitles: List<VideoSubtitleOpenRequest>): Intent =
            putStringArrayListExtra(EXTRA_SUBTITLE_URIS, ArrayList(subtitles.map { it.uri }))
                .putStringArrayListExtra(EXTRA_SUBTITLE_NAMES, ArrayList(subtitles.map { it.displayName }))

        private fun Intent.putEpisodeQueueExtra(episodeQueue: VideoEpisodeQueue?): Intent =
            apply {
                if (episodeQueue != null) {
                    putExtra(EXTRA_EPISODE_QUEUE_ID, VideoEpisodeQueueRegistry.register(episodeQueue))
                }
            }
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
    onAnime4KEnabledSelected: (Boolean) -> Unit,
    onAnime4KModeSelected: (Anime4KMode) -> Unit,
    onAnime4KQualitySelected: (Anime4KQuality) -> Unit,
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
    episodeQueue: VideoEpisodeQueue?,
    currentEpisodeIndex: Int,
    isEpisodeSwitching: Boolean,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onEpisodeSelected: (Int) -> Unit,
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
    var episodePageVisible by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lockButtonVisible by remember { mutableStateOf(true) }
    var lockButtonRevealSignal by remember { mutableIntStateOf(0) }
    val controlsLocked = state.gestureState.controlsLocked

    BackHandler(enabled = episodePageVisible) {
        episodePageVisible = false
    }

    LaunchedEffect(
        controlsVisible,
        menuVisible,
        episodePageVisible,
        controlsLocked,
        controlsAutoHideMillis,
    ) {
        if (!controlsVisible || controlsLocked || menuVisible || episodePageVisible || controlsAutoHideMillis <= 0) {
            return@LaunchedEffect
        }
        delay(controlsAutoHideMillis.toLong())
        menuVisible = false
        controlsVisible = false
    }

    LaunchedEffect(controlsLocked) {
        if (controlsLocked) {
            menuVisible = false
            episodePageVisible = false
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
                        episodePageVisible = false
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
                        episodePageVisible = false
                        menuVisible = !menuVisible
                    },
                    showEpisodeButton = (episodeQueue?.episodes?.size ?: 0) > 1,
                    onEpisodeClick = {
                        menuVisible = false
                        episodePageVisible = true
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
                        episodePageVisible = false
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
                    onAnime4KEnabledSelected = onAnime4KEnabledSelected,
                    onAnime4KModeSelected = onAnime4KModeSelected,
                    onAnime4KQualitySelected = onAnime4KQualitySelected,
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
                    hasPreviousEpisode = episodeQueue?.let { currentEpisodeIndex > 0 } == true,
                    hasNextEpisode = episodeQueue?.let { currentEpisodeIndex < it.episodes.lastIndex } == true,
                    isEpisodeSwitching = isEpisodeSwitching,
                    onPreviousEpisode = onPreviousEpisode,
                    onNextEpisode = onNextEpisode,
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

            if (!controlsLocked && episodePageVisible && episodeQueue != null) {
                EpisodeSelectionPage(
                    queue = episodeQueue,
                    currentEpisodeIndex = currentEpisodeIndex,
                    isSwitching = isEpisodeSwitching,
                    onDismiss = { episodePageVisible = false },
                    onEpisodeSelected = { index ->
                        episodePageVisible = false
                        controlsVisible = true
                        onEpisodeSelected(index)
                    },
                    modifier = Modifier.fillMaxSize(),
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

private data class PreparedVideoEpisode(
    val uri: String,
    val subtitles: List<VideoSubtitleOpenRequest>,
    val webDavStreamIds: List<String> = emptyList(),
)

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
    this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default
