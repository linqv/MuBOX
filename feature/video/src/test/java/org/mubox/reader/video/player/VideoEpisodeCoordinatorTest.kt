package org.mubox.reader.video.player

import org.mubox.reader.core.model.media.LocalVideoOpenRequest
import org.mubox.reader.core.model.media.VideoSubtitleOpenRequest
import org.mubox.reader.core.model.media.WebDavSubtitleOpenRequest
import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import org.mubox.reader.core.model.settings.VideoProxySettings
import org.mubox.reader.core.remote.WebDavClientFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEpisodeCoordinatorTest {
    @Test
    fun localEpisodePreparationDoesNotLoadWebDavDependenciesOrOpenProxy() = runTest {
        val dependencies = FakeVideoPlayerDependencies()
        val gateway = FakeVideoProxyGateway()
        val subtitles = listOf(
            VideoSubtitleOpenRequest("content://subtitles/episode-1", "episode-1.ass"),
        )
        val episode = VideoEpisode.local(
            LocalVideoOpenRequest(
                uri = "content://videos/episode-1",
                displayName = "episode-1.mkv",
                size = 10L,
                lastModified = 20L,
                subtitles = subtitles,
            ),
        )

        val prepared = VideoEpisodeCoordinator(dependencies, gateway).prepare(episode)

        assertEquals("content://videos/episode-1", prepared.uri)
        assertEquals(subtitles, prepared.subtitles)
        assertTrue(prepared.webDavStreamIds.isEmpty())
        assertEquals(0, dependencies.clientFactoryLoadCount)
        assertEquals(0, dependencies.proxySettingsLoadCount)
        assertEquals(0, gateway.openCount)
    }

    @Test
    fun webDavEpisodePreparationLoadsNarrowDependenciesAndMapsProxySession() = runTest {
        val factory = WebDavClientFactory { error("not used by fake gateway") }
        val settings = VideoProxySettings.DEFAULT.copy(seekOptimizationEnabled = false)
        val dependencies = FakeVideoPlayerDependencies(
            clientFactory = factory,
            proxySettings = settings,
        )
        val gateway = FakeVideoProxyGateway(
            session = VideoProxyPlaybackSession(
                url = "http://127.0.0.1/video-stream",
                subtitleUrls = listOf("http://127.0.0.1/subtitle-stream"),
                streamIds = listOf("video-stream", "subtitle-stream"),
            ),
        )
        val request = webDavRequest()

        val prepared = VideoEpisodeCoordinator(dependencies, gateway)
            .prepare(VideoEpisode.webDav(request))

        assertEquals("account-1", dependencies.lastAccountId)
        assertEquals(1, dependencies.clientFactoryLoadCount)
        assertEquals(1, dependencies.proxySettingsLoadCount)
        assertEquals(1, gateway.openCount)
        assertEquals(request, gateway.openedRequest)
        assertSame(factory, gateway.openedClientFactory)
        assertEquals(settings, gateway.openedProxySettings)
        assertEquals("http://127.0.0.1/video-stream", prepared.uri)
        assertEquals(
            listOf(VideoSubtitleOpenRequest("http://127.0.0.1/subtitle-stream", "episode.zh.srt")),
            prepared.subtitles,
        )
        assertEquals(listOf("video-stream", "subtitle-stream"), prepared.webDavStreamIds)
    }

    @Test
    fun missingWebDavAccountStopsBeforeLoadingSettingsOrOpeningProxy() = runTest {
        val dependencies = FakeVideoPlayerDependencies(clientFactory = null)
        val gateway = FakeVideoProxyGateway()

        val failure = runCatching {
            VideoEpisodeCoordinator(dependencies, gateway)
                .prepare(VideoEpisode.webDav(webDavRequest()))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("缺少 WebDAV 账号"))
        assertEquals(1, dependencies.clientFactoryLoadCount)
        assertEquals(0, dependencies.proxySettingsLoadCount)
        assertEquals(0, gateway.openCount)
    }

    @Test
    fun streamOwnershipAndStatisticsStayBehindProxyGateway() {
        val statistics = VideoProxyStatistics(
            currentRange = "bytes=0-99",
            remoteHttpStatus = 206,
            downloadBytesPerSecond = null,
            memoryCacheHits = 2L,
            prefetchState = "active",
            seekFirstFrameMillis = null,
            diagnosticMessage = null,
        )
        val gateway = FakeVideoProxyGateway(
            streamId = "derived-stream",
            statistics = statistics,
        )
        val coordinator = VideoEpisodeCoordinator(FakeVideoPlayerDependencies(), gateway)

        assertEquals(
            listOf("explicit-stream"),
            coordinator.initialWebDavStreamIds(
                uri = "http://127.0.0.1/ignored",
                explicitStreamIds = listOf("explicit-stream"),
            ),
        )
        assertEquals(0, gateway.streamIdLookupCount)
        assertEquals(
            listOf("derived-stream"),
            coordinator.initialWebDavStreamIds(
                uri = "http://127.0.0.1/derived-stream",
                explicitStreamIds = emptyList(),
            ),
        )
        assertEquals(statistics, coordinator.statistics(listOf("derived-stream")))

        coordinator.close(listOf("derived-stream", "subtitle-stream"))

        assertEquals(listOf("derived-stream", "subtitle-stream"), gateway.closedStreamIds)
        assertEquals(listOf("derived-stream"), gateway.statisticsRequests)
    }

    private fun webDavRequest(): WebDavVideoOpenRequest =
        WebDavVideoOpenRequest(
            accountId = "account-1",
            remotePath = "/shows/episode-2.mkv",
            displayName = "episode-2.mkv",
            size = 100L,
            etag = "etag-2",
            lastModified = 200L,
            mimeType = "video/x-matroska",
            subtitles = listOf(
                WebDavSubtitleOpenRequest(
                    remotePath = "/shows/episode.zh.srt",
                    displayName = "episode.zh.srt",
                    size = 30L,
                    etag = "subtitle-etag",
                    lastModified = 40L,
                    mimeType = "application/x-subrip",
                ),
            ),
        )
}

