package com.example.comicdav.video.player

import android.content.Intent
import android.os.Build
import android.os.Parcel
import android.os.Parcelable

data class VideoPlayerOptions(
    val resumeEnabled: Boolean = true,
    val videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO,
    val gpuApiMode: GpuApiMode = GpuApiMode.AUTO,
    val videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    val mpvProfileMode: MpvProfileMode = MpvProfileMode.FAST,
    val controlsAutoHideMillis: Int = 5_000,
    val playerOrientationMode: VideoPlayerOrientationMode = VideoPlayerOrientationMode.VIDEO,
    val proxyDebugInfoEnabled: Boolean = false,
    val videoBackgroundMode: VideoBackgroundMode = VideoBackgroundMode.NONE,
    val anime4kEnabled: Boolean = false,
    val anime4kMode: Anime4KMode = Anime4KMode.A,
    val anime4kQuality: Anime4KQuality = Anime4KQuality.FAST,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        resumeEnabled = parcel.readInt() != 0,
        videoOutputMode = parcel.readEnumOrDefault(VideoOutputMode.AUTO),
        gpuApiMode = parcel.readEnumOrDefault(GpuApiMode.AUTO),
        videoDecoderMode = parcel.readEnumOrDefault(VideoDecoderMode.AUTO),
        mpvProfileMode = parcel.readEnumOrDefault(MpvProfileMode.FAST),
        controlsAutoHideMillis = parcel.readInt(),
        playerOrientationMode = parcel.readEnumOrDefault(VideoPlayerOrientationMode.VIDEO),
        proxyDebugInfoEnabled = parcel.readInt() != 0,
        videoBackgroundMode = parcel.readEnumOrDefault(VideoBackgroundMode.NONE),
        anime4kEnabled = parcel.readInt() != 0,
        anime4kMode = parcel.readEnumOrDefault(Anime4KMode.A),
        anime4kQuality = parcel.readEnumOrDefault(Anime4KQuality.FAST),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(if (resumeEnabled) 1 else 0)
        parcel.writeString(videoOutputMode.name)
        parcel.writeString(gpuApiMode.name)
        parcel.writeString(videoDecoderMode.name)
        parcel.writeString(mpvProfileMode.name)
        parcel.writeInt(controlsAutoHideMillis)
        parcel.writeString(playerOrientationMode.name)
        parcel.writeInt(if (proxyDebugInfoEnabled) 1 else 0)
        parcel.writeString(videoBackgroundMode.name)
        parcel.writeInt(if (anime4kEnabled) 1 else 0)
        parcel.writeString(anime4kMode.name)
        parcel.writeString(anime4kQuality.name)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<VideoPlayerOptions> =
            object : Parcelable.Creator<VideoPlayerOptions> {
                override fun createFromParcel(parcel: Parcel): VideoPlayerOptions =
                    VideoPlayerOptions(parcel)

                override fun newArray(size: Int): Array<VideoPlayerOptions?> = arrayOfNulls(size)
            }
    }
}

