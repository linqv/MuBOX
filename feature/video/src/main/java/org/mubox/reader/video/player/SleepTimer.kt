package org.mubox.reader.video.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 定时关闭选项：固定时长倒计时，或“本集结束后关闭”。
 * [durationMillis] 为 null 表示非固定时长模式（关闭 / 本集结束）。
 */
enum class SleepTimerMode(val durationMillis: Long?) {
    OFF(null),
    MINUTES_10(10L * 60L * 1000L),
    MINUTES_20(20L * 60L * 1000L),
    MINUTES_30(30L * 60L * 1000L),
    MINUTES_45(45L * 60L * 1000L),
    MINUTES_60(60L * 60L * 1000L),
    MINUTES_90(90L * 60L * 1000L),
    END_OF_EPISODE(null),
}

fun SleepTimerMode.controlLabel(): String =
    when (this) {
        SleepTimerMode.OFF -> "关闭"
        SleepTimerMode.MINUTES_10 -> "10分钟"
        SleepTimerMode.MINUTES_20 -> "20分钟"
        SleepTimerMode.MINUTES_30 -> "30分钟"
        SleepTimerMode.MINUTES_45 -> "45分钟"
        SleepTimerMode.MINUTES_60 -> "60分钟"
        SleepTimerMode.MINUTES_90 -> "90分钟"
        SleepTimerMode.END_OF_EPISODE -> "本集结束"
    }

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val remainingMillis: Long = 0L,
) {
    val isActive: Boolean
        get() = mode != SleepTimerMode.OFF
}

/** 状态胶囊/悬浮提示文本；未开启时返回空字符串。 */
fun SleepTimerState.statusText(): String =
    when {
        mode.durationMillis != null -> "定时关闭 · 剩余 ${formatVideoTime(remainingMillis)}"
        mode == SleepTimerMode.END_OF_EPISODE -> "本集结束后自动关闭"
        else -> ""
    }

/** 后台播放通知的附注文本；未开启时返回 null。 */
fun SleepTimerState.notificationStatusText(): String? =
    when {
        mode == SleepTimerMode.OFF -> null
        mode.durationMillis != null -> "剩余 ${formatVideoTime(remainingMillis)}"
        else -> "本集结束后自动关闭"
    }

/**
 * 定时关闭的核心控制：固定时长模式通过周期 tick 倒计时，到期调用 [onExpired]；
 * “本集结束”模式在 [onPlaybackEnded] 时触发。
 */
class SleepTimerController(
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit,
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val tickIntervalMillis: Long = TICK_INTERVAL_MILLIS,
) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var tickerJob: Job? = null
    private var deadlineMillis: Long = 0L

    fun start(mode: SleepTimerMode) {
        stopTicker()
        val duration = mode.durationMillis
        if (duration == null) {
            _state.value = SleepTimerState(mode = mode)
            return
        }
        deadlineMillis = nowMillis() + duration
        _state.value = SleepTimerState(mode = mode, remainingMillis = duration)
        startTicker()
    }

    fun cancel() {
        start(SleepTimerMode.OFF)
    }

    /** “本集结束”模式专用：当前文件自然播放结束时触发。 */
    fun onPlaybackEnded() {
        if (_state.value.mode == SleepTimerMode.END_OF_EPISODE) {
            expire()
        }
    }

    fun destroy() {
        stopTicker()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(tickIntervalMillis)
                tick()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun tick() {
        if (_state.value.mode.durationMillis == null) return
        val remaining = (deadlineMillis - nowMillis()).coerceAtLeast(0L)
        _state.value = _state.value.copy(remainingMillis = remaining)
        if (remaining <= 0L) {
            expire()
        }
    }

    private fun expire() {
        stopTicker()
        _state.value = SleepTimerState()
        onExpired()
    }

    companion object {
        const val TICK_INTERVAL_MILLIS = 500L
    }
}
