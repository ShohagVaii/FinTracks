package com.fintracks.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.util.UUID

private class BikePending(val table: String, val id: String, val message: String)

@Composable
fun BikeScreen(state: UiState, vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf("Fuel") }
    var addFuel by remember { mutableStateOf(false) }
    var addMaint by remember { mutableStateOf(false) }
    var editFuel by remember { mutableStateOf<FuelEntry?>(null) }
    var editMaint by remember { mutableStateOf<MaintEntry?>(null) }
    var sheetFuel by remember { mutableStateOf<FuelEntry?>(null) }
    var sheetMaint by remember { mutableStateOf<MaintEntry?>(null) }
    var pending by remember { mutableStateOf<BikePending?>(null) }
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
    val mileById = s.mileages.associateBy { it.entry.id }
    val privacy = state.privacy

    Page {
        BikeSegments(tab) { tab = it }
        if (tab == "Fuel") {
            FuelStatsCard(s, privacy)
            s.nextKm?.let { (_, km) ->
                val left = km - s.latestOdo
                ServiceCard(left, dueDate(LocalDate.parse(today), left, s.dailyKm))
            }
            MileageChart(s.mileages)
            if (fuel.isEmpty()) EmptyHint(tr("কোনো তেল এন্ট্রি নেই", "No fuel entries"))
            fuel.sortedByDescending { it.odo ?: 0.0 }.forEach { e ->
                FuelRow(e, mileById[e.id], privacy) { sheetFuel = e }
            }
            AddButton(tr("+ তেল ভরুন", "+ Fill up")) { addFuel = true }
        } else {
            if (maint.isEmpty()) EmptyHint(tr("কোনো মেইনটেন্যান্স নেই", "No maintenance entries"))
            maint.sortedByDescending { it.date }.forEach { m ->
                MaintRow(m, privacy) { sheetMaint = m }
            }
            AddButton(tr("+ মেইনটেন্যান্স যোগ", "+ Add maintenance")) { addMaint = true }
        }
    }

    if (addFuel) FuelForm(state, vm, null) { addFuel = false }
    editFuel?.let { e -> FuelForm(state, vm, e) { editFuel = null } }
    if (addMaint) MaintForm(state, vm, null) { addMaint = false }
    editMaint?.let { m -> MaintForm(state, vm, m) { editMaint = null } }

    sheetFuel?.let { e ->
        EntryActionSheet(
            onEdit = { sheetFuel = null; editFuel = e },
            onDelete = { sheetFuel = null; pending = BikePending(BikeLink.FUEL, e.id, tr("তেলের এন্ট্রি আবর্জনায় যাবে", "The fuel entry goes to the Trash")) },
            onDismiss = { sheetFuel = null }
        )
    }
    sheetMaint?.let { m ->
        EntryActionSheet(
            onEdit = { sheetMaint = null; editMaint = m },
            onDelete = { sheetMaint = null; pending = BikePending(BikeLink.MAINT, m.id, tr("মেইনটেন্যান্স এন্ট্রি আবর্জনায় যাবে", "The maintenance entry goes to the Trash")) },
            onDismiss = { sheetMaint = null }
        )
    }
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(tr("মুছে ফেলবেন?", "Delete?"), fontWeight = FontWeight.Bold) },
            text = { Text(p.message) },
            confirmButton = {
                TextButton(onClick = { vm.remove(p.table, p.id); pending = null }) {
                    Text(tr("হ্যাঁ", "Yes"), color = Rose500, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(tr("বাতিল", "Cancel")) } }
        )
    }
}

