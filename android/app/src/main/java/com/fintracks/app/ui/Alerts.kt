package com.fintracks.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fintracks.app.data.Inbox
import com.fintracks.app.data.InboxItem
import com.fintracks.app.data.PushNotifier
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * System alerts are computed from the synced data after each sync. Each alert has an on/off switch, is shown once
 * per day (or once ever, for milestones), goes to the notification and to the inbox. New kinds (for example the
 * birthday wish) only need a Kind entry and a rule in [evaluate].
 */
object SmartAlerts {
    enum class Kind(val bn: String, val en: String) {
        RECEIVABLE("পাওনা বাকি", "Money to collect"),
        CREDIT("বাকি খাতার বকেয়া", "Credit ledger dues"),
        EVENING("সন্ধ্যার মনে করানো", "Evening reminder"),
        BUDGET("বাজেট সতর্কতা", "Budget alerts"),
        SAVINGS("সঞ্চয় মাইলস্টোন", "Savings milestones"),
        BIKE("বাইক সার্ভিস", "Bike service")
    }

    private class Alert(val kind: Kind, val key: String, val title: String, val body: String, val once: Boolean)

    private const val PREFS = "fintrack_alerts"
    private const val MAX_PER_RUN = 6

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context, k: Kind): Boolean = prefs(ctx).getBoolean("on_${k.name}", true)

    fun setEnabled(ctx: Context, k: Kind, on: Boolean) {
        prefs(ctx).edit().putBoolean("on_${k.name}", on).apply()
    }

    /** Called after each sync with the synced tables. */
    fun evaluate(ctx: Context, tables: Map<String, List<JsonObject>>) {
        val today = LocalDate.now()
        val todayIso = today.toString()
        val month = todayIso.take(7)
        val txs = tables["transactions"].orEmpty()
        val alerts = mutableListOf<Alert>()

        // Money others owe you, one line per debt.
        val pays = tables["debt_payments"].orEmpty()
        tables["debts"].orEmpty().filter { it.str("direction") == "owed" }.forEach { d ->
            val rem = debtRemaining(d, pays)
            if (rem > 0.005) {
                alerts += Alert(Kind.RECEIVABLE, "recv-${d.id()}", tr("পাওনা বাকি", "Money to collect"),
                    "${d.str("person")}: ${taka(rem)}", false)
            }
        }

        // Credit ledger: what is still unpaid in total.
        val due = tables["credit_entries"].orEmpty().sumOf { it.num("total") } -
            tables["credit_payments"].orEmpty().sumOf { it.num("amount") }
        if (due > 0.5) {
            alerts += Alert(Kind.CREDIT, "credit-total", tr("বাকি খাতায় বকেয়া", "Credit ledger dues"), taka(due), false)
        }

        // Evening nudge when nothing was added today.
        if (LocalTime.now().hour >= 20 && txs.none { it.str("tx_date").take(10) == todayIso }) {
            alerts += Alert(Kind.EVENING, "evening", tr("আজকের খরচ যোগ করুন", "Add today's spending"),
                tr("আজ এখনো কোনো লেনদেন নেই", "No transaction yet today"), false)
        }

        // Budgets at 80% and 100% of the monthly limit.
        tables["budgets"].orEmpty().forEach { b ->
            val limit = b.num("monthly_limit")
            if (limit <= 0) return@forEach
            val spent = txs.filter {
                it.str("type") == "Expense" && it.str("category") == b.str("category") && it.str("tx_date").startsWith(month)
            }.sumOf { it.num("amount") }
            val pct = spent / limit * 100
            val label = "${b.str("category")} · ${bnDigits("${pct.toInt()}")}%"
            if (pct >= 100) {
                alerts += Alert(Kind.BUDGET, "budget100-${b.id()}-$month", tr("বাজেট ছাড়িয়েছে", "Over budget"), label, true)
            } else if (pct >= 80) {
                alerts += Alert(Kind.BUDGET, "budget80-${b.id()}-$month", tr("বাজেটের ৮০% খরচ", "80% of budget used"), label, true)
            }
        }

        // Savings goals: halfway and reached, once each.
        tables["goals"].orEmpty().forEach { g ->
            val target = g.num("target")
            if (target <= 0) return@forEach
            val pct = g.num("saved") / target * 100
            if (pct >= 100) {
                alerts += Alert(Kind.SAVINGS, "goal100-${g.id()}", tr("লক্ষ্য পূরণ হয়েছে", "Goal reached"), g.str("title"), true)
            } else if (pct >= 50) {
                alerts += Alert(Kind.SAVINGS, "goal50-${g.id()}", tr("লক্ষ্যের অর্ধেক পূরণ", "Halfway to the goal"),
                    "${g.str("title")} · ${bnDigits("${pct.toInt()}")}%", true)
            }
        }

        // Bike service: 200 km before the due km, or 3 days before the due date.
        val latestOdo = bikeStats(BikeStore.fuel, BikeStore.maint, todayIso).latestOdo
        BikeStore.maint.forEach { m ->
            m.nextKm?.let { k ->
                val left = k - latestOdo
                if (left <= 200) {
                    alerts += Alert(Kind.BIKE, "bike-km-${m.id}", tr("বাইক সার্ভিস due", "Bike service due"),
                        bnDigits("${"%.0f".format(k)} km · বাকি ${"%.0f".format(left)} km"), true)
                }
            }
            if (m.nextDate.isNotEmpty()) {
                val days = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(m.nextDate)) }.getOrDefault(999L)
                if (days in 0L..3L) {
                    alerts += Alert(Kind.BIKE, "bike-date-${m.id}", tr("বাইক সার্ভিস due", "Bike service due"),
                        bnDigits(displayDate(m.nextDate)), true)
                }
            }
        }

        var fired = 0
        for (a in alerts) {
            if (fired >= MAX_PER_RUN) break
            if (fire(ctx, a, today)) fired++
        }
    }

    private fun fire(ctx: Context, a: Alert, today: LocalDate): Boolean {
        if (!enabled(ctx, a.kind)) return false
        val p = prefs(ctx)
        val stamp = if (a.once) a.key else "${a.key}-$today"
        if (p.getBoolean(stamp, false)) return false
        p.edit().putBoolean(stamp, true).apply()
        LocalInbox.add(a.title, a.body)
        PushNotifier.show(ctx, mapOf("title" to a.title, "body" to a.body, "kind" to "text", "target" to "dashboard"))
        return true
    }
}

