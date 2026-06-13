package com.example.comicdav.video.player

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.widget.FrameLayout
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
    var mpvProfileMode: MpvProfileMode = MpvProfileMode.FAST
    var videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO
    var gpuApiMode: GpuApiMode = GpuApiMode.AUTO
    var videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO
    var anime4kSettings: Anime4KSettings = Anime4KSettings()
    var anime4kManager: Anime4KManager? = null

    private var surfaceAttached = false
    private val pendingAfterLoadfileActions = mutableListOf<() -> Unit>()

    override fun initOptions() {
        MPVLib.setOptionString("profile", mpvProfileMode.profile)
        MPVLib.setOptionString("gpu-api", gpuApiMode.gpuApi)
        setVo(videoOutputMode.videoOutput)
        MPVLib.setOptionString("hwdec", videoDecoderMode.hwdec)
        MPVLib.setOptionString("hwdec-codecs", "all")
        MPVLib.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
        MPVLib.setOptionString("msg-level", "all=warn")
        val shaderChain = if (anime4kSettings.enabled && anime4kSettings.mode != Anime4KMode.OFF) {
            anime4kManager?.shaderChain(anime4kSettings).orEmpty()
        } else {
            ""
        }
        MPVLib.setOptionString("glsl-shaders", shaderChain)
        MPVLib.setPropertyBoolean("keep-open", true)
        MPVLib.setPropertyBoolean("input-default-bindings", true)
    }

    override fun observeProperties() {
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("core-idle", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NODE_ARRAY)
        MPVLib.observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("volume", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("audio-delay", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("video-params", MPVLib.MpvFormat.MPV_FORMAT_NODE_MAP)
        MPVLib.observeProperty("video-out-params", MPVLib.MpvFormat.MPV_FORMAT_NODE_MAP)
        MPVLib.observeProperty("video-params/aspect", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("video-out-params/aspect", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("hwdec", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        MPVLib.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        MPVLib.observeProperty("current-tracks/video/decoder", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        MPVLib.observeProperty("vo", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        MPVLib.observeProperty("gpu-api", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    }

    override fun postInitOptions() = Unit

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (surfaceAttached) return
        surfaceAttached = true
        super.surfaceCreated(holder)
        flushPendingAfterLoadfileActions()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!surfaceAttached) return
        surfaceAttached = false
        super.surfaceDestroyed(holder)
    }

    fun attachExistingSurfaceIfReady() {
        if (surfaceAttached) return
        val surface = holder.surface ?: return
        if (!surface.isValid) return

        surfaceCreated(holder)
        val frame = holder.surfaceFrame
        if (frame.width() > 0 && frame.height() > 0) {
            surfaceChanged(holder, PixelFormat.RGBA_8888, frame.width(), frame.height())
        }
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
            val parent = FrameLayout(context)
            return LayoutInflater.from(context)
                .inflate(R.layout.view_mubox_mpv, parent, false) as MuBoxMpvView
        }
    }
}
