package org.mubox.reader.video.player

import android.content.Intent
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import org.mubox.reader.core.model.settings.Anime4KProfile
import org.mubox.reader.core.model.settings.AppColorPalette
import org.mubox.reader.core.model.settings.GpuApiMode
import org.mubox.reader.core.model.settings.MpvProfileMode
import org.mubox.reader.core.model.settings.VideoBackgroundMode
import org.mubox.reader.core.model.settings.VideoDecoderMode
import org.mubox.reader.core.model.settings.VideoOutputMode
import org.mubox.reader.core.model.settings.VideoPlayerOrientationMode
import org.mubox.reader.core.model.settings.anime4KProfileFromLegacy

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
    val anime4kProfile: Anime4KProfile = Anime4KProfile.OFF,
    val colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
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
        anime4kProfile = parcel.readEnumOrDefault(Anime4KProfile.OFF),
        colorPalette = parcel.readEnumOrDefault(AppColorPalette.DEFAULT),
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
        parcel.writeString(anime4kProfile.name)
        parcel.writeString(colorPalette.name)
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
    putExtra(VideoPlayerLaunchContract.EXTRA_PLAYER_OPTIONS, options)
        // Keep the scalar extras during the transition so previously shipped readers and
        // restored intents retain the same wire contract.
        .putExtra(VideoPlayerLaunchContract.EXTRA_RESUME_ENABLED, options.resumeEnabled)
        .putExtra(VideoPlayerLaunchContract.EXTRA_VIDEO_OUTPUT_MODE, options.videoOutputMode.name)
        .putExtra(VideoPlayerLaunchContract.EXTRA_GPU_API_MODE, options.gpuApiMode.name)
        .putExtra(VideoPlayerLaunchContract.EXTRA_VIDEO_DECODER_MODE, options.videoDecoderMode.name)
        .putExtra(VideoPlayerLaunchContract.EXTRA_MPV_PROFILE_MODE, options.mpvProfileMode.name)
        .putExtra(VideoPlayerLaunchContract.EXTRA_CONTROLS_AUTO_HIDE_MILLIS, options.controlsAutoHideMillis)
        .putExtra(VideoPlayerLaunchContract.EXTRA_PLAYER_ORIENTATION_MODE, options.playerOrientationMode.name)
        .putExtra(VideoPlayerLaunchContract.EXTRA_PROXY_DEBUG_INFO_ENABLED, options.proxyDebugInfoEnabled)
        .putExtra(VideoPlayerLaunchContract.EXTRA_VIDEO_BACKGROUND_MODE, options.videoBackgroundMode.name)
        .putExtra(VideoPlayerLaunchContract.EXTRA_ANIME4K_PROFILE, options.anime4kProfile.name)
        .putExtra(VideoPlayerLaunchContract.EXTRA_COLOR_PALETTE, options.colorPalette.name)

internal fun Intent.videoPlayerOptions(): VideoPlayerOptions =
    parceledVideoPlayerOptions() ?: legacyVideoPlayerOptions()

private fun Intent.parceledVideoPlayerOptions(): VideoPlayerOptions? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(VideoPlayerLaunchContract.EXTRA_PLAYER_OPTIONS, VideoPlayerOptions::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(VideoPlayerLaunchContract.EXTRA_PLAYER_OPTIONS)
        }
    }.getOrNull()

private fun Intent.legacyVideoPlayerOptions(): VideoPlayerOptions {
    val defaults = VideoPlayerOptions()
    return VideoPlayerOptions(
        resumeEnabled = getBooleanExtra(VideoPlayerLaunchContract.EXTRA_RESUME_ENABLED, defaults.resumeEnabled),
        videoOutputMode = getStringExtra(VideoPlayerLaunchContract.EXTRA_VIDEO_OUTPUT_MODE)
            .toEnumOrDefault(defaults.videoOutputMode),
        gpuApiMode = getStringExtra(VideoPlayerLaunchContract.EXTRA_GPU_API_MODE)
            .toEnumOrDefault(defaults.gpuApiMode),
        videoDecoderMode = getStringExtra(VideoPlayerLaunchContract.EXTRA_VIDEO_DECODER_MODE)
            .toEnumOrDefault(defaults.videoDecoderMode),
        mpvProfileMode = getStringExtra(VideoPlayerLaunchContract.EXTRA_MPV_PROFILE_MODE)
            .toEnumOrDefault(defaults.mpvProfileMode),
        controlsAutoHideMillis = getIntExtra(
            VideoPlayerLaunchContract.EXTRA_CONTROLS_AUTO_HIDE_MILLIS,
            defaults.controlsAutoHideMillis,
        ),
        playerOrientationMode = getStringExtra(VideoPlayerLaunchContract.EXTRA_PLAYER_ORIENTATION_MODE)
            .toEnumOrDefault(defaults.playerOrientationMode),
        proxyDebugInfoEnabled = getBooleanExtra(
            VideoPlayerLaunchContract.EXTRA_PROXY_DEBUG_INFO_ENABLED,
            defaults.proxyDebugInfoEnabled,
        ),
        videoBackgroundMode = getStringExtra(VideoPlayerLaunchContract.EXTRA_VIDEO_BACKGROUND_MODE)
            .toEnumOrDefault(defaults.videoBackgroundMode),
        anime4kProfile = getStringExtra(VideoPlayerLaunchContract.EXTRA_ANIME4K_PROFILE)
            .toEnumOrNull<Anime4KProfile>()
            ?: anime4KProfileFromLegacy(
                enabled = getBooleanExtra(VideoPlayerLaunchContract.EXTRA_ANIME4K_ENABLED, false),
                mode = getStringExtra(VideoPlayerLaunchContract.EXTRA_ANIME4K_MODE),
                quality = getStringExtra(VideoPlayerLaunchContract.EXTRA_ANIME4K_QUALITY),
            ),
        colorPalette = getStringExtra(VideoPlayerLaunchContract.EXTRA_COLOR_PALETTE)
            .toEnumOrDefault(defaults.colorPalette),
    )
}

private inline fun <reified T : Enum<T>> Parcel.readEnumOrDefault(default: T): T =
    readString().toEnumOrDefault(default)

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
    this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default

private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? =
    this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() }
