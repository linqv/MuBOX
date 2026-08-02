package org.mubox.reader.video.player

import `is`.xyz.mpv.MPVNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvTrackParserTest {
    @Test
    fun parseCategorizesTracksAndReportsSelectedPlaybackTracks() {
        val parsed = MpvTrackParser.parse(
            trackList(
                track(id = 1, type = "audio", title = "Japanese", selected = true),
                track(id = 2, type = "audio", title = "English"),
                track(id = 3, type = "sub", title = "中文字幕", selected = true),
                track(id = 4, type = "video", title = "Main", selected = true, decoder = "hevc"),
            ),
        )

        assertEquals(listOf(1, 2), parsed.audioTracks.map { it.id })
        assertEquals(listOf(3), parsed.subtitleTracks.map { it.id })
        assertEquals(1, parsed.selectedAudioTrackId)
        assertEquals(3, parsed.selectedSubtitleTrackId)
        assertEquals("hevc", parsed.selectedVideoDecoder)
    }

    @Test
    fun parseUsesStableTitleFallbacksAndPreservesTrackMetadata() {
        val parsed = MpvTrackParser.parse(
            trackList(
                track(
                    id = 5,
                    type = "sub",
                    externalFilename = "/storage/emulated/0/Subtitles/movie.zh.srt",
                    language = "zh",
                    external = true,
                ),
                track(id = 6, type = "audio", language = "ja"),
            ),
        )

        assertEquals("movie.zh.srt", parsed.subtitleTracks.single().title)
        assertEquals("zh", parsed.subtitleTracks.single().language)
        assertTrue(parsed.subtitleTracks.single().isExternal)
        assertEquals("ja", parsed.audioTracks.single().title)
    }

    @Test
    fun parseIgnoresEntriesWithoutIdsAndHandlesNonArrayNodes() {
        val withoutId = MPVNode.MapNode(
            mapOf(
                "type" to MPVNode.StringNode("audio"),
                "title" to MPVNode.StringNode("Invalid"),
            ),
        )

        val parsed = MpvTrackParser.parse(trackList(withoutId))
        val nonArray = MpvTrackParser.parse(MPVNode.MapNode(emptyMap()))

        assertTrue(parsed.audioTracks.isEmpty())
        assertTrue(parsed.subtitleTracks.isEmpty())
        assertEquals(ParsedMpvTracks(emptyList(), emptyList(), null, null, null), nonArray)
    }

    private fun trackList(vararg tracks: MPVNode): MPVNode =
        MPVNode.ArrayNode(arrayOf(*tracks))

    private fun track(
        id: Int,
        type: String,
        title: String? = null,
        language: String? = null,
        externalFilename: String? = null,
        selected: Boolean = false,
        external: Boolean = false,
        decoder: String? = null,
    ): MPVNode {
        val values = mutableMapOf<String, MPVNode>(
            "id" to MPVNode.IntNode(id.toLong()),
            "type" to MPVNode.StringNode(type),
            "selected" to MPVNode.BooleanNode(selected),
            "external" to MPVNode.BooleanNode(external),
        )
        title?.let { values["title"] = MPVNode.StringNode(it) }
        language?.let { values["lang"] = MPVNode.StringNode(it) }
        externalFilename?.let { values["external-filename"] = MPVNode.StringNode(it) }
        decoder?.let { values["decoder"] = MPVNode.StringNode(it) }
        return MPVNode.MapNode(values)
    }
}
