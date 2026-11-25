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

private fun bikeNum(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

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

/** Mileage per full-tank pair: km between two full fills ÷ litres bought in that stretch (the later fill included). */
fun mileagePairs(fuel: List<FuelEntry>): List<Double> {
    // Entries without an odometer (from the + form) are not part of any mileage pair.
    val f = fuel.filter { it.odo != null }.sortedBy { it.odo ?: 0.0 }
    val full = f.indices.filter { f[it].full }
    val out = mutableListOf<Double>()
    for (k in 1 until full.size) {
        val a = full[k - 1]
        val b = full[k]
        val km = (f[b].odo ?: 0.0) - (f[a].odo ?: 0.0)
        val litres = (a + 1..b).sumOf { f[it].liters ?: 0.0 }
        if (km > 0 && litres > 0) out += km / litres
    }
    return out
}

class BikeStats(
    val monthCost: Double,
    val totalCost: Double,
    val kmDriven: Double,
    val latestOdo: Double,
    val costPerKm: Double?,
    val lastMileage: Double?,
    val avgMileage: Double?,
    val nextKm: Pair<MaintEntry, Double>?,
    val nextDate: MaintEntry?
)

fun bikeStats(fuel: List<FuelEntry>, maint: List<MaintEntry>, today: String): BikeStats {
    val month = today.take(7)
    val totalCost = fuel.sumOf { it.total } + maint.sumOf { it.cost }
    val monthCost = fuel.filter { it.date.startsWith(month) }.sumOf { it.total } +
        maint.filter { it.date.startsWith(month) }.sumOf { it.cost }
    val odos = fuel.mapNotNull { it.odo } + maint.mapNotNull { it.odo }
    val km = if (odos.size >= 2) (odos.maxOrNull() ?: 0.0) - (odos.minOrNull() ?: 0.0) else 0.0
    val latest = odos.maxOrNull() ?: 0.0
    val pairs = mileagePairs(fuel)
    val nextKm = maint.mapNotNull { m -> m.nextKm?.let { m to it } }
        .filter { it.second >= latest }
        .minByOrNull { it.second }
    val nextDate = maint.filter { it.nextDate.isNotEmpty() && it.nextDate >= today }.minByOrNull { it.nextDate }
    return BikeStats(
        monthCost = monthCost,
        totalCost = totalCost,
        kmDriven = km,
        latestOdo = latest,
        costPerKm = if (km > 0) totalCost / km else null,
        lastMileage = pairs.lastOrNull(),
        avgMileage = if (pairs.isEmpty()) null else pairs.average(),
        nextKm = nextKm,
        nextDate = nextDate
    )
}

@Composable
fun BikeScreen(state: UiState, vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf("Fuel") }
    var adding by remember { mutableStateOf(false) }
    var editFuel by remember { mutableStateOf<FuelEntry?>(null) }
    var editMaint by remember { mutableStateOf<MaintEntry?>(null) }
    val today = todayIso()
    val ctx = LocalContext.current
    val fuel = fuelOf(state.rows(BikeLink.FUEL))
    val maint = maintOf(state.rows(BikeLink.MAINT))
    // Phone-only entries from before the sync go up once, when the synced tables are there and empty.
    LaunchedEffect(state.tables.containsKey(BikeLink.FUEL)) {
        if (state.tables.containsKey(BikeLink.FUEL) && fuel.isEmpty() && maint.isEmpty() && BikeLegacy.hasAny(ctx)) {
            vm.batch(BikeLegacy.writes(ctx))
            BikeLegacy.clear(ctx)
        }
    }
    val s = bikeStats(fuel, maint, today)
    val privacy = state.privacy

    Page {
        ChoiceChips(listOf("Fuel" to tr("তেল", "Fuel"), "Maint" to tr("মেইনটেন্যান্স", "Maintenance")), tab) { tab = it }

        AppCard {
            KeyValue(tr("এই মাসে বাইক খরচ", "Bike cost this month"), taka(s.monthCost, privacy))
            KeyValue(tr("সর্বমোট বাইক খরচ", "Total bike cost"), taka(s.totalCost, privacy))
            KeyValue(tr("৳/km খরচ", "Cost per km"), s.costPerKm?.let { taka(it, privacy) } ?: "-")
            KeyValue(tr("সর্বশেষ মাইলেজ", "Last mileage"), s.lastMileage?.let { bnDigits("%.1f km/L".format(it)) } ?: "-")
            KeyValue(tr("গড় মাইলেজ", "Average mileage"), s.avgMileage?.let { bnDigits("%.1f km/L".format(it)) } ?: "-")
            val due = listOfNotNull(
                s.nextKm?.let { bnDigits("%.0f km".format(it.second)) + " (" + bnDigits("%.0f".format(it.second - s.latestOdo)) + " km)" },
                s.nextDate?.let { bnDigits(displayDate(it.nextDate)) }
            )
            KeyValue(tr("পরবর্তী সার্ভিস", "Next service"), if (due.isEmpty()) "-" else due.joinToString("  ·  "))
        }

        AddButton(if (tab == "Fuel") tr("নতুন তেল এন্ট্রি", "New fuel entry") else tr("নতুন মেইনটেন্যান্স", "New maintenance")) { adding = true }

        if (tab == "Fuel") {
            if (fuel.isEmpty()) EmptyHint(tr("কোনো তেল এন্ট্রি নেই", "No fuel entries"))
            fuel.sortedByDescending { it.odo ?: 0.0 }.forEach { e ->
                AppCard {
                    EntryRow(
                        title = taka(e.total, privacy),
                        sub = listOfNotNull(
                            displayDate(e.date),
                            e.odo?.let { bnDigits("%.0f km".format(it)) },
                            e.liters?.let { bnDigits("%.2f L".format(it)) },
                            e.station.ifBlank { null },
                            if (e.full) tr("ফুল ট্যাংক", "Full tank") else null
                        ).joinToString(" · "),
                        onEdit = { editFuel = e },
                        onDelete = { vm.remove(BikeLink.FUEL, e.id) }
                    )
                }
            }
        } else {
            if (maint.isEmpty()) EmptyHint(tr("কোনো মেইনটেন্যান্স নেই", "No maintenance entries"))
            maint.sortedByDescending { it.date }.forEach { m ->
                AppCard {
                    EntryRow(
                        title = taka(m.cost, privacy),
                        sub = listOfNotNull(
                            displayDate(m.date),
                            bikeKindLabel(m.kind),
                            m.item.ifBlank { null },
                            m.odo?.let { bnDigits("%.0f km".format(it)) }
                        ).joinToString(" · "),
                        onEdit = { editMaint = m },
                        onDelete = { vm.remove(BikeLink.MAINT, m.id) }
                    )
                }
            }
        }
    }

    if (adding) {
        if (tab == "Fuel") FuelEntryDialog(state, vm, null) { adding = false }
        else MaintEntryDialog(state, vm, null) { adding = false }
    }
    editFuel?.let { e -> FuelEntryDialog(state, vm, e) { editFuel = null } }
    editMaint?.let { m -> MaintEntryDialog(state, vm, m) { editMaint = null } }
}

