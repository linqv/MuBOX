package com.example.comicdav.video.player

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.SurfaceHolder
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib
import org.mubox.reader.R

interface MpvFileLoader {
    fun playFileWhenReady(uri: String, afterLoadfile: () -> Unit)
    fun destroy()
}

class MuBoxMpvView(
    context: Context,
    attrs: AttributeSet,
) : BaseMPVView(context, attrs), MpvFileLoader {
    private var surfaceAttached = false
    private val pendingAfterLoadfileActions = mutableListOf<() -> Unit>()

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

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAttached = true
        super.surfaceCreated(holder)
        flushPendingAfterLoadfileActions()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAttached = false
        super.surfaceDestroyed(holder)
    }

    override fun playFileWhenReady(uri: String, afterLoadfile: () -> Unit) {
        pendingAfterLoadfileActions.clear()
        if (surfaceAttached) {
            MPVLib.command("loadfile", uri)
            afterLoadfile()
        } else {
            pendingAfterLoadfileActions += afterLoadfile
            playFile(uri)
        }
    }

    private fun flushPendingAfterLoadfileActions() {
        val actions = pendingAfterLoadfileActions.toList()
        pendingAfterLoadfileActions.clear()
        actions.forEach { it() }
    }

    companion object {
        fun create(context: Context): MuBoxMpvView {
            return LayoutInflater.from(context)
                .inflate(R.layout.view_mubox_mpv, null) as MuBoxMpvView
        }
    }
}
