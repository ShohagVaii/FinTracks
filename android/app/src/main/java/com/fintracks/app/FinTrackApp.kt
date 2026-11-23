package com.fintracks.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/** App-wide setup: notification channels for push (created before any push can arrive). */
class FinTrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PUSH, "FinTrack বার্তা", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "FinTrack জরুরি", NotificationManager.IMPORTANCE_HIGH))
    }

    companion object {
        const val CHANNEL_PUSH = "fintrack_push"
        const val CHANNEL_ALERTS = "fintrack_alerts"
    }
}
