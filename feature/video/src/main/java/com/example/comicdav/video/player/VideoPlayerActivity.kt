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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.preferencesDataStore
import com.example.comicdav.core.model.media.LocalVideoOpenRequest
import com.example.comicdav.core.model.media.VideoSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import com.example.comicdav.core.model.settings.Anime4KSettings
import com.example.comicdav.core.model.settings.VideoBackgroundMode
import com.example.comicdav.ui.ComicDavTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

class VideoPlayerActivity : ComponentActivity() {
    private lateinit var mpvView: MuBoxMpvView
    private lateinit var controller: MpvController
    private lateinit var sessionCoordinator: VideoPlayerSessionCoordinator
    private lateinit var audioFocusController: VideoAudioFocusController
    private lateinit var playbackLifecyclePolicy: VideoPlaybackLifecyclePolicy
    private lateinit var playbackPersistenceCoordinator: VideoPlaybackPersistenceCoordinator
    private lateinit var orientationSession: VideoPlayerOrientationSession
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webDavStreamIds by mutableStateOf<List<String>>(emptyList())
    private var videoBackgroundMode = VideoBackgroundMode.NONE
    private var proxyStatistics by mutableStateOf<VideoProxyStatistics?>(null)
    private var episodeQueue: VideoEpisodeQueue? = null
    private var currentEpisodeIndex by mutableIntStateOf(0)
    private var isEpisodeSwitching by mutableStateOf(false)
    private var isActivityInForeground = false
    private var playerMediaContext by mutableStateOf(
        VideoPlayerMediaContext(displayName = "视频", source = SOURCE_LOCAL, remotePath = null),
    )
    private val playerDependencies: VideoPlayerDependencies by lazy(LazyThreadSafetyMode.NONE) {
        (application as? VideoPlayerDependenciesOwner)?.videoPlayerDependencies
            ?: error("Application does not provide video player dependencies")
    }
    private val episodeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        VideoEpisodeCoordinator(
            dependencies = playerDependencies,
            proxyGateway = VideoProxyManagerGateway,
        )
    }
    private val systemBarsHandler = Handler(Looper.getMainLooper())
    private val hideStatusBarRunnable = Runnable { hidePlayerStatusBar() }
    private val playbackSessionId: String = UUID.randomUUID().toString()
    private lateinit var playbackStopReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchArguments = VideoPlayerLaunchContract.read(this, intent)
        val playerOptions = launchArguments.options
        val initialPlayerOrientationMode = playerOptions.playerOrientationMode
        orientationSession = VideoPlayerOrientationSession(initialPlayerOrientationMode)
        requestedOrientation = orientationSession.initialRequestedOrientation()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val launchUri = launchArguments.uri
        val launchPlaybackKey = launchArguments.playbackKey
        episodeQueue = launchArguments.episodeQueue?.withCurrentPlaybackKey(launchPlaybackKey)
        val restoredEpisode = restoredVideoEpisodeSelection(
            episodeQueue = episodeQueue,
            savedEpisodeIndex = savedInstanceState
                ?.takeIf { it.containsKey(STATE_CURRENT_EPISODE_INDEX) }
                ?.getInt(STATE_CURRENT_EPISODE_INDEX),
        )
        currentEpisodeIndex = restoredEpisode?.index ?: episodeQueue?.currentIndex ?: 0
        val initialPlaybackKey = restoredEpisode?.episode?.playbackKey ?: launchPlaybackKey
        playerMediaContext = restoredEpisode?.episode?.toPlayerMediaContext()
            ?: VideoPlayerMediaContext(
                displayName = launchArguments.displayName,
                source = launchArguments.source,
                remotePath = launchArguments.remotePath,
            )
        val initialVideoOutputMode = playerOptions.videoOutputMode
        val initialGpuApiMode = playerOptions.gpuApiMode
        val initialVideoDecoderMode = playerOptions.videoDecoderMode
        val initialMpvProfileMode = playerOptions.mpvProfileMode
        val initialAnime4KSettings = Anime4KSettings(
            enabled = playerOptions.anime4kEnabled,
            mode = playerOptions.anime4kMode,
            quality = playerOptions.anime4kQuality,
        )
        val anime4kManager = Anime4KManager(applicationContext)
        val startupCompatibility = anime4kStartupCompatibility(
            settings = initialAnime4KSettings,
            requestedVideoOutputMode = initialVideoOutputMode,
            gpuApiMode = initialGpuApiMode,
        )
        val controlsAutoHideMillis = playerOptions.controlsAutoHideMillis
        val proxyDebugInfoEnabled = playerOptions.proxyDebugInfoEnabled
        val initialVideoBackgroundMode = playerOptions.videoBackgroundMode
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
        val playbackStateStore = VideoPlaybackStateStore(applicationContext.videoPlaybackStateDataStore)
        val progressSaver = VideoPlaybackProgressSaver(playbackPersistenceScope) { key, positionMillis, durationMillis ->
            playbackStateStore.savePosition(
                playbackKey = key,
                positionMillis = positionMillis,
                durationMillis = durationMillis,
            )
        }
        if (launchUri.isNullOrBlank() && restoredEpisode == null) {
            finish()
            return
        }
        if (restoredEpisode == null && launchArguments.isWebDav) {
            webDavStreamIds = episodeCoordinator.initialWebDavStreamIds(
                uri = requireNotNull(launchUri),
                explicitStreamIds = launchArguments.webDavStreamIds,
            )
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
        playbackPersistenceCoordinator = VideoPlaybackPersistenceCoordinator(
            autoSaveScope = activityScope,
            resumeEnabled = playerOptions.resumeEnabled,
            initialPlaybackKey = initialPlaybackKey,
            loadPosition = { key ->
                withContext(Dispatchers.IO) {
                    playbackStateStore.loadPosition(key)
                }
            },
            savePositionAsync = { key, positionMillis, durationMillis ->
                progressSaver.saveAsync(
                    playbackKey = key,
                    positionMillis = positionMillis,
                    durationMillis = durationMillis,
                )
            },
            currentProgress = { controller.progress.value },
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
        val playbackInputResolver = AndroidVideoPlaybackInputResolver(this)
        sessionCoordinator = VideoPlayerSessionCoordinator(
            scope = activityScope,
            controller = controller,
            runtime = AndroidVideoPlayerMpvRuntime(
                context = this,
                mpvView = mpvView,
                filesDirectoryPath = filesDir.path,
                cacheDirectoryPath = cacheDir.path,
            ),
            dispatchToMain = { action -> runOnUiThread { action() } },
            isHostFinishing = { isFinishing },
            isHostInForeground = { isActivityInForeground },
            resolvePlaybackInput = { request -> playbackInputResolver.resolve(request) },
            requestAudioFocus = audioFocusController::request,
            onPlaybackEnded = playbackLifecyclePolicy::playbackEnded,
            onPlaybackInterrupted = playbackLifecyclePolicy::playbackInterrupted,
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
                        proxyStatistics = episodeCoordinator.statistics(webDavStreamIds)
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
                    onConfigureSystemBars = ::configurePlayerSystemBars,
                    onRestoreSystemBars = ::restorePlayerSystemBars,
                )
            }
        }

        isEpisodeSwitching = true
        sessionCoordinator.launchLoad {
            var preparedRestoredEpisode: PreparedVideoEpisode? = null
            var adoptedRestoredEpisode = false
            try {
                if (!sessionCoordinator.prepare()) return@launchLoad
                controller.setStartupRendererState(
                    videoOutputMode = startupCompatibility.effectiveVideoOutputMode,
                    gpuApiMode = initialGpuApiMode,
                    decoderMode = initialVideoDecoderMode,
                )
                val playbackUri: String
                val playbackDisplayName: String
                val playbackSubtitles: List<VideoSubtitleOpenRequest>
                val playbackIsWebDav: Boolean
                if (restoredEpisode != null) {
                    val episode = restoredEpisode.episode
                    val prepared = episodeCoordinator.prepare(episode)
                    preparedRestoredEpisode = prepared
                    playbackUri = prepared.uri
                    playbackDisplayName = episode.displayName
                    playbackSubtitles = prepared.subtitles
                    playbackIsWebDav = episode.source == VideoEpisodeSource.WEB_DAV
                } else {
                    playbackUri = requireNotNull(launchUri)
                    playbackDisplayName = launchArguments.displayName
                    playbackSubtitles = launchArguments.subtitles
                    playbackIsWebDav = launchArguments.isWebDav
                }
                val startPositionMillis = playbackPersistenceCoordinator.loadStartPosition(
                    onFailure = { error ->
                        System.err.println("Failed to load video resume position: ${error.message ?: error::class.java.simpleName}")
                    },
                )
                if (!sessionCoordinator.canLoad()) return@launchLoad
                val loaded = sessionCoordinator.load(
                    uri = playbackUri,
                    displayName = playbackDisplayName,
                    startPositionMillis = startPositionMillis,
                    subtitles = playbackSubtitles,
                    isWebDav = playbackIsWebDav,
                )
                if (loaded) {
                    preparedRestoredEpisode?.let { prepared ->
                        webDavStreamIds = prepared.webDavStreamIds
                        adoptedRestoredEpisode = true
                    }
                    playbackPersistenceCoordinator.startAutoSave()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                controller.onError(error.message ?: "视频播放器初始化失败")
            } finally {
                if (!adoptedRestoredEpisode) {
                    preparedRestoredEpisode?.webDavStreamIds?.let(episodeCoordinator::close)
                }
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
        if (
            ::playbackLifecyclePolicy.isInitialized &&
            !isFinishing &&
            (!::sessionCoordinator.isInitialized || !sessionCoordinator.isClosingOrClosed)
        ) {
            playbackLifecyclePolicy.moveToBackground()
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_CURRENT_EPISODE_INDEX, currentEpisodeIndex)
        super.onSaveInstanceState(outState)
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
        playbackLifecyclePolicy.cleanup()
        finish()
    }

    private fun cleanupPlayer() {
        if (!::sessionCoordinator.isInitialized) return
        sessionCoordinator.cleanup(
            onBeforeMpvCleanup = {
                playbackPersistenceCoordinator.stopAutoSave()
                playbackPersistenceCoordinator.saveCurrentPositionAsync()
                runCatching { audioFocusController.abandon() }
                runCatching { VideoPlaybackService.stop(this) }
            },
            onAfterMpvCleanup = {
                episodeCoordinator.close(webDavStreamIds)
                webDavStreamIds = emptyList()
            },
        )
    }

    private fun switchToEpisode(targetIndex: Int) {
        val queue = episodeQueue ?: return
        val episode = queue.episodes.getOrNull(targetIndex) ?: return
        if (targetIndex == currentEpisodeIndex || isEpisodeSwitching) return

        val wasPlayingBeforeSwitch = !controller.state.value.isPaused
        controller.setPaused(true)
        playbackPersistenceCoordinator.saveCurrentPositionAsync()
        isEpisodeSwitching = true
        sessionCoordinator.launchLoad {
            var preparedEpisode: PreparedVideoEpisode? = null
            var adoptedEpisode = false
            try {
                val prepared = episodeCoordinator.prepare(episode)
                preparedEpisode = prepared
                val startPositionMillis = playbackPersistenceCoordinator.loadStartPosition(
                    playbackKey = episode.playbackKey,
                    onFailure = { error ->
                        System.err.println("Failed to load episode resume position: ${error.message ?: error::class.java.simpleName}")
                    },
                )
                if (!sessionCoordinator.canLoad()) return@launchLoad
                sessionCoordinator.beginEpisodeTransition()
                val loaded = sessionCoordinator.load(
                    uri = prepared.uri,
                    displayName = episode.displayName,
                    startPositionMillis = startPositionMillis,
                    subtitles = prepared.subtitles,
                    isWebDav = episode.source == VideoEpisodeSource.WEB_DAV,
                )
                if (!loaded) {
                    sessionCoordinator.cancelEpisodeTransition()
                    return@launchLoad
                }

                val previousStreamIds = webDavStreamIds
                webDavStreamIds = prepared.webDavStreamIds
                playbackPersistenceCoordinator.adoptPlaybackKey(episode.playbackKey)
                currentEpisodeIndex = targetIndex
                playerMediaContext = episode.toPlayerMediaContext()
                adoptedEpisode = true
                episodeCoordinator.close(previousStreamIds)
                playbackPersistenceCoordinator.startAutoSave()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                controller.onError(error.message ?: "切换剧集失败，请重试")
            } finally {
                if (!adoptedEpisode) {
                    sessionCoordinator.cancelEpisodeTransition()
                    preparedEpisode?.webDavStreamIds?.let(episodeCoordinator::close)
                    if (wasPlayingBeforeSwitch && isActivityInForeground && sessionCoordinator.canLoad()) {
                        if (audioFocusController.request()) controller.setPaused(false)
                    }
                }
                isEpisodeSwitching = false
            }
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

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 2001
        private const val STATE_CURRENT_EPISODE_INDEX = "video_player_current_episode_index"
        const val EXTRA_SOURCE = VideoPlayerLaunchContract.EXTRA_SOURCE
        const val EXTRA_URI = VideoPlayerLaunchContract.EXTRA_URI
        const val EXTRA_DISPLAY_NAME = VideoPlayerLaunchContract.EXTRA_DISPLAY_NAME
        const val EXTRA_SIZE = VideoPlayerLaunchContract.EXTRA_SIZE
        const val EXTRA_LAST_MODIFIED = VideoPlayerLaunchContract.EXTRA_LAST_MODIFIED
        const val EXTRA_SUBTITLE_URIS = VideoPlayerLaunchContract.EXTRA_SUBTITLE_URIS
        const val EXTRA_SUBTITLE_NAMES = VideoPlayerLaunchContract.EXTRA_SUBTITLE_NAMES
        const val EXTRA_WEB_DAV_STREAM_IDS = VideoPlayerLaunchContract.EXTRA_WEB_DAV_STREAM_IDS
        const val EXTRA_PLAYBACK_KEY = VideoPlayerLaunchContract.EXTRA_PLAYBACK_KEY
        const val EXTRA_RESUME_ENABLED = VideoPlayerLaunchContract.EXTRA_RESUME_ENABLED
        const val EXTRA_REMOTE_PATH = VideoPlayerLaunchContract.EXTRA_REMOTE_PATH
        const val EXTRA_VIDEO_OUTPUT_MODE = VideoPlayerLaunchContract.EXTRA_VIDEO_OUTPUT_MODE
        const val EXTRA_GPU_API_MODE = VideoPlayerLaunchContract.EXTRA_GPU_API_MODE
        const val EXTRA_VIDEO_DECODER_MODE = VideoPlayerLaunchContract.EXTRA_VIDEO_DECODER_MODE
        const val EXTRA_MPV_PROFILE_MODE = VideoPlayerLaunchContract.EXTRA_MPV_PROFILE_MODE
        const val EXTRA_CONTROLS_AUTO_HIDE_MILLIS = VideoPlayerLaunchContract.EXTRA_CONTROLS_AUTO_HIDE_MILLIS
        const val EXTRA_PLAYER_ORIENTATION_MODE = VideoPlayerLaunchContract.EXTRA_PLAYER_ORIENTATION_MODE
        const val EXTRA_PROXY_DEBUG_INFO_ENABLED = VideoPlayerLaunchContract.EXTRA_PROXY_DEBUG_INFO_ENABLED
        const val EXTRA_VIDEO_BACKGROUND_MODE = VideoPlayerLaunchContract.EXTRA_VIDEO_BACKGROUND_MODE
        const val EXTRA_ANIME4K_ENABLED = VideoPlayerLaunchContract.EXTRA_ANIME4K_ENABLED
        const val EXTRA_ANIME4K_MODE = VideoPlayerLaunchContract.EXTRA_ANIME4K_MODE
        const val EXTRA_ANIME4K_QUALITY = VideoPlayerLaunchContract.EXTRA_ANIME4K_QUALITY
        const val EXTRA_PLAYER_OPTIONS = VideoPlayerLaunchContract.EXTRA_PLAYER_OPTIONS
        const val EXTRA_EPISODE_QUEUE_ID = VideoPlayerLaunchContract.EXTRA_EPISODE_QUEUE_ID
        const val SOURCE_LOCAL = VideoPlayerLaunchContract.SOURCE_LOCAL

        fun localIntent(
            context: Context,
            request: LocalVideoOpenRequest,
            options: VideoPlayerOptions = VideoPlayerOptions(),
            episodeQueue: VideoEpisodeQueue? = null,
        ): Intent =
            VideoPlayerLaunchContract.localIntent(
                context = context,
                request = request,
                options = options,
                episodeQueue = episodeQueue,
            )

        fun webDavIntent(
            context: Context,
            request: WebDavVideoOpenRequest,
            uri: String,
            subtitleUrls: List<String>,
            streamIds: List<String>,
            options: VideoPlayerOptions = VideoPlayerOptions(),
            episodeQueue: VideoEpisodeQueue? = null,
        ): Intent =
            VideoPlayerLaunchContract.webDavIntent(
                context = context,
                request = request,
                uri = uri,
                subtitleUrls = subtitleUrls,
                streamIds = streamIds,
                options = options,
                episodeQueue = episodeQueue,
            )
    }
}


private const val PLAYER_STATUS_BAR_REHIDE_MILLIS = 3_000L
private const val PROXY_STATISTICS_SAMPLE_INTERVAL_MILLIS = 1_000L
