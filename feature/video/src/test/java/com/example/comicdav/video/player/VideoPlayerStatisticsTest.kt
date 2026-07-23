package com.example.comicdav.video.player

import com.example.comicdav.core.model.settings.VideoDecoderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerStatisticsTest {
    @Test
    fun snapshotBuilderUsesCurrentMpvStateAndMediaContext() {
        val state = MpvPlayerState(
            displayName = "ignored.mp4",
            currentHwdec = "mediacodec-copy",
            activeHwdec = "mediacodec-copy",
            activeVideoDecoder = "h264_mediacodec",
            currentVideoOutput = "gpu-next",
            currentGpuApi = "vulkan",
            currentGpuContext = "androidvk",
            decoderDroppedFrames = 2,
            outputDroppedFrames = 3,
            videoParams = VideoParams(
                codec = "h264",
                width = 1280,
                height = 720,
                frameRate = 23.976,
            ),
            subtitleTracks = listOf(
                MpvTrack(
                    id = 2,
                    type = MpvTrackType.SUBTITLE,
                    title = "movie.zh.ass",
                    isSelected = true,
                    isExternal = true,
                ),
            ),
            selectedSubtitleTrackId = 2,
        )

        val snapshot = buildVideoPlayerStatisticsSnapshot(
            mediaContext = VideoPlayerMediaContext(
                displayName = "Episode 01.mkv",
                source = "webdav",
                remotePath = "/private/Episode 01.mkv",
            ),
            state = state,
        )

        assertEquals("Episode 01.mkv", snapshot.media.displayName)
        assertEquals("webdav", snapshot.media.source)
        assertEquals("/private/Episode 01.mkv", snapshot.media.remotePath)
        assertEquals("mkv", snapshot.media.container)
        assertEquals("h264", snapshot.media.videoCodec)
        assertEquals("1280x720", snapshot.media.resolution)
        assertEquals("movie.zh.ass", snapshot.media.subtitleSource)
        assertEquals("h264_mediacodec / mediacodec-copy", snapshot.runtime.decoder)
        assertEquals("gpu-next / androidvk", snapshot.runtime.renderer)
        assertEquals(23.976, snapshot.runtime.estimatedFps)
        assertEquals(5L, snapshot.runtime.droppedFrames)
    }

    @Test
    fun snapshotBuilderPrefersActualSoftwareDecoderOverRequestedHardwareMode() {
        val state = MpvPlayerState(
            decoderMode = VideoDecoderMode.HARDWARE_PLUS,
            currentHwdec = "mediacodec",
            activeHwdec = "no",
            activeVideoDecoder = "libdav1d",
            videoParams = VideoParams(codec = "av1", width = 1920, height = 1080),
        )

        val snapshot = buildVideoPlayerStatisticsSnapshot(
            mediaContext = VideoPlayerMediaContext(
                displayName = "Movie.av1.mkv",
                source = "local",
            ),
            state = state,
        )

        assertEquals("libdav1d", snapshot.runtime.decoder)
        assertTrue(snapshot.debugLines().contains("decoder=libdav1d"))
    }

    @Test
    fun proxyStatisticsRedactsCredentialsAuthorizationAndSensitiveQueryValues() {
        val snapshot = VideoPlayerStatisticsSnapshot(
            media = MediaInfoSnapshot(
                displayName = "movie.mkv",
                source = "webdav",
                remotePath = "/secret/private/movie.mkv",
                container = "matroska",
            ),
            runtime = MpvRuntimeStatistics(
                decoder = "h264",
                renderer = "gpu-next",
                estimatedFps = 23.976,
                droppedFrames = 2,
                avSyncSeconds = -0.02,
                cacheUsedBytes = 1_048_576,
            ),
            proxy = VideoProxyStatistics(
                currentRange = "bytes=0-8388607",
                remoteHttpStatus = 206,
                downloadBytesPerSecond = 65536,
                memoryCacheHits = 3,
                prefetchState = "active http://user:pass@example.test/movie.mkv?token=abc123",
                seekFirstFrameMillis = 420,
                diagnosticMessage = "Authorization: Basic abcdef password=hunter2 /secret/private/movie.mkv",
            ),
        )

        val redacted = snapshot.redacted()
        val rendered = redacted.debugLines().joinToString("\n")

        assertTrue(rendered.contains("movie.mkv"))
        assertTrue(rendered.contains("path=<redacted-path>"))
        assertTrue(rendered.contains("Authorization: <redacted>"))
        assertFalse(rendered.contains("user:pass"))
        assertFalse(rendered.contains("abc123"))
        assertFalse(rendered.contains("hunter2"))
        assertFalse(rendered.contains("/secret/private/movie.mkv"))
    }

    @Test
    fun proxyDebugLinesAreHiddenWhenProxyDebugInfoIsDisabled() {
        val snapshot = VideoPlayerStatisticsSnapshot(
            media = MediaInfoSnapshot(
                displayName = "movie.mkv",
                source = "webdav",
                remotePath = "/secret/private/movie.mkv",
                container = "mkv",
            ),
            runtime = MpvRuntimeStatistics(
                decoder = "h264",
                renderer = "gpu-next",
                estimatedFps = 23.976,
                droppedFrames = null,
                avSyncSeconds = null,
                cacheUsedBytes = null,
            ),
            proxy = VideoProxyStatistics(
                currentRange = "bytes=0-8388607",
                remoteHttpStatus = 206,
                downloadBytesPerSecond = 65536,
                memoryCacheHits = 3,
                prefetchState = "active",
                seekFirstFrameMillis = 420,
                diagnosticMessage = "remote_fetch range=0-8388607",
            ),
        )

        val lines = snapshot.redacted().debugLines(includeProxyDebugInfo = false)

        assertTrue(lines.contains("file=movie.mkv"))
        assertFalse(lines.any { it.startsWith("proxy-") })
        assertFalse(lines.any { it.contains("range=0-8388607") })
    }

    @Test
    fun localStatisticsKeepNonSensitiveMediaDetails() {
        val snapshot = VideoPlayerStatisticsSnapshot(
            media = MediaInfoSnapshot(
                displayName = "Episode 01.mp4",
                source = "local",
                remotePath = null,
                container = "mp4",
                videoCodec = "h265",
                resolution = "1920x1080",
                audioCodec = "aac",
                audioChannels = "2.0",
                subtitleSource = "内封字幕",
            ),
            runtime = MpvRuntimeStatistics(
                decoder = "mediacodec-copy",
                renderer = "gpu",
                estimatedFps = 60.0,
                droppedFrames = 0,
                avSyncSeconds = 0.0,
                cacheUsedBytes = 0,
            ),
            proxy = null,
        )

        val lines = snapshot.redacted().debugLines()

        assertEquals("file=Episode 01.mp4", lines[0])
        assertTrue(lines.contains("video=h265 1920x1080 60.0fps"))
        assertTrue(lines.contains("audio=aac 2.0"))
        assertTrue(lines.contains("subtitle=内封字幕"))
    }
}
