package org.mubox.reader.video.player

import `is`.xyz.mpv.MPVNode
import java.util.Locale

internal fun mpvEndFileErrorMessage(data: MPVNode): String? {
    val fields = runCatching { data.asMap() }.getOrNull() ?: return null
    val reasonNode = fields["reason"] ?: return null
    val reasonText = reasonNode.asString()?.lowercase(Locale.ROOT)
    val reasonCode = reasonNode.asInt()
    if (reasonText in normalEndFileReasons || reasonCode in normalEndFileReasonCodes) return null

    val detail = fields["file_error"]?.asString()
        ?: fields["error"]?.asString()
        ?: reasonText
        ?: reasonCode?.toString()
        ?: return null
    return "视频播放失败：$detail"
}

internal fun isMpvEndFileStop(data: MPVNode): Boolean {
    val fields = runCatching { data.asMap() }.getOrNull() ?: return false
    val reasonNode = fields["reason"] ?: return false
    return reasonNode.asString()?.lowercase(Locale.ROOT) == "stop" || reasonNode.asInt() == 2L
}

private val normalEndFileReasons = setOf("eof", "stop", "quit", "redirect")
private val normalEndFileReasonCodes = setOf(0L, 2L, 3L, 5L)
