package com.fintracks.app.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fintracks.app.FinTrackApp
import com.fintracks.app.MainActivity
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Receives FCM messages. The server sends data-only messages, so the app always builds the
 * notification itself: same look for every push, and the tap always carries the target screen.
 */
class FinTrackMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            PushTokens.onNewToken(app, Repository(app), token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PushNotifier.show(applicationContext, message.data)
    }
}

object PushNotifier {
    private const val MAX_IMAGE_BYTES = 2_000_000

    @android.annotation.SuppressLint("MissingPermission") // permission is checked just below
    fun show(ctx: Context, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val title = data["title"]?.takeIf { it.isNotBlank() } ?: "FinTrack"
        val body = data["body"].orEmpty()
        val kind = data["kind"] ?: "text"
        val alert = kind == "alert"
        val channel = if (alert) FinTrackApp.CHANNEL_ALERTS else FinTrackApp.CHANNEL_PUSH

        val tap = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(PushRouter.EXTRA_TARGET, data["target"] ?: "dashboard")
        }
        val id = (data["campaign_id"] ?: System.nanoTime().toString()).hashCode()
        val pi = PendingIntent.getActivity(ctx, id, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(NotifyStyle.SMALL)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (alert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
        NotifyStyle.large(ctx)?.let { builder.setLargeIcon(it) }

        // Banner: big picture, https only, small size, short timeout. Without it, a plain text notification.
        if (kind == "banner") {
            fetchBanner(data["image_url"])?.let { bmp ->
                builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).setBigContentTitle(title).setSummaryText(body))
            }
        }

        NotificationManagerCompat.from(ctx).notify(id, builder.build())
    }

    private fun fetchBanner(url: String?): Bitmap? {
        if (url == null || !url.startsWith("https://")) return null
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = false
            }
            conn.inputStream.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_IMAGE_BYTES) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Gets this device's FCM token and saves it for the signed-in user. */
object PushSetup {
    suspend fun register(ctx: Context, repo: Repository) {
        val app = ctx.applicationContext
        try {
            val token = suspendCancellableCoroutine<String?> { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
            if (token != null) PushTokens.onNewToken(app, repo, token) else PushTokens.flush(app, repo)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PushTokens.flush(app, repo)
        }
    }
}
