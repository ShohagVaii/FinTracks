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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Result of one send run: drained = nothing left in the queue; dropped = refused by the server. */
data class FlushResult(val drained: Boolean, val dropped: Int)

/**
 * Local-first store for every table (see README in the Notes of AppViewModel).
 *
 * - Cache: the last server copy of every table, kept in a file per user, so screens work offline.
 * - Queue: every write is recorded here first, then sent in order. A network failure keeps the
 *   queue for the next attempt. A server refusal (for example an over-payment) drops that change and
 *   every later change of the same group, so a payment and its transaction never split.
 * - Overlay: queued changes are applied on top of any table list, so the screen shows them at once
 *   and a refresh from the server never hides them. A queued delete is a tombstone: a refresh cannot
 *   bring the row back before the delete is sent.
 */
class LocalOutbox(ctx: Context, private val repo: Repository) {
    private val dir = File(ctx.applicationContext.filesDir, "local").apply { mkdirs() }
    private val lock = Mutex()
    private var loadedKey: String? = null
    private var pending: List<JsonObject> = emptyList()
    private var cache: Map<String, List<JsonObject>> = emptyMap()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private fun key(): String = runCatching { repo.uid() }.getOrDefault("anon")

    private fun fileFor(k: String): File = File(dir, "outbox-$k.json")

    /** Loads queue and cache for the signed-in user (once per user). */
    @Synchronized
    private fun ensureLoaded() {
        val k = key()
        if (loadedKey == k) return
        loadedKey = k
        val text = runCatching { fileFor(k).takeIf { it.exists() }?.readText() }.getOrNull()
        val root = runCatching { Json.parseToJsonElement(text ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
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
        val target = fileFor(key())
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(target)
    }

    /** Clears memory; with [deleteFile] also removes this user's saved copy and queue from the phone. */
    @Synchronized
    fun reset(deleteFile: Boolean = false) {
        if (deleteFile) runCatching { fileFor(key()).delete() }
        pending = emptyList(); cache = emptyMap(); loadedKey = null
        _pendingCount.value = 0
    }

    /** Adds changes to the end of the queue and saves them to the phone. */
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

    /** Stores the latest server copy of the given tables for offline use. */
    @Synchronized
    fun remember(tables: Map<String, List<JsonObject>>) {
        ensureLoaded()
        cache = tables
        persist()
    }

    /** The saved server copy (every table that was ever fetched), or empty when nothing was saved yet. */
    @Synchronized
    fun cached(): Map<String, List<JsonObject>> {
        ensureLoaded()
        return cache
    }

    private enum class Outcome { SENT, REFUSED, RETRY }

    /** Sends one run of queued changes. A run is one upsert of several rows, or a single update/delete. */
    private suspend fun sendRun(run: List<JsonObject>) {
        val first = run.first()
        val table = first.str("table")
        when (first.str("op")) {
            "insert" -> repo.upsert(table, run.map { it["body"] as JsonObject })
            "update" -> repo.update(table, first.str("id"), first["body"] as JsonObject)
            else -> repo.delete(table, first.str("id"))
        }
    }

    private suspend fun trySend(run: List<JsonObject>): Outcome = try {
        sendRun(run)
        Outcome.SENT
    } catch (e: CancellationException) {
        throw e
    } catch (e: SupabaseException) {
        // 401 (session), 408, 429 and 5xx are retried later; other 4xx mean the server refused the change.
        if (e.code in 400..499 && e.code != 401 && e.code != 408 && e.code != 429) Outcome.REFUSED else Outcome.RETRY
    } catch (e: Exception) {
        Outcome.RETRY
    }

    /** Consecutive inserts into one table (no group) are sent together, up to 400 rows per request. */
    private fun insertRun(queue: List<JsonObject>, start: Int): List<JsonObject> {
        val first = queue[start]
        if (first.str("op") != "insert" || first.str("group").isNotEmpty()) return listOf(first)
        var end = start + 1
        while (end < queue.size && end - start < 400) {
            val o = queue[end]
            if (o.str("op") != "insert" || o.str("table") != first.str("table") || o.str("group").isNotEmpty()) break
            end++
        }
        return queue.subList(start, end)
    }

    /**
     * Sends the queue in order. Stops at the first network failure. A refused run is re-sent row by row,
     * so only the refused row is dropped; a refused change also drops the rest of its group.
     */
    suspend fun flush(): FlushResult = lock.withLock {
        val queue = synchronized(this) { ensureLoaded(); pending }
        var done = 0
        var dropped = 0
        var drained = true
        var rejectedGroup: String? = null
        var i = 0
        while (i < queue.size) {
            val op = queue[i]
            val group = op.str("group")
            if (group.isNotEmpty() && group == rejectedGroup) { done++; dropped++; i++; continue }
            val run = insertRun(queue, i)
            when (trySend(run)) {
                Outcome.SENT -> { done += run.size; i += run.size }
                Outcome.RETRY -> { drained = false; break }
                Outcome.REFUSED -> {
                    if (run.size == 1) {
                        done++; dropped++; i++
                        if (group.isNotEmpty()) rejectedGroup = group
                    } else {
                        // Only this one row is sent alone; the rest of the run is tried again from the next row.
                        when (trySend(listOf(op))) {
                            Outcome.SENT -> { done++; i++ }
                            Outcome.REFUSED -> { done++; dropped++; i++ }
                            Outcome.RETRY -> { drained = false; break }
                        }
                    }
                }
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

    companion object {
        /** Builds one queued change. insert/update carry the full row (for display) and the body (for the server). */
        fun op(kind: String, table: String, id: String, body: JsonObject? = null, full: JsonObject? = null, group: String = ""): JsonObject {
            val map = linkedMapOf<String, JsonElement>(
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
