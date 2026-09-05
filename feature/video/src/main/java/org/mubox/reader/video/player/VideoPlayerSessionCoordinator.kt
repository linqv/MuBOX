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

private data class EpisodeTransitionState(
    val oldPlaylistEntryId: Long?,
    val resumePlayback: Boolean,
    val onCompleted: () -> Unit,
    var newPlaylistEntryId: Long? = null,
    var isOldEndPending: Boolean = true,
    var isFileLoaded: Boolean = false,
    var isReplacementTerminal: Boolean = false,
)

private data class FileLoadedRouting(
    val shouldApply: Boolean,
    val shouldResume: Boolean = false,
    val completion: (() -> Unit)? = null,
)

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
    private var activePlaylistEntryId: Long? = null
    private var episodeTransition: EpisodeTransitionState? = null
    private var naturalEndSignaled = false

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
                requiresSurface = request.requiresSurface,
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
            if (property == "eof-reached") {
                routeEofReached(value)
            } else {
                dispatchProperty { propertyEventRouter.route(property, value) }
            }
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
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> routeStartFile(data)
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
        requiresSurface: Boolean? = null,
    ): Boolean =
        playbackLoader.load(
            VideoPlaybackLoadRequest(
                uri = uri,
                displayName = displayName,
                startPositionMillis = startPositionMillis,
                subtitles = subtitles,
                isWebDav = isWebDav,
                requiresSurface = requiresSurface,
            ),
        )

    fun beginEpisodeTransition(
        resumePlayback: Boolean = true,
        onTransitionCompleted: () -> Unit = {},
    ) {
        synchronized(lock) {
            if (state != VideoPlayerSessionState.READY) return
            episodeTransition = EpisodeTransitionState(
                oldPlaylistEntryId = activePlaylistEntryId,
                resumePlayback = resumePlayback,
                onCompleted = onTransitionCompleted,
            )
        }
    }

    fun cancelEpisodeTransition() {
        val completion = synchronized(lock) {
            episodeTransition?.onCompleted.also {
                episodeTransition = null
            }
        }
        completion?.let(dispatchToMain)
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
            activePlaylistEntryId = null
            episodeTransition = null
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

    private fun routeStartFile(data: MPVNode) {
        val playlistEntryId = mpvEventPlaylistEntryId(data)
        synchronized(lock) {
            naturalEndSignaled = false
            if (playlistEntryId == null) return@synchronized
            episodeTransition?.let { transition ->
                if (playlistEntryId != transition.oldPlaylistEntryId) {
                    transition.newPlaylistEntryId = playlistEntryId
                }
            }
            activePlaylistEntryId = playlistEntryId
        }
    }

    /**
     * With keep-open enabled mpv stays on the last frame, so eof-reached is the reliable natural
     * completion signal. END_FILE may only arrive later when loadfile unloads the old entry.
     */
    private fun routeEofReached(reached: Boolean) {
        val shouldDispatch = synchronized(lock) {
            if (!reached) {
                naturalEndSignaled = false
                false
            } else if (naturalEndSignaled) {
                false
            } else {
                naturalEndSignaled = true
                true
            }
        }
        if (!shouldDispatch) return

        dispatchToMain {
            if (isClosingOrClosed) return@dispatchToMain
            controller.onPlaybackEnded()
            onPlaybackEnded()
        }
    }

    private fun routeFileLoaded() {
        // FILE_LOADED does not carry a playlist entry ID. Capture the active entry while handling
        // the native event so a queued main-thread callback cannot be mistaken for a later entry.
        val loadedPlaylistEntryId = synchronized(lock) { activePlaylistEntryId }
        dispatchToMain {
            if (isClosingOrClosed) return@dispatchToMain
            val routing = synchronized(lock) {
                val transition = episodeTransition
                    ?: return@synchronized FileLoadedRouting(shouldApply = true)
                val belongsToReplacement = when {
                    loadedPlaylistEntryId != null && transition.newPlaylistEntryId != null ->
                        loadedPlaylistEntryId == transition.newPlaylistEntryId

                    loadedPlaylistEntryId != null && transition.oldPlaylistEntryId != null ->
                        loadedPlaylistEntryId != transition.oldPlaylistEntryId

                    transition.oldPlaylistEntryId != null && transition.newPlaylistEntryId == null ->
                        false

                    // Compatibility fallback for bridges that omit playlist entry IDs.
                    else -> true
                }
                if (!belongsToReplacement || transition.isFileLoaded) {
                    return@synchronized FileLoadedRouting(shouldApply = false)
                }

                transition.isFileLoaded = true
                FileLoadedRouting(
                    shouldApply = true,
                    shouldResume = transition.resumePlayback,
                    completion = transitionCompletionIfReadyLocked(),
                )
            }
            if (!routing.shouldApply) return@dispatchToMain

            controller.onFileLoaded()
            if (routing.shouldResume && isHostInForeground() && canLoad()) {
                controller.setPaused(false)
            }
            routing.completion?.invoke()
        }
    }

    private fun routeEndFile(data: MPVNode) {
        val playlistEntryId = mpvEventPlaylistEntryId(data)
        val errorMessage = mpvEndFileErrorMessage(data)
        var completion: (() -> Unit)? = null
        var isDuplicateNaturalEnd = false
        val ignoreTransitionEndFile = synchronized(lock) {
            val transition = episodeTransition
            val isOldEntryEnd = transition?.isOldEndPending == true && when {
                transition.oldPlaylistEntryId != null && playlistEntryId != null ->
                    transition.oldPlaylistEntryId == playlistEntryId

                else -> isMpvTransitionReplacementEndFile(data)
            }

            if (isOldEntryEnd) {
                transition?.isOldEndPending = false
            } else if (transition != null) {
                val isKnownReplacementEntry = playlistEntryId != null &&
                    playlistEntryId != transition.oldPlaylistEntryId
                if (isKnownReplacementEntry || !isMpvTransitionReplacementEndFile(data)) {
                    transition.isReplacementTerminal = true
                }
            }

            if (playlistEntryId != null && activePlaylistEntryId == playlistEntryId) {
                activePlaylistEntryId = null
            }
            isDuplicateNaturalEnd = !isOldEntryEnd && errorMessage == null && naturalEndSignaled
            if (!isOldEntryEnd && errorMessage == null && !isDuplicateNaturalEnd) {
                naturalEndSignaled = true
            }
            completion = transitionCompletionIfReadyLocked()
            isOldEntryEnd
        }

        dispatchToMain {
            if (isClosingOrClosed) return@dispatchToMain
            if (!ignoreTransitionEndFile && !isDuplicateNaturalEnd) {
                if (errorMessage == null) {
                    controller.onPlaybackEnded()
                    onPlaybackEnded()
                } else {
                    controller.onError(errorMessage)
                    onPlaybackInterrupted()
                }
            }
            completion?.invoke()
        }
    }

    private fun transitionCompletionIfReadyLocked(): (() -> Unit)? {
        val transition = episodeTransition ?: return null
        if (transition.isOldEndPending) return null
        if (!transition.isFileLoaded && !transition.isReplacementTerminal) return null

        episodeTransition = null
        return transition.onCompleted
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
