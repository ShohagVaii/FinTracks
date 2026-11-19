package com.fintracks.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Local, offline parser for bKash / Nagad / Rocket / bank SMS.
 * Nothing leaves the device here: the SMS is only turned into a suggestion the user must confirm.
 */
object SmsParser {
    private val amountRegex =
        Regex("""(?i)(?:tk|taka|bdt|টাকা|৳)\s*\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val expenseWords = listOf(
        "sent", "send money", "paid", "payment", "cash out", "cashout", "withdraw", "purchase", "পাঠিয়েছেন", "পরিশোধ"
    )
    private val incomeWords = listOf(
        "received", "cash in", "cashin", "deposit", "refund", "পেয়েছেন", "জমা"
    )

    fun parse(body: String): SmsSuggestion? {
        val text = body.trim()
        if (text.isEmpty()) return null
        val match = amountRegex.find(text) ?: return null
        val amount = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null

        val lower = text.lowercase()
        val type = when {
            expenseWords.any { lower.contains(it) } -> "Expense"
            incomeWords.any { lower.contains(it) } -> "Income"
            else -> return null
        }
        val provider = when {
            lower.contains("bkash") -> "Bkash"
            lower.contains("nagad") -> "Nagad"
            lower.contains("rocket") -> "Rocket"
            lower.contains("bank") || lower.contains("a/c") || lower.contains("card") -> "Bank"
            else -> "Cash"
        }
        return SmsSuggestion(
            amount = amount,
            type = type,
            provider = provider,
            note = "SMS: ${text.take(80)}",
            raw = text.take(200)
        )
    }
}

/** Stores pending SMS suggestions in SharedPreferences so the app can show them later. */
object PendingSmsStore {
    private const val KEY = "pending_sms"
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(SmsSuggestion.serializer())

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("fintracks", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<SmsSuggestion> =
        prefs(ctx).getString(KEY, null)
            ?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
            ?: emptyList()

    fun save(ctx: Context, list: List<SmsSuggestion>) {
        prefs(ctx).edit().putString(KEY, json.encodeToString(listSerializer, list)).apply()
    }

    fun add(ctx: Context, suggestion: SmsSuggestion) {
        save(ctx, (load(ctx) + suggestion).takeLast(20))
    }
}

/** Receives incoming SMS (permission RECEIVE_SMS). Only parsed transaction-like messages are kept. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val body = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?.joinToString("") { it.messageBody.orEmpty() }
            ?: return
        val suggestion = SmsParser.parse(body) ?: return
        PendingSmsStore.add(context, suggestion)
        Notifier.showSuggestion(context, suggestion)
    }
}

object Notifier {
    private const val CHANNEL_ID = "sms_suggestions"

    fun showSuggestion(ctx: Context, s: SmsSuggestion) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SMS সাজেশন", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        val pending = PendingIntent.getActivity(
            ctx, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("নতুন লেনদেনের সাজেশন")
            .setContentText("${s.provider} · ${s.amount} টাকা (${if (s.type == "Income") "আয়" else "ব্যয়"}) — অ্যাপে নিশ্চিত করুন")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(s.id.hashCode(), notification)
    }
}
