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

internal fun mpvEventPlaylistEntryId(data: MPVNode): Long? =
    runCatching { data.asMap()?.get("playlist_entry_id")?.asInt() }.getOrNull()

/** Fallback for event bridges that omit playlist_entry_id on a replaced entry. */
internal fun isMpvTransitionReplacementEndFile(data: MPVNode): Boolean {
    val fields = runCatching { data.asMap() }.getOrNull() ?: return false
    val reasonNode = fields["reason"] ?: return false
    return reasonNode.asString()?.lowercase(Locale.ROOT) in transitionReplacementReasons ||
        reasonNode.asInt() in transitionReplacementReasonCodes
}

private val normalEndFileReasons = setOf("eof", "stop", "quit", "redirect")
private val normalEndFileReasonCodes = setOf(0L, 2L, 3L, 5L)
private val transitionReplacementReasons = setOf("stop", "quit", "redirect")
private val transitionReplacementReasonCodes = setOf(2L, 3L, 5L)
