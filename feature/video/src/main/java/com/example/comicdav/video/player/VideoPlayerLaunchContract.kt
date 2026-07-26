package com.example.comicdav.video.player

import android.content.Context
import android.content.Intent
import com.example.comicdav.core.model.media.LocalVideoOpenRequest
import com.example.comicdav.core.model.media.VideoSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest

/** Owns the stable Intent wire contract used to launch [VideoPlayerActivity]. */
object VideoPlayerLaunchContract {
    const val EXTRA_SOURCE = "com.example.comicdav.video.extra.SOURCE"
    const val EXTRA_URI = "com.example.comicdav.video.extra.URI"
    const val EXTRA_DISPLAY_NAME = "com.example.comicdav.video.extra.DISPLAY_NAME"
    const val EXTRA_SIZE = "com.example.comicdav.video.extra.SIZE"
    const val EXTRA_LAST_MODIFIED = "com.example.comicdav.video.extra.LAST_MODIFIED"
    const val EXTRA_ACCOUNT_ID = "com.example.comicdav.video.extra.ACCOUNT_ID"
    const val EXTRA_ETAG = "com.example.comicdav.video.extra.ETAG"
    const val EXTRA_SUBTITLE_URIS = "com.example.comicdav.video.extra.SUBTITLE_URIS"
    const val EXTRA_SUBTITLE_NAMES = "com.example.comicdav.video.extra.SUBTITLE_NAMES"
    const val EXTRA_WEB_DAV_STREAM_IDS = "com.example.comicdav.video.extra.WEB_DAV_STREAM_IDS"
    const val EXTRA_PLAYBACK_KEY = "com.example.comicdav.video.extra.PLAYBACK_KEY"
    const val EXTRA_RESUME_ENABLED = "com.example.comicdav.video.extra.RESUME_ENABLED"
    const val EXTRA_REMOTE_PATH = "com.example.comicdav.video.extra.REMOTE_PATH"
    const val EXTRA_VIDEO_OUTPUT_MODE = "com.example.comicdav.video.extra.VIDEO_OUTPUT_MODE"
    const val EXTRA_GPU_API_MODE = "com.example.comicdav.video.extra.GPU_API_MODE"
    const val EXTRA_VIDEO_DECODER_MODE = "com.example.comicdav.video.extra.VIDEO_DECODER_MODE"
    const val EXTRA_MPV_PROFILE_MODE = "com.example.comicdav.video.extra.MPV_PROFILE_MODE"
    const val EXTRA_CONTROLS_AUTO_HIDE_MILLIS = "com.example.comicdav.video.extra.CONTROLS_AUTO_HIDE_MILLIS"
    const val EXTRA_PLAYER_ORIENTATION_MODE = "com.example.comicdav.video.extra.PLAYER_ORIENTATION_MODE"
    const val EXTRA_PROXY_DEBUG_INFO_ENABLED = "com.example.comicdav.video.extra.PROXY_DEBUG_INFO_ENABLED"
    const val EXTRA_VIDEO_BACKGROUND_MODE = "com.example.comicdav.video.extra.VIDEO_BACKGROUND_MODE"
    const val EXTRA_ANIME4K_PROFILE = "com.example.comicdav.video.extra.ANIME4K_PROFILE"
    // Read-only legacy extras kept for restoring intents created by older versions.
    const val EXTRA_ANIME4K_ENABLED = "com.example.comicdav.video.extra.ANIME4K_ENABLED"
    const val EXTRA_ANIME4K_MODE = "com.example.comicdav.video.extra.ANIME4K_MODE"
    const val EXTRA_ANIME4K_QUALITY = "com.example.comicdav.video.extra.ANIME4K_QUALITY"
    const val EXTRA_PLAYER_OPTIONS = "com.example.comicdav.video.extra.PLAYER_OPTIONS"
    const val EXTRA_EPISODE_QUEUE = "com.example.comicdav.video.extra.EPISODE_QUEUE_ID"
    // Kept as a source-compatible alias for callers compiled against the former registry contract.
    const val EXTRA_EPISODE_QUEUE_ID = EXTRA_EPISODE_QUEUE
    const val SOURCE_LOCAL = "local"
    const val SOURCE_WEB_DAV = "webdav"

