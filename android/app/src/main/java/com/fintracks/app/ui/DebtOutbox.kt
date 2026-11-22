package com.fintracks.app.ui

import android.content.Context
import com.fintracks.app.data.Repository
import com.fintracks.app.data.SupabaseException
import com.fintracks.app.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The debt tables that are cached and queued offline. */
val DEBT_TABLES = setOf("debt_people", "debts", "debt_payments")

/** Result of one send attempt: drained = nothing left in the queue; dropped = rejected by the server. */
data class FlushResult(val drained: Boolean, val dropped: Int)

/**
 * Offline outbox for debts (ধার). Every debt write is kept in a per-user queue on this phone,
 * shown at once through [overlay], and sent in order. A network failure keeps the queue for the
 * next attempt; a server rejection (for example an over-payment) drops that change and every
 * later change of the same group, so a payment and its transaction never split.
 * The last server copy of the three debt tables is cached, so the debt screen can open offline.
 */
class DebtOutbox(ctx: Context, private val repo: Repository) {
    private val prefs = ctx.applicationContext.getSharedPreferences("fintrack_debts", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var loadedKey: String? = null
    private var pending: List<JsonObject> = emptyList()
    private var cache: Map<String, List<JsonObject>> = emptyMap()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private fun key(): String = "debts-" + runCatching { repo.uid() }.getOrDefault("anon")

    /** Loads queue and cache for the signed-in user (once per user). */
    @Synchronized
    private fun ensureLoaded() {
        val k = key()
        if (loadedKey == k) return
        loadedKey = k
        val raw = prefs.getString(k, null)
        val root = runCatching { Json.parseToJsonElement(raw ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
        pending = (root["pending"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        cache = (root["cache"] as? JsonObject).orEmpty().mapValues { (_, v) ->
            (v as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }
        _pendingCount.value = pending.size
    }

    @Synchronized
    private fun persist() {
        val root = JsonObject(
            mapOf(
                "pending" to JsonArray(pending),
                "cache" to JsonObject(cache.mapValues { JsonArray(it.value) })
            )
        )
        prefs.edit().putString(key(), root.toString()).apply()
    }

    /** Clears the in-memory copy (used by the app reset). */
    @Synchronized
    fun reset() {
        pending = emptyList(); cache = emptyMap(); loadedKey = null
        _pendingCount.value = 0
    }

    /** Adds changes to the end of the queue. */
    @Synchronized
    fun enqueue(ops: List<JsonObject>) {
        ensureLoaded()
        pending = pending + ops
        persist()
        _pendingCount.value = pending.size
    }

    /** Applies every queued change on top of the given tables (idempotent, so it is safe to repeat). */
    @Synchronized
    fun overlay(tables: Map<String, List<JsonObject>>): Map<String, List<JsonObject>> {
        ensureLoaded()
        if (pending.isEmpty()) return tables
        val out = tables.toMutableMap()
        for (op in pending) {
            val table = op.str("table")
            val id = op.str("id")
            val rows = out[table].orEmpty().filterNot { it.str("id") == id }
            out[table] = if (op.str("op") == "delete") rows else rows + (op["row"] as JsonObject)
        }
        return out
    }

    /** Stores the latest server copy of the debt tables for offline use. */
    @Synchronized
    fun remember(tables: Map<String, List<JsonObject>>) {
        ensureLoaded()
        cache = DEBT_TABLES.associateWith { tables[it].orEmpty() }
        persist()
    }

    /** The cached debt tables, or empty lists when nothing was cached yet. */
    @Synchronized
    fun cached(): Map<String, List<JsonObject>> {
        ensureLoaded()
        return DEBT_TABLES.associateWith { cache[it].orEmpty() }
    }

    /** Sends the queue in order. Stops at the first network failure; drops server rejections with their group. */
    suspend fun flush(): FlushResult = lock.withLock {
        val queue = synchronized(this) { ensureLoaded(); pending }
        var done = 0
        var dropped = 0
        var drained = true
        var rejectedGroup: String? = null
        for (op in queue) {
            val group = op.str("group")
            if (group.isNotEmpty() && group == rejectedGroup) { done++; dropped++; continue }
            try {
                send(op)
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: SupabaseException) {
                // 401 (session) and 408/429/5xx are retried later; other 4xx mean the server refused the change.
                val refused = e.code in 400..499 && e.code != 401 && e.code != 408 && e.code != 429
                if (!refused) { drained = false; break }
                done++; dropped++
                if (group.isNotEmpty()) rejectedGroup = group
            } catch (e: Exception) {
                drained = false
                break
            }
        }
        synchronized(this) {
            ensureLoaded()
            pending = pending.drop(done)
            persist()
            _pendingCount.value = pending.size
        }
        FlushResult(drained && synchronized(this) { pending.isEmpty() }, dropped)
    }

    private suspend fun send(op: JsonObject) {
        val table = op.str("table")
        val id = op.str("id")
        when (op.str("op")) {
            "insert" -> repo.upsert(table, listOf(op["body"] as JsonObject))
            "update" -> repo.update(table, id, op["body"] as JsonObject)
            else -> repo.delete(table, id)
        }
    }

    companion object {
        /** Builds one queued change. insert/update carry the full row for display and the body for the server. */
        fun op(kind: String, table: String, id: String, body: JsonObject? = null, full: JsonObject? = null, group: String = ""): JsonObject {
            val map = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                "op" to JsonPrimitive(kind),
                "table" to JsonPrimitive(table),
                "id" to JsonPrimitive(id)
            )
            if (body != null) map["body"] = body
            if (full != null) map["row"] = full
            if (group.isNotEmpty()) map["group"] = JsonPrimitive(group)
            return JsonObject(map)
        }
    }
}