/** Two-segment tab with a sliding indicator. */
@Composable
private fun BikeSegments(tab: String, onTab: (String) -> Unit) {
    val items = listOf("Fuel" to tr("তেল", "Fuel"), "Maint" to tr("মেইনটেন্যান্স", "Maintenance"))
    val target = if (tab == "Fuel") 0f else 1f
    val pos by animateFloatAsState(target, label = "bikeTab")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(faint(0.06f)).padding(4.dp)
    ) {
        val half = maxWidth / 2
        Box(
            Modifier.offset(x = half * pos).width(half).height(40.dp)
                .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
        )
        Row(Modifier.fillMaxWidth()) {
            items.forEach { (key, label) ->
                val on = tab == key
                Box(
                    Modifier.weight(1f).height(40.dp).clickable { onTab(key) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else muted
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier, color: Color = Color.Unspecified, approx: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = color, maxLines = 1)
        if (approx) ApproxTag()
    }
}

@Composable
private fun ApproxTag() {
    Text(
        tr("আনুমানিক", "Estimate"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Amber500, maxLines = 1,
        modifier = Modifier.border(1.dp, Amber500, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
private fun FuelStatsCard(s: BikeStats, privacy: Boolean) {
    AppCard {
        Row(Modifier.fillMaxWidth()) {
            StatCell(
                tr("গড় মাইলেজ (কিমি/লি)", "Average mileage (km/L)"),
                s.avgMileage?.let { bnDigits("%.1f".format(it)) } ?: "-",
                Modifier.weight(1f),
                color = if (s.avgApprox && s.avgMileage != null) Amber500 else Color.Unspecified,
                approx = s.avgApprox && s.avgMileage != null
            )
            StatCell(tr("মোট বাইক খরচ", "Total bike cost"), taka(s.totalCost, privacy), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            StatCell(tr("প্রতি কিমি খরচ", "Cost per km"), s.costPerKm?.let { taka(it, privacy) } ?: "-", Modifier.weight(1f))
            StatCell(tr("এই মাসে প্রতি কিমি", "This month per km"), s.monthPerKm?.let { taka(it, privacy) } ?: "-", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ServiceCard(kmLeft: Double, date: LocalDate?) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(20.dp).height(20.dp))
            Column(Modifier.weight(1f)) {
                val text = if (kmLeft >= 0) {
                    tr("পরের সার্ভিস: আর ", "Next service: ") + bnDigits("%.0f".format(kmLeft)) + tr(" কিমি বাকি", " km left")
                } else {
                    tr("সার্ভিস পেরিয়ে গেছে: ", "Service overdue: ") + bnDigits("%.0f".format(-kmLeft)) + tr(" কিমি", " km")
                }
                Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                date?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(displayDate(it.toString()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ApproxTag()
                    }
                }
            }
        }
    }
}

/** Index of the nearest point to a tap at x, for n points across the given width. */
internal fun nearestPoint(x: Float, width: Float, n: Int): Int {
    if (n <= 1 || width <= 0f) return 0
    return Math.round(x / width * (n - 1)).coerceIn(0, n - 1)
}

@Composable
private fun MileageChart(all: List<FillMileage>) {
    val pts = all.filter { it.kmPerL != null }
    if (pts.isEmpty()) return
    var sel by remember(pts) { mutableStateOf<Int?>(null) }
    var width by remember { mutableFloatStateOf(0f) }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val values = pts.map { it.kmPerL ?: 0.0 }
    val lo = values.min()
    val hi = values.max()
    val span = (hi - lo).takeIf { it > 0 } ?: maxOf(1.0, hi * 0.2)
    val floor = lo - span * 0.2
    AppCard {
        Text(tr("মাইলেজ ট্রেন্ড (কিমি/লি)", "Mileage trend (km/L)"), fontSize = 13.sp, color = grid)
        sel?.let { i ->
            val p = pts[i]
            Text(
                displayDate(p.entry.date) + "  ·  " + bnDigits("%.1f".format(p.kmPerL ?: 0.0)) +
                    if (p.approx) "  ·  " + tr("আনুমানিক", "Estimate") else "",
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = if (p.approx) Amber500 else primary
            )
        }
        Row(Modifier.fillMaxWidth().height(150.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(34.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(bnDigits("%.0f".format(hi)), fontSize = 10.sp, color = grid)
                Text(bnDigits("%.0f".format(lo)), fontSize = 10.sp, color = grid)
            }
            Canvas(
                Modifier.weight(1f).fillMaxSize()
                    .onSizeChanged { width = it.width.toFloat() }
                    .pointerInput(pts, width) {
                        detectTapGestures { off -> sel = nearestPoint(off.x, width, pts.size) }
                    }
            ) {
                val w = size.width
                val h = size.height
                val pad = 10.dp.toPx()
                for (g in 0..2) {
                    val y = pad + g * (h - 2 * pad) / 2
                    drawLine(grid.copy(alpha = 0.15f), Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
                }
                fun xAt(i: Int): Float = if (pts.size == 1) w / 2 else pad + i * (w - 2 * pad) / (pts.size - 1)
                fun yAt(v: Double): Float = (h - pad - ((v - floor) / (span * 1.4)) * (h - 2 * pad)).toFloat()
                for (i in 1 until pts.size) {
                    drawLine(
                        primary.copy(alpha = 0.5f),
                        Offset(xAt(i - 1), yAt(values[i - 1])), Offset(xAt(i), yAt(values[i])),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                pts.forEachIndexed { i, p ->
                    val c = if (p.approx) Amber500 else primary
                    val r = if (sel == i) 7.dp.toPx() else 4.5.dp.toPx()
                    drawCircle(c, r, Offset(xAt(i), yAt(values[i])))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val first = pts.first().entry.date
            val last = pts.last().entry.date
            Text(shortDate(first), fontSize = 10.sp, color = grid)
            Text(shortDate(last), fontSize = 10.sp, color = grid)
        }
        Text(tr("কমলা বিন্দু = আনুমানিক হিসাব", "Orange dot = estimated"), fontSize = 11.sp, color = Amber500)
    }
}

/** dd/MM from an ISO date, in Bengali digits. */
private fun shortDate(iso: String): String =
    if (iso.length >= 10) bnDigits(iso.substring(8, 10) + "/" + iso.substring(5, 7)) else iso

@Composable
private fun FuelRow(e: FuelEntry, m: FillMileage?, privacy: Boolean, onLong: () -> Unit) {
    AppCard(onLongClick = onLong) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(taka(e.total, privacy), fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text(displayDate(e.date), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            e.odo?.let { Text(bnDigits("%.0f km".format(it)), fontSize = 12.sp) }
            e.liters?.let { Text(bnDigits("%.2f L".format(it)), fontSize = 12.sp) }
            if (e.station.isNotBlank()) Text(e.station, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }
        if (m != null && (m.kmPerL != null || m.approx)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.kmPerL?.let {
                    Text(
                        bnDigits("%.1f km/L".format(it)), fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = if (m.approx) Amber500 else MaterialTheme.colorScheme.primary
                    )
                }
                if (m.approx) ApproxTag()
            }
        }
    }
}

@Composable
private fun MaintRow(m: MaintEntry, privacy: Boolean, onLong: () -> Unit) {
    AppCard(onLongClick = onLong) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(taka(m.cost, privacy), fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text(displayDate(m.date), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(bikeKindLabel(m.kind), fontSize = 12.sp)
            if (m.item.isNotBlank()) Text(m.item, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            m.odo?.let { Text(bnDigits("%.0f km".format(it)), fontSize = 12.sp) }
        }
    }
}

/** Bottom sheet opened by a long press on an entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryActionSheet(onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SheetAction(Icons.Default.Edit, tr("এডিট", "Edit"), MaterialTheme.colorScheme.onSurface, onEdit)
            SheetAction(Icons.Default.Delete, tr("মুছুন", "Delete"), Rose500, onDelete)
        }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.width(22.dp).height(22.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** Full-screen form page (fuel and maintenance). */
@Composable
private fun FormPage(title: String, onDismiss: () -> Unit, save: @Composable () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("ফিরে যান", "Back")) }
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) { content() }
                Box(Modifier.fillMaxWidth().padding(16.dp)) { save() }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FuelForm(state: UiState, vm: AppViewModel, existing: FuelEntry?, onDone: () -> Unit) {
    var date by remember { mutableStateOf(existing?.date ?: todayIso()) }
    var odo by remember { mutableStateOf(existing?.odo?.let { bikeNum(it) } ?: "") }
    var total by remember { mutableStateOf(existing?.total?.let { bikeNum(it) } ?: "") }
    var liters by remember { mutableStateOf(existing?.liters?.let { bikeNum(it) } ?: "") }
    var price by remember { mutableStateOf(existing?.pricePerL?.let { bikeNum(it) } ?: "") }
    var station by remember { mutableStateOf(existing?.station ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var mode by remember {
        mutableStateOf(state.rows(BikeLink.TX).firstOrNull { it.id() == existing?.txId }?.str("payment_mode")?.ifEmpty { null } ?: "Cash")
    }
    var error by remember { mutableStateOf<String?>(null) }
    val auto = autoFuel(parseAmount(total), parseAmount(liters), parseAmount(price))

    FormPage(
        title = tr("তেল ভরা", "Fuel up"),
        onDismiss = onDone,
        save = {
            AddButton(tr("সেভ করুন", "Save")) {
                val od = parseAmount(odo)
                val t = parseAmount(total)
                val lt = parseAmount(liters)
                val pr = parseAmount(price)
                val (fl, fp) = autoFuel(t, lt, pr)
                when {
                    od == null || od < 0 -> error = tr("ওডোমিটার লিখুন", "Enter the odometer reading")
                    t == null || t <= 0 -> error = tr("টাকার পরিমাণ লিখুন", "Enter the amount")
                    !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
                    existing == null -> {
                        val id = UUID.randomUUID().toString()
                        val txId = UUID.randomUUID().toString()
                        val noteText = listOf(station.trim(), note.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
                        vm.batch(listOf(
                            Triple(BikeLink.TX, BikeLink.txRow(state.currency, txId, t, date, noteText, id, mode), null),
                            Triple(BikeLink.FUEL, fuelRow(id, date, od, t, fl, fp, station.trim(), true, note.trim(), txId), null)
                        ))
                        onDone()
                    }
                    else -> {
                        val body = fuelRow(existing.id, date, od, t, fl, fp, station.trim(), existing.full, note.trim(), existing.txId)
                        vm.saveBikeEntry(BikeLink.FUEL, body, existing.id, mode)
                        onDone()
                    }
                }
            }
        },
        content = {
            error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
            FieldLabel(tr("তারিখ", "Date"))
            DateField(tr("তারিখ", "Date"), date, { date = it })
            TextIn(tr("ওডোমিটার (কিমি)", "Odometer (km)"), odo, { odo = it }, number = true)
            TextIn(tr("টাকা খরচ (৳)", "Amount (৳)"), total, { total = it }, number = true)
            TextIn(tr("লিটার", "Litres"), liters, { liters = it }, number = true)
            if (liters.isBlank()) auto.first?.let { FieldLabel("= " + bnDigits("%.2f".format(it)) + " " + tr("লিটার", "L")) }
            TextIn(tr("দাম/লিটার (৳)", "Price per litre (৳)"), price, { price = it }, number = true)
            if (price.isBlank()) auto.second?.let { FieldLabel("= ৳" + bnDigits("%.2f".format(it))) }
            FieldLabel(tr("ওয়ালেট (টাকা যেখান থেকে গেল)", "Wallet"))
            PickerField(mode, CatStore.paymentMethods(), icon = Icons.Default.AccountBalanceWallet) { mode = it }
            TextIn(tr("পাম্পের নাম (ঐচ্ছিক)", "Station (optional)"), station, { station = it })
            TextIn(tr("নোট / রুট (ঐচ্ছিক)", "Note / route (optional)"), note, { note = it })
        }
    )
}

@Composable
private fun MaintForm(state: UiState, vm: AppViewModel, existing: MaintEntry?, onDone: () -> Unit) {
    var date by remember { mutableStateOf(existing?.date ?: todayIso()) }
    var kind by remember { mutableStateOf(existing?.kind ?: "service") }
    var item by remember { mutableStateOf(existing?.item ?: "") }
    var cost by remember { mutableStateOf(existing?.cost?.let { bikeNum(it) } ?: "") }
    var odo by remember { mutableStateOf(existing?.odo?.let { bikeNum(it) } ?: "") }
    var nextKm by remember { mutableStateOf(existing?.nextKm?.let { bikeNum(it) } ?: "") }
    var nextDate by remember { mutableStateOf(existing?.nextDate ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var mode by remember {
        mutableStateOf(state.rows(BikeLink.TX).firstOrNull { it.id() == existing?.txId }?.str("payment_mode")?.ifEmpty { null } ?: "Cash")
    }
    var error by remember { mutableStateOf<String?>(null) }

    FormPage(
        title = tr("মেইনটেন্যান্স", "Maintenance"),
        onDismiss = onDone,
        save = {
            AddButton(tr("সেভ করুন", "Save")) {
                val c = parseAmount(cost)
                val nextOk = if (isValidIsoDate(nextDate)) nextDate else ""
                when {
                    c == null || c <= 0 -> error = tr("খরচ লিখুন", "Enter the cost")
                    !isValidIsoDate(date) -> error = tr("তারিখ বাছাই করুন", "Pick a date")
                    existing == null -> {
                        val id = UUID.randomUUID().toString()
                        val txId = UUID.randomUUID().toString()
                        val label = item.trim().ifEmpty { bikeKindLabel(kind) }
                        vm.batch(listOf(
                            Triple(BikeLink.TX, BikeLink.txRow(state.currency, txId, c, date, label, id, mode), null),
                            Triple(BikeLink.MAINT, maintRow(id, date, kind, item.trim(), c, parseAmount(odo),
                                parseAmount(nextKm), nextOk, note.trim(), txId), null)
                        ))
                        onDone()
                    }
                    else -> {
                        val body = maintRow(existing.id, date, kind, item.trim(), c, parseAmount(odo),
                            parseAmount(nextKm), nextOk, note.trim(), existing.txId)
                        vm.saveBikeEntry(BikeLink.MAINT, body, existing.id, mode)
                        onDone()
                    }
                }
            }
        },
        content = {
            error?.let { Text(it, color = readableOn(Rose500), fontSize = 12.sp) }
            FieldLabel(tr("ধরন", "Type"))
            // A row saved with an older kind keeps its kind in the list until it is changed.
            val kindOptions = bikeKinds().let { o -> if (o.any { it.first == kind }) o else o + (kind to bikeKindLabel(kind)) }
            PickerField(kind, kindOptions) { kind = it }
            TextIn(tr("আইটেম / কাজ", "Item / work"), item, { item = it })
            TextIn(tr("খরচ (৳)", "Cost (৳)"), cost, { cost = it }, number = true)
            FieldLabel(tr("তারিখ", "Date"))
            DateField(tr("তারিখ", "Date"), date, { date = it })
            TextIn(tr("ওডোমিটার (ঐচ্ছিক)", "Odometer (optional)"), odo, { odo = it }, number = true)
            TextIn(tr("পরের সার্ভিস কত কিমিতে (ঐচ্ছিক)", "Next service at km (optional)"), nextKm, { nextKm = it }, number = true)
            DateField(tr("পরের সার্ভিস তারিখ (ঐচ্ছিক)", "Next service date (optional)"), nextDate, { nextDate = it }, optional = true)
            FieldLabel(tr("ওয়ালেট (টাকা যেখান থেকে গেল)", "Wallet"))
            PickerField(mode, CatStore.paymentMethods(), icon = Icons.Default.AccountBalanceWallet) { mode = it }
            TextIn(tr("নোট (ঐচ্ছিক)", "Note (optional)"), note, { note = it })
        }
    )
}
