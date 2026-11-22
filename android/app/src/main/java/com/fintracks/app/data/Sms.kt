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
 *
 * Steps: Bengali digits become ASCII; the amount is the first currency-marked number that is not a
 * balance, fee or charge; the type is the earliest income or expense word; the date is read from the text.
 */
object SmsParser {
    private const val BN_DIGITS = "০১২৩৪৫৬৭৮৯"
    private const val CURRENCY = "(?:tk|taka|bdt|৳|টাকা)"
    private val prefixAmount = Regex("""(?i)$CURRENCY\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val suffixAmount = Regex("""(?i)([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*$CURRENCY""")
    // Words that mark a number as NOT the transaction amount.
    private val notAmountWords = listOf("balance", "bal ", "fee", "charge", "vat", "limit", "ব্যালেন্স", "ফি", "চার্জ", "সীমা")
    private val expenseWords = listOf(
        "sent", "send money", "paid", "payment", "cash out", "cashout", "withdraw", "withdrawn", "purchase",
        "debited", "debit", "spent", "deducted", "পাঠিয়েছেন", "পাঠানো", "পরিশোধ", "উত্তোলন", "কেনাকাটা", "ডেবিট", "কেটে"
    )
    private val incomeWords = listOf(
        "received", "cash in", "cashin", "deposit", "deposited", "refund", "credited", "credit", "added", "cashback",
        "পেয়েছেন", "জমা", "ক্রেডিট", "যোগ হয়েছে"
    )
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val isoDate = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val numDate = Regex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})""")
    private val wordDate = Regex("""(?i)(\d{1,2})[- ]?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[- ,]?(\d{2,4})""")

    /** Replaces Bengali digits (০-৯) with ASCII digits so one set of rules works for both. */
    fun normalizeDigits(text: String): String = buildString(text.length) {
        for (ch in text) {
            val i = BN_DIGITS.indexOf(ch)
            append(if (i >= 0) ('0' + i) else ch)
        }
    }

    fun parse(body: String): SmsSuggestion? {
        val text = normalizeDigits(body.trim())
        if (text.isEmpty()) return null
        val amount = findAmount(text) ?: return null
        val type = findType(text.lowercase()) ?: return null
        val lower = text.lowercase()
        val provider = when {
            lower.contains("bkash") || text.contains("বিকাশ") -> "Bkash"
            lower.contains("nagad") || text.contains("নগদ") -> "Nagad"
            lower.contains("rocket") || text.contains("রকেট") -> "Rocket"
            lower.contains("bank") || lower.contains("a/c") || lower.contains("account") || lower.contains("card") ||
                text.contains("ব্যাংক") || text.contains("একাউন্ট") -> "Bank"
            else -> "Cash"
        }
        return SmsSuggestion(
            amount = amount,
            type = type,
            provider = provider,
            note = "SMS: ${body.trim().take(80)}",
            raw = body.trim().take(200),
            date = findDate(text) ?: ""
        )
    }

    /** First currency-marked amount that is not a balance, fee or charge. */
    internal fun findAmount(text: String): Double? {
        val found = (prefixAmount.findAll(text) + suffixAmount.findAll(text))
            .sortedBy { it.range.first }
        for (m in found) {
            val before = text.substring(maxOf(0, m.range.first - 24), m.range.first).lowercase()
            if (notAmountWords.any { before.contains(it) }) continue
            val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            if (value > 0.0) return value
        }
        return null
    }

    /** The type whose keyword appears first in the text (so "You have sent ... payment" is an expense). */
    internal fun findType(lower: String): String? {
        val expense = expenseWords.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val income = incomeWords.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull()
        return when {
            expense == null && income == null -> null
            expense == null -> "Income"
            income == null -> "Expense"
            expense < income -> "Expense"
            else -> "Income"
        }
    }

    /** Date in the SMS as yyyy-MM-dd, or null. Day-first numeric dates (19/11/2026) are the Bangladesh format. */
    internal fun findDate(text: String): String? {
        isoDate.find(text)?.let { m ->
            return iso(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        wordDate.find(text)?.let { m ->
            val month = months.indexOf(m.groupValues[2].lowercase().take(3)) + 1
            return iso(yearOf(m.groupValues[3]), month, m.groupValues[1].toInt())
        }
        numDate.find(text)?.let { m ->
            return iso(yearOf(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        return null
    }

    private fun yearOf(y: String): Int = y.toInt().let { if (y.length == 2) 2000 + it else it }

    private fun iso(y: Int, m: Int, d: Int): String? = runCatching {
        java.time.LocalDate.of(y, m, d).toString()
    }.getOrNull()
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
            .setSmallIcon(NotifyStyle.SMALL)
            .setLargeIcon(NotifyStyle.large(ctx))
            .setContentTitle("নতুন লেনদেনের সাজেশন")
            .setContentText("${s.provider} · ${s.amount} টাকা (${if (s.type == "Income") "আয়" else "ব্যয়"}) — অ্যাপে নিশ্চিত করুন")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(s.id.hashCode(), notification)
    }
}
