package org.mubox.reader.video.player

import kotlin.random.Random

/** 「听视频」在当前文件自然播放结束后的续播方式。 */
internal enum class ListenPlaybackMode {
    SEQUENTIAL,
    SHUFFLE,
    LOOP,
}

internal fun ListenPlaybackMode.controlLabel(): String =
    when (this) {
        ListenPlaybackMode.SEQUENTIAL -> "顺序连播"
        ListenPlaybackMode.SHUFFLE -> "随机播放"
        ListenPlaybackMode.LOOP -> "循环"
    }

internal fun ListenPlaybackMode.detailText(): String =
    when (this) {
        ListenPlaybackMode.SEQUENTIAL -> "当前集结束后播放下一集，播完最后一集后停止"
        ListenPlaybackMode.SHUFFLE -> "当前集结束后，从队列中随机播放其他剧集"
        ListenPlaybackMode.LOOP -> "按顺序连续播放，播完最后一集后从第一集继续"
    }

/**
 * 返回自然播放结束后的目标剧集；null 表示保持结束状态。
 *
 * [random] 可注入，便于对随机模式做确定性测试。
 */
internal fun nextListenEpisodeIndex(
    mode: ListenPlaybackMode,
    currentIndex: Int,
    episodeCount: Int,
    random: Random = Random.Default,
): Int? {
    if (episodeCount <= 0) return null
    val normalizedCurrent = currentIndex.coerceIn(0, episodeCount - 1)
    return when (mode) {
        ListenPlaybackMode.SEQUENTIAL ->
            (normalizedCurrent + 1).takeIf { it < episodeCount }

        ListenPlaybackMode.SHUFFLE -> {
            if (episodeCount == 1) {
                normalizedCurrent
            } else {
                val offset = random.nextInt(1, episodeCount)
                (normalizedCurrent + offset) % episodeCount
            }
        }

        ListenPlaybackMode.LOOP -> (normalizedCurrent + 1) % episodeCount
    }
}

internal fun restoredListenPlaybackMode(savedName: String?): ListenPlaybackMode =
    savedName
        ?.let { name -> ListenPlaybackMode.entries.firstOrNull { it.name == name } }
        ?: ListenPlaybackMode.SEQUENTIAL
