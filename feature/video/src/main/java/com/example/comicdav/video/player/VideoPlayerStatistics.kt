package com.example.comicdav.video.player

data class VideoPlayerStatisticsSnapshot(
    val media: MediaInfoSnapshot,
    val runtime: MpvRuntimeStatistics,
    val proxy: VideoProxyStatistics? = null,
) {
    fun redacted(): VideoPlayerStatisticsSnapshot =
        copy(
            media = media.redacted(),
            proxy = proxy?.redacted(),
        )

    fun debugLines(includeProxyDebugInfo: Boolean = true): List<String> {
        val lines = mutableListOf<String>()
        lines += "file=${media.displayName}"
        lines += "source=${media.source}"
        media.remotePath?.let { lines += "path=$it" }
        media.container?.let { lines += "container=$it" }
        if (media.videoCodec != null || media.resolution != null || runtime.estimatedFps != null) {
            lines += listOfNotNull(
                media.videoCodec,
                media.resolution,
                runtime.estimatedFps?.let { "${it}fps" },
            ).joinToString(separator = " ", prefix = "video=")
        }
        if (media.audioCodec != null || media.audioChannels != null) {
            lines += listOfNotNull(media.audioCodec, media.audioChannels)
                .joinToString(separator = " ", prefix = "audio=")
        }
        media.subtitleSource?.let { lines += "subtitle=$it" }
        lines += "decoder=${runtime.decoder ?: "unknown"}"
        lines += "renderer=${runtime.renderer ?: "unknown"}"
        runtime.droppedFrames?.let { lines += "dropped=$it" }
        runtime.avSyncSeconds?.let { lines += "av-sync=${it}s" }
        runtime.cacheUsedBytes?.let { lines += "cache=$it" }
        if (includeProxyDebugInfo) {
            proxy?.debugLines()?.let(lines::addAll)
        }
        return lines
    }
}

data class VideoPlayerMediaContext(
    val displayName: String,
    val source: String,
    val remotePath: String? = null,
)

fun buildVideoPlayerStatisticsSnapshot(
    mediaContext: VideoPlayerMediaContext,
    state: MpvPlayerState,
    proxy: VideoProxyStatistics? = null,
): VideoPlayerStatisticsSnapshot {
    val selectedSubtitle = state.subtitleTracks.firstOrNull { it.id == state.selectedSubtitleTrackId }
    return VideoPlayerStatisticsSnapshot(
        media = MediaInfoSnapshot(
            displayName = mediaContext.displayName,
            source = mediaContext.source,
            remotePath = mediaContext.remotePath,
            container = mediaContext.displayName.substringAfterLast('.', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() }
                ?.lowercase(),
            videoCodec = state.videoParams.codec,
            resolution = state.videoParams.resolutionLabel(),
            subtitleSource = selectedSubtitle?.title,
        ),
        runtime = MpvRuntimeStatistics(
            decoder = activeDecoderLabel(state),
            renderer = listOfNotNull(
                state.currentVideoOutput ?: state.videoOutputMode.videoOutput,
                state.currentGpuContext
                    ?: state.currentGpuApi?.takeIf { it != "auto" }
                    ?: state.gpuApiMode.gpuApi.takeIf { it != "auto" },
            ).joinToString(" / ").ifBlank { null },
            estimatedFps = state.videoOutParams.frameRate ?: state.videoParams.frameRate,
            droppedFrames = listOfNotNull(
                state.decoderDroppedFrames,
                state.outputDroppedFrames,
            ).takeIf { it.isNotEmpty() }?.sum(),
            avSyncSeconds = null,
            cacheUsedBytes = null,
        ),
        proxy = proxy,
    )
}

private fun activeDecoderLabel(state: MpvPlayerState): String {
    val decoder = state.activeVideoDecoder.takeUnless { it.isNullOrBlank() }
    val hwdec = state.activeHwdec.takeUnless { it.isNullOrBlank() }
    return when {
        decoder != null && hwdec != null && hwdec != "no" -> "$decoder / $hwdec"
        decoder != null -> decoder
        hwdec != null -> if (hwdec == "no") "software" else hwdec
        else -> state.currentHwdec ?: state.decoderMode.hwdec
    }
}

data class MediaInfoSnapshot(
    val displayName: String,
    val source: String,
    val remotePath: String? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    val resolution: String? = null,
    val audioCodec: String? = null,
    val audioChannels: String? = null,
    val subtitleSource: String? = null,
) {
    fun redacted(): MediaInfoSnapshot =
        copy(remotePath = remotePath?.let { REDACTED_PATH })
}

data class MpvRuntimeStatistics(
    val decoder: String?,
    val renderer: String?,
    val estimatedFps: Double?,
    val droppedFrames: Long?,
    val avSyncSeconds: Double?,
    val cacheUsedBytes: Long?,
)

data class VideoProxyStatistics(
    val currentRange: String?,
    val remoteHttpStatus: Int?,
    val downloadBytesPerSecond: Long?,
    val memoryCacheHits: Long?,
    val prefetchState: String?,
    val seekFirstFrameMillis: Long?,
    val diagnosticMessage: String?,
) {
    fun redacted(): VideoProxyStatistics =
        copy(
            prefetchState = prefetchState?.redactVideoStatisticText(),
            diagnosticMessage = diagnosticMessage?.redactVideoStatisticText(),
        )

    fun debugLines(): List<String> =
        listOfNotNull(
            currentRange?.let { "proxy-range=$it" },
            remoteHttpStatus?.let { "proxy-status=$it" },
            downloadBytesPerSecond?.let { "proxy-speed=$it" },
            memoryCacheHits?.let { "proxy-cache-hits=$it" },
            prefetchState?.let { "proxy-prefetch=$it" },
            seekFirstFrameMillis?.let { "proxy-first-frame=$it" },
            diagnosticMessage?.let { "proxy-diagnostic=$it" },
        )
}

private const val REDACTED_PATH = "<redacted-path>"

private fun VideoParams.resolutionLabel(): String? {
    val w = width ?: return null
    val h = height ?: return null
    return "${w}x$h"
}

private fun String.redactVideoStatisticText(): String =
    replace(USER_INFO_URL_REGEX, "://<redacted>@")
        .replace(AUTHORIZATION_REGEX) { match -> "${match.groupValues[1]}<redacted>" }
        .replace(SECRET_QUERY_REGEX) { match -> "${match.groupValues[1]}=<redacted>" }
        .replace(ABSOLUTE_PATH_WITH_EXTENSION_REGEX, REDACTED_PATH)

private val USER_INFO_URL_REGEX = Regex("://[^/@\\s]+@")
private val AUTHORIZATION_REGEX = Regex("(?i)(authorization\\s*[:=]\\s*)[^\\n,]+")
private val SECRET_QUERY_REGEX = Regex("(?i)\\b(password|passwd|token|access_token|refresh_token|secret)=([^\\s&]+)")
private val ABSOLUTE_PATH_WITH_EXTENSION_REGEX = Regex("/[^\\s]*\\.[A-Za-z0-9]{2,5}")
