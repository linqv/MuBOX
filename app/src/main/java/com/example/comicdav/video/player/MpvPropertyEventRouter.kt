package com.example.comicdav.video.player

import `is`.xyz.mpv.MPVNode

/** Routes typed mpv property callbacks into the player state model. */
internal class MpvPropertyEventRouter(
    private val controller: MpvController,
) {
    fun route(property: String, value: Long) {
        when (property) {
            "aid" -> controller.onAudioTrackChanged(value.toInt())
            "sid" -> controller.onSubtitleTrackChanged(value.toInt().takeIf { it > 0 })
        }
    }

    fun route(property: String, value: Boolean) {
        if (property == "pause") {
            controller.onPauseChanged(value)
        }
    }

    fun route(property: String, value: String) {
        when (property) {
            "aid" -> controller.onAudioTrackChanged(value.toIntOrNull())
            "sid" -> controller.onSubtitleTrackChanged(value.toIntOrNull())
            "hwdec" -> controller.onHwdecChanged(value)
            "hwdec-current" -> controller.onActiveHwdecChanged(value)
            "current-tracks/video/decoder" -> controller.onActiveVideoDecoderChanged(value)
            "vo" -> controller.onVoChanged(value)
            "gpu-api" -> controller.onGpuApiChanged(value)
        }
    }

    fun route(property: String, value: Double) {
        when (property) {
            "duration" -> controller.onDurationChanged(value)
            "time-pos" -> controller.onPositionChanged(value)
            "speed" -> controller.onSpeedChanged(value)
            "volume" -> controller.onVolumeChanged(value)
            "audio-delay" -> controller.onAudioDelayChanged(value)
            "video-params/aspect" -> controller.onVideoAspectChanged(value)
            "video-out-params/aspect" -> controller.onVideoOutAspectChanged(value)
        }
    }

    fun route(property: String, value: MPVNode) {
        when (property) {
            "track-list" -> controller.onTrackListChanged(value)
            "video-params" -> controller.onVideoParamsChanged(value)
            "video-out-params" -> controller.onVideoOutParamsChanged(value)
        }
    }
}
