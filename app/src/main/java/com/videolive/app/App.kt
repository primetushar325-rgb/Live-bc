package com.videolive.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.videolive.app.stream.StreamService

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(StreamService.CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    StreamService.CHANNEL_ID,
                    "Live streaming",
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = "Keeps the live stream running in the background"
                channel.setShowBadge(false)
                nm.createNotificationChannel(channel)
            }
        }
    }
}
