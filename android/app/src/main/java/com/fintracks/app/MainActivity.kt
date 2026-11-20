package com.fintracks.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.fintracks.app.ui.AppLock
import com.fintracks.app.ui.FinTracksApp

/** FragmentActivity is required by BiometricPrompt. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && AppLock.isEnabled(this)) AppLock.lock()
        setContent { FinTracksApp() }
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
