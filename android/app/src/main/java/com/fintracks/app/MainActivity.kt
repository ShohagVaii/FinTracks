package com.fintracks.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.fintracks.app.ui.AppLock
import com.fintracks.app.ui.DailyReminder
import com.fintracks.app.ui.DailySyncWorker
import com.fintracks.app.data.PushRouter
import com.fintracks.app.ui.FinTracksApp

/** FragmentActivity is required by BiometricPrompt. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.fintracks.app.ui.Immersive.load(this)
        if (savedInstanceState == null && AppLock.isEnabled(this)) AppLock.lock()
        DailySyncWorker.schedule(this)
        DailyReminder.ensureScheduled(this)
        setContent { FinTracksApp() }
        PushRouter.put(intent?.getStringExtra(PushRouter.EXTRA_TARGET))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        PushRouter.put(intent.getStringExtra(PushRouter.EXTRA_TARGET))
    }

    override fun onStop() {
        super.onStop()
        AppLock.markBackground()
    }

    override fun onStart() {
        super.onStart()
        AppLock.checkOnForeground(this)
    }
}
