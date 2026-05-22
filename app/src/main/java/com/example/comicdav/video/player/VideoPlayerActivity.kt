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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
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
                    onVideoOutputModeSelected = controller::setVideoOutputMode,
                    onGpuApiModeSelected = controller::setGpuApiMode,
                    onControlsLockedChanged = controller::setControlsLocked,
                    onVolumeDelta = controller::adjustGestureVolume,
                    onBrightnessDelta = ::handleBrightnessGesture,
                    onDoubleTapSeek = controller::handleDoubleTapSeek,
                    onZoomDelta = controller::adjustGestureZoom,
                    onClearHud = controller::clearGestureHud,
                    mediaContext = mediaContext,
                    queue = playbackQueue,
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
        const val SOURCE_LOCAL = "local"

        fun localIntent(
            context: Context,
            request: LocalVideoOpenRequest,
            resumeEnabled: Boolean = true,
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
                .putQueueExtras(queue)
                .putSubtitleExtras(request.subtitles)

        fun webDavIntent(
            context: Context,
            request: WebDavVideoOpenRequest,
            uri: String,
            subtitleUrls: List<String>,
            streamIds: List<String>,
            resumeEnabled: Boolean = true,
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
    onVideoOutputModeSelected: (VideoOutputMode) -> Unit,
    onGpuApiModeSelected: (GpuApiMode) -> Unit,
    onControlsLockedChanged: (Boolean) -> Unit,
    onVolumeDelta: (Int) -> Unit,
    onBrightnessDelta: (Int) -> Unit,
    onDoubleTapSeek: (Boolean) -> Unit,
    onZoomDelta: (Float) -> Unit,
    onClearHud: () -> Unit,
    mediaContext: VideoPlayerMediaContext,
    queue: VideoPlaybackQueue?,
) {
    var openPanel by remember { mutableStateOf<PlayerOptionPanel?>(null) }

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
                    modifier = Modifier.fillMaxSize(),
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                )
            }

            IconButton(
                onClick = {
                    onControlsLockedChanged(!state.gestureState.controlsLocked)
                    openPanel = null
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(48.dp),
            ) {
                Icon(
                    imageVector = if (state.gestureState.controlsLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (state.gestureState.controlsLocked) "解锁控制" else "锁定控制",
                    tint = Color.White,
                )
            }

            if (!state.gestureState.controlsLocked) {
                EdgeFloatingControls(
                    activePanel = openPanel,
                    onPanelSelected = { panel ->
                        openPanel = if (openPanel == panel) null else panel
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                )
                PlayerOptionSheet(
                    panel = openPanel,
                    state = state,
                    mediaContext = mediaContext,
                    queue = queue,
                    onDismiss = { openPanel = null },
                    onSpeedSelected = onSpeedSelected,
                    onAudioTrackSelected = onAudioTrackSelected,
                    onSubtitleTrackSelected = onSubtitleTrackSelected,
                    onSubtitlesDisabled = onSubtitlesDisabled,
                    onSubtitleDelayChanged = onSubtitleDelayChanged,
                    onAudioDelayChanged = onAudioDelayChanged,
                    onScaleModeSelected = onScaleModeSelected,
                    onDecoderModeSelected = onDecoderModeSelected,
                    onVideoOutputModeSelected = onVideoOutputModeSelected,
                    onGpuApiModeSelected = onGpuApiModeSelected,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 72.dp),
                )
            }

            GestureHud(
                message = state.gestureState.hudMessage,
                onTimeout = onClearHud,
                modifier = Modifier.align(Alignment.Center),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (state.isPaused) "播放" else "暂停",
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = formatVideoTime(state.positionMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                    Slider(
                        value = state.positionMillis.toFloat(),
                        onValueChange = { if (!state.gestureState.controlsLocked) onSeek(it.roundToLong()) },
                        valueRange = 0f..state.durationMillis.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatVideoTime(state.durationMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
                if (state.gestureState.controlsLocked) {
                    Text(
                        text = "控制已锁定",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
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
                    onTap = { onClearHud() },
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

private enum class PlayerOptionPanel {
    SPEED,
    TRACKS,
    DELAYS,
    VIDEO,
    INFO,
    QUEUE,
}

@Composable
private fun EdgeFloatingControls(
    activePanel: PlayerOptionPanel?,
    onPanelSelected: (PlayerOptionPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        FloatingPanelButton("速", activePanel == PlayerOptionPanel.SPEED) { onPanelSelected(PlayerOptionPanel.SPEED) }
        FloatingPanelButton("轨", activePanel == PlayerOptionPanel.TRACKS) { onPanelSelected(PlayerOptionPanel.TRACKS) }
        FloatingPanelButton("延", activePanel == PlayerOptionPanel.DELAYS) { onPanelSelected(PlayerOptionPanel.DELAYS) }
        FloatingPanelButton("画", activePanel == PlayerOptionPanel.VIDEO) { onPanelSelected(PlayerOptionPanel.VIDEO) }
        FloatingPanelButton("信", activePanel == PlayerOptionPanel.INFO) { onPanelSelected(PlayerOptionPanel.INFO) }
        FloatingPanelButton("队", activePanel == PlayerOptionPanel.QUEUE) { onPanelSelected(PlayerOptionPanel.QUEUE) }
    }
}

@Composable
private fun FloatingPanelButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
        ) {
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        TextButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.52f)),
        ) {
            Text(text = text, style = MaterialTheme.typography.labelMedium, color = Color.White)
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
    onSpeedSelected: (Double) -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
    onSubtitleDelayChanged: (Long) -> Unit,
    onAudioDelayChanged: (Long) -> Unit,
    onScaleModeSelected: (VideoScaleMode) -> Unit,
    onDecoderModeSelected: (VideoDecoderMode) -> Unit,
    onVideoOutputModeSelected: (VideoOutputMode) -> Unit,
    onGpuApiModeSelected: (GpuApiMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (panel == null) return

    Column(
        modifier = modifier
            .widthIn(min = 260.dp, max = 360.dp)
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(12.dp),
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
            TextButton(onClick = onDismiss, modifier = Modifier.size(width = 56.dp, height = 36.dp)) {
                Text("收起", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        when (panel) {
            PlayerOptionPanel.SPEED -> PlaybackSpeedControls(
                selectedSpeed = state.playbackSpeed,
                onSpeedSelected = onSpeedSelected,
            )
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
            PlayerOptionPanel.VIDEO -> VideoModeControls(
                scaleMode = state.scaleMode,
                decoderMode = state.decoderMode,
                videoOutputMode = state.videoOutputMode,
                gpuApiMode = state.gpuApiMode,
                onScaleModeSelected = onScaleModeSelected,
                onDecoderModeSelected = onDecoderModeSelected,
                onVideoOutputModeSelected = onVideoOutputModeSelected,
                onGpuApiModeSelected = onGpuApiModeSelected,
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

@Composable
private fun VideoModeControls(
    scaleMode: VideoScaleMode,
    decoderMode: VideoDecoderMode,
    videoOutputMode: VideoOutputMode,
    gpuApiMode: GpuApiMode,
    onScaleModeSelected: (VideoScaleMode) -> Unit,
    onDecoderModeSelected: (VideoDecoderMode) -> Unit,
    onVideoOutputModeSelected: (VideoOutputMode) -> Unit,
    onGpuApiModeSelected: (GpuApiMode) -> Unit,
) {
    ControlGroup(label = "画面") {
        VideoScaleMode.entries.forEach { mode ->
            CompactTextButton(mode.label, scaleMode == mode) { onScaleModeSelected(mode) }
        }
    }
    ControlGroup(label = "解码") {
        VideoDecoderMode.entries.forEach { mode ->
            CompactTextButton(mode.label, decoderMode == mode) { onDecoderModeSelected(mode) }
        }
    }
    ControlGroup(label = "输出") {
        VideoOutputMode.entries.forEach { mode ->
            CompactTextButton(mode.label, videoOutputMode == mode) { onVideoOutputModeSelected(mode) }
        }
    }
    ControlGroup(label = "GPU API") {
        GpuApiMode.entries.forEach { mode ->
            CompactTextButton(mode.label, gpuApiMode == mode) { onGpuApiModeSelected(mode) }
        }
    }
}

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
            modifier = Modifier.size(width = 72.dp, height = 36.dp),
        ) {
            Text(text = text, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
    } else {
        TextButton(
            onClick = onClick,
            modifier = Modifier.size(width = 72.dp, height = 36.dp),
        ) {
            Text(text = text, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}

private val playbackSpeedPresets = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
private const val MAX_VISIBLE_TRACK_BUTTONS = 4
private const val PLAYER_GESTURE_HORIZONTAL_PADDING_DP = 8
private const val PLAYER_GESTURE_TOP_PADDING_DP = 72
private const val PLAYER_GESTURE_END_PADDING_DP = 72
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
    get() = when (this) {
        VideoDecoderMode.AUTO -> "auto"
        VideoDecoderMode.SOFTWARE -> "SW"
        VideoDecoderMode.HARDWARE -> "HW"
        VideoDecoderMode.HARDWARE_PLUS -> "HW+"
    }

private val VideoOutputMode.label: String
    get() = when (this) {
        VideoOutputMode.AUTO -> "auto"
        VideoOutputMode.GPU_NEXT -> "gpu-next"
    }

private val GpuApiMode.label: String
    get() = when (this) {
        GpuApiMode.AUTO -> "auto"
        GpuApiMode.VULKAN -> "vulkan"
    }

private val PlayerOptionPanel.title: String
    get() = when (this) {
        PlayerOptionPanel.SPEED -> "倍速"
        PlayerOptionPanel.TRACKS -> "音轨 / 字幕"
        PlayerOptionPanel.DELAYS -> "延迟"
        PlayerOptionPanel.VIDEO -> "画面 / 解码"
        PlayerOptionPanel.INFO -> "信息"
        PlayerOptionPanel.QUEUE -> "播放队列"
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
