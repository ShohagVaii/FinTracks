package com.fintracks.app.ui

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.row
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Category name of the expense that a bike entry creates. */
const val BIKE_CAT = "বাইক"

/** One fuel fill. The matching expense transaction has source "bike" and source_ref = [id]. */
@Serializable
data class FuelEntry(
    val id: String,
    val date: String,
    val odo: Double,
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

/**
 * Bike entries kept on this phone (no new table, so no schema change). Each entry's expense is a normal
 * transaction, so it syncs; [dropByTx] keeps the two in step when the transaction is deleted.
 */
object BikeStore {
    private const val PREFS = "fintrack_bike"
    private val json = Json { ignoreUnknownKeys = true }
    private val fuelSer = ListSerializer(FuelEntry.serializer())
    private val maintSer = ListSerializer(MaintEntry.serializer())
    private var prefs: SharedPreferences? = null

    var fuel by mutableStateOf<List<FuelEntry>>(emptyList())
        private set
    var maint by mutableStateOf<List<MaintEntry>>(emptyList())
        private set

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        fuel = runCatching { json.decodeFromString(fuelSer, p.getString("fuel", "[]") ?: "[]") }.getOrDefault(emptyList())
        maint = runCatching { json.decodeFromString(maintSer, p.getString("maint", "[]") ?: "[]") }.getOrDefault(emptyList())
    }

    private fun persistFuel(list: List<FuelEntry>) {
        fuel = list
        prefs?.edit()?.putString("fuel", json.encodeToString(fuelSer, list))?.apply()
    }

    private fun persistMaint(list: List<MaintEntry>) {
        maint = list
        prefs?.edit()?.putString("maint", json.encodeToString(maintSer, list))?.apply()
    }

    fun addFuel(e: FuelEntry) = persistFuel(fuel + e)
    fun deleteFuel(id: String) = persistFuel(fuel.filterNot { it.id == id })
    fun addMaint(e: MaintEntry) = persistMaint(maint + e)
    fun deleteMaint(id: String) = persistMaint(maint.filterNot { it.id == id })

    /** The transaction was deleted (anywhere): drop the bike entry it came from. */
    fun dropByTx(txId: String) {
        if (fuel.any { it.txId == txId }) persistFuel(fuel.filterNot { it.txId == txId })
        if (maint.any { it.txId == txId }) persistMaint(maint.filterNot { it.txId == txId })
    }
}

/** Mileage per full-tank pair: km between two full fills ÷ litres bought in that stretch (the later fill included). */
fun mileagePairs(fuel: List<FuelEntry>): List<Double> {
    val f = fuel.sortedBy { it.odo }
    val full = f.indices.filter { f[it].full }
    val out = mutableListOf<Double>()
    for (k in 1 until full.size) {
        val a = full[k - 1]
        val b = full[k]
        val km = f[b].odo - f[a].odo
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
    val odos = fuel.map { it.odo } + maint.mapNotNull { it.odo }
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

/** The expense transaction for a bike entry. Same shape as a normal expense, marked with source "bike". */
private fun bikeTxRow(state: UiState, txId: String, amount: Double, date: String, note: String, sourceRef: String): JsonObject =
    row(
        "id" to txId, "type" to "Expense", "amount" to amount, "currency" to state.currency,
        "category" to BIKE_CAT, "note" to note, "tx_date" to date, "payment_mode" to "Cash", "mood" to "",
        "account_id" to JsonNull, "to_account_id" to JsonNull, "is_recurring" to false,
        "tags" to JsonArray(emptyList()), "source" to "bike", "source_ref" to sourceRef
    )

@Composable
fun BikeScreen(state: UiState, vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf("Fuel") }
    var adding by remember { mutableStateOf(false) }
    val today = todayIso()
    val fuel = BikeStore.fuel
    val maint = BikeStore.maint
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
            fuel.sortedByDescending { it.odo }.forEach { e ->
                AppCard {
                    EntryRow(
                        title = taka(e.total, privacy),
                        sub = listOfNotNull(
                            displayDate(e.date),
                            bnDigits("%.0f km".format(e.odo)),
                            e.liters?.let { bnDigits("%.2f L".format(it)) },
                            e.station.ifBlank { null },
                            if (e.full) tr("ফুল ট্যাংক", "Full tank") else null
                        ).joinToString(" · "),
                        onDelete = {
                            BikeStore.deleteFuel(e.id)
                            if (e.txId.isNotEmpty()) vm.remove("transactions", e.txId)
                        }
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
                            bikeKinds().firstOrNull { it.first == m.kind }?.second,
                            m.item.ifBlank { null },
                            m.odo?.let { bnDigits("%.0f km".format(it)) }
                        ).joinToString(" · "),
                        onDelete = {
                            BikeStore.deleteMaint(m.id)
                            if (m.txId.isNotEmpty()) vm.remove("transactions", m.txId)
                        }
                    )
                }
            }
        }
    }

    if (adding) {
        if (tab == "Fuel") FuelEntryDialog(state, vm) { adding = false }
        else MaintEntryDialog(state, vm) { adding = false }
    }
}

