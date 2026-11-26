package com.fintracks.app.ui

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fintracks.app.data.PushNotifier
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** One open debt with a due date. Owed = someone owes me (পাওনা), otherwise I owe (দেনা). */
data class DebtDue(val id: String, val dueIso: String, val who: String, val owed: Boolean)

/**
 * Due-date reminders at 09:00 the day before and on the due day (WorkManager, same pattern as custom reminders).
 * `sync` is the single entry point: it schedules every open due date and cancels anything no longer open.
 */
object DebtReminders {
    private const val PREFS = "debt_reminders"
    private const val KEY = "work_names"
    private const val HOUR = 9

    fun sync(ctx: Context, dues: List<DebtDue>) {
        val wm = WorkManager.getInstance(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY, emptySet()).orEmpty()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val keep = HashSet<String>()
        for (d in dues) {
            val due = runCatching { LocalDate.parse(d.dueIso) }.getOrNull() ?: continue
            val who = if (d.owed) "পাওনা" else "দেনা"
            val jobs = listOf(
                Triple("debt-${d.id}-pre", due.minusDays(1).atTime(HOUR, 0).atZone(zone).toInstant().toEpochMilli(),
                    "আগামীকাল মেয়াদ" to "${d.who} · $who · মেয়াদ ${displayDate(d.dueIso)}"),
                Triple("debt-${d.id}-day", due.atTime(HOUR, 0).atZone(zone).toInstant().toEpochMilli(),
                    "আজ মেয়াদ" to "${d.who} · $who · মেয়াদ ${displayDate(d.dueIso)}")
            )
            for ((name, at, text) in jobs) {
                if (at <= now) continue
                enqueue(ctx, name, at - now, text.first, text.second)
                keep += name
            }
        }
        for (old in previous - keep) wm.cancelUniqueWork(old)
        prefs.edit().putStringSet(KEY, keep).apply()
    }

    private fun enqueue(ctx: Context, name: String, delayMs: Long, title: String, body: String) {
        val req = OneTimeWorkRequestBuilder<DebtReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString("title", title).putString("body", body).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }
}

class DebtReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val title = inputData.getString("title") ?: return Result.success()
        val body = inputData.getString("body").orEmpty()
        LocalInbox.add(title, body)
        PushNotifier.show(ctx, mapOf("title" to title, "body" to body, "kind" to "text", "target" to "dashboard"))
        return Result.success()
    }
}

/** Reminder message templates in three tones. Each direction has its own text; the user can edit them. */
object DebtTemplates {
    private const val PREFS = "debt_templates"
    val tones = listOf("soft", "normal", "firm")

    private val defaultsOwed = mapOf(
        "soft" to "ভাই, কেমন আছেন? আপনার কাছে {কারণ} বাবদ ৳{বাকি} পাওনা আছে। সুবিধামতো দিয়ে দিলে ভালো হতো। ধন্যবাদ।",
        "normal" to "আসসালামু আলাইকুম। {কারণ} বাবদ ৳{বাকি} বাকি আছে। কবে দিতে পারবেন জানাবেন।",
        "firm" to "{তারিখ}-এর মধ্যে ৳{বাকি} ফেরত দেওয়ার কথা ছিল। আজই দিন।"
    )
    private val defaultsOwe = mapOf(
        "soft" to "ভাই, কেমন আছেন? আমার কাছে আপনার {কারণ} বাবদ ৳{বাকি} দেনা আছে। সুবিধামতো পরিশোধ করব। ধন্যবাদ।",
        "normal" to "আসসালামু আলাইকুম। {কারণ} বাবদ ৳{বাকি} আমার দেওয়া বাকি আছে। কবে দিতে পারব জানাব।",
        "firm" to "{তারিখ}-এর মধ্যে ৳{বাকি} দেওয়ার কথা ছিল। আমি শীঘ্রই দিচ্ছি।"
    )

    fun defaultText(owed: Boolean, tone: String): String =
        (if (owed) defaultsOwed else defaultsOwe)[tone].orEmpty()

    fun get(ctx: Context, owed: Boolean, tone: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(owed, tone), null)
            ?: defaultText(owed, tone)

    fun set(ctx: Context, owed: Boolean, tone: String, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(owed, tone), text).apply()
    }

    fun reset(ctx: Context, owed: Boolean) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        tones.forEach { e.remove(key(owed, it)) }
        e.apply()
    }

    fun render(template: String, reason: String, amount: String, date: String): String =
        template.replace("{কারণ}", reason).replace("{বাকি}", amount).replace("{তারিখ}", date)

    private fun key(owed: Boolean, tone: String) = (if (owed) "owed" else "owe") + "-" + tone
}
