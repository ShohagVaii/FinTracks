package com.fintracks.app.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow

/** App lock: biometric prompt, locked after the chosen idle time (0 = immediately). */
object AppLock {
    private const val PREFS = "fintrack_lock"
    val locked = MutableStateFlow(false)
    private var stoppedAt = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)
    fun timeoutSeconds(ctx: Context): Int = prefs(ctx).getInt("timeout", 0)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        if (!on) locked.value = false
    }

    fun setTimeout(ctx: Context, seconds: Int) {
        prefs(ctx).edit().putInt("timeout", seconds).apply()
    }

    fun lock() { locked.value = true }
    fun unlock() { locked.value = false }

    fun markBackground() {
        stoppedAt = System.currentTimeMillis()
    }

    fun checkOnForeground(ctx: Context) {
        if (!isEnabled(ctx)) return
        val elapsedSec = (System.currentTimeMillis() - stoppedAt) / 1000
        if (stoppedAt == 0L || elapsedSec >= timeoutSeconds(ctx)) locked.value = true
    }
}

/** Shown over the app while locked. Uses the device fingerprint/face unlock. */
@Composable
fun LockScreen(onSignOut: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    var message by remember { mutableStateOf<String?>(null) }

    fun prompt() {
        val can = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            message = "ফোনে ফিঙ্গারপ্রিন্ট বা ফেস আনলক সেট করুন, অথবা লগআউট করুন"
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                message = null
                AppLock.unlock()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                message = errString.toString()
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("FinTrack আনলক")
            .setSubtitle("এগিয়ে যেতে আপনার ফিঙ্গারপ্রিন্ট বা ফেস দিন")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("বাতিল")
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }

    LaunchedEffect(Unit) { prompt() }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FinTrack লক করা আছে", fontWeight = FontWeight.Bold)
        Text("আপনার ডেটা নিরাপদ রাখতে আনলক করুন", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { prompt() }) { Text("আনলক করুন") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onSignOut) { Text("লগআউট") }
    }
}