/** Long press opens Edit / Delete. The trash icon deletes at once, as before. */
@Composable
private fun EntryRow(title: String, sub: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onLongPress = { menu = true }) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        IconButton(modifier = Modifier.pressScale(), onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = tr("মুছুন", "Delete"))
        }
    }
    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text(title, fontWeight = FontWeight.Bold) },
            confirmButton = {
                Row {
                    TextButton(onClick = { menu = false; onEdit() }) { Text(tr("সম্পাদনা", "Edit")) }
                    TextButton(onClick = { menu = false; onDelete() }) { Text(tr("মুছুন", "Delete")) }
                }
            },
            dismissButton = { TextButton(onClick = { menu = false }) { Text(tr("বাতিল", "Cancel")) } }
        )
    }
}

/** New fuel entry (existing = null) or edit of one. Amount, date and note also move to its expense. */
@Composable
private fun FuelEntryDialog(state: UiState, vm: AppViewModel, existing: FuelEntry?, onDone: () -> Unit) {
    var date by remember { mutableStateOf(existing?.date ?: todayIso()) }
    var odo by remember { mutableStateOf(existing?.odo?.let { bikeNum(it) } ?: "") }
    var total by remember { mutableStateOf(if (existing != null) bikeNum(existing.total) else "") }
    var liters by remember { mutableStateOf(existing?.liters?.let { bikeNum(it) } ?: "") }
    var price by remember { mutableStateOf(existing?.pricePerL?.let { bikeNum(it) } ?: "") }
    var station by remember { mutableStateOf(existing?.station ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    // Litres come from total ÷ pump price when litres are blank; the price comes from total ÷ litres otherwise.
    val t = parseAmount(total)
    val lt = parseAmount(liters)
    val pr = parseAmount(price)
    val effLiters = lt ?: if (pr != null && pr > 0 && t != null) t / pr else null
    val effPrice = pr ?: if (lt != null && lt > 0 && t != null) t / lt else null

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(tr("তেল এন্ট্রি", "Fuel entry"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(tr("তারিখ", "Date"), date, { date = it })
                TextIn(tr("ওডোমিটার (km)", "Odometer (km)"), odo, { odo = it }, number = true)
                TextIn(tr("টাকা (৳)", "Amount (৳)"), total, { total = it }, number = true)
                TextIn(tr("লিটার (ঐচ্ছিক)", "Litres (optional)"), liters, { liters = it }, number = true)
                TextIn(tr("পাম্পের দর/লিটার (ঐচ্ছিক)", "Price per litre (optional)"), price, { price = it }, number = true)
                effLiters?.let { Text(tr("লিটার", "Litres") + ": " + bnDigits("%.2f".format(it)), fontSize = 12.sp) }
                effPrice?.let { Text(tr("দর/লিটার", "Per litre") + ": ৳" + bnDigits("%.2f".format(it)), fontSize = 12.sp) }
                TextIn(tr("পাম্পের নাম", "Station"), station, { station = it })
                TextIn(tr("নোট", "Note"), note, { note = it })
                error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val od = parseAmount(odo)
                // An entry that has no odometer (from the + form) may stay blank when it is edited.
                val odoOk = (od != null && od >= 0) || (existing != null && existing.odo == null && odo.isBlank())
                when {
                    !odoOk -> error = tr("ওডোমিটার লিখুন", "Enter the odometer reading")
                    t == null || t <= 0 -> error = tr("টাকার পরিমাণ লিখুন", "Enter the amount")
                    !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
                    existing == null -> {
                        val id = UUID.randomUUID().toString()
                        val txId = UUID.randomUUID().toString()
                        val noteText = listOf(station.trim(), note.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
                        val amount = t!!
                        vm.batch(listOf(
                            Triple(BikeLink.TX, BikeLink.txRow(state.currency, txId, amount, date, noteText, id), null),
                            Triple(BikeLink.FUEL, fuelRow(id, date, od, amount, effLiters, effPrice, station.trim(), true, note.trim(), txId), null)
                        ))
                        onDone()
                    }
                    else -> {
                        // Full tank is not asked any more; an edit keeps what the entry had.
                        val body = fuelRow(existing.id, date, od, t!!, effLiters, effPrice, station.trim(), existing.full, note.trim(), existing.txId)
                        vm.saveBikeEntry(BikeLink.FUEL, body, existing.id)
                        onDone()
                    }
                }
            }) { Text(tr("সেভ", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(tr("বাতিল", "Cancel")) } }
    )
}

/** New maintenance item (existing = null) or edit of one. Cost, date and label also move to its expense. */
@Composable
private fun MaintEntryDialog(state: UiState, vm: AppViewModel, existing: MaintEntry?, onDone: () -> Unit) {
    var date by remember { mutableStateOf(existing?.date ?: todayIso()) }
    var kind by remember { mutableStateOf(existing?.kind ?: "mobil") }
    var item by remember { mutableStateOf(existing?.item ?: "") }
    var cost by remember { mutableStateOf(if (existing != null) bikeNum(existing.cost) else "") }
    var odo by remember { mutableStateOf(existing?.odo?.let { bikeNum(it) } ?: "") }
    var nextKm by remember { mutableStateOf(existing?.nextKm?.let { bikeNum(it) } ?: "") }
    var nextDate by remember { mutableStateOf(existing?.nextDate ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(tr("মেইনটেন্যান্স", "Maintenance"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(tr("তারিখ", "Date"), date, { date = it })
                ChoiceChips(bikeKinds(), kind) { kind = it }
                TextIn(tr("আইটেমের নাম", "Item"), item, { item = it })
                TextIn(tr("খরচ (৳)", "Cost (৳)"), cost, { cost = it }, number = true)
                TextIn(tr("ওডোমিটার (km, ঐচ্ছিক)", "Odometer (km, optional)"), odo, { odo = it }, number = true)
                TextIn(tr("পরবর্তী সার্ভিস (km, ঐচ্ছিক)", "Next service (km, optional)"), nextKm, { nextKm = it }, number = true)
                DateField(tr("পরবর্তী সার্ভিস তারিখ", "Next service date"), nextDate, { nextDate = it }, optional = true)
                TextIn(tr("নোট", "Note"), note, { note = it })
                error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = parseAmount(cost)
                when {
                    c == null || c <= 0 -> error = tr("খরচ লিখুন", "Enter the cost")
                    !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
                    existing == null -> {
                        val id = UUID.randomUUID().toString()
                        val txId = UUID.randomUUID().toString()
                        val label = item.trim().ifEmpty { bikeKindLabel(kind) }
                        vm.batch(listOf(
                            Triple(BikeLink.TX, BikeLink.txRow(state.currency, txId, c!!, date, label, id), null),
                            Triple(BikeLink.MAINT, maintRow(id, date, kind, item.trim(), c!!, parseAmount(odo),
                                parseAmount(nextKm), if (isValidIsoDate(nextDate)) nextDate else "", note.trim(), txId), null)
                        ))
                        onDone()
                    }
                    else -> {
                        val body = maintRow(existing.id, date, kind, item.trim(), c!!, parseAmount(odo),
                            parseAmount(nextKm), if (isValidIsoDate(nextDate)) nextDate else "", note.trim(), existing.txId)
                        vm.saveBikeEntry(BikeLink.MAINT, body, existing.id)
                        onDone()
                    }
                }
            }) { Text(tr("সেভ", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(tr("বাতিল", "Cancel")) } }
    )
}
