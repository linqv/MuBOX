package org.mubox.reader.video.player

import `is`.xyz.mpv.MPVLib
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewBackedMpvEngineTest {
    @Test
    fun viewBackedEngineUsesSurfaceAwareFileLoader() {
        val loader = FakeMpvFileLoader()
        val engine = ViewBackedMpvEngine(loader)
        var callbackCount = 0

        engine.loadFile("http://127.0.0.1:49152/stream/1") {
            callbackCount += 1
        }

        assertEquals(listOf("http://127.0.0.1:49152/stream/1"), loader.loadedUris)
        assertEquals(1, callbackCount)
    }

    @Test
    fun viewBackedEngineDestroysItsViewOwnedRuntime() {
        val loader = FakeMpvFileLoader()

        ViewBackedMpvEngine(loader).destroy()

        assertEquals(1, loader.destroyCount)
    }

    @Test
    fun fileLoadWaitsForSurfaceAttachmentBeforeRunningPostLoadAction() {
        val events = mutableListOf<String>()
        val loader = SurfaceAwareMpvFileLoader(
            loadDirectly = { events += "direct:$it" },
            loadThroughView = { events += "view:$it" },
        )

        loader.playFileWhenReady("content://videos/episode-1") {
            events += "after-loadfile"
        }

        assertEquals(listOf("view:content://videos/episode-1"), events)
        assertTrue(loader.markSurfaceAttached())
        events += "surface-attached"
        loader.flushPendingAfterLoadfileActions()
        assertEquals(
            listOf(
                "view:content://videos/episode-1",
                "surface-attached",
                "after-loadfile",
            ),
            events,
        )
    }

    @Test
    fun fileLoadUsesDirectMpvCommandWhenSurfaceIsAlreadyAttached() {
        val events = mutableListOf<String>()
        val loader = SurfaceAwareMpvFileLoader(
            loadDirectly = { events += "direct:$it" },
            loadThroughView = { events += "view:$it" },
        )
        loader.markSurfaceAttached()

        loader.playFileWhenReady("file:///movies/episode-2.mkv") {
            events += "after-loadfile"
        }

        assertEquals(
            listOf("direct:file:///movies/episode-2.mkv", "after-loadfile"),
            events,
        )
    }

    @Test
    fun newerDeferredLoadReplacesStalePostLoadAction() {
        val callbacks = mutableListOf<String>()
        val loader = SurfaceAwareMpvFileLoader(
            loadDirectly = {},
            loadThroughView = {},
        )

        loader.playFileWhenReady("first") { callbacks += "first" }
        loader.playFileWhenReady("second") { callbacks += "second" }
        loader.markSurfaceAttached()
        loader.flushPendingAfterLoadfileActions()

        assertEquals(listOf("second"), callbacks)
    }

    @Test
    fun surfaceTransitionsAreIdempotentAndLoadsDeferAgainAfterDetach() {
        val events = mutableListOf<String>()
        val loader = SurfaceAwareMpvFileLoader(
            loadDirectly = { events += "direct:$it" },
            loadThroughView = { events += "view:$it" },
        )

        assertTrue(loader.markSurfaceAttached())
        assertFalse(loader.markSurfaceAttached())
        assertTrue(loader.markSurfaceDetached())
        assertFalse(loader.markSurfaceDetached())
        loader.playFileWhenReady("after-detach") { events += "callback" }

        assertEquals(listOf("view:after-detach"), events)
    }

    @Test
    fun mpvViewSubscribesToTheTypedPlaybackPropertyContract() {
        val api = RecordingMpvNativeApi()

        observeMpvPlaybackProperties(api)

        assertEquals(
            listOf(
                "observe:pause=${MPVLib.MpvFormat.MPV_FORMAT_FLAG}",
                "observe:duration=${MPVLib.MpvFormat.MPV_FORMAT_DOUBLE}",
                "observe:time-pos=${MPVLib.MpvFormat.MPV_FORMAT_DOUBLE}",
                "observe:core-idle=${MPVLib.MpvFormat.MPV_FORMAT_FLAG}",
                "observe:track-list=${MPVLib.MpvFormat.MPV_FORMAT_NODE}",
                "observe:aid=${MPVLib.MpvFormat.MPV_FORMAT_INT64}",
                "observe:sid=${MPVLib.MpvFormat.MPV_FORMAT_INT64}",
                "observe:speed=${MPVLib.MpvFormat.MPV_FORMAT_DOUBLE}",
                "observe:container-fps=${MPVLib.MpvFormat.MPV_FORMAT_DOUBLE}",
                "observe:volume=${MPVLib.MpvFormat.MPV_FORMAT_DOUBLE}",
                "observe:audio-delay=${MPVLib.MpvFormat.MPV_FORMAT_DOUBLE}",
                "observe:video-params=${MPVLib.MpvFormat.MPV_FORMAT_NODE}",
                "observe:video-out-params=${MPVLib.MpvFormat.MPV_FORMAT_NODE}",
                "observe:video-params/aspect=${MPVLib.MpvFormat.MPV_FORMAT_DOUBLE}",
                "observe:video-out-params/aspect=${MPVLib.MpvFormat.MPV_FORMAT_DOUBLE}",
                "observe:hwdec=${MPVLib.MpvFormat.MPV_FORMAT_STRING}",
                "observe:hwdec-current=${MPVLib.MpvFormat.MPV_FORMAT_STRING}",
                "observe:current-tracks/video/decoder=${MPVLib.MpvFormat.MPV_FORMAT_STRING}",
                "observe:vo=${MPVLib.MpvFormat.MPV_FORMAT_STRING}",
                "observe:current-vo=${MPVLib.MpvFormat.MPV_FORMAT_STRING}",
                "observe:gpu-api=${MPVLib.MpvFormat.MPV_FORMAT_STRING}",
                "observe:current-gpu-context=${MPVLib.MpvFormat.MPV_FORMAT_STRING}",
                "observe:decoder-frame-drop-count=${MPVLib.MpvFormat.MPV_FORMAT_INT64}",
                "observe:frame-drop-count=${MPVLib.MpvFormat.MPV_FORMAT_INT64}",
            ),
            api.events,
        )
    }
}

private class FakeMpvFileLoader : MpvFileLoader {
    val loadedUris = mutableListOf<String>()
    var destroyCount = 0

    override fun playFileWhenReady(uri: String, afterLoadfile: () -> Unit) {
        loadedUris += uri
        afterLoadfile()
    }

    override fun destroy() {
        destroyCount += 1
    }
}