    fun localIntent(
        context: Context,
        request: LocalVideoOpenRequest,
        options: VideoPlayerOptions = VideoPlayerOptions(),
        episodeQueue: VideoEpisodeQueue? = null,
    ): Intent =
        Intent(context, VideoPlayerActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(EXTRA_SOURCE, SOURCE_LOCAL)
            .putExtra(EXTRA_URI, request.uri)
            .putExtra(EXTRA_DISPLAY_NAME, request.displayName)
            .putExtra(EXTRA_REMOTE_PATH, request.uri)
            .putExtra(EXTRA_SIZE, request.size ?: -1L)
            .putExtra(EXTRA_LAST_MODIFIED, request.lastModified ?: -1L)
            .putExtra(
                EXTRA_PLAYBACK_KEY,
                localVideoPlaybackKey(
                    uri = request.uri,
                    size = request.size,
                    lastModified = request.lastModified,
                ),
            )
            .putVideoPlayerOptions(options)
            .putSubtitleExtras(request.subtitles)
            .putEpisodeQueueExtra(context, episodeQueue)

    fun webDavIntent(
        context: Context,
        request: WebDavVideoOpenRequest,
        uri: String,
        subtitleUrls: List<String>,
        streamIds: List<String>,
        options: VideoPlayerOptions = VideoPlayerOptions(),
        episodeQueue: VideoEpisodeQueue? = null,
    ): Intent {
        val subtitles = request.subtitles.zip(subtitleUrls).map { (subtitle, subtitleUrl) ->
            VideoSubtitleOpenRequest(
                uri = subtitleUrl,
                displayName = subtitle.displayName,
            )
        }
        return Intent(context, VideoPlayerActivity::class.java)
            .putExtra(EXTRA_SOURCE, SOURCE_WEB_DAV)
            .putExtra(EXTRA_URI, uri)
            .putExtra(EXTRA_REMOTE_PATH, request.remotePath)
            .putExtra(EXTRA_DISPLAY_NAME, request.displayName)
            .putExtra(EXTRA_SIZE, request.size ?: -1L)
            .putExtra(EXTRA_LAST_MODIFIED, request.lastModified ?: -1L)
            .putExtra(EXTRA_ACCOUNT_ID, request.accountId)
            .putExtra(EXTRA_ETAG, request.etag)
            .putExtra(
                EXTRA_PLAYBACK_KEY,
                webDavVideoPlaybackKey(
                    accountId = request.accountId,
                    remotePath = request.remotePath,
                    size = request.size,
                    etag = request.etag,
                    lastModified = request.lastModified,
                ),
            )
            .putVideoPlayerOptions(options)
            .putStringArrayListExtra(EXTRA_WEB_DAV_STREAM_IDS, ArrayList(streamIds))
            .putSubtitleExtras(subtitles)
            .putEpisodeQueueExtra(
                context,
                episodeQueue ?: VideoEpisodeQueue(listOf(VideoEpisode.webDav(request))),
            )
    }

    internal fun read(context: Context, intent: Intent): VideoPlayerLaunchArguments =
        VideoPlayerLaunchArguments(
            uri = intent.getStringExtra(EXTRA_URI),
            displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
                ?: intent.data?.lastPathSegment
                ?: "视频",
            source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_LOCAL,
            remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH),
            subtitles = intent.subtitleRequests(),
            playbackKey = intent.getStringExtra(EXTRA_PLAYBACK_KEY),
            size = intent.getLongExtra(EXTRA_SIZE, -1L).takeIf { it >= 0L },
            lastModified = intent.getLongExtra(EXTRA_LAST_MODIFIED, -1L).takeIf { it >= 0L },
            accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID),
            etag = intent.getStringExtra(EXTRA_ETAG),
            options = intent.videoPlayerOptions(),
            episodeQueue = VideoEpisodeQueueStore.read(
                context.applicationContext.noBackupFilesDir,
                intent.getStringExtra(EXTRA_EPISODE_QUEUE),
            ),
            webDavStreamIds = intent.getStringArrayListExtra(EXTRA_WEB_DAV_STREAM_IDS).orEmpty(),
        )

    private fun Intent.subtitleRequests(): List<VideoSubtitleOpenRequest> {
        val uris = getStringArrayListExtra(EXTRA_SUBTITLE_URIS).orEmpty()
        val names = getStringArrayListExtra(EXTRA_SUBTITLE_NAMES).orEmpty()
        return uris.mapIndexed { index, uri ->
            VideoSubtitleOpenRequest(
                uri = uri,
                displayName = names.getOrNull(index) ?: uri.substringAfterLast('/'),
            )
        }
    }

    private fun Intent.putSubtitleExtras(subtitles: List<VideoSubtitleOpenRequest>): Intent =
        putStringArrayListExtra(EXTRA_SUBTITLE_URIS, ArrayList(subtitles.map { it.uri }))
            .putStringArrayListExtra(EXTRA_SUBTITLE_NAMES, ArrayList(subtitles.map { it.displayName }))

    private fun Intent.putEpisodeQueueExtra(
        context: Context,
        episodeQueue: VideoEpisodeQueue?,
    ): Intent =
        apply {
            if (episodeQueue != null) {
                putExtra(
                    EXTRA_EPISODE_QUEUE,
                    VideoEpisodeQueueStore.save(context.applicationContext.noBackupFilesDir, episodeQueue),
                )
            }
        }
}

internal data class VideoPlayerLaunchArguments(
    val uri: String?,
    val displayName: String,
    val source: String,
    val remotePath: String?,
    val subtitles: List<VideoSubtitleOpenRequest>,
    val playbackKey: String?,
    val size: Long?,
    val lastModified: Long?,
    val accountId: String?,
    val etag: String?,
    val options: VideoPlayerOptions,
    val episodeQueue: VideoEpisodeQueue?,
    val webDavStreamIds: List<String>,
) {
    val isWebDav: Boolean
        get() = source == VideoPlayerLaunchContract.SOURCE_WEB_DAV
}
