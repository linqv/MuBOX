package org.mubox.reader.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvControllerAudioOnlyTest {

    @Test
    fun setAudioOnlyDisablesAndRestoresVideoTrack() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.setAudioOnly(true)

        assertTrue(controller.state.value.audioOnlyEnabled)
        assertEquals("no", engine.stringProperties["vid"])

        controller.setAudioOnly(false)

        assertFalse(controller.state.value.audioOnlyEnabled)
        assertEquals("auto", engine.stringProperties["vid"])
    }

    @Test
    fun loadReappliesAudioOnlyAfterLoadingNewFile() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        controller.setAudioOnly(true)

        controller.load(uri = "content://media/episode2.mkv", displayName = "第二集")

        assertEquals("no", engine.stringProperties["vid"])
        assertTrue(controller.state.value.audioOnlyEnabled)
    }

    @Test
    fun loadDoesNotRequireSurfaceWhenAudioOnlyIsEnabled() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        controller.setAudioOnly(true)

        controller.load(uri = "content://media/episode2.mkv", displayName = "第二集")

        assertEquals(listOf(false), engine.requiresSurfaceValues)
    }

    @Test
    fun loadRequiresSurfaceWhenVideoPlaybackIsActive() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.load(uri = "content://media/episode2.mkv", displayName = "第二集")

        assertEquals(listOf(true), engine.requiresSurfaceValues)
    }

    @Test
    fun backgroundEpisodeLoadCanBypassDetachedVideoSurface() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.load(
            uri = "content://media/episode2.mkv",
            displayName = "第二集",
            requiresSurface = false,
        )

        assertEquals(listOf(false), engine.requiresSurfaceValues)
    }

    @Test
    fun showGestureHudPublishesMessage() {
        val controller = MpvController(FakeMpvEngine())

        controller.showGestureHud("听视频模式已开启")

        assertEquals("听视频模式已开启", controller.state.value.gestureState.hudMessage)
    }
}
