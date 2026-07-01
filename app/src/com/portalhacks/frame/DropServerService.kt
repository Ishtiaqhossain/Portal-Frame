package com.portalhacks.frame

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * A foreground service that runs the LAN [LocalDropServer] independently of the slideshow,
 * so phones can push photos onto the frame at *any* time — not only while the slideshow
 * Activity happens to be on screen. On each upload it broadcasts [ACTION_UPLOAD] (scoped to
 * this app) so a running [SlideshowComposeActivity] can show the new photo immediately;
 * when nothing is on screen, the photo is still saved and appears next time the frame wakes.
 */
class DropServerService : Service() {

    private var server: LocalDropServer? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTI_ID, buildNotification())
        val token = DropAuth.token(this)
        server = LocalDropServer(this, token) {
            sendBroadcast(Intent(ACTION_UPLOAD).setPackage(packageName))
        }.also { it.start() }
        Log.i(TAG, "drop server service started")
    }

    // Sticky so the OS restarts us if killed — the frame should always be reachable.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        // minSdk 28, so notification channels always exist.
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "Frame photo drop", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        mgr.createNotificationChannel(ch)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Photo sharing is on")
            .setContentText("Open Frame Settings to show the QR for adding photos from a phone")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PortalFrame"
        private const val CHANNEL = "frame_drop"
        private const val NOTI_ID = 42

        /** App-internal broadcast sent after photo(s) are pushed; carries no data. */
        const val ACTION_UPLOAD = "com.portalhacks.frame.UPLOAD"

        /** Start (or no-op if already running) the always-on drop server. */
        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, DropServerService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start drop server service", e)
            }
        }
    }
}
