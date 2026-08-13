package org.mubox.reader.video.player

import android.content.Context
import android.util.Log
import org.mubox.reader.core.model.media.VideoSubtitleOpenRequest
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native mpv operations kept behind a seam so the session lifecycle is unit-testable. */
internal interface VideoPlayerMpvRuntime {
    fun copyAssets()

    fun addObserver(observer: MPVLib.EventObserver)

    fun removeObserver(observer: MPVLib.EventObserver)

    fun addLogObserver(observer: MPVLib.LogObserver)

    fun removeLogObserver(observer: MPVLib.LogObserver)

    fun initialize()

    fun attachExistingSurfaceIfReady()
}

internal class AndroidVideoPlayerMpvRuntime(
    context: Context,
    private val mpvView: MuBoxMpvView,
    private val filesDirectoryPath: String,
    private val cacheDirectoryPath: String,
) : VideoPlayerMpvRuntime {
    private val applicationContext = context.applicationContext

    override fun copyAssets() {
        Utils.copyAssets(applicationContext)
    }

    override fun addObserver(observer: MPVLib.EventObserver) {
        MPVLib.addObserver(observer)
    }

    override fun removeObserver(observer: MPVLib.EventObserver) {
        MPVLib.removeObserver(observer)
    }

    override fun addLogObserver(observer: MPVLib.LogObserver) {
        MPVLib.addLogObserver(observer)
    }

    override fun removeLogObserver(observer: MPVLib.LogObserver) {
        MPVLib.removeLogObserver(observer)
    }

    override fun initialize() {
        mpvView.initialize(filesDirectoryPath, cacheDirectoryPath)
    }

    override fun attachExistingSurfaceIfReady() {
        mpvView.attachExistingSurfaceIfReady()
    }
}

/** Resolves launch inputs without leaking Android URI ownership rules back into the Activity. */
internal class AndroidVideoPlaybackInputResolver(
    private val context: Context,
) {
    fun resolve(request: VideoPlaybackLoadRequest): ResolvedPlaybackInput {
        if (request.isWebDav) {
            return ResolvedPlaybackInput(
                videoUri = ManagedPlaybackUri(request.uri),
                subtitles = request.subtitles.map { subtitle ->
                    ResolvedSubtitlePlaybackUri(
                        uri = ManagedPlaybackUri(subtitle.uri),
                        displayName = subtitle.displayName,
                    )
                },
            )
        }

        val localUriResolver = LocalVideoUriResolver(context)
        return ResolvedPlaybackInput(
            videoUri = localUriResolver.resolveForPlayback(request.uri),
            subtitles = request.subtitles.map { subtitle ->
                ResolvedSubtitlePlaybackUri(
                    uri = localUriResolver.resolveSubtitleForPlayback(
                        subtitle.uri,
                        subtitle.displayName,
                    ),
                    displayName = subtitle.displayName,
                )
            },
        )
    }
}

internal enum class VideoPlayerSessionState {
    NEW,
    PREPARING,
    READY,
    CLEANING_UP,
    CLOSED,
}

/**
 * Owns one mpv session: native observer registration, preparation and loading, the active load
 * job, episode-transition event routing, and exactly-once native cleanup.
 */
