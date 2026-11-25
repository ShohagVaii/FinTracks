package com.fintracks.app.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fintracks.app.MainActivity
import com.fintracks.app.data.NotifyStyle
import com.fintracks.app.data.PushRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LIVE_CH = "live_usage"
private const val LIVE_ID = 4710
private const val LIVE_PREFS = "fintrack_usage"
private const val LIVE_KEY = "live_notif"

/** The switch for the live notification. On by default. */
object LiveUsagePrefs {
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(LIVE_PREFS, Context.MODE_PRIVATE).getBoolean(LIVE_KEY, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(LIVE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(LIVE_KEY, on).apply()
    }
}

/** Starts the service when the switch is on, stops it when off. Failures (background start limits) are ignored. */
fun syncLiveUsage(ctx: Context) {
    val intent = Intent(ctx, LiveUsageService::class.java)
    if (LiveUsagePrefs.enabled(ctx)) {
        runCatching { ContextCompat.startForegroundService(ctx, intent) }
    } else {
        ctx.stopService(intent)
    }
}

/**
 * Ongoing notification: live speed (download and upload) every second while the screen is on, every five seconds
 * while it is off; the monthly split (mobile and Wi-Fi) refreshes every minute. A tap opens the data usage page.
 */
class LiveUsageService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LiveUsagePrefs.enabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(LIVE_ID, build(duText(0L, perSecond = true), ""))
        if (job == null) job = scope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        val pm = getSystemService(PowerManager::class.java)
        var prevRx = TrafficStats.getTotalRxBytes()
        var prevTx = TrafficStats.getTotalTxBytes()
        var month = ""
        var monthAt = 0L
        while (true) {
            val interval = if (pm.isInteractive) 1000L else 5000L
            delay(interval)
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val rxRate = (rx - prevRx).coerceAtLeast(0L) * 1000L / interval
            val txRate = (tx - prevTx).coerceAtLeast(0L) * 1000L / interval
            prevRx = rx
            prevTx = tx
            val now = System.currentTimeMillis()
            if (now - monthAt >= 60_000L) {
                month = monthLine()
                monthAt = now
            }
            val speed = "↓ " + duText(rxRate, perSecond = true) + "  ·  ↑ " + duText(txRate, perSecond = true)
            getSystemService(NotificationManager::class.java).notify(LIVE_ID, build(speed, month))
        }
    }

    private fun monthLine(): String {
        if (!duHasUsageAccess(this)) return tr("ব্যবহার অনুমতি লাগবে", "Usage access needed")
        val t = duTotals(this, duStart(DuSpan.MONTH), System.currentTimeMillis())
        return tr("মোবাইল: ", "Mobile: ") + duText(t.mobile) + "  ·  Wi-Fi: " + duText(t.wifi)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(LIVE_CH, tr("লাইভ ডাটা", "Live data"), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun build(speed: String, month: String): Notification {
        val open = Intent(this, MainActivity::class.java)
            .putExtra(PushRouter.EXTRA_TARGET, "data")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, LIVE_CH)
            .setSmallIcon(NotifyStyle.SMALL)
            .setContentTitle(speed)
            .setContentText(month)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

/** Switch row on the data usage page. */
@Composable
fun LiveNotifRow() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(LiveUsagePrefs.enabled(ctx)) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(faint(0.05f))
            .border(1.dp, faint(0.1f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Notifications, contentDescription = null, tint = DuCyan, modifier = Modifier.size(18.dp))
            Text(tr("লাইভ নোটিফিকেশন", "Live notification"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Switch(checked = on, onCheckedChange = {
            on = it
            LiveUsagePrefs.setEnabled(ctx, it)
            syncLiveUsage(ctx)
        })
    }
}
