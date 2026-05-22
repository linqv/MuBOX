package com.example.comicdav.video.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.comicdav.ui.ComicDavTheme
import com.example.comicdav.video.LocalVideoOpenRequest
import com.example.comicdav.video.VideoSubtitleOpenRequest
import com.example.comicdav.video.WebDavVideoOpenRequest
import com.example.comicdav.video.proxy.MuBoxVideoProxy
import com.example.comicdav.video.proxy.VideoProxyManager
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mpvObserverRegistered = false
    private var mpvInitialized = false
    private var isCleaningUp = false
    private var webDavStreamIds: List<String> = emptyList()
    private var playbackKey: String? = null
    private var resumeEnabled = true
    private var loadJob: Job? = null

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
                    "sub-delay" -> controller.onSubtitleDelayChanged(value)
                    "audio-delay" -> controller.onAudioDelayChanged(value)
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val uri = intent.getStringExtra(EXTRA_URI)
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: intent.data?.lastPathSegment ?: "视频"
        val source = intent.getStringExtra(EXTRA_SOURCE)
        val mediaContext = VideoPlayerMediaContext(
            displayName = displayName,
            source = source ?: SOURCE_LOCAL,
            remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH),
        )
        val playbackQueue = intent.playbackQueue()
        val subtitles = intent.subtitleRequests()
        playbackKey = intent.getStringExtra(EXTRA_PLAYBACK_KEY)
        resumeEnabled = intent.getBooleanExtra(EXTRA_RESUME_ENABLED, true)
        val initialVideoOutputMode = intent.getStringExtra(EXTRA_VIDEO_OUTPUT_MODE)
            .toEnumOrDefault(VideoOutputMode.AUTO)
        val initialGpuApiMode = intent.getStringExtra(EXTRA_GPU_API_MODE)
            .toEnumOrDefault(GpuApiMode.AUTO)
        val initialVideoDecoderMode = intent.getStringExtra(EXTRA_VIDEO_DECODER_MODE)
            .toEnumOrDefault(VideoDecoderMode.AUTO)
        val controlsAutoHideMillis = intent.getIntExtra(EXTRA_CONTROLS_AUTO_HIDE_MILLIS, 5_000)
        playbackStateStore = VideoPlaybackStateStore(applicationContext.videoPlaybackStateDataStore)
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
        val mpvPrepared = prepareMpv()
        if (mpvPrepared) {
            controller.setVideoOutputMode(initialVideoOutputMode)
            controller.setGpuApiMode(initialGpuApiMode)
            controller.setDecoderMode(initialVideoDecoderMode)
        }
        setContent {
            ComicDavTheme {
                val state by controller.state.collectAsState()
                BackHandler {
                    closePlayer()
                }
                VideoPlayerScreen(
                    state = state,
                    mpvView = mpvView,
                    onClose = ::closePlayer,
                    onPlayPause = controller::togglePlayPause,
                    onSeek = controller::seekTo,
                    onSpeedSelected = controller::setPlaybackSpeed,
                    onAudioTrackSelected = controller::selectAudioTrack,
                    onSubtitleTrackSelected = controller::selectSubtitleTrack,
                    onSubtitlesDisabled = controller::disableSubtitles,
                    onSubtitleDelayChanged = controller::adjustSubtitleDelay,
                    onAudioDelayChanged = controller::adjustAudioDelay,
                    onScaleModeSelected = controller::setScaleMode,
                    onDecoderModeSelected = controller::setDecoderMode,
                    onControlsLockedChanged = controller::setControlsLocked,
                    onVolumeDelta = controller::adjustGestureVolume,
                    onBrightnessDelta = ::handleBrightnessGesture,
                    onDoubleTapSeek = controller::handleDoubleTapSeek,
                    onZoomDelta = controller::adjustGestureZoom,
                    onClearHud = controller::clearGestureHud,
                    mediaContext = mediaContext,
                    queue = playbackQueue,
                    controlsAutoHideMillis = controlsAutoHideMillis,
                )
            }
        }

        if (mpvPrepared) {
            loadJob = activityScope.launch {
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
                loadMpv(
                    uri = uri,
                    displayName = displayName,
                    startPositionMillis = startPositionMillis,
                    subtitles = subtitles,
                )
            }
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

    override fun onDestroy() {
        if (::playbackLifecyclePolicy.isInitialized) {
            playbackLifecyclePolicy.cleanup()
        }
        activityScope.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun closePlayer() {
        cancelPendingLoad()
        playbackLifecyclePolicy.cleanup()
        finish()
    }

    private fun cleanupPlayer() {
        if (isCleaningUp) return
        cancelPendingLoad()
        isCleaningUp = true
        try {
            savePlaybackPositionNow()
            runCatching {
                if (::audioFocusController.isInitialized) {
                    audioFocusController.abandon()
                }
            }
            webDavStreamIds.forEach(VideoProxyManager::close)
            webDavStreamIds = emptyList()
            if (mpvObserverRegistered) {
                MPVLib.removeObserver(mpvObserver)
                mpvObserverRegistered = false
            }
            if (mpvInitialized) {
                controller.destroy()
                mpvInitialized = false
            }
        } finally {
            isCleaningUp = false
        }
    }

    private fun prepareMpv(): Boolean =
        runCatching {
            Utils.copyAssets(this)
            mpvView.initialize(filesDir.path, cacheDir.path)
            mpvInitialized = true
            MPVLib.addObserver(mpvObserver)
            mpvObserverRegistered = true
        }.onFailure { error ->
            controller.onError(error.message ?: "视频播放器初始化失败")
        }.isSuccess

    private fun loadMpv(
        uri: String,
        displayName: String,
        startPositionMillis: Long,
        subtitles: List<VideoSubtitleOpenRequest>,
    ) {
        if (!canLoadMpv()) return
        runCatching {
            val isWebDav = intent.getStringExtra(EXTRA_SOURCE) == SOURCE_WEB_DAV
            val localUriResolver = LocalVideoUriResolver(this)
            val playableUri = if (intent.getStringExtra(EXTRA_SOURCE) == SOURCE_WEB_DAV) {
                uri
            } else {
                localUriResolver.resolve(uri)
            }
            val playableSubtitles = subtitles.map { subtitle ->
                if (isWebDav) {
                    subtitle
                } else {
                    subtitle.copy(uri = localUriResolver.resolveSubtitle(subtitle.uri, subtitle.displayName))
                }
            }
            if (!canLoadMpv()) return
            if (audioFocusController.request()) {
                controller.load(
                    playableUri,
                    displayName,
                    startPositionMillis = startPositionMillis,
                    subtitles = playableSubtitles,
                )
            } else {
                controller.markPaused(true)
                controller.onError("无法获取音频焦点，已暂停播放")
            }
        }.onFailure { error ->
            controller.onError(error.message ?: "视频播放器初始化失败")
        }
    }

    private fun canLoadMpv(): Boolean =
        mpvInitialized && !isCleaningUp && !isFinishing

    private fun savePlaybackPositionNow() {
        if (!resumeEnabled || !::playbackStateStore.isInitialized || !::controller.isInitialized) return
        val key = playbackKey?.takeIf { it.isNotBlank() } ?: return
        val state = controller.state.value
        runCatching {
            runBlocking(Dispatchers.IO) {
                playbackStateStore.savePosition(
                    playbackKey = key,
                    positionMillis = state.positionMillis,
                    durationMillis = state.durationMillis,
                )
            }
        }
    }

    private fun cancelPendingLoad() {
        loadJob?.cancel()
        loadJob = null
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

    private fun Intent.playbackQueue(): VideoPlaybackQueue? {
        val keys = getStringArrayListExtra(EXTRA_QUEUE_KEYS).orEmpty()
        val names = getStringArrayListExtra(EXTRA_QUEUE_NAMES).orEmpty()
        val uris = getStringArrayListExtra(EXTRA_QUEUE_URIS).orEmpty()
        if (keys.isEmpty() || keys.size != names.size || keys.size != uris.size) return null
        val source = when (getStringExtra(EXTRA_QUEUE_SOURCE)) {
            QUEUE_SOURCE_WEB_DAV -> VideoQueueSource.WEB_DAV
            QUEUE_SOURCE_LOCAL -> VideoQueueSource.LOCAL
            else -> return null
        }
        val items = keys.indices.map { index ->
            VideoQueueItem(
                playbackKey = keys[index],
                displayName = names[index],
                sourceUri = uris[index],
                source = source,
            )
        }
        return runCatching {
            VideoPlaybackQueue(
                items = items,
                currentIndex = getIntExtra(EXTRA_QUEUE_INDEX, 0),
            )
        }.getOrNull()
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
        const val EXTRA_QUEUE_KEYS = "com.example.comicdav.video.extra.QUEUE_KEYS"
        const val EXTRA_QUEUE_NAMES = "com.example.comicdav.video.extra.QUEUE_NAMES"
        const val EXTRA_QUEUE_URIS = "com.example.comicdav.video.extra.QUEUE_URIS"
        const val EXTRA_QUEUE_SOURCE = "com.example.comicdav.video.extra.QUEUE_SOURCE"
        const val EXTRA_QUEUE_INDEX = "com.example.comicdav.video.extra.QUEUE_INDEX"
        const val EXTRA_VIDEO_OUTPUT_MODE = "com.example.comicdav.video.extra.VIDEO_OUTPUT_MODE"
        const val EXTRA_GPU_API_MODE = "com.example.comicdav.video.extra.GPU_API_MODE"
        const val EXTRA_VIDEO_DECODER_MODE = "com.example.comicdav.video.extra.VIDEO_DECODER_MODE"
        const val EXTRA_CONTROLS_AUTO_HIDE_MILLIS = "com.example.comicdav.video.extra.CONTROLS_AUTO_HIDE_MILLIS"
        const val SOURCE_LOCAL = "local"

        fun localIntent(
            context: Context,
            request: LocalVideoOpenRequest,
            resumeEnabled: Boolean = true,
            videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO,
            gpuApiMode: GpuApiMode = GpuApiMode.AUTO,
            videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
            controlsAutoHideMillis: Int = 5_000,
            queue: VideoPlaybackQueue? = null,
        ): Intent =
            Intent(context, VideoPlayerActivity::class.java)
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
                .putExtra(EXTRA_CONTROLS_AUTO_HIDE_MILLIS, controlsAutoHideMillis)
                .putQueueExtras(queue)
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
            controlsAutoHideMillis: Int = 5_000,
            queue: VideoPlaybackQueue? = null,
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
                    .putExtra(EXTRA_CONTROLS_AUTO_HIDE_MILLIS, controlsAutoHideMillis)
                    .putStringArrayListExtra(EXTRA_WEB_DAV_STREAM_IDS, ArrayList(streamIds))
                    .putQueueExtras(queue)
                    .putSubtitleExtras(subtitles)
                }

        private const val SOURCE_WEB_DAV = "webdav"
        private const val QUEUE_SOURCE_LOCAL = "local"
        private const val QUEUE_SOURCE_WEB_DAV = "webdav"

        private fun Intent.putSubtitleExtras(subtitles: List<VideoSubtitleOpenRequest>): Intent =
            putStringArrayListExtra(EXTRA_SUBTITLE_URIS, ArrayList(subtitles.map { it.uri }))
                .putStringArrayListExtra(EXTRA_SUBTITLE_NAMES, ArrayList(subtitles.map { it.displayName }))

        private fun Intent.putQueueExtras(queue: VideoPlaybackQueue?): Intent {
            if (queue == null) return this
            return putStringArrayListExtra(EXTRA_QUEUE_KEYS, ArrayList(queue.items.map { it.playbackKey }))
                .putStringArrayListExtra(EXTRA_QUEUE_NAMES, ArrayList(queue.items.map { it.displayName }))
                .putStringArrayListExtra(EXTRA_QUEUE_URIS, ArrayList(queue.items.map { it.sourceUri }))
                .putExtra(EXTRA_QUEUE_SOURCE, queue.currentItem?.source?.extraValue())
                .putExtra(EXTRA_QUEUE_INDEX, queue.currentIndex)
        }

        private fun VideoQueueSource.extraValue(): String =
            when (this) {
                VideoQueueSource.LOCAL -> QUEUE_SOURCE_LOCAL
                VideoQueueSource.WEB_DAV -> QUEUE_SOURCE_WEB_DAV
            }
    }
}

@Composable
private fun VideoPlayerScreen(
    state: MpvPlayerState,
    mpvView: MuBoxMpvView,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedSelected: (Double) -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
    onSubtitleDelayChanged: (Long) -> Unit,
    onAudioDelayChanged: (Long) -> Unit,
    onScaleModeSelected: (VideoScaleMode) -> Unit,
    onDecoderModeSelected: (VideoDecoderMode) -> Unit,
    onControlsLockedChanged: (Boolean) -> Unit,
    onVolumeDelta: (Int) -> Unit,
    onBrightnessDelta: (Int) -> Unit,
    onDoubleTapSeek: (Boolean) -> Unit,
    onZoomDelta: (Float) -> Unit,
    onClearHud: () -> Unit,
    mediaContext: VideoPlayerMediaContext,
    queue: VideoPlaybackQueue?,
    controlsAutoHideMillis: Int,
) {
    var openPanel by remember { mutableStateOf<PlayerOptionPanel?>(null) }
    var activeBottomControl by remember { mutableStateOf<PlayerBottomQuickControl?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    val showControls = controlsVisible || state.gestureState.controlsLocked

    LaunchedEffect(
        controlsVisible,
        activeBottomControl,
        openPanel,
        state.gestureState.controlsLocked,
        controlsAutoHideMillis,
    ) {
        if (!controlsVisible || state.gestureState.controlsLocked || controlsAutoHideMillis <= 0) return@LaunchedEffect
        delay(controlsAutoHideMillis.toLong())
        activeBottomControl = null
        openPanel = null
        controlsVisible = false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { mpvView },
                modifier = Modifier.fillMaxSize(),
            )

            if (!state.gestureState.controlsLocked) {
                PlayerGestureOverlay(
                    onVolumeDelta = onVolumeDelta,
                    onBrightnessDelta = onBrightnessDelta,
                    onDoubleTapSeek = onDoubleTapSeek,
                    onZoomDelta = onZoomDelta,
                    onClearHud = onClearHud,
                    onOverlayTap = {
                        activeBottomControl = null
                        openPanel = null
                        controlsVisible = !controlsVisible
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (showControls) {
                PlayerTopBar(
                    title = state.displayName,
                    source = mediaContext.source,
                    controlsLocked = state.gestureState.controlsLocked,
                    onClose = onClose,
                    onControlsLockedChanged = {
                        onControlsLockedChanged(!state.gestureState.controlsLocked)
                        openPanel = null
                        activeBottomControl = null
                        controlsVisible = true
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }

            if (!state.gestureState.controlsLocked && controlsVisible) {
                PlayerSideControls(
                    state = state,
                    activePanel = openPanel,
                    onPanelSelected = { panel ->
                        openPanel = if (openPanel == panel) null else panel
                    },
                    onDismiss = { openPanel = null },
                    onAudioTrackSelected = onAudioTrackSelected,
                    onSubtitleTrackSelected = onSubtitleTrackSelected,
                    onSubtitlesDisabled = onSubtitlesDisabled,
                    onSubtitleDelayChanged = onSubtitleDelayChanged,
                    onAudioDelayChanged = onAudioDelayChanged,
                    mediaContext = mediaContext,
                    queue = queue,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(),
                )
            }

            GestureHud(
                message = state.gestureState.hudMessage,
                onTimeout = onClearHud,
                modifier = Modifier.align(Alignment.Center),
            )

            if (showControls) {
                PlayerBottomControls(
                    state = state,
                    activeControl = activeBottomControl,
                    onActiveControlChanged = { control ->
                        activeBottomControl = if (activeBottomControl == control) null else control
                        openPanel = null
                        controlsVisible = true
                    },
                    onPlayPause = {
                        controlsVisible = true
                        onPlayPause()
                    },
                    onSeek = {
                        controlsVisible = true
                        onSeek(it)
                    },
                    onSpeedSelected = onSpeedSelected,
                    onScaleModeSelected = onScaleModeSelected,
                    onDecoderModeSelected = onDecoderModeSelected,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun PlayerGestureOverlay(
    onVolumeDelta: (Int) -> Unit,
    onBrightnessDelta: (Int) -> Unit,
    onDoubleTapSeek: (Boolean) -> Unit,
    onZoomDelta: (Float) -> Unit,
    onClearHud: () -> Unit,
    onOverlayTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(
                start = PLAYER_GESTURE_HORIZONTAL_PADDING_DP.dp,
                top = PLAYER_GESTURE_TOP_PADDING_DP.dp,
                end = PLAYER_GESTURE_END_PADDING_DP.dp,
                bottom = PLAYER_GESTURE_BOTTOM_PADDING_DP.dp,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        onDoubleTapSeek(offset.x >= size.width / 2f)
                    },
                    onTap = {
                        onClearHud()
                        onOverlayTap()
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures(
                    onGesture = { centroid, pan, zoom, _ ->
                        if (zoom != 1f) {
                            onZoomDelta((zoom - 1f) * PINCH_ZOOM_STEP_SCALE)
                            return@detectTransformGestures
                        }
                        dispatchVerticalGesture(
                            centroid = centroid,
                            containerWidth = size.width.toFloat(),
                            panY = pan.y,
                            onBrightnessDelta = onBrightnessDelta,
                            onVolumeDelta = onVolumeDelta,
                        )
                    },
                )
            },
    )
}

private fun dispatchVerticalGesture(
    centroid: Offset,
    containerWidth: Float,
    panY: Float,
    onBrightnessDelta: (Int) -> Unit,
    onVolumeDelta: (Int) -> Unit,
) {
    val deltaPercent = (-panY / VERTICAL_GESTURE_PIXELS_PER_PERCENT).roundToInt()
    if (deltaPercent == 0) return
    if (centroid.x < containerWidth / 2f) {
        onBrightnessDelta(deltaPercent)
    } else {
        onVolumeDelta(deltaPercent)
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    source: String,
    controlsLocked: Boolean,
    onClose: () -> Unit,
    onControlsLockedChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerOverlayIconButton(
            icon = Icons.Filled.Close,
            contentDescription = "关闭",
            onClick = onClose,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = source.videoSourceLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PlayerOverlayIconButton(
            icon = if (controlsLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (controlsLocked) "解锁控制" else "锁定控制",
            onClick = onControlsLockedChanged,
        )
    }
}

@Composable
private fun PlayerOverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val backgroundColor = if (selected) PlayerAccentColor else PlayerOverlayColor
    val contentColor = if (selected) PlayerOnAccentColor else Color.White
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(PLAYER_OVERLAY_BUTTON_SIZE_DP.dp)
            .background(backgroundColor, MaterialTheme.shapes.small),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
        )
    }
}

@Composable
private fun PlayerBottomControls(
    state: MpvPlayerState,
    activeControl: PlayerBottomQuickControl?,
    onActiveControlChanged: (PlayerBottomQuickControl) -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedSelected: (Double) -> Unit,
    onScaleModeSelected: (VideoScaleMode) -> Unit,
    onDecoderModeSelected: (VideoDecoderMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.84f),
                        Color.Black.copy(alpha = 0.94f),
                    ),
                ),
            )
            .padding(start = 16.dp, top = 44.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatVideoTime(state.positionMillis),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
            )
            Text(
                text = formatVideoTime(state.durationMillis),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
            )
        }
        Slider(
            value = state.positionMillis.toFloat(),
            onValueChange = { if (!state.gestureState.controlsLocked) onSeek(it.roundToLong()) },
            valueRange = 0f..state.durationMillis.coerceAtLeast(1L).toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        PlayerBottomQuickControls(
            state = state,
            activeControl = activeControl,
            onActiveControlChanged = onActiveControlChanged,
            onPlayPause = onPlayPause,
        )
        activeControl?.let { control ->
            PlayerBottomQuickSelectionPanel(
                control = control,
                state = state,
                onSpeedSelected = onSpeedSelected,
                onScaleModeSelected = onScaleModeSelected,
                onDecoderModeSelected = onDecoderModeSelected,
            )
        }
        Text(
            text = state.bottomStatusText(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.gestureState.controlsLocked) {
            Text(
                text = "控制已锁定",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.84f),
            )
        }
    }
}

@Composable
private fun PlayerBottomQuickControls(
    state: MpvPlayerState,
    activeControl: PlayerBottomQuickControl?,
    onActiveControlChanged: (PlayerBottomQuickControl) -> Unit,
    onPlayPause: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PLAYER_PRIMARY_CONTROL_TOUCH_SIZE_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxItemsInEachRow = 2,
        ) {
            BottomQuickButton(
                text = state.playbackSpeed.trimmedSpeed() + "x",
                contentDescription = "倍速",
                selected = activeControl == PlayerBottomQuickControl.SPEED,
                onClick = { onActiveControlChanged(PlayerBottomQuickControl.SPEED) },
            )
            BottomQuickButton(
                text = state.scaleMode.label,
                contentDescription = "画面",
                selected = activeControl == PlayerBottomQuickControl.SCALE,
                onClick = { onActiveControlChanged(PlayerBottomQuickControl.SCALE) },
            )
        }
        Box(
            modifier = Modifier.size(PLAYER_PRIMARY_CONTROL_TOUCH_SIZE_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(PLAYER_PRIMARY_CONTROL_VISUAL_SIZE_DP.dp)
                    .background(PlayerAccentColor, MaterialTheme.shapes.small)
                    .clickable(role = Role.Button, onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (state.isPaused) "播放" else "暂停",
                    tint = PlayerOnAccentColor,
                )
            }
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            BottomQuickButton(
                text = state.decoderMode.label,
                contentDescription = "解码",
                selected = activeControl == PlayerBottomQuickControl.DECODER,
                onClick = { onActiveControlChanged(PlayerBottomQuickControl.DECODER) },
            )
        }
    }
}

@Composable
private fun BottomQuickButton(
    text: String,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (selected) PlayerAccentColor else Color.White.copy(alpha = 0.08f)
    val contentColor = if (selected) PlayerOnAccentColor else Color.White
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .size(width = 58.dp, height = 36.dp)
            .background(backgroundColor, MaterialTheme.shapes.small)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlayerBottomQuickSelectionPanel(
    control: PlayerBottomQuickControl,
    state: MpvPlayerState,
    onSpeedSelected: (Double) -> Unit,
    onScaleModeSelected: (VideoScaleMode) -> Unit,
    onDecoderModeSelected: (VideoDecoderMode) -> Unit,
) {
    ControlGroup(label = control.label) {
        when (control) {
            PlayerBottomQuickControl.SPEED -> playbackSpeedPresets.forEach { speed ->
                CompactTextButton(
                    text = "${speed}x",
                    selected = state.playbackSpeed == speed,
                    onClick = { onSpeedSelected(speed) },
                )
            }
            PlayerBottomQuickControl.SCALE -> VideoScaleMode.entries.forEach { mode ->
                CompactTextButton(mode.label, state.scaleMode == mode) { onScaleModeSelected(mode) }
            }
            PlayerBottomQuickControl.DECODER -> VideoDecoderMode.entries.forEach { mode ->
                CompactTextButton(mode.label, state.decoderMode == mode) { onDecoderModeSelected(mode) }
            }
        }
    }
}

@Composable
private fun GestureHud(
    message: String?,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.isNullOrBlank()) return
    LaunchedEffect(message) {
        delay(GESTURE_HUD_TIMEOUT_MILLIS)
        onTimeout()
    }
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.72f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal enum class PlayerOptionPanel {
    TRACKS,
    DELAYS,
    INFO,
    QUEUE,
}

internal enum class PlayerBottomQuickControl {
    SPEED,
    SCALE,
    DECODER,
}

internal data class PlayerOptionPanelDescriptor(
    val icon: ImageVector,
    val contentDescription: String,
    val visibleText: String = "",
)

internal fun PlayerOptionPanel.sideRailDescriptor(): PlayerOptionPanelDescriptor =
    when (this) {
        PlayerOptionPanel.TRACKS -> PlayerOptionPanelDescriptor(
            icon = Icons.Filled.Subtitles,
            contentDescription = "音轨与字幕",
        )
        PlayerOptionPanel.DELAYS -> PlayerOptionPanelDescriptor(
            icon = Icons.Filled.Sync,
            contentDescription = "音画同步",
        )
        PlayerOptionPanel.INFO -> PlayerOptionPanelDescriptor(
            icon = Icons.Filled.Info,
            contentDescription = "播放信息",
        )
        PlayerOptionPanel.QUEUE -> PlayerOptionPanelDescriptor(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = "播放队列",
        )
    }

@Composable
private fun PlayerSideControls(
    state: MpvPlayerState,
    activePanel: PlayerOptionPanel?,
    onPanelSelected: (PlayerOptionPanel) -> Unit,
    onDismiss: () -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
    onSubtitleDelayChanged: (Long) -> Unit,
    onAudioDelayChanged: (Long) -> Unit,
    mediaContext: VideoPlayerMediaContext,
    queue: VideoPlaybackQueue?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val isLandscape = maxWidth > maxHeight
        val railAndMargins = (PLAYER_OVERLAY_BUTTON_SIZE_DP + PLAYER_OPTION_SHEET_RAIL_GAP_DP + 24).dp
        val availableSheetMaxWidth = (maxWidth - railAndMargins).coerceAtLeast(220.dp)
        val sheetMaxWidth = availableSheetMaxWidth.coerceAtMost(if (isLandscape) 320.dp else 360.dp)

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(PLAYER_OPTION_SHEET_RAIL_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (activePanel != null) {
                PlayerOptionSheet(
                    panel = activePanel,
                    state = state,
                    mediaContext = mediaContext,
                    queue = queue,
                    onDismiss = onDismiss,
                    onAudioTrackSelected = onAudioTrackSelected,
                    onSubtitleTrackSelected = onSubtitleTrackSelected,
                    onSubtitlesDisabled = onSubtitlesDisabled,
                    onSubtitleDelayChanged = onSubtitleDelayChanged,
                    onAudioDelayChanged = onAudioDelayChanged,
                    modifier = Modifier.widthIn(min = 220.dp, max = sheetMaxWidth),
                )
            }
            EdgeFloatingControls(
                state = state,
                activePanel = activePanel,
                compact = isLandscape,
                onPanelSelected = onPanelSelected,
            )
        }
    }
}

@Composable
private fun EdgeFloatingControls(
    state: MpvPlayerState,
    activePanel: PlayerOptionPanel?,
    compact: Boolean,
    onPanelSelected: (PlayerOptionPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        FlowRow(
            modifier = modifier.widthIn(max = (PLAYER_OVERLAY_BUTTON_SIZE_DP * 2 + 6).dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 2,
        ) {
            PlayerOptionPanel.entries.forEach { panel ->
                FloatingPanelButton(
                    panel = panel,
                    state = state,
                    selected = activePanel == panel,
                    onClick = { onPanelSelected(panel) },
                )
            }
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        PlayerOptionPanel.entries.forEach { panel ->
            FloatingPanelButton(
                panel = panel,
                state = state,
                selected = activePanel == panel,
                onClick = { onPanelSelected(panel) },
            )
        }
    }
}

@Composable
private fun FloatingPanelButton(
    panel: PlayerOptionPanel,
    state: MpvPlayerState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val descriptor = panel.sideRailDescriptor()
    Box {
        PlayerOverlayIconButton(
            icon = descriptor.icon,
            contentDescription = descriptor.contentDescription,
            onClick = onClick,
            selected = selected,
        )
        panel.statusBadgeText(state)?.let { badge ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(width = 24.dp, height = 18.dp),
                color = if (selected) PlayerOnAccentColor else PlayerAccentColor,
                contentColor = if (selected) PlayerAccentColor else PlayerOnAccentColor,
                shape = MaterialTheme.shapes.small,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerOptionSheet(
    panel: PlayerOptionPanel?,
    state: MpvPlayerState,
    mediaContext: VideoPlayerMediaContext,
    queue: VideoPlaybackQueue?,
    onDismiss: () -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
    onSubtitleDelayChanged: (Long) -> Unit,
    onAudioDelayChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (panel == null) return

    Column(
        modifier = modifier
            .widthIn(min = 220.dp, max = 360.dp)
            .background(PlayerSheetColor, MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = panel.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.White.copy(alpha = 0.08f), MaterialTheme.shapes.small),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "收起",
                    tint = Color.White,
                )
            }
        }
        when (panel) {
            PlayerOptionPanel.TRACKS -> TrackSelectionControls(
                audioTracks = state.audioTracks,
                subtitleTracks = state.subtitleTracks,
                selectedAudioTrackId = state.selectedAudioTrackId,
                selectedSubtitleTrackId = state.selectedSubtitleTrackId,
                onAudioTrackSelected = onAudioTrackSelected,
                onSubtitleTrackSelected = onSubtitleTrackSelected,
                onSubtitlesDisabled = onSubtitlesDisabled,
            )
            PlayerOptionPanel.DELAYS -> DelayControls(
                subtitleDelayMillis = state.subtitleDelayMillis,
                audioDelayMillis = state.audioDelayMillis,
                onSubtitleDelayChanged = onSubtitleDelayChanged,
                onAudioDelayChanged = onAudioDelayChanged,
            )
            PlayerOptionPanel.INFO -> StatisticsControls(
                snapshot = buildVideoPlayerStatisticsSnapshot(
                    mediaContext = mediaContext,
                    state = state,
                ),
            )
            PlayerOptionPanel.QUEUE -> QueueControls(queue = queue)
        }
    }
}

@Composable
private fun PlaybackSpeedControls(
    selectedSpeed: Double,
    onSpeedSelected: (Double) -> Unit,
) {
    ControlGroup(label = "倍速") {
        playbackSpeedPresets.forEach { speed ->
            CompactTextButton(
                text = "${speed}x",
                selected = selectedSpeed == speed,
                onClick = { onSpeedSelected(speed) },
            )
        }
    }
}

@Composable
private fun TrackSelectionControls(
    audioTracks: List<MpvTrack>,
    subtitleTracks: List<MpvTrack>,
    selectedAudioTrackId: Int?,
    selectedSubtitleTrackId: Int?,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
) {
    ControlGroup(label = "音轨") {
        audioTracks.take(MAX_VISIBLE_TRACK_BUTTONS).forEach { track ->
            CompactTextButton(
                text = track.shortLabel(),
                selected = selectedAudioTrackId == track.id,
                onClick = { onAudioTrackSelected(track.id) },
            )
        }
        if (audioTracks.isEmpty()) {
            Text(text = "自动", style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
    ControlGroup(label = "字幕") {
        CompactTextButton(
            text = "关闭",
            selected = selectedSubtitleTrackId == null,
            onClick = onSubtitlesDisabled,
        )
        subtitleTracks.take(MAX_VISIBLE_TRACK_BUTTONS).forEach { track ->
            CompactTextButton(
                text = track.shortLabel(),
                selected = selectedSubtitleTrackId == track.id,
                onClick = { onSubtitleTrackSelected(track.id) },
            )
        }
    }
}

@Composable
private fun DelayControls(
    subtitleDelayMillis: Long,
    audioDelayMillis: Long,
    onSubtitleDelayChanged: (Long) -> Unit,
    onAudioDelayChanged: (Long) -> Unit,
) {
    ControlGroup(label = "字幕延迟 ${subtitleDelayMillis}ms") {
        CompactTextButton("-250", false) { onSubtitleDelayChanged(-250L) }
        CompactTextButton("+250", false) { onSubtitleDelayChanged(250L) }
        CompactTextButton("归零", subtitleDelayMillis == 0L) { onSubtitleDelayChanged(-subtitleDelayMillis) }
    }
    ControlGroup(label = "音频延迟 ${audioDelayMillis}ms") {
        CompactTextButton("-100", false) { onAudioDelayChanged(-100L) }
        CompactTextButton("+100", false) { onAudioDelayChanged(100L) }
        CompactTextButton("归零", audioDelayMillis == 0L) { onAudioDelayChanged(-audioDelayMillis) }
    }
}

internal fun bottomQuickControlLabels(): List<String> = listOf("倍速", "画面", "解码")

internal fun scaleModeControlGroupLabels(): List<String> = listOf("画面")

@Composable
private fun StatisticsControls(
    snapshot: VideoPlayerStatisticsSnapshot,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        snapshot.redacted().debugLines().forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QueueControls(queue: VideoPlaybackQueue?) {
    ControlGroup(label = "队列") {
        val previousName = queue?.previousItem()?.displayName
        val currentName = queue?.currentItem?.displayName
        val nextName = queue?.nextItem()?.displayName
        CompactTextButton(previousName?.shortQueueLabel() ?: "无上一集", selected = false, onClick = {})
        CompactTextButton(currentName?.shortQueueLabel() ?: "当前", selected = true, onClick = {})
        CompactTextButton(nextName?.shortQueueLabel() ?: "无下一集", selected = false, onClick = {})
    }
}

@Composable
private fun ControlGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.weight(0.24f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            modifier = Modifier.weight(0.76f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = { content() },
        )
    }
}

@Composable
private fun CompactTextButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.size(width = 78.dp, height = 38.dp),
        ) {
            Text(text = text, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
    } else {
        TextButton(
            onClick = onClick,
            modifier = Modifier
                .size(width = 78.dp, height = 38.dp)
                .background(Color.White.copy(alpha = 0.06f), MaterialTheme.shapes.small),
        ) {
            Text(text = text, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}

private val playbackSpeedPresets = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
private val PlayerOverlayColor = Color.Black.copy(alpha = 0.58f)
private val PlayerSheetColor = Color(0xE60C0F14)
private val PlayerAccentColor = Color(0xFFE9F7EF)
private val PlayerOnAccentColor = Color(0xFF0B2418)
internal const val PLAYER_PRIMARY_CONTROL_TOUCH_SIZE_DP = 44
internal const val PLAYER_PRIMARY_CONTROL_VISUAL_SIZE_DP = 38
internal const val PLAYER_OVERLAY_BUTTON_SIZE_DP = 44
internal const val PLAYER_OPTION_SHEET_RAIL_GAP_DP = 8
private const val MAX_VISIBLE_TRACK_BUTTONS = 4
private const val PLAYER_GESTURE_HORIZONTAL_PADDING_DP = 8
private const val PLAYER_GESTURE_TOP_PADDING_DP = 72
private const val PLAYER_GESTURE_END_PADDING_DP = 64
private const val PLAYER_GESTURE_BOTTOM_PADDING_DP = 116
private const val VERTICAL_GESTURE_PIXELS_PER_PERCENT = 8f
private const val PINCH_ZOOM_STEP_SCALE = 1.2f
private const val GESTURE_HUD_TIMEOUT_MILLIS = 900L

private val VideoScaleMode.label: String
    get() = when (this) {
        VideoScaleMode.FIT -> "适应"
        VideoScaleMode.FILL -> "填充"
        VideoScaleMode.ORIGINAL -> "原始"
        VideoScaleMode.RATIO_16_9 -> "16:9"
        VideoScaleMode.RATIO_4_3 -> "4:3"
    }

private val VideoDecoderMode.label: String
    get() = videoDecoderModeLabel(this)

private val PlayerOptionPanel.title: String
    get() = when (this) {
        PlayerOptionPanel.TRACKS -> "音轨 / 字幕"
        PlayerOptionPanel.DELAYS -> "延迟"
        PlayerOptionPanel.INFO -> "信息"
        PlayerOptionPanel.QUEUE -> "播放队列"
    }

private val PlayerBottomQuickControl.label: String
    get() = when (this) {
        PlayerBottomQuickControl.SPEED -> "倍速"
        PlayerBottomQuickControl.SCALE -> "画面"
        PlayerBottomQuickControl.DECODER -> "解码"
    }

private fun PlayerOptionPanel.statusBadgeText(state: MpvPlayerState): String? =
    when (this) {
        PlayerOptionPanel.TRACKS -> state.subtitleTracks
            .size
            .takeIf { it > 1 }
            ?.toString()
        PlayerOptionPanel.DELAYS -> listOf(state.subtitleDelayMillis, state.audioDelayMillis)
            .firstOrNull { it != 0L }
            ?.let { if (it > 0L) "+${it}" else it.toString() }
        PlayerOptionPanel.INFO -> null
        PlayerOptionPanel.QUEUE -> null
    }

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
    this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default

private fun Double.trimmedSpeed(): String =
    if (this % 1.0 == 0.0) roundToInt().toString() else toString()

private fun MpvPlayerState.bottomStatusText(): String {
    if (gestureState.controlsLocked) return "防误触观看"
    val parts = listOfNotNull(
        decoderMode.label.takeIf { it.isNotBlank() },
        videoOutputModeLabel(videoOutputMode).takeIf { it.isNotBlank() },
        scaleMode.label.takeIf { it.isNotBlank() },
        selectedSubtitleTrackId?.let { "字幕开启" },
        gestureState.brightnessPercent?.let { "亮度 $it%" },
    )
    return parts.joinToString(" · ").ifBlank { "准备播放" }
}

private fun String.videoSourceLabel(): String =
    when (lowercase()) {
        VideoPlayerActivity.SOURCE_LOCAL -> "本地视频"
        "webdav" -> "WebDAV"
        else -> this.ifBlank { "视频" }
    }

private fun MpvTrack.shortLabel(): String =
    title.takeIf { it.isNotBlank() }?.let { raw ->
        if (raw.length <= 8) raw else raw.take(7) + "..."
    } ?: "#$id"

private fun String.shortQueueLabel(): String =
    if (length <= 8) this else take(7) + "..."

private fun formatVideoTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
