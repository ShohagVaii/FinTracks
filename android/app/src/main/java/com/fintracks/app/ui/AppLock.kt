package com.fintracks.app.ui

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App lock: 4–6 digit PIN (stored as salted SHA-256, never plain) plus fingerprint / face unlock
 * and, on Android 11+, the phone's own PIN/pattern as a fallback. Locks after the chosen idle time.
 */
object AppLock {
    private const val PREFS = "fintrack_lock"
    val locked = MutableStateFlow(false)
    private var stoppedAt = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)
    fun hasPin(ctx: Context): Boolean = prefs(ctx).getString("pin_hash", null) != null
    fun timeoutSeconds(ctx: Context): Int = prefs(ctx).getInt("timeout", 0)

    fun setTimeout(ctx: Context, seconds: Int) {
        prefs(ctx).edit().putInt("timeout", seconds).apply()
    }

    /** Enabling needs a PIN to exist. Disabling is done by the caller after checking the PIN. */
    fun enable(ctx: Context) = prefs(ctx).edit().putBoolean("enabled", true).apply()
    fun disable(ctx: Context) {
        prefs(ctx).edit().putBoolean("enabled", false).apply()
        locked.value = false
    }

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs(ctx).edit().putString("pin_salt", saltB64).putString("pin_hash", hash(saltB64, pin)).apply()
    }

    fun checkPin(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        val salt = p.getString("pin_salt", null) ?: return false
        return p.getString("pin_hash", null) == hash(salt, pin)
    }

    private fun hash(saltB64: String, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((saltB64 + ":" + pin).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
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

    /** Fingerprint / face (and device credential on Android 11+). */
    fun biometricAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK
}

/** Lock screen: PIN pad always available; biometric button when the phone has it set up. */
@Composable
fun LockScreen(onSignOut: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val auth = AppLock.biometricAuthenticators()
    val bioAvailable = BiometricManager.from(ctx).canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt() {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                error = null
                AppLock.unlock()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelling is not an error to show; the PIN pad is still there.
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    error = errString.toString()
                }
            }
        }
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("FinTrack আনলক")
            .setSubtitle("ফিঙ্গারপ্রিন্ট বা ফেস দিয়ে আনলক করুন")
            .setAllowedAuthenticators(auth)
        // A negative button is not allowed when device credential is enabled.
        if (auth and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) builder.setNegativeButtonText("PIN ব্যবহার করুন")
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(builder.build())
    }

    fun press(d: String) {
        if (pin.length >= 6) return
        pin += d
        if (pin.length >= 4 && AppLock.checkPin(ctx, pin)) {
            AppLock.unlock()
            pin = ""
        } else if (pin.length == 6) {
            error = "ভুল PIN"
            pin = ""
        }
    }

    LaunchedEffect(Unit) { if (bioAvailable) prompt() }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FinTrack লক করা আছে", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("PIN দিন", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
            repeat(6) { i ->
                Box(
                    Modifier.size(14.dp).background(
                        if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    )
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("bio", "0", "del"))
        keys.forEach { rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                rowKeys.forEach { k ->
                    val label = when (k) { "del" -> "⌫"; "bio" -> if (bioAvailable) "👆" else ""; else -> k }
                    Box(
                        Modifier
                            .size(68.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .clickable(enabled = label.isNotEmpty()) {
                                when (k) {
                                    "del" -> pin = pin.dropLast(1)
                                    "bio" -> prompt()
                                    else -> press(k)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Spacer16()
        OutlinedButton(onClick = onSignOut) { Text("লগআউট") }
    }
}

@Composable
private fun Spacer16() {
    Box(Modifier.height(16.dp))
}

/** Asks for a new PIN twice (setup). Returns the PIN through onSave when both match. */
@Composable
fun PinSetupDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("নতুন PIN সেট করুন", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(first, { first = it.filter(Char::isDigit).take(6) }, label = { Text("PIN (৪–৬ সংখ্যা)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                OutlinedTextField(second, { second = it.filter(Char::isDigit).take(6) }, label = { Text("আবার লিখুন") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    first.length !in 4..6 -> error = "PIN ৪ থেকে ৬ সংখ্যার হতে হবে"
                    first != second -> error = "দুটি PIN মিলছে না"
                    else -> onSave(first)
                }
            }) { Text("সেভ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } }
    )
}

/** Asks for the current PIN before a sensitive change (turning the lock off). */
@Composable
fun PinVerifyDialog(title: String, onDismiss: () -> Unit, onVerified: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("বর্তমান PIN") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (AppLock.checkPin(ctx, pin)) onVerified() else error = "ভুল PIN"
            }) { Text("নিশ্চিত") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } }
    )
}