private class FakeVideoPlayerDependencies(
    private val clientFactory: WebDavClientFactory? = WebDavClientFactory {
        error("not used by fake gateway")
    },
    private val proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
) : VideoPlayerDependencies {
    var clientFactoryLoadCount = 0
    var proxySettingsLoadCount = 0
    var lastAccountId: String? = null

    override suspend fun loadProxySettings(): VideoProxySettings {
        proxySettingsLoadCount += 1
        return proxySettings
    }

    override suspend fun loadWebDavClientFactory(accountId: String): WebDavClientFactory? {
        clientFactoryLoadCount += 1
        lastAccountId = accountId
        return clientFactory
    }
}

private class FakeVideoProxyGateway(
    private val session: VideoProxyPlaybackSession = VideoProxyPlaybackSession(
        url = "http://127.0.0.1/video",
        subtitleUrls = emptyList(),
        streamIds = listOf("video"),
    ),
    private val streamId: String? = null,
    private val statistics: VideoProxyStatistics? = null,
) : VideoProxyGateway {
    var openCount = 0
    var openedRequest: WebDavVideoOpenRequest? = null
    var openedClientFactory: WebDavClientFactory? = null
    var openedProxySettings: VideoProxySettings? = null
    var streamIdLookupCount = 0
    var closedStreamIds: List<String> = emptyList()
    val statisticsRequests = mutableListOf<String>()

    override suspend fun open(
        request: WebDavVideoOpenRequest,
        clientFactory: WebDavClientFactory,
        proxySettings: VideoProxySettings,
    ): VideoProxyPlaybackSession {
        openCount += 1
        openedRequest = request
        openedClientFactory = clientFactory
        openedProxySettings = proxySettings
        return session
    }

    override fun close(streamIds: Iterable<String>) {
        closedStreamIds = streamIds.toList()
    }

    override fun streamIdFromUrl(url: String): String? {
        streamIdLookupCount += 1
        return streamId
    }

    override fun statistics(streamId: String): VideoProxyStatistics? {
        statisticsRequests += streamId
        return statistics
    }
}
