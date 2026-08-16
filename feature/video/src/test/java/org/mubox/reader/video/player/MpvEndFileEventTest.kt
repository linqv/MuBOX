package org.mubox.reader.video.player

import `is`.xyz.mpv.MPVNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvEndFileEventTest {
    @Test
    fun endFileErrorReasonReturnsUserVisibleMessage() {
        val data = MPVNode.MapNode(
            mapOf(
                "reason" to MPVNode.StringNode("error"),
                "file_error" to MPVNode.StringNode("HTTP 502"),
            ),
        )

        assertEquals("视频播放失败：HTTP 502", mpvEndFileErrorMessage(data))
    }

    @Test
    fun normalEndFileReasonsDoNotReturnErrors() {
        val eof = MPVNode.MapNode(mapOf("reason" to MPVNode.StringNode("eof")))
        val stopped = MPVNode.MapNode(mapOf("reason" to MPVNode.StringNode("stop")))
        val numericEof = MPVNode.MapNode(mapOf("reason" to MPVNode.IntNode(0L)))

        assertNull(mpvEndFileErrorMessage(eof))
        assertNull(mpvEndFileErrorMessage(stopped))
        assertNull(mpvEndFileErrorMessage(numericEof))
    }

    @Test
    fun numericErrorReasonReturnsUserVisibleMessage() {
        val data = MPVNode.MapNode(
            mapOf(
                "reason" to MPVNode.IntNode(4L),
                "error" to MPVNode.StringNode("network unreachable"),
            ),
        )

        assertEquals("视频播放失败：network unreachable", mpvEndFileErrorMessage(data))
    }
}