/** Alerts shown in the inbox from this phone. They sit next to the server campaigns in [Inbox]. */
object LocalInbox {
    private const val PREFS = "fintrack_inbox"
    private const val MAX = 100
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Stored.serializer())
    private var prefs: SharedPreferences? = null
    private var list: List<Stored> = emptyList()

    @Serializable
    private data class Stored(val id: String, val title: String, val body: String, val sentAt: String, val read: Boolean)

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        list = runCatching { json.decodeFromString(ser, p.getString("items", "[]") ?: "[]") }.getOrDefault(emptyList())
    }

    fun items(): List<InboxItem> = list.map { InboxItem(it.id, it.title, it.body, null, "dashboard", it.sentAt, it.read) }

    /** Safe to call from a background thread; the inbox list is refreshed on the main thread. */
    fun add(title: String, body: String) {
        list = (listOf(Stored("local-${UUID.randomUUID()}", title, body, Instant.now().toString(), false)) + list).take(MAX)
        save()
    }

    fun markRead(ids: Collection<String>) {
        list = list.map { if (it.id in ids) it.copy(read = true) else it }
        save()
    }

    private fun save() {
        prefs?.edit()?.putString("items", json.encodeToString(ser, list))?.apply()
        Handler(Looper.getMainLooper()).post { Inbox.publish() }
    }
}

/** A reminder the user made. Repeat is none, daily, weekly or monthly. */
@Serializable
data class CustomReminder(val id: String, val title: String, val body: String, val atMillis: Long, val repeat: String = "none")

object CustomReminders {
    private const val PREFS = "fintrack_custom_reminders"
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(CustomReminder.serializer())

    fun list(ctx: Context): List<CustomReminder> =
        runCatching { json.decodeFromString(ser, ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", "[]") ?: "[]") }
            .getOrDefault(emptyList())

    private fun save(ctx: Context, items: List<CustomReminder>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", json.encodeToString(ser, items)).apply()
    }

    fun add(ctx: Context, r: CustomReminder) {
        save(ctx, list(ctx) + r)
        schedule(ctx, r)
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, list(ctx).filterNot { it.id == id })
        WorkManager.getInstance(ctx).cancelUniqueWork("custom-$id")
    }

    internal fun update(ctx: Context, r: CustomReminder) {
        save(ctx, list(ctx).map { if (it.id == r.id) r else it })
        schedule(ctx, r)
    }

    private fun schedule(ctx: Context, r: CustomReminder) {
        val delay = (r.atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val req = OneTimeWorkRequestBuilder<CustomReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString("id", r.id).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("custom-${r.id}", ExistingWorkPolicy.REPLACE, req)
    }

    /** The next time after now for a repeating reminder. */
    internal fun nextMillis(r: CustomReminder): Long {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var t = Instant.ofEpochMilli(r.atMillis).atZone(zone)
        while (!t.isAfter(now)) {
            t = when (r.repeat) {
                "daily" -> t.plusDays(1)
                "weekly" -> t.plusWeeks(1)
                else -> t.plusMonths(1)
            }
        }
        return t.toInstant().toEpochMilli()
    }
}

class CustomReminderWorker(ctx: android.content.Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString("id") ?: return Result.success()
        val r = CustomReminders.list(ctx).firstOrNull { it.id == id } ?: return Result.success()
        LocalInbox.add(r.title, r.body)
        PushNotifier.show(ctx, mapOf("title" to r.title, "body" to r.body, "kind" to "text", "target" to "dashboard"))
        if (r.repeat == "none") {
            CustomReminders.remove(ctx, id)
        } else {
            CustomReminders.update(ctx, r.copy(atMillis = CustomReminders.nextMillis(r)))
        }
        return Result.success()
    }
}

/** Settings page: on/off per system alert, and the user's own reminders. */
@Composable
fun NotifPage() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val refresh = tick // re-reads the lists after a change

