package com.fintracks.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import com.fintracks.app.ui.LocalOutbox
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
/** A field the parser could not find in an SMS. */
enum class SmsPart(val bn: String, val en: String) {
    PROVIDER("মাধ্যম", "Method"), TYPE("ধরন", "Type"), AMOUNT("পরিমাণ", "Amount"), REF("আইডি", "ID")
}

/** Either a readable transaction, or the list of parts that were not found. */
sealed class SmsParseResult {
    data class Ok(val suggestion: SmsSuggestion) : SmsParseResult()
    data class Fail(val missing: List<SmsPart>) : SmsParseResult()
}

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
    private val balanceRe = Regex("""(?i)balance(?:\s+is)?\s*:?\s*(?:$CURRENCY\.?\s*)?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    // TrxID (bKash), TxnID (Nagad) and Txn ID (Rocket) all share this shape.
    private val refRe = Regex("""(?i)(?:trx|txn)\s*id\s*:?\s*([A-Z0-9]{4,15})""")
    private val bankAc = Regex("""(?i)a/c\s*\**\s*(\d{3,6})""")
    private val timeRe = Regex("""(\d{1,2}):(\d{2})(?::\d{2})?\s*([AaPp][Mm])?""")
    private val numDate = Regex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})""")
    private val wordDate = Regex("""(?i)(\d{1,2})[- ]?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[- ,]?(\d{2,4})""")

    /** Replaces Bengali digits (০-৯) with ASCII digits so one set of rules works for both. */
    fun normalizeDigits(text: String): String = buildString(text.length) {
        for (ch in text) {
            val i = BN_DIGITS.indexOf(ch)
            append(if (i >= 0) ('0' + i) else ch)
        }
    }

    /** The transaction, or null when the SMS is not a readable transaction. */
    fun parse(body: String): SmsSuggestion? = (parseResult(body) as? SmsParseResult.Ok)?.suggestion

    /** Full parse with the reason when it fails: which of method, type, amount or ID was not found. */
    fun parseResult(body: String): SmsParseResult {
        val raw = body.trim()
        val text = normalizeDigits(raw)
        val amount = findAmount(text)
        val type = findType(text.lowercase())
        val provider = findProvider(text)
        if (amount == null || type == null || provider == null) {
            return SmsParseResult.Fail(buildList {
                if (provider == null) add(SmsPart.PROVIDER)
                if (type == null) add(SmsPart.TYPE)
                if (amount == null) add(SmsPart.AMOUNT)
            })
        }
        val date = findDate(text) ?: ""
        val balance = findBalance(text)
        return SmsParseResult.Ok(
            SmsSuggestion(
                amount = amount,
                type = type,
                provider = provider,
                note = "SMS: ${raw.take(80)}",
                raw = raw.take(200),
                date = date,
                time = findTime(text) ?: "",
                balance = balance,
                ref = findRef(text, provider, amount, date, balance)
            )
        )
    }

    /** Method from its name in the text. bKash receipts often have no name, so the TrxID label identifies them. */
    internal fun findProvider(text: String): String? {
        val lower = text.lowercase()
        return when {
            lower.contains("nagad") || text.contains("নগদ") -> "Nagad"
            lower.contains("rocket") || text.contains("রকেট") -> "Rocket"
            lower.contains("bkash") || text.contains("বিকাশ") -> "Bkash"
            lower.contains("trxid") || lower.contains("trx id") -> "Bkash"
            lower.contains("bank") || lower.contains("a/c") || lower.contains("account") || lower.contains("card") ||
                text.contains("ব্যাংক") || text.contains("একাউন্ট") -> "Bank"
            else -> "Cash"
        }
    }

    /** Transaction ID; for a bank SMS the account and the figures that identify the entry. Null when none. */
    internal fun findRef(text: String, provider: String, amount: Double, date: String, balance: Double?): String? {
        if (provider == "Bank") {
            val ac = bankAc.find(text)?.groupValues?.get(1) ?: return null
            return "A/C $ac|$date|$amount|${balance ?: ""}"
        }
        return refRe.find(text)?.groupValues?.get(1)
    }

    /** Balance after the transaction, when stated. */
    internal fun findBalance(text: String): Double? =
        balanceRe.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    /** Time as HH:mm (24h). Handles AM/PM and plain 24h times. */
    internal fun findTime(text: String): String? {
        val m = timeRe.find(text) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        when (m.groupValues[3].lowercase()) {
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
        }
        return "%02d:%02d".format(h, min)
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

/** SMS auto-add switch (default on once both SMS permissions are granted) and the TrxIDs already added. */
object SmsAuto {
    private const val KEY_ON = "sms_auto"
    private const val KEY_SEEN = "sms_seen_refs"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("fintracks", Context.MODE_PRIVATE)

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun enabled(ctx: Context): Boolean = hasPermission(ctx) && prefs(ctx).getBoolean(KEY_ON, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
    }

    fun seen(ctx: Context, ref: String): Boolean = prefs(ctx).getStringSet(KEY_SEEN, emptySet()).orEmpty().contains(ref)

    fun markSeen(ctx: Context, ref: String) {
        val p = prefs(ctx)
        val all = p.getStringSet(KEY_SEEN, emptySet()).orEmpty().toList() + ref
        p.edit().putStringSet(KEY_SEEN, all.takeLast(500).toSet()).apply()
    }
}

/**
 * Receives incoming SMS (RECEIVE_SMS + READ_SMS). Only a confirmed match is added: a known method (not Cash),
 * a type, an amount and a transaction ID that has not been added before. Anything else is dropped silently.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!SmsAuto.enabled(context)) return
        val body = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?.joinToString("") { it.messageBody.orEmpty() }
            ?: return
        val s = (SmsParser.parseResult(body) as? SmsParseResult.Ok)?.suggestion ?: return
        val ref = s.ref ?: return
        if (s.provider == "Cash" || SmsAuto.seen(context, ref)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                addTransaction(context, s)
                SmsAuto.markSeen(context, ref)
                Notifier.showAdded(context, s)
            } catch (e: Exception) {
                // Not added: the TrxID is not marked, so a later copy of this SMS can still be added.
            } finally {
                pending.finish()
            }
        }
    }

    /** Queues the insert in the offline outbox (the same path as the app's own save), then sends it. */
    private suspend fun addTransaction(ctx: Context, s: SmsSuggestion) {
        val repo = Repository(ctx)
        val outbox = LocalOutbox(ctx, repo)
        val id = java.util.UUID.randomUUID().toString()
        val body = row(
            "id" to id,
            "type" to s.type,
            "amount" to s.amount,
            "currency" to "BDT",
            "category" to "অন্যান্য",
            "note" to s.note,
            "tx_date" to s.date.ifEmpty { java.time.LocalDate.now().toString() },
            "payment_mode" to s.provider,
            "is_recurring" to false,
            "tags" to JsonArray(emptyList())
        )
        outbox.enqueue(listOf(LocalOutbox.op("insert", "transactions", id, body, body)))
        outbox.flush()
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

    /** Shown after an SMS transaction was added automatically. */
    fun showAdded(ctx: Context, s: SmsSuggestion) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "SMS সাজেশন", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        val pending = PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val kind = if (s.type == "Income") "আয়" else "ব্যয়"
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(NotifyStyle.SMALL)
            .setLargeIcon(NotifyStyle.large(ctx))
            .setContentTitle("SMS থেকে লেনদেন যোগ হয়েছে")
            .setContentText("${s.provider} · ${"%,.2f".format(s.amount)} টাকা ($kind)")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify((s.ref ?: s.id).hashCode(), notification)
    }
}
