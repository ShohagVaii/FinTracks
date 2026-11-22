package com.fintracks.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException

/**
 * Holds this device's FCM token until it can be saved for a signed-in user.
 * The Firebase messaging service (added with the Firebase SDK) calls [onNewToken]; sign-in calls [flush];
 * sign-out calls [forget]. Until the SDK is wired, nothing calls these, so no token is ever stored.
 */
object PushTokens {
    private const val PREFS = "fintracks_push"
    private const val KEY_TOKEN = "fcm_token"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Remember the newest token and try to save it right away. */
    suspend fun onNewToken(ctx: Context, repo: Repository, token: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, token).apply()
        flush(ctx, repo)
    }

    /** Save the stored token for the current user. Keeps it for the next try when offline or signed out. */
    suspend fun flush(ctx: Context, repo: Repository) {
        val token = prefs(ctx).getString(KEY_TOKEN, null) ?: return
        if (repo.session == null) return
        try {
            repo.registerDeviceToken(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or server error: the token stays stored and is retried on the next flush.
        }
    }

    /** On sign-out: remove this device's token from the account, then forget it locally. */
    suspend fun forget(ctx: Context, repo: Repository) {
        val token = prefs(ctx).getString(KEY_TOKEN, null) ?: return
        try {
            if (repo.session != null) repo.unregisterDeviceToken(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Still forget locally; the server row is removed when the account is deleted or the token is replaced.
        }
        prefs(ctx).edit().remove(KEY_TOKEN).apply()
    }
}
