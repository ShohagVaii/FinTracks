package com.fintracks.app.ui

import android.content.Context
import com.fintracks.app.data.id
import com.fintracks.app.data.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant

/** A validated backup, ready to restore. Nothing is written until the user confirms. */
class BackupPlan(val tables: Map<String, List<JsonObject>>, val exportedAt: String) {
    val total: Int get() = tables.values.sumOf { it.size }
}

/**
 * Full JSON backup of every user table. The PIN, the lock settings and the session are never included.
 * Restore validates the whole file first; a bad file changes nothing.
 */
object Backup {
    const val FORMAT_VERSION = 1
    private const val MAX_ROWS = 50_000
    private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val codec = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun json(state: UiState): String {
        val root = buildJsonObject {
            put("app", "FinTrack")
            put("format_version", FORMAT_VERSION)
            put("exported_at", Instant.now().toString())
            put("currency", state.currency)
            putJsonObject("tables") {
                TABLES.forEach { (table, _) -> put(table, JsonArray(state.rows(table))) }
            }
        }
        return codec.encodeToString(JsonObject.serializer(), root)
    }

    fun file(ctx: Context, state: UiState): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        return File(dir, "fintrack-backup-${todayIso()}.json").also { it.writeText(json(state), Charsets.UTF_8) }
    }

    /** Checks the whole file. Throws IllegalArgumentException with a Bengali message when anything is wrong. */
    fun parse(text: String): BackupPlan {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException(tr("এটি সঠিক JSON ফাইল নয়", "This is not a valid JSON file"))
        }
        if (root.str("app") != "FinTrack") throw IllegalArgumentException("এটি FinTrack ব্যাকআপ ফাইল নয়")
        val version = (root["format_version"] as? JsonPrimitive)?.intOrNull ?: 0
        if (version < 1 || version > FORMAT_VERSION) {
            throw IllegalArgumentException(tr("এই ব্যাকআপের ফরম্যাট ($version) এই অ্যাপ সমর্থন করে না", "This backup format ($version) is not supported by this app"))
        }
        val tablesObj = root["tables"] as? JsonObject ?: throw IllegalArgumentException(tr("ব্যাকআপে টেবিল তথ্য নেই", "The backup has no table data"))
        val known = TABLES.map { it.first }.toSet()
        val out = LinkedHashMap<String, List<JsonObject>>()
        for ((name, value) in tablesObj) {
            if (name !in known) throw IllegalArgumentException(tr("অজানা টেবিল: $name", "Unknown table: $name"))
            val rows = (value as? JsonArray ?: throw IllegalArgumentException(tr("$name তথ্য সঠিক নয়", "$name data is invalid"))).map {
                it as? JsonObject ?: throw IllegalArgumentException(tr("$name-এ একটি সারি সঠিক নয়", "A row in $name is invalid"))
            }
            rows.forEach { row ->
                if (!UUID_RE.matches(row.id())) throw IllegalArgumentException(tr("$name-এ একটি সারির ID সঠিক নয়", "A row ID in $name is invalid"))
                if (name == "transactions" && row.str("type") !in setOf("Income", "Expense")) {
                    throw IllegalArgumentException(tr("transactions-এ একটি সারির ধরন (Income/Expense) সঠিক নয়", "A transactions row has an invalid type (Income/Expense)"))
                }
            }
            if (rows.map { it.id() }.toSet().size != rows.size) throw IllegalArgumentException(tr("$name-এ একই ID একাধিকবার আছে", "$name has duplicate IDs"))
            out[name] = rows
        }
        val plan = BackupPlan(out, root.str("exported_at"))
        if (plan.total == 0) throw IllegalArgumentException(tr("ব্যাকআপে কোনো ডেটা নেই", "The backup has no data"))
        if (plan.total > MAX_ROWS) throw IllegalArgumentException(tr("ব্যাকআপ খুব বড় ($MAX_ROWS সারির বেশি)", "Backup is too large (over $MAX_ROWS rows)"))
        return plan
    }
}

/** Parent tables first, so foreign keys always find their rows. Must list every table in TABLES. */
val RESTORE_ORDER: List<String> = listOf(
    "categories", "accounts", "credit_shops", "mess_members", "transactions", "budgets", "goals", "debts", "loans",
    "subscriptions", "investments", "credit_entries", "credit_payments", "bill_splits", "shopping_items",
    "mess_bazaars", "mess_meals", "mess_deposits", "cash_counts"
)