    Page {
        AppCard {
            SectionTitle(tr("সিস্টেম নোটিফিকেশন", "System notifications"))
            SmartAlerts.Kind.values().forEach { k ->
                SwitchRow(tr(k.bn, k.en), SmartAlerts.enabled(ctx, k)) { on ->
                    SmartAlerts.setEnabled(ctx, k, on)
                    tick++
                }
            }
        }
        AppCard {
            SectionTitle(tr("আমার রিমাইন্ডার", "My reminders"))
            val mine = CustomReminders.list(ctx).sortedBy { it.atMillis }
            if (mine.isEmpty()) EmptyHint(tr("কোনো রিমাইন্ডার নেই", "No reminders"))
            mine.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            bnDigits(reminderTime(r.atMillis) + " · " + repeatLabel(r.repeat)),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(modifier = Modifier.pressScale(), onClick = {
                        CustomReminders.remove(ctx, r.id)
                        tick++
                    }) { Icon(Icons.Default.Delete, contentDescription = tr("মুছুন", "Delete")) }
                }
            }
            AddButton(tr("নতুন রিমাইন্ডার", "New reminder")) { adding = true }
        }
    }
    if (adding) CustomReminderDialog(ctx) { adding = false; tick++ }
}

private fun reminderTime(atMillis: Long): String {
    val t = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
    return displayDate(t.toLocalDate().toString()) + " " + "%02d:%02d".format(t.hour, t.minute)
}

private fun repeatLabel(repeat: String): String = when (repeat) {
    "daily" -> tr("প্রতিদিন", "Daily")
    "weekly" -> tr("সাপ্তাহিক", "Weekly")
    "monthly" -> tr("মাসিক", "Monthly")
    else -> tr("একবার", "Once")
}

@Composable
private fun CustomReminderDialog(ctx: android.content.Context, onDone: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayIso()) }
    var time by remember { mutableStateOf("09:00") }
    var repeat by remember { mutableStateOf("none") }
    var error by remember { mutableStateOf<String?>(null) }
    val repeatOptions = listOf(
        "none" to tr("একবার", "Once"),
        "daily" to tr("প্রতিদিন", "Daily"),
        "weekly" to tr("সাপ্তাহিক", "Weekly"),
        "monthly" to tr("মাসিক", "Monthly")
    )

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(tr("নতুন রিমাইন্ডার", "New reminder"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextIn(tr("শিরোনাম", "Title"), title, { title = it })
                TextIn(tr("লেখা", "Text"), body, { body = it })
                DateField(tr("তারিখ", "Date"), date, { date = it })
                TextIn(tr("সময় (HH:mm)", "Time (HH:mm)"), time, { time = it })
                ChoiceChips(repeatOptions, repeat) { repeat = it }
                error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = runCatching { LocalDate.parse(date) }.getOrNull()
                val t = runCatching { LocalTime.parse(time.trim()) }.getOrNull()
                val at = if (d != null && t != null) LocalDateTime.of(d, t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null
                when {
                    title.isBlank() -> error = tr("শিরোনাম লিখুন", "Enter a title")
                    at == null -> error = tr("সঠিক তারিখ ও সময় দিন", "Enter a valid date and time")
                    at <= System.currentTimeMillis() -> error = tr("ভবিষ্যতের সময় দিন", "Pick a time in the future")
                    else -> {
                        CustomReminders.add(ctx, CustomReminder(UUID.randomUUID().toString(), title.trim(), body.trim(), at, repeat))
                        onDone()
                    }
                }
            }) { Text(tr("সেভ", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(tr("বাতিল", "Cancel")) } }
    )
}
