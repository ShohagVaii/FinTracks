package com.fintracks.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.fintracks.app.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    state: UiState,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onGoogle: (String) -> Unit
) {
    var isSignUp by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "FinTracks",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            tr("আপনার স্মার্ট আর্থিক সহায়ক", "Your smart finance companion"),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        if (isSignUp) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(tr("নাম", "Name")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(tr("ইমেইল", "Email")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(tr("পাসওয়ার্ড (কমপক্ষে ৬ অক্ষর)", "Password (at least 6 characters)")) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        val shownError = localError ?: state.message
        if (shownError != null) {
            Spacer(Modifier.height(8.dp))
            Text(shownError, color = MaterialTheme.colorScheme.error)
        }
        state.notice?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !state.busy && email.isNotBlank() && password.length >= 6 && (!isSignUp || name.isNotBlank()),
            onClick = {
                localError = null
                if (isSignUp) onSignUp(name.trim(), email.trim(), password) else onSignIn(email.trim(), password)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSignUp) tr("অ্যাকাউন্ট তৈরি করুন", "Create account") else tr("লগইন", "Log in"))
        }
        TextButton(onClick = { isSignUp = !isSignUp; localError = null }) {
            Text(if (isSignUp) tr("আগে থেকে অ্যাকাউন্ট আছে? লগইন করুন", "Already have an account? Log in") else tr("নতুন অ্যাকাউন্ট খুলুন", "Create a new account"))
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = !state.busy,
            onClick = {
                if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
                    localError = tr("Google লগইন এখনো কনফিগার করা হয়নি", "Google login is not set up yet")
                    return@OutlinedButton
                }
                scope.launch {
                    try {
                        onGoogle(googleIdToken(context))
                    } catch (e: Exception) {
                        localError = tr("Google লগইন ব্যর্থ হয়েছে", "Google login failed")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tr("Google দিয়ে চালিয়ে যান", "Continue with Google"))
        }
    }
}

/** Gets a Google ID token through Credential Manager. Requires a Web client ID from Google Cloud. */
private suspend fun googleIdToken(context: Context): String {
    val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val result = CredentialManager.create(context).getCredential(context, request)
    return GoogleIdTokenCredential.createFrom(result.credential.data).idToken
}
