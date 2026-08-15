package org.mubox.reader.video.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.mubox.reader.video.R

class VideoPlaybackService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            sendBroadcast(
                playbackStoppedIntent(intent.getStringExtra(EXTRA_PLAYBACK_SESSION_ID))
                    .setPackage(packageName),
            )
            stopSelf()
            return START_NOT_STICKY
        }
        val displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME) ?: "音频播放"
        val playbackSessionId = intent?.getStringExtra(EXTRA_PLAYBACK_SESSION_ID)
        startForeground(NOTIFICATION_ID, buildNotification(this, displayName, playbackSessionId, null))
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
                "视频音频播放",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "后台音频播放通知"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_PLAYBACK_STOPPED = "org.mubox.reader.video.action.PLAYBACK_STOPPED"
        private const val CHANNEL_ID = "video_background_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "stop"
        private const val EXTRA_DISPLAY_NAME = "org.mubox.reader.video.extra.DISPLAY_NAME"
        private const val EXTRA_PLAYBACK_SESSION_ID = "org.mubox.reader.video.extra.PLAYBACK_SESSION_ID"

        fun start(context: Context, displayName: String, playbackSessionId: String) {
            val intent = Intent(context, VideoPlaybackService::class.java)
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
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

        /** 更新正在播放的前台通知，[statusText] 用于展示定时关闭剩余时间等附注。 */
        fun update(context: Context, displayName: String, playbackSessionId: String, statusText: String?) {
            val notification = buildNotification(context, displayName, playbackSessionId, statusText)
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }

        private fun buildNotification(
            context: Context,
            displayName: String,
            playbackSessionId: String?,
            statusText: String?,
        ): Notification {
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stopIntent = PendingIntent.getService(
                context,
                1,
                Intent(context, VideoPlaybackService::class.java)
                    .setAction(ACTION_STOP)
                    .apply {
                        playbackSessionId?.let { putExtra(EXTRA_PLAYBACK_SESSION_ID, it) }
                    },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val contentText = if (statusText.isNullOrBlank()) {
                displayName
            } else {
                "$displayName · $statusText"
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("MuBOX 音频播放")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .addAction(NotificationCompat.Action.Builder(0, "停止", stopIntent).build())
                .setOngoing(true)
                .build()
        }

        internal fun playbackStoppedIntent(playbackSessionId: String?): Intent =
            Intent(ACTION_PLAYBACK_STOPPED).apply {
                playbackSessionId?.let { putExtra(EXTRA_PLAYBACK_SESSION_ID, it) }
            }

        internal fun isPlaybackStoppedForSession(intent: Intent, playbackSessionId: String): Boolean =
            intent.action == ACTION_PLAYBACK_STOPPED &&
                intent.getStringExtra(EXTRA_PLAYBACK_SESSION_ID) == playbackSessionId
    }
}
