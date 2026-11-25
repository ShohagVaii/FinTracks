package com.fintracks.app.ui

import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.UUID

/** Category name of the expense that a bike entry creates. */
const val BIKE_CAT = "বাইক"

/** One fuel fill. The matching expense transaction has source "bike" and source_ref = [id]. odo is empty for entries made from the + form. */
@Serializable
data class FuelEntry(
    val id: String,
    val date: String,
    val odo: Double?,
    val total: Double,
    val liters: Double? = null,
    val pricePerL: Double? = null,
    val station: String = "",
    val full: Boolean = true,
    val note: String = "",
    val txId: String = ""
)

/** One maintenance item (mobil, service, parts, tyre, chain, other) with an optional next-due mark. */
@Serializable
data class MaintEntry(
    val id: String,
    val date: String,
    val kind: String,
    val item: String,
    val cost: Double,
    val odo: Double? = null,
    val nextKm: Double? = null,
    val nextDate: String = "",
    val note: String = "",
    val txId: String = ""
)

fun bikeKinds() = listOf(
    "mobil" to tr("মোবিল", "Oil"),
    "service" to tr("সার্ভিস", "Service"),
    "parts" to tr("পার্টস", "Parts"),
    "tyre" to tr("টায়ার", "Tyre"),
    "chain" to tr("চেইন", "Chain"),
    "other" to tr("অন্যান্য", "Other")
)

fun bikeKindLabel(kind: String): String = bikeKinds().firstOrNull { it.first == kind }?.second ?: kind

private fun JsonObject.optNum(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

internal fun bikeNum(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

/** Synced rows -> the entry models the screens and alert rules use. */
fun fuelOf(rows: List<JsonObject>): List<FuelEntry> = rows.map { r ->
    FuelEntry(
        id = r.id(), date = r.str("tx_date").take(10), odo = r.optNum("odo"), total = r.num("total"),
        liters = r.optNum("liters"), pricePerL = r.optNum("price_per_l"), station = r.str("station"),
        full = r.bool("full_tank"), note = r.str("note"), txId = r.str("tx_id")
    )
}

fun maintOf(rows: List<JsonObject>): List<MaintEntry> = rows.map { r ->
    MaintEntry(
        id = r.id(), date = r.str("tx_date").take(10), kind = r.str("kind"), item = r.str("item"),
        cost = r.num("cost"), odo = r.optNum("odo"), nextKm = r.optNum("next_km"),
        nextDate = r.str("next_date").take(10), note = r.str("note"), txId = r.str("tx_id")
    )
}

fun fuelRow(id: String, date: String, odo: Double?, total: Double, liters: Double?, price: Double?,
            station: String, full: Boolean, note: String, txId: String): JsonObject = row(
    "id" to id, "tx_date" to date, "odo" to (odo ?: JsonNull), "total" to total,
    "liters" to (liters ?: JsonNull), "price_per_l" to (price ?: JsonNull),
    "station" to station, "full_tank" to full, "note" to note,
    "tx_id" to txId.ifEmpty { null }
)

fun maintRow(id: String, date: String, kind: String, item: String, cost: Double, odo: Double?,
             nextKm: Double?, nextDate: String, note: String, txId: String): JsonObject = row(
    "id" to id, "tx_date" to date, "kind" to kind, "item" to item, "cost" to cost,
    "odo" to (odo ?: JsonNull), "next_km" to (nextKm ?: JsonNull),
    "next_date" to (nextDate.takeIf { it.isNotEmpty() } ?: JsonNull),
    "note" to note, "tx_id" to txId.ifEmpty { null }
)

/** Entries saved on this phone before the sync. Uploaded once, then the local copy is cleared. */
object BikeLegacy {
    private const val PREFS = "fintrack_bike"
    private val json = Json { ignoreUnknownKeys = true }

    fun hasAny(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("fuel", "[]") != "[]" || p.getString("maint", "[]") != "[]"
    }

    /** Rows for the synced tables; the expense transactions already reached the server with normal sync. */
    fun writes(ctx: Context): List<Triple<String, JsonObject, String?>> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val f = runCatching { json.decodeFromString(ListSerializer(FuelEntry.serializer()), p.getString("fuel", "[]") ?: "[]") }.getOrDefault(emptyList())
        val m = runCatching { json.decodeFromString(ListSerializer(MaintEntry.serializer()), p.getString("maint", "[]") ?: "[]") }.getOrDefault(emptyList())
        return f.map { e ->
            Triple("bike_fuel_entries", fuelRow(e.id, e.date, e.odo, e.total, e.liters, e.pricePerL, e.station, e.full, e.note, e.txId), null)
        } + m.map { e ->
            Triple("bike_maintenance_entries", maintRow(e.id, e.date, e.kind, e.item, e.cost, e.odo, e.nextKm, e.nextDate, e.note, e.txId), null)
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("fuel").remove("maint").apply()
    }
}

