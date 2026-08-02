package org.mubox.reader.video.player

import org.mubox.reader.core.model.media.VideoSubtitleOpenRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ResolvedPlaybackInput(
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

internal data class ResolvedSubtitlePlaybackUri(
    val uri: ManagedPlaybackUri,
    val displayName: String,
)

internal data class VideoPlaybackLoadRequest(
    val uri: String,
    val displayName: String,
    val startPositionMillis: Long,
    val subtitles: List<VideoSubtitleOpenRequest>,
    val isWebDav: Boolean,
)

/**
 * Owns the failure and resource-transfer rules for one player load. Android URI resolution and
 * the mpv controller stay injected, so ordering and descriptor ownership are executable tests.
 */
internal class VideoPlaybackLoadCoordinator(
    private val canLoad: () -> Boolean,
    private val resolutionDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val resolvePlaybackInput: suspend (VideoPlaybackLoadRequest) -> ResolvedPlaybackInput,
    private val requestAudioFocus: () -> Boolean,
    private val startPlayback: (
        input: ResolvedPlaybackInput,
        request: VideoPlaybackLoadRequest,
        onFileLoaded: () -> Unit,
    ) -> Unit,
    private val onAudioFocusDenied: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    suspend fun load(request: VideoPlaybackLoadRequest): Boolean {
        if (!canLoad()) return false

        val resolvedInput = try {
            withContext(resolutionDispatcher) {
                resolvePlaybackInput(request)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error)
            return false
        }

        var consumedByMpv = false
        try {
            if (!canLoad()) return false
            if (!requestAudioFocus()) {
                onAudioFocusDenied()
                return false
            }

            startPlayback(resolvedInput, request) {
                resolvedInput.videoUri.markConsumed()
            }
            resolvedInput.markConsumed()
            consumedByMpv = true
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error)
            return false
        } finally {
            if (!consumedByMpv) {
                resolvedInput.closeIfUnused()
            }
        }
    }
}
