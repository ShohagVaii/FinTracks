package com.fintracks.app.ui

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Tables whose deletes go to the Trash. Notes and cash counts keep their own trash on the server. */
internal val SERVER_TRASH_TABLES: Set<String> = setOf("notes", "cash_counts")
internal val LOCAL_TRASH_TABLES: Set<String> get() = TRASH_TABLES - SERVER_TRASH_TABLES

/**
 * The Trash lives only on this phone, one file per signed-in user. A deleted row is removed from the
 * server and its copy (with deleted_at) is kept here until it is restored or purged after 30 days.
 */
internal object LocalTrash {
    private fun file(ctx: Context, uid: String) = File(ctx.filesDir, "local_trash_${uid.ifEmpty { "none" }}.json")

    @Synchronized
    fun read(ctx: Context, uid: String): Map<String, List<JsonObject>> = runCatching {
        val f = file(ctx, uid)
        if (!f.exists()) return emptyMap()
        Json.parseToJsonElement(f.readText()).jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonObject } }
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun write(ctx: Context, uid: String, data: Map<String, List<JsonObject>>) {
        val root = JsonObject(data.filterValues { it.isNotEmpty() }.mapValues { (_, rows) -> JsonArray(rows) })
        file(ctx, uid).writeText(root.toString())
    }

    fun add(ctx: Context, uid: String, table: String, row: JsonObject) {
        val m = read(ctx, uid).toMutableMap()
        m[table] = m[table].orEmpty().filterNot { it.id() == row.id() } + row
        write(ctx, uid, m)
    }

    fun remove(ctx: Context, uid: String, table: String, id: String) {
        val m = read(ctx, uid).toMutableMap()
        m[table] = m[table].orEmpty().filterNot { it.id() == id }
        write(ctx, uid, m)
    }

    fun removeStamps(ctx: Context, uid: String, stamps: Set<String>) {
        val m = read(ctx, uid).mapValues { (_, rows) -> rows.filterNot { it.str("deleted_at") in stamps } }
        write(ctx, uid, m)
    }
}
