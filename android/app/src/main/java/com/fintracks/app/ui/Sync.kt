package com.fintracks.app.ui

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fintracks.app.data.Repository
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.data.strOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/** Shared sync steps: fetch every table, create monthly recurring copies, reminders, widget refresh. */
object Sync {
    private const val PREFS = "fintrack_sync"

    /** Tables that may be missing on the server until their migration is applied. */
    private val OPTIONAL_TABLES = setOf("debt_payments", "ai_chat_messages", "bike_fuel_entries", "bike_maintenance_entries")

    /** Remembers when the last successful table fetch finished (shown in Settings). */
    fun markOk(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_ok", System.currentTimeMillis()).apply()
    }

    /** Epoch millis of the last successful sync, or 0 when there has never been one on this phone. */
    fun lastOk(ctx: Context): Long =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_ok", 0L)

    suspend fun fetchTables(repo: Repository): Map<String, List<JsonObject>> = coroutineScope {
        // Required tables still fail the whole fetch as before. An optional table that cannot be read
        // is left out of the map, so callers keep its last known rows instead of wiping them.
        TABLES.map { (t, q) ->
            async {
                if (t in OPTIONAL_TABLES) t to runCatching { repo.list(t, q) }.getOrNull()
                else t to repo.list(t, q)
            }
        }.awaitAll().mapNotNull { (t, rows) -> rows?.let { t to it } }.toMap()
    }

    /**
     * Each transaction marked is_recurring is copied once per month into the current month
     * (same day, clamped to month length). The copy itself is not recurring.
     */
    /**
     * Creates this month's copy of each recurring transaction. The copy is queued in the local outbox
     * (works offline, sent later); the "done" mark is saved after each queued copy, so a failure
     * never creates the same copy twice.
     */
    suspend fun materializeRecurring(ctx: Context, outbox: LocalOutbox, txs: List<JsonObject>): Boolean {
        val prefs = ctx.getSharedPreferences("fintrack_recurring", Context.MODE_PRIVATE)
        val month = monthPrefix()
        val key = "done-$month"
        val done = (prefs.getStringSet(key, null) ?: emptySet()).toMutableSet()
        var created = false
        for (t in txs.filter { it.bool("is_recurring") }) {
            val day = dateOf(t, "tx_date")
            if (day.length < 10 || day.take(7) >= month) continue
            if (!done.add(t.id())) continue
            val dd = minOf(day.substring(8).toInt(), YearMonth.parse(month).lengthOfMonth())
            val nid = java.util.UUID.randomUUID().toString()
            val copy = row(
                "id" to nid,
                "type" to t.str("type"),
                "amount" to t.num("amount"),
                "currency" to t.str("currency").ifEmpty { "BDT" },
                "category" to t.str("category"),
                "note" to t.str("note"),
                "tx_date" to "$month-${dd.toString().padStart(2, '0')}",
                "payment_mode" to t.str("payment_mode"),
                "mood" to t.str("mood"),
                "account_id" to t.strOrNull("account_id"),
                "to_account_id" to t.strOrNull("to_account_id"),
                "is_recurring" to false
            )
            outbox.enqueue(listOf(LocalOutbox.op("insert", "transactions", nid, copy, copy)))
            prefs.edit().putStringSet(key, done).apply()
            created = true
        }
        prefs.edit().putStringSet(key, done).apply()
        return created
    }

    /** Full sync used by the app after login and by the daily worker. Returns the fresh tables. */
    suspend fun run(ctx: Context, repo: Repository, privacy: Boolean, outbox: LocalOutbox = LocalOutbox(ctx, repo)): Map<String, List<JsonObject>> {
        var tables = fetchTables(repo)
        if (materializeRecurring(ctx, outbox, liveTables(tables)["transactions"].orEmpty())) {
            outbox.flush()
            tables = fetchTables(repo)
        }
        val live = liveTables(tables)
        Reminders.check(ctx, live)
        SmartAlerts.evaluate(ctx, live)
        FinTrackWidgetProvider.refresh(ctx, live, privacy)
        return live
    }
}

/** Runs once a day in the background, so reminders and recurring copies happen even if the app is not opened. */
class DailySyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            val repo = Repository(applicationContext)
            if (repo.session == null) return Result.success()
            val outbox = LocalOutbox(applicationContext, repo)
            Sync.run(applicationContext, repo, privacy = false, outbox = outbox)
            outbox.flush()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<DailySyncWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("fintrack-daily", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