internal fun Intent.putVideoPlayerOptions(options: VideoPlayerOptions): Intent =
    putExtra(VideoPlayerActivity.EXTRA_PLAYER_OPTIONS, options)
        // Keep the scalar extras during the transition so previously shipped readers and
        // restored intents retain the same wire contract.
        .putExtra(VideoPlayerActivity.EXTRA_RESUME_ENABLED, options.resumeEnabled)
        .putExtra(VideoPlayerActivity.EXTRA_VIDEO_OUTPUT_MODE, options.videoOutputMode.name)
        .putExtra(VideoPlayerActivity.EXTRA_GPU_API_MODE, options.gpuApiMode.name)
        .putExtra(VideoPlayerActivity.EXTRA_VIDEO_DECODER_MODE, options.videoDecoderMode.name)
        .putExtra(VideoPlayerActivity.EXTRA_MPV_PROFILE_MODE, options.mpvProfileMode.name)
        .putExtra(VideoPlayerActivity.EXTRA_CONTROLS_AUTO_HIDE_MILLIS, options.controlsAutoHideMillis)
        .putExtra(VideoPlayerActivity.EXTRA_PLAYER_ORIENTATION_MODE, options.playerOrientationMode.name)
        .putExtra(VideoPlayerActivity.EXTRA_PROXY_DEBUG_INFO_ENABLED, options.proxyDebugInfoEnabled)
        .putExtra(VideoPlayerActivity.EXTRA_VIDEO_BACKGROUND_MODE, options.videoBackgroundMode.name)
        .putExtra(VideoPlayerActivity.EXTRA_ANIME4K_ENABLED, options.anime4kEnabled)
        .putExtra(VideoPlayerActivity.EXTRA_ANIME4K_MODE, options.anime4kMode.name)
        .putExtra(VideoPlayerActivity.EXTRA_ANIME4K_QUALITY, options.anime4kQuality.name)

internal fun Intent.videoPlayerOptions(): VideoPlayerOptions =
    parceledVideoPlayerOptions() ?: legacyVideoPlayerOptions()

private fun Intent.parceledVideoPlayerOptions(): VideoPlayerOptions? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(VideoPlayerActivity.EXTRA_PLAYER_OPTIONS, VideoPlayerOptions::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(VideoPlayerActivity.EXTRA_PLAYER_OPTIONS)
        }
    }.getOrNull()

private fun Intent.legacyVideoPlayerOptions(): VideoPlayerOptions {
    val defaults = VideoPlayerOptions()
    return VideoPlayerOptions(
        resumeEnabled = getBooleanExtra(VideoPlayerActivity.EXTRA_RESUME_ENABLED, defaults.resumeEnabled),
        videoOutputMode = getStringExtra(VideoPlayerActivity.EXTRA_VIDEO_OUTPUT_MODE)
            .toEnumOrDefault(defaults.videoOutputMode),
        gpuApiMode = getStringExtra(VideoPlayerActivity.EXTRA_GPU_API_MODE)
            .toEnumOrDefault(defaults.gpuApiMode),
        videoDecoderMode = getStringExtra(VideoPlayerActivity.EXTRA_VIDEO_DECODER_MODE)
            .toEnumOrDefault(defaults.videoDecoderMode),
        mpvProfileMode = getStringExtra(VideoPlayerActivity.EXTRA_MPV_PROFILE_MODE)
            .toEnumOrDefault(defaults.mpvProfileMode),
        controlsAutoHideMillis = getIntExtra(
            VideoPlayerActivity.EXTRA_CONTROLS_AUTO_HIDE_MILLIS,
            defaults.controlsAutoHideMillis,
        ),
        playerOrientationMode = getStringExtra(VideoPlayerActivity.EXTRA_PLAYER_ORIENTATION_MODE)
            .toEnumOrDefault(defaults.playerOrientationMode),
        proxyDebugInfoEnabled = getBooleanExtra(
            VideoPlayerActivity.EXTRA_PROXY_DEBUG_INFO_ENABLED,
            defaults.proxyDebugInfoEnabled,
        ),
        videoBackgroundMode = getStringExtra(VideoPlayerActivity.EXTRA_VIDEO_BACKGROUND_MODE)
            .toEnumOrDefault(defaults.videoBackgroundMode),
        anime4kEnabled = getBooleanExtra(
            VideoPlayerActivity.EXTRA_ANIME4K_ENABLED,
            defaults.anime4kEnabled,
        ),
        anime4kMode = getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_MODE)
            .toEnumOrDefault(defaults.anime4kMode),
        anime4kQuality = getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_QUALITY)
            .toEnumOrDefault(defaults.anime4kQuality),
    )
}

private inline fun <reified T : Enum<T>> Parcel.readEnumOrDefault(default: T): T =
    readString().toEnumOrDefault(default)

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
    this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default
