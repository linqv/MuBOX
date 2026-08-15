package org.mubox.reader.video.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {

    private fun controller(
        testScope: TestScope,
        onExpired: () -> Unit = {},
    ) = SleepTimerController(
        scope = testScope,
        onExpired = onExpired,
        nowMillis = { testScope.testScheduler.currentTime },
    )

    @Test
    fun startDurationModeExposesModeAndFullRemaining() = runTest {
        val timer = controller(this)

        timer.start(SleepTimerMode.MINUTES_10)

        assertEquals(SleepTimerMode.MINUTES_10, timer.state.value.mode)
        assertEquals(600_000L, timer.state.value.remainingMillis)
        assertTrue(timer.state.value.isActive)
        timer.destroy()
    }

    @Test
    fun countdownDecreasesAndExpiresAtDeadline() = runTest {
        var expiredCount = 0
        val timer = controller(this) { expiredCount += 1 }

        timer.start(SleepTimerMode.MINUTES_10)
        advanceTimeBy(61_000L)

        // 最后一个 tick 在 60_500ms 执行：600_000 - 60_500 = 539_500
        assertEquals(539_500L, timer.state.value.remainingMillis)

        advanceTimeBy(600_000L)

        assertEquals(1, expiredCount)
        assertEquals(SleepTimerMode.OFF, timer.state.value.mode)
        assertFalse(timer.state.value.isActive)
        timer.destroy()
    }

    @Test
    fun cancelStopsCountdownAndClearsState() = runTest {
        var expiredCount = 0
        val timer = controller(this) { expiredCount += 1 }

        timer.start(SleepTimerMode.MINUTES_30)
        advanceTimeBy(120_000L)
        timer.cancel()

        assertEquals(SleepTimerMode.OFF, timer.state.value.mode)
        assertFalse(timer.state.value.isActive)

        advanceTimeBy(3_600_000L)

        assertEquals(0, expiredCount)
        timer.destroy()
    }

    @Test
    fun endOfEpisodeExpiresWhenPlaybackEnds() = runTest {
        var expiredCount = 0
        val timer = controller(this) { expiredCount += 1 }

        timer.start(SleepTimerMode.END_OF_EPISODE)

        assertEquals(SleepTimerMode.END_OF_EPISODE, timer.state.value.mode)
        assertEquals(0, expiredCount)

        timer.onPlaybackEnded()

        assertEquals(1, expiredCount)
        assertEquals(SleepTimerMode.OFF, timer.state.value.mode)
        timer.destroy()
    }

    @Test
    fun fixedDurationIgnoresPlaybackEnded() = runTest {
        var expiredCount = 0
        val timer = controller(this) { expiredCount += 1 }

        timer.start(SleepTimerMode.MINUTES_10)
        timer.onPlaybackEnded()

        assertEquals(0, expiredCount)
        assertEquals(SleepTimerMode.MINUTES_10, timer.state.value.mode)
        timer.destroy()
    }

    @Test
    fun restartReplacesPreviousTimer() = runTest {
        val timer = controller(this)

        timer.start(SleepTimerMode.MINUTES_10)
        advanceTimeBy(60_000L)
        timer.start(SleepTimerMode.MINUTES_30)

        assertEquals(SleepTimerMode.MINUTES_30, timer.state.value.mode)
        assertEquals(1_800_000L, timer.state.value.remainingMillis)
        timer.destroy()
    }

    @Test
    fun offModeClearsActiveTimer() = runTest {
        val timer = controller(this)

        timer.start(SleepTimerMode.MINUTES_45)
        timer.start(SleepTimerMode.OFF)

        assertEquals(SleepTimerState(), timer.state.value)
        assertFalse(timer.state.value.isActive)
        timer.destroy()
    }

    @Test
    fun labelsAndStatusTexts() {
        assertEquals("关闭", SleepTimerMode.OFF.controlLabel())
        assertEquals("30分钟", SleepTimerMode.MINUTES_30.controlLabel())
        assertEquals("本集结束", SleepTimerMode.END_OF_EPISODE.controlLabel())

        assertEquals(
            "定时关闭 · 剩余 9:59",
            SleepTimerState(mode = SleepTimerMode.MINUTES_10, remainingMillis = 599_000L).statusText(),
        )
        assertEquals(
            "本集结束后自动关闭",
            SleepTimerState(mode = SleepTimerMode.END_OF_EPISODE).statusText(),
        )
        assertEquals("", SleepTimerState().statusText())

        assertNull(SleepTimerState().notificationStatusText())
        assertEquals(
            "剩余 29:59",
            SleepTimerState(mode = SleepTimerMode.MINUTES_30, remainingMillis = 1_799_000L)
                .notificationStatusText(),
        )
        assertEquals(
            "本集结束后自动关闭",
            SleepTimerState(mode = SleepTimerMode.END_OF_EPISODE).notificationStatusText(),
        )
    }
}
