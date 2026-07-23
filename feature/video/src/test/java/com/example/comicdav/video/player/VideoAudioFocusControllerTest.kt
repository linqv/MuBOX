package com.example.comicdav.video.player

import com.example.comicdav.core.model.settings.VideoBackgroundMode
import android.content.Context
import android.content.Intent
import android.os.Build
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
        var isPlaying = true
        var pauseCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            isCurrentlyPlaying = { isPlaying },
            onPausePlayback = {
                pauseCount += 1
                isPlaying = false
            },
            onResumePlayback = { isPlaying = true },
            onCleanupPlayback = {},
            backgroundCleanupScheduler = FakeBackgroundCleanupScheduler(),
        )

        policy.moveToBackground()
        policy.moveToBackground()
        policy.returnToForeground()
        // User manually resumes playback after returning to foreground
        isPlaying = true
        policy.moveToBackground()

        assertEquals(2, pauseCount)
    }

    @Test
    fun pausedPlaybackReleasesBackgroundResourcesOnceUntilForegrounded() {
        var pauseCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            isCurrentlyPlaying = { false },
            onPausePlayback = { pauseCount += 1 },
            onCleanupPlayback = {},
            backgroundCleanupScheduler = FakeBackgroundCleanupScheduler(),
        )

        policy.moveToBackground()
        policy.moveToBackground()
        policy.returnToForeground()
        policy.moveToBackground()

        assertEquals(2, pauseCount)
    }

    @Test
    fun resumeOnReturnPausedPlaybackReleasesResourcesWithoutResuming() {
        var pauseCount = 0
        var resumeCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.RESUME_ON_RETURN,
            isCurrentlyPlaying = { false },
            onPausePlayback = { pauseCount += 1 },
            onResumePlayback = { resumeCount += 1 },
            onCleanupPlayback = {},
            backgroundCleanupScheduler = FakeBackgroundCleanupScheduler(),
        )

        policy.moveToBackground()
        policy.returnToForeground()

        assertEquals(1, pauseCount)
        assertEquals(0, resumeCount)
    }

    @Test
    fun backgroundPolicyCleanupIsIdempotent() {
        var cleanupCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            isCurrentlyPlaying = { true },
            onPausePlayback = {},
            onCleanupPlayback = { cleanupCount += 1 },
            backgroundCleanupScheduler = FakeBackgroundCleanupScheduler(),
        )

        policy.cleanup()
        policy.cleanup()

        assertEquals(1, cleanupCount)
    }

    @Test
    fun foregroundReturnCancelsBackgroundCleanupWithoutAutoResume() {
        var pauseCount = 0
        var cleanupCount = 0
        var isPlaying = true
        val scheduler = FakeBackgroundCleanupScheduler()
        val policy = VideoPlaybackLifecyclePolicy(
            isCurrentlyPlaying = { isPlaying },
            onPausePlayback = {
                pauseCount += 1
                isPlaying = false
            },
            onCleanupPlayback = { cleanupCount += 1 },
            backgroundCleanupDelayMillis = 100,
            backgroundCleanupScheduler = scheduler,
        )

        policy.moveToBackground()
        policy.returnToForeground()
        scheduler.runPending()

        assertEquals(1, pauseCount)
        assertEquals(0, cleanupCount)
    }

    @Test
    fun backgroundPlayReturnCancelsPausedBackgroundCleanup() {
        var pauseCount = 0
        var cleanupCount = 0
        val scheduler = FakeBackgroundCleanupScheduler()
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.BACKGROUND_PLAY,
            isCurrentlyPlaying = { false },
            onPausePlayback = { pauseCount += 1 },
            onCleanupPlayback = { cleanupCount += 1 },
            backgroundCleanupDelayMillis = 100,
            backgroundCleanupScheduler = scheduler,
        )

        policy.moveToBackground()
        policy.returnToForeground()
        scheduler.runPending()

        assertEquals(1, pauseCount)
        assertEquals(0, cleanupCount)
    }

    @Test
    fun backgroundTimeoutCleansUpPlaybackWhenActivityDoesNotReturn() {
        var cleanupCount = 0
        val scheduler = FakeBackgroundCleanupScheduler()
        val policy = VideoPlaybackLifecyclePolicy(
            isCurrentlyPlaying = { true },
            onPausePlayback = {},
            onCleanupPlayback = { cleanupCount += 1 },
            backgroundCleanupDelayMillis = 100,
            backgroundCleanupScheduler = scheduler,
        )

        policy.moveToBackground()
        scheduler.runPending()
        scheduler.runPending()

        assertEquals(1, cleanupCount)
    }

    @Test
    fun playbackEndedStopsForegroundPlaybackWithoutCleanup() {
        var foregroundStopCount = 0
        var cleanupCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.BACKGROUND_PLAY,
            isCurrentlyPlaying = { true },
            onPausePlayback = {},
            onCleanupPlayback = { cleanupCount += 1 },
            onStopForegroundPlayback = { foregroundStopCount += 1 },
            backgroundCleanupScheduler = FakeBackgroundCleanupScheduler(),
        )

        policy.playbackEnded()

        assertEquals(1, foregroundStopCount)
        assertEquals(0, cleanupCount)
    }

    @Test
    fun backgroundPlaybackEndedCleansUpAndFinishesBackgroundSession() {
        var foregroundStopCount = 0
        var cleanupCount = 0
        var timeoutCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.BACKGROUND_PLAY,
            isCurrentlyPlaying = { true },
            onPausePlayback = {},
            onCleanupPlayback = { cleanupCount += 1 },
            onBackgroundTimeoutAfterCleanup = { timeoutCount += 1 },
            onStopForegroundPlayback = { foregroundStopCount += 1 },
            backgroundCleanupScheduler = FakeBackgroundCleanupScheduler(),
        )

        policy.moveToBackground()
        policy.playbackEnded()

        assertEquals(1, foregroundStopCount)
        assertEquals(1, cleanupCount)
        assertEquals(1, timeoutCount)
    }

    @Test
    fun backgroundPlayStartFailurePausesAndSchedulesCleanup() {
        var pauseCount = 0
        var cleanupCount = 0
        val scheduler = FakeBackgroundCleanupScheduler()
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.BACKGROUND_PLAY,
            isCurrentlyPlaying = { true },
            onPausePlayback = { pauseCount += 1 },
            onCleanupPlayback = { cleanupCount += 1 },
            onStartForegroundPlayback = { false },
            backgroundCleanupDelayMillis = 100,
            backgroundCleanupScheduler = scheduler,
        )

        policy.moveToBackground()
        scheduler.runPending()

        assertEquals(1, pauseCount)
        assertEquals(1, cleanupCount)
    }

    @Test
    fun backgroundPlayStartSuccessKeepsPlaybackWithoutSchedulingCleanup() {
        var pauseCount = 0
        var cleanupCount = 0
        val scheduler = FakeBackgroundCleanupScheduler()
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.BACKGROUND_PLAY,
            isCurrentlyPlaying = { true },
            onPausePlayback = { pauseCount += 1 },
            onCleanupPlayback = { cleanupCount += 1 },
            onStartForegroundPlayback = { true },
            backgroundCleanupDelayMillis = 100,
            backgroundCleanupScheduler = scheduler,
        )

        policy.moveToBackground()
        scheduler.runPending()

        assertEquals(0, pauseCount)
        assertEquals(0, cleanupCount)
    }

    @Test
    fun playbackStoppedIntentMatchesOnlySamePlaybackSession() {
        val matchingIntent = VideoPlaybackService.playbackStoppedIntent("session-a")
        val missingSessionIntent = Intent(VideoPlaybackService.ACTION_PLAYBACK_STOPPED)

        assertTrue(VideoPlaybackService.isPlaybackStoppedForSession(matchingIntent, "session-a"))
        assertFalse(VideoPlaybackService.isPlaybackStoppedForSession(matchingIntent, "session-b"))
        assertFalse(VideoPlaybackService.isPlaybackStoppedForSession(missingSessionIntent, "session-a"))
    }

    @Test
    fun notificationPermissionDecisionPreservesBackgroundPlaybackMode() {
        val decision = videoBackgroundPermissionDecision(
            requestedMode = VideoBackgroundMode.BACKGROUND_PLAY,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            postNotificationsGranted = false,
        )

        assertEquals(VideoBackgroundMode.BACKGROUND_PLAY, decision.mode)
        assertTrue(decision.shouldRequestPostNotifications)
    }

    @Test
    fun playbackEndedInNoneModeCancelsCleanupTimerWithoutCleanup() {
        var cleanupCount = 0
        var foregroundStopCount = 0
        val scheduler = FakeBackgroundCleanupScheduler()
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.NONE,
            isCurrentlyPlaying = { true },
            onPausePlayback = {},
            onCleanupPlayback = { cleanupCount += 1 },
            onStopForegroundPlayback = { foregroundStopCount += 1 },
            backgroundCleanupScheduler = scheduler,
        )

        policy.moveToBackground()
        policy.playbackEnded()
        scheduler.runPending()

        assertEquals(0, cleanupCount)
        assertEquals(1, foregroundStopCount)
    }

    @Test
    fun playbackInterruptedInBackgroundPlayCleansUpAndFinishes() {
        var cleanupCount = 0
        var timeoutCount = 0
        var foregroundStopCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.BACKGROUND_PLAY,
            isCurrentlyPlaying = { true },
            onPausePlayback = {},
            onCleanupPlayback = { cleanupCount += 1 },
            onBackgroundTimeoutAfterCleanup = { timeoutCount += 1 },
            onStopForegroundPlayback = { foregroundStopCount += 1 },
            backgroundCleanupScheduler = FakeBackgroundCleanupScheduler(),
        )

        policy.moveToBackground()
        policy.playbackInterrupted()

        assertEquals(1, cleanupCount)
        assertEquals(1, timeoutCount)
        assertEquals(1, foregroundStopCount)
    }

    @Test
    fun playbackInterruptedInForegroundDoesNotCleanUp() {
        var cleanupCount = 0
        var foregroundStopCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.BACKGROUND_PLAY,
            isCurrentlyPlaying = { true },
            onPausePlayback = {},
            onCleanupPlayback = { cleanupCount += 1 },
            onStopForegroundPlayback = { foregroundStopCount += 1 },
            backgroundCleanupScheduler = FakeBackgroundCleanupScheduler(),
        )

        policy.playbackInterrupted()

        assertEquals(0, cleanupCount)
        assertEquals(0, foregroundStopCount)
    }

    @Test
    fun playbackInterruptedInNonBackgroundPlayModeDoesNotCleanUp() {
        var cleanupCount = 0
        val scheduler = FakeBackgroundCleanupScheduler()
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.NONE,
            isCurrentlyPlaying = { true },
            onPausePlayback = {},
            onCleanupPlayback = { cleanupCount += 1 },
            backgroundCleanupScheduler = scheduler,
        )

        policy.moveToBackground()
        policy.playbackInterrupted()

        assertEquals(0, cleanupCount)
        scheduler.runPending()
        assertEquals(1, cleanupCount)
    }

    @Test
    fun playbackInterruptedAfterPlaybackEndedIsIdempotent() {
        var cleanupCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.BACKGROUND_PLAY,
            isCurrentlyPlaying = { true },
            onPausePlayback = {},
            onCleanupPlayback = { cleanupCount += 1 },
            onBackgroundTimeoutAfterCleanup = {},
            backgroundCleanupScheduler = FakeBackgroundCleanupScheduler(),
        )

        policy.moveToBackground()
        policy.playbackEnded()
        policy.playbackInterrupted()

        assertEquals(1, cleanupCount)
    }

    @Test
    fun playbackInterruptedAfterCleanupIsNoOp() {
        var cleanupCount = 0
        val policy = VideoPlaybackLifecyclePolicy(
            mode = VideoBackgroundMode.BACKGROUND_PLAY,
            isCurrentlyPlaying = { true },
            onPausePlayback = {},
            onCleanupPlayback = { cleanupCount += 1 },
            backgroundCleanupScheduler = FakeBackgroundCleanupScheduler(),
        )

        policy.cleanup()
        policy.playbackInterrupted()

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

private class FakeBackgroundCleanupScheduler : BackgroundCleanupScheduler {
    private var pending: (() -> Unit)? = null

    override fun schedule(delayMillis: Long, action: () -> Unit): BackgroundCleanupHandle {
        pending = action
        return BackgroundCleanupHandle {
            if (pending === action) {
                pending = null
            }
        }
    }

    fun runPending() {
        val action = pending ?: return
        pending = null
        action()
    }
}