internal class VideoPlayerSessionCoordinator(
    private val scope: CoroutineScope,
    private val controller: MpvController,
    private val runtime: VideoPlayerMpvRuntime,
    private val dispatchToMain: (() -> Unit) -> Unit,
    private val isHostFinishing: () -> Boolean,
    private val isHostInForeground: () -> Boolean,
    resolvePlaybackInput: suspend (VideoPlaybackLoadRequest) -> ResolvedPlaybackInput,
    requestAudioFocus: () -> Boolean,
    private val onPlaybackEnded: () -> Unit,
    private val onPlaybackInterrupted: () -> Unit,
    private val preparationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    resolutionDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logShaderDiagnostic: (String) -> Unit = { message ->
        Log.w(MPV_SHADER_LOG_TAG, message)
    },
) {
    private val lock = Any()
    private val propertyEventRouter = MpvPropertyEventRouter(controller)

    @Volatile
    private var state = VideoPlayerSessionState.NEW
    private var loadJob: Job? = null
    private var observerRegistered = false
    private var logObserverRegistered = false
    private var nativeInitialized = false
    private var ignoreNextStopEndFile = false
    private var resumeEpisodeWhenFileLoaded = false

    private val playbackLoader = VideoPlaybackLoadCoordinator(
        canLoad = ::canLoad,
        resolutionDispatcher = resolutionDispatcher,
        resolvePlaybackInput = resolvePlaybackInput,
        requestAudioFocus = requestAudioFocus,
        startPlayback = { resolvedInput, request, onFileLoaded ->
            controller.load(
                resolvedInput.videoUri.uri,
                request.displayName,
                startPositionMillis = request.startPositionMillis,
                subtitles = resolvedInput.subtitleRequests(),
                onFileLoaded = onFileLoaded,
            )
        },
        onAudioFocusDenied = {
            controller.markPaused(true)
            controller.onError("无法获取音频焦点，已暂停播放")
        },
        onFailure = { error ->
            controller.onError(error.message ?: "视频播放器初始化失败")
        },
    )

    internal val currentState: VideoPlayerSessionState
        get() = state

    val isClosingOrClosed: Boolean
        get() = state == VideoPlayerSessionState.CLEANING_UP ||
            state == VideoPlayerSessionState.CLOSED

    internal val hasPendingLoad: Boolean
        get() = synchronized(lock) { loadJob?.isActive == true }

    val mpvObserver: MPVLib.EventObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit

        override fun eventProperty(property: String, value: Long) {
            dispatchProperty { propertyEventRouter.route(property, value) }
        }

        override fun eventProperty(property: String, value: Boolean) {
            dispatchProperty { propertyEventRouter.route(property, value) }
        }

        override fun eventProperty(property: String, value: String) {
            dispatchProperty { propertyEventRouter.route(property, value) }
        }

        override fun eventProperty(property: String, value: Double) {
            dispatchProperty { propertyEventRouter.route(property, value) }
        }

        override fun eventProperty(property: String, value: MPVNode) {
            dispatchProperty { propertyEventRouter.route(property, value) }
        }

        override fun event(eventId: Int, data: MPVNode) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> routeFileLoaded()
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> routeEndFile(data)
            }
        }
    }

    val mpvLogObserver: MPVLib.LogObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            if (isMpvShaderDiagnostic(prefix, text)) {
                logShaderDiagnostic("mpv[$prefix][$level] ${text.trim()}")
            }
        }
    }

    fun canLoad(): Boolean =
        state == VideoPlayerSessionState.READY && !isHostFinishing()

    fun launchLoad(block: suspend () -> Unit): Job {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            block()
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (loadJob === job) loadJob = null
            }
        }

        var previousJob: Job? = null
        val accepted = synchronized(lock) {
            if (state == VideoPlayerSessionState.CLEANING_UP || state == VideoPlayerSessionState.CLOSED) {
                false
            } else {
                previousJob = loadJob
                loadJob = job
                true
            }
        }
        if (!accepted) {
            job.cancel()
            return job
        }

        previousJob?.cancel()
        job.start()
        return job
    }

    fun cancelPendingLoad() {
        val job = synchronized(lock) {
            loadJob.also { loadJob = null }
        }
        job?.cancel()
    }

    suspend fun prepare(): Boolean {
        val shouldPrepare = synchronized(lock) {
            when (state) {
                VideoPlayerSessionState.READY -> return true
                VideoPlayerSessionState.NEW -> {
                    state = VideoPlayerSessionState.PREPARING
                    true
                }
                VideoPlayerSessionState.PREPARING,
                VideoPlayerSessionState.CLEANING_UP,
                VideoPlayerSessionState.CLOSED,
                -> false
            }
        }
        if (!shouldPrepare) return false

        return try {
            withContext(preparationDispatcher) {
                runtime.copyAssets()
            }
            if (!isStillPreparing() || isHostFinishing()) {
                resetPreparingState()
                return false
            }

            runtime.addLogObserver(mpvLogObserver)
            synchronized(lock) { logObserverRegistered = true }
            if (!isStillPreparing()) return rollbackPreparation()

            runtime.initialize()
            synchronized(lock) { nativeInitialized = true }
            if (!isStillPreparing()) return rollbackPreparation()

            runtime.attachExistingSurfaceIfReady()
            if (!isStillPreparing()) return rollbackPreparation()

            runtime.addObserver(mpvObserver)
            synchronized(lock) { observerRegistered = true }

            synchronized(lock) {
                if (state == VideoPlayerSessionState.PREPARING) {
                    state = VideoPlayerSessionState.READY
                    true
                } else {
                    false
                }
            }.also { ready ->
                if (!ready) rollbackPreparation()
            }
        } catch (error: CancellationException) {
            rollbackPreparation()
            throw error
        } catch (error: Throwable) {
            rollbackPreparation()
            controller.onError(error.message ?: "视频播放器初始化失败")
            false
        }
    }

    suspend fun load(
        uri: String,
        displayName: String,
        startPositionMillis: Long,
        subtitles: List<VideoSubtitleOpenRequest>,
        isWebDav: Boolean,
    ): Boolean =
        playbackLoader.load(
            VideoPlaybackLoadRequest(
                uri = uri,
                displayName = displayName,
                startPositionMillis = startPositionMillis,
                subtitles = subtitles,
                isWebDav = isWebDav,
            ),
        )

    fun beginEpisodeTransition() {
        synchronized(lock) {
            if (state != VideoPlayerSessionState.READY) return
            ignoreNextStopEndFile = true
            resumeEpisodeWhenFileLoaded = true
        }
    }

    fun cancelEpisodeTransition() {
        synchronized(lock) {
            ignoreNextStopEndFile = false
            resumeEpisodeWhenFileLoaded = false
        }
    }

    /**
     * Cleans up exactly once. The callbacks preserve the Activity's external-resource ordering
     * around native teardown without duplicating a second cleanup flag there.
     */
    fun cleanup(
        onBeforeMpvCleanup: () -> Unit = {},
        onAfterMpvCleanup: () -> Unit = {},
    ): Boolean {
        val pendingLoad = synchronized(lock) {
            if (state == VideoPlayerSessionState.CLEANING_UP || state == VideoPlayerSessionState.CLOSED) {
                return false
            }
            state = VideoPlayerSessionState.CLEANING_UP
            ignoreNextStopEndFile = false
            resumeEpisodeWhenFileLoaded = false
            loadJob.also { loadJob = null }
        }
        pendingLoad?.cancel()

        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                if (failure == null) failure = error
            }
        }

        attempt(onBeforeMpvCleanup)
        unregisterObserver(::attempt)
        unregisterLogObserver(::attempt)
        destroyNativePlayer(::attempt)
        attempt(onAfterMpvCleanup)
        synchronized(lock) { state = VideoPlayerSessionState.CLOSED }

        failure?.let { throw it }
        return true
    }

    private fun dispatchProperty(action: () -> Unit) {
        dispatchToMain {
            if (!isClosingOrClosed) action()
        }
    }

    private fun routeFileLoaded() {
        dispatchToMain {
            if (isClosingOrClosed) return@dispatchToMain
            controller.onFileLoaded()
            val shouldResume = synchronized(lock) {
                if (!resumeEpisodeWhenFileLoaded) {
                    false
                } else {
                    resumeEpisodeWhenFileLoaded = false
                    true
                }
            }
            if (shouldResume && isHostInForeground() && canLoad()) {
                controller.setPaused(false)
            }
        }
    }

    private fun routeEndFile(data: MPVNode) {
        val ignoreExpectedStop = synchronized(lock) {
            if (ignoreNextStopEndFile && isMpvEndFileStop(data)) {
                ignoreNextStopEndFile = false
                true
            } else {
                false
            }
        }
        if (ignoreExpectedStop) return

        val errorMessage = mpvEndFileErrorMessage(data)
        dispatchToMain {
            if (isClosingOrClosed) return@dispatchToMain
            if (errorMessage == null) {
                controller.onPlaybackEnded()
                onPlaybackEnded()
            } else {
                controller.onError(errorMessage)
                onPlaybackInterrupted()
            }
        }
    }

    private fun isStillPreparing(): Boolean =
        state == VideoPlayerSessionState.PREPARING

    private fun resetPreparingState() {
        synchronized(lock) {
            if (state == VideoPlayerSessionState.PREPARING) {
                state = VideoPlayerSessionState.NEW
            }
        }
    }

    private fun rollbackPreparation(): Boolean {
        unregisterObserver { action -> runCatching(action) }
        unregisterLogObserver { action -> runCatching(action) }
        destroyNativePlayer { action -> runCatching(action) }
        resetPreparingState()
        return false
    }

    private fun unregisterObserver(attempt: (() -> Unit) -> Unit) {
        val shouldRemove = synchronized(lock) {
            observerRegistered.also { observerRegistered = false }
        }
        if (shouldRemove) attempt { runtime.removeObserver(mpvObserver) }
    }

    private fun unregisterLogObserver(attempt: (() -> Unit) -> Unit) {
        val shouldRemove = synchronized(lock) {
            logObserverRegistered.also { logObserverRegistered = false }
        }
        if (shouldRemove) attempt { runtime.removeLogObserver(mpvLogObserver) }
    }

    private fun destroyNativePlayer(attempt: (() -> Unit) -> Unit) {
        val shouldDestroy = synchronized(lock) {
            nativeInitialized.also { nativeInitialized = false }
        }
        if (shouldDestroy) attempt(controller::destroy)
    }
}

private const val MPV_SHADER_LOG_TAG = "MuBoxMpvShader"
