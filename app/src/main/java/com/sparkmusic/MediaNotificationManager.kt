package com.sparkmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver

class MediaNotificationManager(
    private val service: MusicPlaybackService,
    private val mediaSession: MediaSessionCompat
) {
    private val notificationManager =
        service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var isForeground = false

    companion object {
        private const val TAG = "MediaNotification"
        private const val CHANNEL_ID = "music_playback_channel"
        private const val NOTIFICATION_ID = 1
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showNotification() {
        val controller = mediaSession.controller
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val notification = createNotification(metadata, playbackState)
        service.startForeground(NOTIFICATION_ID, notification)
        isForeground = true
    }

    fun hideNotification() {
        service.stopForeground(true)
        isForeground = false
    }

    /**
     * Makes sure the service is in the foreground, which is required after the service was started
     * with startForegroundService(), for example by [MediaButtonReceiver].
     */
    fun ensureForeground() {
        if (!isForeground) showNotification()
    }

    /** Refreshes a notification that is already shown, for example when artwork arrives. */
    fun refreshIfForeground() {
        if (isForeground) showNotification()
    }

    private fun createNotification(
        metadata: MediaMetadataCompat?,
        playbackState: PlaybackStateCompat?
    ): Notification {
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Unknown"
        val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "Unknown Artist"
        val artwork = metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)

        val isPlaying = playbackState?.state == PlaybackStateCompat.STATE_PLAYING

        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (artwork != null) {
            builder.setLargeIcon(artwork)
        }

        contentIntent()?.let { builder.setContentIntent(it) }

        addAction(
            builder,
            android.R.drawable.ic_media_previous,
            "Previous",
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        )
        addAction(
            builder,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pause" else "Play",
            if (isPlaying) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY
        )
        addAction(
            builder,
            android.R.drawable.ic_media_next,
            "Next",
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        )

        return builder.build()
    }

    private fun contentIntent(): PendingIntent? {
        val launchIntent = service.packageManager.getLaunchIntentForPackage(service.packageName)
            ?: return null

        return PendingIntent.getActivity(
            service,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Adds a transport control. [MediaButtonReceiver.buildMediaButtonPendingIntent] returns null
     * when the app does not declare a media button receiver, so the action is skipped instead of
     * adding a control that cannot dispatch anything.
     */
    private fun addAction(
        builder: NotificationCompat.Builder,
        icon: Int,
        title: String,
        action: Long
    ) {
        val actionIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(service, action)
        if (actionIntent == null) {
            Log.w(TAG, "No media button receiver declared, skipping action $action")
            return
        }
        builder.addAction(NotificationCompat.Action(icon, title, actionIntent))
    }
}