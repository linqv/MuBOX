package com.example.comicdav.video.player

import com.example.comicdav.core.model.media.VideoSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import com.example.comicdav.core.model.settings.VideoProxySettings
import com.example.comicdav.core.remote.WebDavClientFactory
import com.example.comicdav.video.proxy.MuBoxVideoProxy
import com.example.comicdav.video.proxy.VideoProxyManager

internal class VideoEpisodeCoordinator(
    private val dependencies: VideoPlayerDependencies,
    private val proxyGateway: VideoProxyGateway,
) {
    suspend fun prepare(episode: VideoEpisode): PreparedVideoEpisode =
        when (episode.source) {
            VideoEpisodeSource.LOCAL -> {
                val request = requireNotNull(episode.localRequest)
                PreparedVideoEpisode(
                    uri = request.uri,
                    subtitles = request.subtitles,
                )
            }
            VideoEpisodeSource.WEB_DAV -> prepareWebDav(requireNotNull(episode.webDavRequest))
        }

    fun initialWebDavStreamIds(
        uri: String,
        explicitStreamIds: List<String>,
    ): List<String> =
        explicitStreamIds.ifEmpty {
            listOfNotNull(proxyGateway.streamIdFromUrl(uri)?.takeIf(String::isNotBlank))
        }

    fun close(streamIds: Iterable<String>) {
        proxyGateway.close(streamIds)
    }

    fun statistics(streamIds: List<String>): VideoProxyStatistics? =
        streamIds.firstOrNull()?.let(proxyGateway::statistics)

    private suspend fun prepareWebDav(request: WebDavVideoOpenRequest): PreparedVideoEpisode {
        val clientFactory = dependencies.loadWebDavClientFactory(request.accountId)
            ?: error("缺少 WebDAV 账号，请重新连接后再切换剧集")
        val proxySettings = dependencies.loadProxySettings()
        val session = proxyGateway.open(
            request = request,
            clientFactory = clientFactory,
            proxySettings = proxySettings,
        )
        return PreparedVideoEpisode(
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

internal interface VideoProxyGateway {
    suspend fun open(
        request: WebDavVideoOpenRequest,
        clientFactory: WebDavClientFactory,
        proxySettings: VideoProxySettings,
    ): VideoProxyPlaybackSession

    fun close(streamIds: Iterable<String>)

    fun streamIdFromUrl(url: String): String?

    fun statistics(streamId: String): VideoProxyStatistics?
}

internal data class VideoProxyPlaybackSession(
    val url: String,
    val subtitleUrls: List<String>,
    val streamIds: List<String>,
)

internal data class PreparedVideoEpisode(
    val uri: String,
    val subtitles: List<VideoSubtitleOpenRequest>,
    val webDavStreamIds: List<String> = emptyList(),
)

internal object VideoProxyManagerGateway : VideoProxyGateway {
    override suspend fun open(
        request: WebDavVideoOpenRequest,
        clientFactory: WebDavClientFactory,
        proxySettings: VideoProxySettings,
    ): VideoProxyPlaybackSession {
        val session = VideoProxyManager.open(
            request = request,
            clientFactory = clientFactory,
            proxySettings = proxySettings,
        )
        return VideoProxyPlaybackSession(
            url = session.url,
            subtitleUrls = session.subtitleUrls,
            streamIds = session.streamIds,
        )
    }

    override fun close(streamIds: Iterable<String>) {
        VideoProxyManager.close(streamIds)
    }

    override fun streamIdFromUrl(url: String): String? =
        MuBoxVideoProxy.streamIdFromUrl(url).takeIf(String::isNotBlank)

    override fun statistics(streamId: String): VideoProxyStatistics? =
        VideoProxyManager.statistics(streamId)?.let { statistics ->
            VideoProxyStatistics(
                currentRange = statistics.currentRange,
                remoteHttpStatus = statistics.remoteHttpStatus,
                downloadBytesPerSecond = null,
                memoryCacheHits = statistics.memoryCacheHits,
                prefetchState = statistics.prefetchState,
                seekFirstFrameMillis = null,
                diagnosticMessage = statistics.diagnosticMessage,
            )
        }
}
