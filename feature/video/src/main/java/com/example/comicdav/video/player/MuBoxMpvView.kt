package com.example.comicdav.video.player

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.widget.FrameLayout
import `is`.xyz.mpv.BaseMPVView
import com.example.comicdav.core.model.settings.Anime4KProfile
import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.MpvProfileMode
import com.example.comicdav.core.model.settings.VideoDecoderMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import com.example.comicdav.video.VideoPlaybackMemoryBudget
import com.example.comicdav.video.R

interface MpvFileLoader {
    fun playFileWhenReady(uri: String, afterLoadfile: () -> Unit)
    fun destroy()
}

class MuBoxMpvView(
    context: Context,
    attrs: AttributeSet,
) : BaseMPVView(context, attrs), MpvFileLoader {
    internal var nativeApi: MpvNativeApi = RealMpvNativeApi
    private var startupConfiguration = MpvViewStartupConfiguration()

    var mpvProfileMode: MpvProfileMode
        get() = startupConfiguration.profileMode
        set(value) {
            startupConfiguration = startupConfiguration.copy(profileMode = value)
        }
    var videoOutputMode: VideoOutputMode
        get() = startupConfiguration.videoOutputMode
        set(value) {
            startupConfiguration = startupConfiguration.copy(videoOutputMode = value)
        }
    var gpuApiMode: GpuApiMode
        get() = startupConfiguration.gpuApiMode
        set(value) {
            startupConfiguration = startupConfiguration.copy(gpuApiMode = value)
        }
    var videoDecoderMode: VideoDecoderMode
        get() = startupConfiguration.videoDecoderMode
        set(value) {
            startupConfiguration = startupConfiguration.copy(videoDecoderMode = value)
        }
    var anime4kProfile: Anime4KProfile
        get() = startupConfiguration.anime4kProfile
        set(value) {
            startupConfiguration = startupConfiguration.copy(anime4kProfile = value)
        }
    var anime4kManager: Anime4KManager? = null

    private val surfaceAwareFileLoader = SurfaceAwareMpvFileLoader(
        loadDirectly = { uri -> nativeApi.command("loadfile", uri) },
        loadThroughView = ::playFile,
    )

    override fun initOptions() {
        MpvStartupOptionsApplier(
            nativeApi = nativeApi,
            setVideoOutput = ::setVo,
            initializeAnime4K = { anime4kManager?.initialize() },
            anime4KShaderChain = { profile -> anime4kManager?.shaderChain(profile).orEmpty() },
            memoryBudget = VideoPlaybackMemoryBudget.current(),
        ).apply(startupConfiguration)
    }

    override fun observeProperties() {
        observeMpvPlaybackProperties(nativeApi)
    }

    override fun postInitOptions() = Unit

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!surfaceAwareFileLoader.markSurfaceAttached()) return
        super.surfaceCreated(holder)
        surfaceAwareFileLoader.flushPendingAfterLoadfileActions()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!surfaceAwareFileLoader.markSurfaceDetached()) return
        super.surfaceDestroyed(holder)
    }

    fun attachExistingSurfaceIfReady() {
        if (surfaceAwareFileLoader.isSurfaceAttached) return
        val surface = holder.surface ?: return
        if (!surface.isValid) return

        surfaceCreated(holder)
        val frame = holder.surfaceFrame
        if (frame.width() > 0 && frame.height() > 0) {
            surfaceChanged(holder, PixelFormat.RGBA_8888, frame.width(), frame.height())
        }
    }

    override fun playFileWhenReady(uri: String, afterLoadfile: () -> Unit) {
        surfaceAwareFileLoader.playFileWhenReady(uri, afterLoadfile)
    }

    companion object {
        fun create(context: Context): MuBoxMpvView {
            val parent = FrameLayout(context)
            return LayoutInflater.from(context)
                .inflate(R.layout.view_mubox_mpv, parent, false) as MuBoxMpvView
        }
    }
}