@Composable
private fun EntryRow(title: String, sub: String, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        IconButton(modifier = Modifier.pressScale(), onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = tr("মুছুন", "Delete"))
        }
    }
}

@Composable
private fun FuelEntryDialog(state: UiState, vm: AppViewModel, onDone: () -> Unit) {
    var date by remember { mutableStateOf(todayIso()) }
    var odo by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var station by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
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
                SwitchRow(tr("ফুল ট্যাংক", "Full tank"), full) { full = it }
                TextIn(tr("নোট", "Note"), note, { note = it })
                error?.let { Text(it, color = readableOn(expenseRed), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val od = parseAmount(odo)
                when {
                    od == null || od < 0 -> error = tr("ওডোমিটার লিখুন", "Enter the odometer reading")
                    t == null || t <= 0 -> error = tr("টাকার পরিমাণ লিখুন", "Enter the amount")
                    !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
                    else -> {
                        val amount = t!!
                        val id = UUID.randomUUID().toString()
                        val txId = UUID.randomUUID().toString()
                        val noteText = listOf(station.trim(), note.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
                        vm.save("transactions", bikeTxRow(state, txId, amount, date, noteText, id), null)
                        BikeStore.addFuel(
                            FuelEntry(id, date, od!!, amount, effLiters, effPrice, station.trim(), full, note.trim(), txId)
                        )
                        onDone()
                    }
                }
            }) { Text(tr("সেভ", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(tr("বাতিল", "Cancel")) } }
    )
}

@Composable
private fun MaintEntryDialog(state: UiState, vm: AppViewModel, onDone: () -> Unit) {
    var date by remember { mutableStateOf(todayIso()) }
    var kind by remember { mutableStateOf("mobil") }
    var item by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var odo by remember { mutableStateOf("") }
    var nextKm by remember { mutableStateOf("") }
    var nextDate by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
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
                error?.let { Text(it, color = readableOn(expenseRed), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = parseAmount(cost)
                when {
                    c == null || c <= 0 -> error = tr("খরচ লিখুন", "Enter the cost")
                    !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
                    else -> {
                        val amount = c!!
                        val id = UUID.randomUUID().toString()
                        val txId = UUID.randomUUID().toString()
                        val label = item.trim().ifEmpty { bikeKinds().firstOrNull { it.first == kind }?.second.orEmpty() }
                        vm.save("transactions", bikeTxRow(state, txId, amount, date, label, id), null)
                        BikeStore.addMaint(
                            MaintEntry(
                                id = id, date = date, kind = kind, item = item.trim(), cost = amount,
                                odo = parseAmount(odo), nextKm = parseAmount(nextKm),
                                nextDate = if (isValidIsoDate(nextDate)) nextDate else "",
                                note = note.trim(), txId = txId
                            )
                        )
                        onDone()
                    }
                }
            }) { Text(tr("সেভ", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(tr("বাতিল", "Cancel")) } }
    )
}

