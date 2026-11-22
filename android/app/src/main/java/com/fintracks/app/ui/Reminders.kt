package com.fintracks.app.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fintracks.app.data.id
import com.fintracks.app.data.str
import com.fintracks.app.data.strOrNull
import com.fintracks.app.data.bool
import com.fintracks.app.data.num
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import com.fintracks.app.data.NotifyStyle

/**
 * Bill and due-date reminders: subscriptions renewing, debts and credit items due within 3 days.
 * Each reminder notifies at most once per day.
 */
object Reminders {
    private const val CHANNEL = "bill_reminders"
    private const val PREFS = "fintrack_reminders"

    fun check(ctx: Context, tables: Map<String, List<JsonObject>>) {
        val today = LocalDate.now()
        val soon = today.plusDays(3)
        fun inWindow(d: String?): Boolean {
            if (d.isNullOrEmpty()) return false
            return runCatching { LocalDate.parse(d.take(10)) }.getOrNull()?.let { !it.isBefore(today.minusDays(0)) && !it.isAfter(soon) } == true
        }
        val items = mutableListOf<Pair<String, String>>()
        tables["subscriptions"].orEmpty().filter { it.bool("is_active") && nextRenewal(it, today)?.let { d -> inWindow(d.toString()) } == true }.forEach {
            val next = nextRenewal(it, today)?.toString().orEmpty()
            items += "sub-${it.id()}" to "${it.str("name")} নবায়ন: $next · ${fmtMoney(it.num("amount"))}"
        }
        val debtPays = tables["debt_payments"].orEmpty()
        tables["debts"].orEmpty().filter { it.str("direction") == "owe" && debtRemaining(it, debtPays) > 0.005 && inWindow(it.strOrNull("due_date")) }.forEach {
            items += "debt-${it.id()}" to "ধার পরিশোধ: ${it.str("person")} · ${fmtMoney(debtRemaining(it, debtPays))}"
        }
        tables["credit_entries"].orEmpty().filter { inWindow(it.strOrNull("due_date")) }.forEach {
            items += "credit-${it.id()}" to "বাকি শেষ তারিখ: ${it.str("item_name")} · ${it.str("due_date").take(10)}"
        }
        if (items.isEmpty()) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        items.forEach { (key, text) ->
            val stamp = "$key-$today"
            if (prefs.getBoolean(stamp, false)) return@forEach
            prefs.edit().putBoolean(stamp, true).apply()
            notify(ctx, key.hashCode(), text)
        }
    }

    private fun notify(ctx: Context, id: Int, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("বিল রিমাইন্ডার", "Bill reminder"), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        val pending = PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(NotifyStyle.SMALL)
            .setLargeIcon(NotifyStyle.large(ctx))
            .setContentTitle(tr("FinTrack রিমাইন্ডার", "FinTrack reminder"))
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}
