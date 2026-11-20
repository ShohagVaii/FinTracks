package com.fintracks.app.ui

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant

/**
 * Full JSON backup of every user table. The PIN, the lock settings and the session are never included.
 * Import (restore) is validated before any write; see the roadmap.
 */
object Backup {
    const val FORMAT_VERSION = 1

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
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

    fun file(ctx: Context, state: UiState): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        return File(dir, "fintrack-backup-${todayIso()}.json").also { it.writeText(json(state), Charsets.UTF_8) }
    }
}
