package com.watchdog.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService

class CameraStreamService : LifecycleService() {
    private lateinit var engine: CameraStreamEngine

    override fun onCreate() {
        super.onCreate()
        engine = CameraStreamEngine(context = this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cameraId = intent?.getStringExtra(EXTRA_CAMERA_ID).orEmpty()
        startForeground(NOTIFICATION_ID, buildNotification("Camera service starting..."))

        val started = if (cameraId.isNotBlank()) {
            engine.start(
                lifecycleOwner = this,
                cameraDeviceId = cameraId
            )
        } else {
            false
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val text = if (started) {
            "Streaming mode active"
        } else {
            "Waiting for valid config"
        }
        manager.notify(NOTIFICATION_ID, buildNotification(text))

        return START_STICKY
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Watchdog Camera Stream",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Watchdog Camera")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build()
    }

    companion object {
        const val EXTRA_CAMERA_ID = "extra_camera_id"
        private const val CHANNEL_ID = "watchdog_camera_stream_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
