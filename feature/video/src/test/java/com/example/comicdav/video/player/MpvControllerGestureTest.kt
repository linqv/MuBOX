package com.example.comicdav.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvControllerGestureTest {
    @Test
    fun gestureVolumeUpdatesMpvVolumeAndHudWithinBounds() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.adjustGestureVolume(15)
        controller.adjustGestureVolume(90)

        assertEquals(100, controller.state.value.gestureState.volumePercent)
        assertEquals("音量 100%", controller.state.value.gestureState.hudMessage)
        assertEquals(listOf(65.0, 100.0), engine.doublePropertyHistory("volume"))
    }

    @Test
    fun gestureBrightnessUpdatesStateAndHudWithinBounds() {
        val controller = MpvController(FakeMpvEngine())

        controller.adjustGestureBrightness(-70)

        assertEquals(0, controller.state.value.gestureState.brightnessPercent)
        assertEquals("亮度 0%", controller.state.value.gestureState.hudMessage)
    }

    @Test
    fun doubleTapSeekUsesVisibleHudAndClampsToDuration() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        controller.onDurationChanged(120.0)
        controller.onPositionChanged(115.0)

        controller.handleDoubleTapSeek(forward = true)

        assertEquals(120_000L, controller.state.value.positionMillis)
        assertEquals("快进 10秒", controller.state.value.gestureState.hudMessage)
        assertEquals(listOf(listOf("seek", "120.0", "absolute")), engine.commands)
    }

    @Test
    fun horizontalSwipeSeekUsesDurationFractionAndHud() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        controller.onDurationChanged(120.0)
        controller.onPositionChanged(30.0)

        controller.beginHorizontalSwipeSeek()
        controller.handleHorizontalSwipeSeek(0.1f)
        controller.endHorizontalSwipeSeek()
        controller.beginHorizontalSwipeSeek()
        controller.handleHorizontalSwipeSeek(-0.2f)
        controller.endHorizontalSwipeSeek()

        assertEquals(18_000L, controller.state.value.positionMillis)
        assertEquals("快退 24秒", controller.state.value.gestureState.hudMessage)
        assertEquals(
            listOf(
                listOf("seek", "42.0", "absolute"),
                listOf("seek", "18.0", "absolute"),
            ),
            engine.commands,
        )
    }

    @Test
    fun horizontalSwipeHudShowsCumulativeDeltaAcrossGestureUpdates() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        controller.onDurationChanged(600.0)
        controller.onPositionChanged(60.0)

        controller.beginHorizontalSwipeSeek()
        controller.handleHorizontalSwipeSeek(0.05f)
        controller.handleHorizontalSwipeSeek(0.05f)
        controller.endHorizontalSwipeSeek()

        assertEquals(120_000L, controller.state.value.positionMillis)
        assertEquals("快进 1分", controller.state.value.gestureState.hudMessage)
        assertEquals(
            listOf(
                listOf("seek", "90.0", "absolute"),
                listOf("seek", "120.0", "absolute"),
            ),
            engine.commands,
        )
    }

    @Test
    fun pinchZoomMapsToVideoZoomPropertyAndHud() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.adjustGestureZoom(0.3f)
        controller.adjustGestureZoom(3.0f)

        assertEquals(2.0f, controller.state.value.gestureState.zoom)
        assertEquals("缩放 200%", controller.state.value.gestureState.hudMessage)
        assertEquals(listOf(0.3, 2.0), engine.doublePropertyHistory("video-zoom"))
    }

    @Test
    fun lockedControlsIgnoreGestureMutations() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        controller.setControlsLocked(true)

        controller.adjustGestureVolume(20)
        controller.adjustGestureBrightness(20)
        controller.handleDoubleTapSeek(forward = false)
        controller.handleHorizontalSwipeSeek(0.2f)
        controller.adjustGestureZoom(0.5f)

        assertNull(controller.state.value.gestureState.volumePercent)
        assertNull(controller.state.value.gestureState.brightnessPercent)
        assertEquals(0f, controller.state.value.gestureState.zoom)
        assertEquals("控制已锁定", controller.state.value.gestureState.hudMessage)
        assertEquals(emptyList<List<String>>(), engine.commands)
        assertEquals(emptyList<Double>(), engine.doublePropertyHistory("volume"))
        assertEquals(emptyList<Double>(), engine.doublePropertyHistory("video-zoom"))
    }

    @Test
    fun clearGestureHudKeepsLastGestureValues() {
        val controller = MpvController(FakeMpvEngine())
        controller.adjustGestureBrightness(10)

        controller.clearGestureHud()

        assertEquals(60, controller.state.value.gestureState.brightnessPercent)
        assertNull(controller.state.value.gestureState.hudMessage)
    }
}
