package org.mubox.reader.video.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import org.mubox.reader.video.R

internal data class VideoPlaybackNotificationState(
    val displayName: String,
    val isPaused: Boolean,
    val episodeNumber: Int? = null,
    val episodeCount: Int? = null,
    val hasPreviousEpisode: Boolean = false,
    val hasNextEpisode: Boolean = false,
    val statusText: String? = null,
) {
    fun detailText(): String = buildList {
        add(if (isPaused) "已暂停" else "正在播放")
        if (episodeNumber != null && episodeCount != null && episodeCount > 0) {
            add("第 ${episodeNumber.coerceIn(1, episodeCount)} / $episodeCount 集")
        }
        statusText?.takeIf(String::isNotBlank)?.let(::add)
    }.joinToString(" · ")
}

internal enum class VideoPlaybackNotificationControl(val wireValue: String) {
    TOGGLE_PLAY_PAUSE("toggle_play_pause"),
    PREVIOUS_EPISODE("previous_episode"),
    NEXT_EPISODE("next_episode"),
    ;

    companion object {
        fun fromWireValue(value: String?): VideoPlaybackNotificationControl? =
            entries.firstOrNull { it.wireValue == value }
    }
}

class VideoPlaybackService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                sendBroadcast(
                    playbackStoppedIntent(intent.getStringExtra(EXTRA_PLAYBACK_SESSION_ID))
                        .setPackage(packageName),
                )
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PLAYBACK_CONTROL -> {
                sendBroadcast(
                    Intent(intent).setComponent(null).setPackage(packageName),
                )
                return START_NOT_STICKY
            }
        }

        val state = intent?.notificationState()
            ?: VideoPlaybackNotificationState(displayName = "视频", isPaused = false)
        val playbackSessionId = intent?.getStringExtra(EXTRA_PLAYBACK_SESSION_ID)
        startForeground(NOTIFICATION_ID, buildNotification(this, state, playbackSessionId))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "视频后台播放",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "视频后台播放状态与控制"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_PLAYBACK_STOPPED = "org.mubox.reader.video.action.PLAYBACK_STOPPED"
        internal const val ACTION_PLAYBACK_CONTROL = "org.mubox.reader.video.action.PLAYBACK_CONTROL"
        private const val CHANNEL_ID = "video_background_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "org.mubox.reader.video.action.STOP"
        private const val EXTRA_DISPLAY_NAME = "org.mubox.reader.video.extra.DISPLAY_NAME"
        private const val EXTRA_PLAYBACK_SESSION_ID = "org.mubox.reader.video.extra.PLAYBACK_SESSION_ID"
        private const val EXTRA_IS_PAUSED = "org.mubox.reader.video.extra.IS_PAUSED"
        private const val EXTRA_EPISODE_NUMBER = "org.mubox.reader.video.extra.EPISODE_NUMBER"
        private const val EXTRA_EPISODE_COUNT = "org.mubox.reader.video.extra.EPISODE_COUNT"
        private const val EXTRA_HAS_PREVIOUS_EPISODE = "org.mubox.reader.video.extra.HAS_PREVIOUS_EPISODE"
        private const val EXTRA_HAS_NEXT_EPISODE = "org.mubox.reader.video.extra.HAS_NEXT_EPISODE"
        private const val EXTRA_STATUS_TEXT = "org.mubox.reader.video.extra.STATUS_TEXT"
        private const val EXTRA_PLAYBACK_CONTROL = "org.mubox.reader.video.extra.PLAYBACK_CONTROL"

        internal fun start(
            context: Context,
            state: VideoPlaybackNotificationState,
            playbackSessionId: String,
        ) {
            val intent = Intent(context, VideoPlaybackService::class.java)
                .putNotificationState(state)
                .putExtra(EXTRA_PLAYBACK_SESSION_ID, playbackSessionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VideoPlaybackService::class.java))
        }

        internal fun update(
            context: Context,
            state: VideoPlaybackNotificationState,
            playbackSessionId: String,
        ) {
            val notification = buildNotification(context, state, playbackSessionId)
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }

        internal fun buildNotification(
            context: Context,
            state: VideoPlaybackNotificationState,
            playbackSessionId: String?,
        ): Notification {
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val actions = buildList {
                if (state.hasPreviousEpisode) {
                    add(
                        notificationAction(
                            context = context,
                            iconRes = R.drawable.ic_skip_previous,
                            title = "上一集",
                            requestCode = 1,
                            playbackSessionId = playbackSessionId,
                            control = VideoPlaybackNotificationControl.PREVIOUS_EPISODE,
                        ),
                    )
                }
                add(
                    notificationAction(
                        context = context,
                        iconRes = if (state.isPaused) R.drawable.ic_play else R.drawable.ic_pause,
                        title = if (state.isPaused) "播放" else "暂停",
                        requestCode = 2,
                        playbackSessionId = playbackSessionId,
                        control = VideoPlaybackNotificationControl.TOGGLE_PLAY_PAUSE,
                    ),
                )
                if (state.hasNextEpisode) {
                    add(
                        notificationAction(
                            context = context,
                            iconRes = R.drawable.ic_skip_next,
                            title = "下一集",
                            requestCode = 3,
                            playbackSessionId = playbackSessionId,
                            control = VideoPlaybackNotificationControl.NEXT_EPISODE,
                        ),
                    )
                }
            }
            val stopIntent = PendingIntent.getService(
                context,
                4,
                Intent(context, VideoPlaybackService::class.java)
                    .setAction(ACTION_STOP)
                    .apply {
                        playbackSessionId?.let { putExtra(EXTRA_PLAYBACK_SESSION_ID, it) }
                    },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stopAction = Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_stop),
                "停止",
                stopIntent,
            ).build()

            val style = Notification.MediaStyle()
                .setShowActionsInCompactView(*actions.indices.toList().toIntArray())

            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(state.displayName)
                .setContentText(state.detailText())
                .setSubText("MuBOX 视频播放")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setStyle(style)
                .apply {
                    actions.forEach(::addAction)
                    addAction(stopAction)
                }
                .build()
        }

        private fun notificationAction(
            context: Context,
            iconRes: Int,
            title: String,
            requestCode: Int,
            playbackSessionId: String?,
            control: VideoPlaybackNotificationControl,
        ): Notification.Action {
            val pendingIntent = PendingIntent.getService(
                context,
                requestCode,
                playbackControlIntent(playbackSessionId, control)
                    .setClass(context, VideoPlaybackService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Action.Builder(
                Icon.createWithResource(context, iconRes),
                title,
                pendingIntent,
            ).build()
        }

        internal fun playbackStoppedIntent(playbackSessionId: String?): Intent =
            Intent(ACTION_PLAYBACK_STOPPED).apply {
                playbackSessionId?.let { putExtra(EXTRA_PLAYBACK_SESSION_ID, it) }
            }

        internal fun isPlaybackStoppedForSession(intent: Intent, playbackSessionId: String): Boolean =
            intent.action == ACTION_PLAYBACK_STOPPED &&
                intent.getStringExtra(EXTRA_PLAYBACK_SESSION_ID) == playbackSessionId

        internal fun playbackControlIntent(
            playbackSessionId: String?,
            control: VideoPlaybackNotificationControl,
        ): Intent = Intent(ACTION_PLAYBACK_CONTROL).apply {
            playbackSessionId?.let { putExtra(EXTRA_PLAYBACK_SESSION_ID, it) }
            putExtra(EXTRA_PLAYBACK_CONTROL, control.wireValue)
        }

        internal fun playbackControlForSession(
            intent: Intent,
            playbackSessionId: String,
        ): VideoPlaybackNotificationControl? {
            if (intent.action != ACTION_PLAYBACK_CONTROL) return null
            if (intent.getStringExtra(EXTRA_PLAYBACK_SESSION_ID) != playbackSessionId) return null
            return VideoPlaybackNotificationControl.fromWireValue(
                intent.getStringExtra(EXTRA_PLAYBACK_CONTROL),
            )
        }

        private fun Intent.putNotificationState(state: VideoPlaybackNotificationState): Intent =
            putExtra(EXTRA_DISPLAY_NAME, state.displayName)
                .putExtra(EXTRA_IS_PAUSED, state.isPaused)
                .putExtra(EXTRA_EPISODE_NUMBER, state.episodeNumber ?: -1)
                .putExtra(EXTRA_EPISODE_COUNT, state.episodeCount ?: -1)
                .putExtra(EXTRA_HAS_PREVIOUS_EPISODE, state.hasPreviousEpisode)
                .putExtra(EXTRA_HAS_NEXT_EPISODE, state.hasNextEpisode)
                .apply {
                    state.statusText?.let { putExtra(EXTRA_STATUS_TEXT, it) }
                }

        private fun Intent.notificationState(): VideoPlaybackNotificationState =
            VideoPlaybackNotificationState(
                displayName = getStringExtra(EXTRA_DISPLAY_NAME) ?: "视频",
                isPaused = getBooleanExtra(EXTRA_IS_PAUSED, false),
                episodeNumber = getIntExtra(EXTRA_EPISODE_NUMBER, -1).takeIf { it > 0 },
                episodeCount = getIntExtra(EXTRA_EPISODE_COUNT, -1).takeIf { it > 0 },
                hasPreviousEpisode = getBooleanExtra(EXTRA_HAS_PREVIOUS_EPISODE, false),
                hasNextEpisode = getBooleanExtra(EXTRA_HAS_NEXT_EPISODE, false),
                statusText = getStringExtra(EXTRA_STATUS_TEXT),
            )
    }
}
