package com.example.comicdav.video

import android.app.ActivityManager
import android.content.Context

data class VideoPlaybackMemoryBudget(
    val totalBytes: Long,
    val mpvForwardBytes: Long,
    val mpvBackwardBytes: Long,
    val proxyBytes: Long,
) {
    init {
        require(totalBytes >= 0L)
        require(mpvForwardBytes + mpvBackwardBytes + proxyBytes <= totalBytes)
    }

    companion object {
        private const val BYTES_PER_MIB = 1024L * 1024L
        private const val MIN_TOTAL_MIB = 48L
        private const val MAX_TOTAL_MIB = 192L

        @Volatile
        private var configuredBudget: VideoPlaybackMemoryBudget? = null

        fun configure(context: Context) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            configuredBudget = fromMemoryClassMb(activityManager?.memoryClass ?: fallbackMemoryClassMb())
        }

        fun current(): VideoPlaybackMemoryBudget =
            configuredBudget ?: fromMemoryClassMb(fallbackMemoryClassMb())

        internal fun fromMemoryClassMb(memoryClassMb: Int): VideoPlaybackMemoryBudget {
            require(memoryClassMb > 0) { "memoryClassMb must be positive" }
            val totalMib = (memoryClassMb.toLong() / 4L).coerceIn(MIN_TOTAL_MIB, MAX_TOTAL_MIB)
            val totalBytes = totalMib * BYTES_PER_MIB
            val equalShare = totalBytes / 3L
            return VideoPlaybackMemoryBudget(
                totalBytes = totalBytes,
                mpvForwardBytes = equalShare,
                mpvBackwardBytes = equalShare,
                proxyBytes = totalBytes - equalShare * 2L,
            )
        }

        private fun fallbackMemoryClassMb(): Int =
            (Runtime.getRuntime().maxMemory() / BYTES_PER_MIB).coerceAtLeast(1L).toInt()
    }
}
