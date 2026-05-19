package com.example.comicdav.video.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoAudioFocusControllerTest {
    @Test
    fun requestReturnsTrueAndAbandonReleasesOnlyAfterGrantedFocus() {
        val focus = FakeAudioFocusGateway(requestResult = true)
        val controller = VideoAudioFocusController(
            context = ApplicationProvider.getApplicationContext<Context>(),
            onFocusLost = {},
            focusGateway = focus,
        )

        assertTrue(controller.request())
        controller.abandon()
        controller.abandon()

        assertEquals(1, focus.requestCalls)
        assertEquals(1, focus.abandonCalls)
    }

    @Test
    fun requestReturnsFalseAndAbandonDoesNotReleaseWhenFocusDenied() {
        val focus = FakeAudioFocusGateway(requestResult = false)
        val controller = VideoAudioFocusController(
            context = ApplicationProvider.getApplicationContext<Context>(),
            onFocusLost = {},
            focusGateway = focus,
        )

        assertFalse(controller.request())
        controller.abandon()

        assertEquals(1, focus.requestCalls)
        assertEquals(0, focus.abandonCalls)
    }

    @Test
    fun focusLossAndTransientLossNotifyFocusLost() {
        val focus = FakeAudioFocusGateway(requestResult = true)
        var lostCount = 0
        VideoAudioFocusController(
            context = ApplicationProvider.getApplicationContext<Context>(),
            onFocusLost = { lostCount += 1 },
            focusGateway = focus,
        ).request()

        focus.sendLoss()
        focus.sendTransientLoss()
        focus.sendDuck()

        assertEquals(2, lostCount)
    }

    @Test
    fun backgroundPolicyPausesOnceUntilForegrounded() {
        var pauseCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            onPausePlayback = { pauseCount += 1 },
            onCleanupPlayback = {},
        )

        policy.setPausedForBackground(true)
        policy.setPausedForBackground(true)
        policy.setPausedForBackground(false)
        policy.setPausedForBackground(true)

        assertEquals(2, pauseCount)
    }

    @Test
    fun backgroundPolicyCleanupIsIdempotent() {
        var cleanupCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            onPausePlayback = {},
            onCleanupPlayback = { cleanupCount += 1 },
        )

        policy.cleanup()
        policy.cleanup()

        assertEquals(1, cleanupCount)
    }
}

private class FakeAudioFocusGateway(
    private val requestResult: Boolean,
) : VideoAudioFocusGateway {
    var requestCalls = 0
    var abandonCalls = 0
    private var listener: VideoAudioFocusListener? = null

    override fun request(listener: VideoAudioFocusListener): Boolean {
        requestCalls += 1
        this.listener = listener
        return requestResult
    }

    override fun abandon() {
        abandonCalls += 1
    }

    fun sendLoss() {
        listener?.onAudioFocusChange(VideoAudioFocusChange.Loss)
    }

    fun sendTransientLoss() {
        listener?.onAudioFocusChange(VideoAudioFocusChange.TransientLoss)
    }

    fun sendDuck() {
        listener?.onAudioFocusChange(VideoAudioFocusChange.Duck)
    }
}
