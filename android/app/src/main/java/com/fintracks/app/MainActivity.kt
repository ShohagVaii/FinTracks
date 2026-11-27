package com.fintracks.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.fintracks.app.ui.AppLock
import com.fintracks.app.ui.DailyReminder
import com.fintracks.app.ui.BirthdayWish
import com.fintracks.app.ui.DailySyncWorker
import com.fintracks.app.data.PushRouter
import com.fintracks.app.ui.FinTracksRoot

/** FragmentActivity is required by BiometricPrompt. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The one splash: no fixed delay, it closes as soon as the first frame is drawn.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.fintracks.app.ui.Immersive.load(this)
        if (savedInstanceState == null && AppLock.isEnabled(this)) AppLock.lock()
        setContent { FinTracksRoot() }
        PushRouter.put(intent?.getStringExtra(PushRouter.EXTRA_TARGET))
        // Background work is queued after the first frame, so it never delays the home screen.
        window.decorView.post {
            DailySyncWorker.schedule(this)
            BirthdayWish.schedule(this)
            DailyReminder.ensureScheduled(this)
        }
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
