package io.github.rootscripts.riff

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RiffAudioService : Service() {

    private val binder = LocalBinder()

    companion object {
        const val CHANNEL_ID = "riff_playback_channel"
        const val NOTIFICATION_ID = 4230
        const val ACTION_PLAY = "io.github.rootscripts.riff.ACTION_PLAY"
        const val ACTION_PAUSE = "io.github.rootscripts.riff.ACTION_PAUSE"
        const val ACTION_NEXT = "io.github.rootscripts.riff.ACTION_NEXT"
        const val ACTION_PREV = "io.github.rootscripts.riff.ACTION_PREV"
    }

    inner class LocalBinder : Binder() {
        fun getService(): RiffAudioService = this@RiffAudioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null) {
            MainActivity.instance?.handleMediaAction(action)
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun updateNotification(title: String, artist: String, isPlaying: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            val pauseIntent = Intent(this, RiffAudioService::class.java).apply { action = ACTION_PAUSE }
            val pi = PendingIntent.getService(this, 1, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationCompat.Action(android.R.drawable.ic_media_pause, getString(R.string.pause), pi)
        } else {
            val playIntent = Intent(this, RiffAudioService::class.java).apply { action = ACTION_PLAY }
            val pi = PendingIntent.getService(this, 1, playIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationCompat.Action(android.R.drawable.ic_media_play, getString(R.string.play), pi)
        }

        val prevIntent = Intent(this, RiffAudioService::class.java).apply { action = ACTION_PREV }
        val prevPi = PendingIntent.getService(this, 2, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val prevAction = NotificationCompat.Action(android.R.drawable.ic_media_previous, getString(R.string.previous), prevPi)

        val nextIntent = Intent(this, RiffAudioService::class.java).apply { action = ACTION_NEXT }
        val nextPi = PendingIntent.getService(this, 3, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nextAction = NotificationCompat.Action(android.R.drawable.ic_media_next, getString(R.string.next), nextPi)

        val displayTitle = title.ifEmpty { "Riff Music Player" }
        val displayArtist = artist.ifEmpty { "Playing on Android" }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(displayTitle)
            .setContentText(displayArtist)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(isPlaying)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    fun stopNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
