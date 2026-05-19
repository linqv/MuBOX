package com.example.comicdav.video.player

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib
import org.mubox.reader.R

class MuBoxMpvView(
    context: Context,
    attrs: AttributeSet,
) : BaseMPVView(context, attrs) {
    override fun initOptions() {
        setVo("gpu")
        MPVLib.setOptionString("hwdec", "mediacodec,mediacodec-copy,no")
        MPVLib.setOptionString("hwdec-codecs", "all")
        MPVLib.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
        MPVLib.setOptionString("msg-level", "all=warn")
        MPVLib.setPropertyBoolean("keep-open", true)
        MPVLib.setPropertyBoolean("input-default-bindings", true)
    }

    override fun observeProperties() {
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("core-idle", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    override fun postInitOptions() = Unit

    companion object {
        fun create(context: Context): MuBoxMpvView {
            return LayoutInflater.from(context)
                .inflate(R.layout.view_mubox_mpv, null) as MuBoxMpvView
        }
    }
}
