package com.example.comicdav.video.player

import `is`.xyz.mpv.MPVNode

internal data class ParsedMpvTracks(
    val audioTracks: List<MpvTrack>,
    val subtitleTracks: List<MpvTrack>,
    val selectedAudioTrackId: Int?,
    val selectedSubtitleTrackId: Int?,
    val selectedVideoDecoder: String?,
)

internal object MpvTrackParser {
    fun parse(trackList: MPVNode): ParsedMpvTracks {
        val tracks = trackList.asArray().orEmpty().mapNotNull(::parseTrack)
        return ParsedMpvTracks(
            audioTracks = tracks.filter { it.type == MpvTrackType.AUDIO },
            subtitleTracks = tracks.filter { it.type == MpvTrackType.SUBTITLE },
            selectedAudioTrackId = tracks.selectedTrack(MpvTrackType.AUDIO)?.id,
            selectedSubtitleTrackId = tracks.selectedTrack(MpvTrackType.SUBTITLE)?.id,
            selectedVideoDecoder = tracks.selectedTrack(MpvTrackType.VIDEO)?.decoder,
        )
    }

    private fun parseTrack(node: MPVNode): MpvTrack? {
        val id = node.nodeInt("id")?.toInt() ?: return null
        val rawType = node.nodeString("type").orEmpty()
        return MpvTrack(
            id = id,
            type = rawType.toTrackType(),
            title = node.nodeString("title")
                ?: node.nodeString("external-filename")?.substringAfterLast('/')
                ?: node.nodeString("lang")
                ?: "$rawType $id",
            language = node.nodeString("lang"),
            decoder = node.nodeString("decoder"),
            isSelected = node.nodeBoolean("selected") == true,
            isExternal = node.nodeBoolean("external") == true,
        )
    }

    private fun List<MpvTrack>.selectedTrack(type: MpvTrackType): MpvTrack? =
        firstOrNull { it.type == type && it.isSelected }

    private fun String.toTrackType(): MpvTrackType =
        when (this) {
            "audio" -> MpvTrackType.AUDIO
            "sub" -> MpvTrackType.SUBTITLE
            "video" -> MpvTrackType.VIDEO
            else -> MpvTrackType.UNKNOWN
        }

    private fun MPVNode.nodeString(key: String): String? = this[key]?.asString()

    private fun MPVNode.nodeInt(key: String): Long? = this[key]?.asInt()

    private fun MPVNode.nodeBoolean(key: String): Boolean? = this[key]?.asBoolean()
}
