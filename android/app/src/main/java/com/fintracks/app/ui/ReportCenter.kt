package com.fintracks.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val RptCyan = Color(0xFF38C7E0)
private val RptAmber = Color(0xFFF59E0B)
private val RptPalette = listOf(Color(0xFFFF9F43), Color(0xFF38C7E0), Color(0xFF8B7CF6), Color(0xFF2ECC71), Color(0xFFFB7185), Color(0xFFF59E0B))

/** Download icon button shown in the Report page header. */
@Composable
fun ReportExportButton(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(faint(0.06f))
            .border(1.dp, faint(0.1f), RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Download, contentDescription = tr("এক্সপোর্ট সেন্টার", "Export center"), tint = RptCyan, modifier = Modifier.size(22.dp))
    }
}

/** Range label in Bangla, e.g. "১ – ৩০ নভেম্বর ২০২৬". Falls back to the stored label when a bound is missing. */
fun rangeText(range: DateRange): String {
    val f = range.from
    val t = range.to
    if (AppPrefs.english || f == null || t == null) return range.label
    val fm = BN_MONTHS[f.monthValue - 1]
    val tm = BN_MONTHS[t.monthValue - 1]
    return if (f.year == t.year && f.monthValue == t.monthValue) {
        if (f == t) "${bnDigits(f.dayOfMonth.toString())} $fm ${bnDigits(f.year.toString())}"
        else "${bnDigits(f.dayOfMonth.toString())} – ${bnDigits(t.dayOfMonth.toString())} $fm ${bnDigits(f.year.toString())}"
    } else {
        "${bnDigits(f.dayOfMonth.toString())} $fm ${bnDigits(f.year.toString())} – ${bnDigits(t.dayOfMonth.toString())} $tm ${bnDigits(t.year.toString())}"
    }
}

@Composable
fun ReportsScreen(state: UiState, onExport: () -> Unit) {
    var period by remember { mutableStateOf(Period.MONTH) }
    var customFrom by remember { mutableStateOf(todayIso().substring(0, 8) + "01") }
    var customTo by remember { mutableStateOf(todayIso()) }
    val range = rangeFor(period, customFrom, customTo)
    val txs = filterByRange(state.rows("transactions"), range)
    val income = txs.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
    val expense = txs.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
    val net = income - expense
    val expCats = categoryTotals(txs, "Expense")
    val incCats = categoryTotals(txs, "Income")
    val owed = debtsRemaining(state, "owed")
    val owe = debtsRemaining(state, "owe")
    val credit = state.rows("credit_shops").sumOf { shopBalance(it.id(), state) }
    val p = state.privacy
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // Daily series over the selected range.
    val days = rangeDays(txs, range)
    val dayLabels = days.map { bnDigits(it.dayOfMonth.toString()) }
    val incByDay = txs.filter { it.str("type") == "Income" }.groupBy { dateOf(it, "tx_date") }.mapValues { e -> e.value.sumOf { it.num("amount") } }
    val expByDay = txs.filter { it.str("type") == "Expense" }.groupBy { dateOf(it, "tx_date") }.mapValues { e -> e.value.sumOf { it.num("amount") } }
    val dayIncome = days.map { incByDay[it.toString()] ?: 0.0 }
    val dayExpense = days.map { expByDay[it.toString()] ?: 0.0 }
    val cumulative = dayIncome.zip(dayExpense) { i, e -> i - e }.runningFold(0.0) { acc, v -> acc + v }.drop(1)

    // Last six calendar months, always monthly, whatever the range.
    val today = LocalDate.now()
    val months = (5 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()) }
    val allTx = state.rows("transactions")
    val incByMonth = allTx.filter { it.str("type") == "Income" }.groupBy { dateOf(it, "tx_date").take(7) }.mapValues { e -> e.value.sumOf { it.num("amount") } }
    val expByMonth = allTx.filter { it.str("type") == "Expense" }.groupBy { dateOf(it, "tx_date").take(7) }.mapValues { e -> e.value.sumOf { it.num("amount") } }
    val monthIncome = months.map { incByMonth[it.toString()] ?: 0.0 }
    val monthExpense = months.map { expByMonth[it.toString()] ?: 0.0 }
    val monthLabels = months.map { tr(BN_MONTHS[it.monthValue - 1], it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) }

    // People with debts created in the range: unpaid part per direction.
    val names = state.rows("debt_people").associate { it.id() to it.str("name") }
    val pays = state.rows("debt_payments")
    val personRows = state.rows("debts").filter { range.contains(it.str("created_at")) }
        .groupBy { d -> d.str("person").ifEmpty { names[d.str("person_id")].orEmpty() }.ifEmpty { "—" } }
        .map { (name, list) ->
            Triple(
                name,
                list.filter { it.str("direction") == "owed" }.sumOf { debtRemaining(it, pays) },
                list.filter { it.str("direction") == "owe" }.sumOf { debtRemaining(it, pays) }
            )
        }
        .filter { it.second > 0.005 || it.third > 0.005 }
        .sortedByDescending { maxOf(it.second, it.third) }

    // Shops: purchases in the range minus payments in the range.
    val entriesInRange = state.rows("credit_entries").filter { range.contains(it.str("purchase_date")) }
    val paysInRange = state.rows("credit_payments").filter { range.contains(it.str("paid_on")) }
    val shopRows = state.rows("credit_shops").map { sh ->
        sh.str("name") to (entriesInRange.filter { it.str("shop_id") == sh.id() }.sumOf { it.num("total") } -
            paysInRange.filter { it.str("shop_id") == sh.id() }.sumOf { it.num("amount") })
    }.filter { it.second > 0.005 }.sortedByDescending { it.second }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PeriodPicker(period, customFrom, customTo, onPeriod = { period = it }, onFrom = { customFrom = it }, onTo = { customTo = it })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DateRange, contentDescription = null, tint = RptCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(rangeText(range), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                tr("কাস্টম রেঞ্জ", "Custom range"), color = RptCyan, fontSize = 13.sp,
                modifier = Modifier.clickable { period = Period.CUSTOM }
            )
        }

        // Hero: net savings with income and expense below.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(faint(0.05f))
                .border(1.dp, faint(0.1f), RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(tr("নিট সঞ্চয়", "Net savings"), color = muted, fontSize = 13.sp)
            Text(taka(net, p), color = RptCyan, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth().height(1.dp).background(faint(0.1f)))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Emerald500))
                        Text(tr("মোট আয়", "Income"), color = muted, fontSize = 13.sp)
                    }
                    Text(taka(income, p), color = Emerald500, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Rose500))
                        Text(tr("মোট ব্যয়", "Expense"), color = muted, fontSize = 13.sp)
                    }
                    Text(taka(expense, p), color = Rose500, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 1. Income and expense trend (same chart as home).
        ReportCard(
            title = tr("আয়-ব্যয়ের ট্রেন্ড", "Income and expense trend"),
            legend = { ReportLegend(tr("আয়", "Income"), incomeTone(), tr("ব্যয়", "Expense"), expenseTone()) }
        ) {
            if (hasAmounts(dayIncome, dayExpense)) {
                DualLineChart(
                    dayIncome, incomeTone(), dayExpense, expenseTone(),
                    labels = dayLabels,
                    format = { taka(it, p) },
                    firstName = tr("আয়", "Income"),
                    secondName = tr("ব্যয়", "Expense")
                )
            } else {
                ReportEmpty()
            }
        }

        // 2. Daily income and expense bars.
        ReportCard(
            title = tr("প্রতিদিনের আয়-ব্যয়", "Daily income and expense"),
            legend = { ReportLegend(tr("আয়", "Income"), incomeTone(), tr("ব্যয়", "Expense"), expenseTone()) }
        ) {
            PairedBars(dayIncome, dayExpense, dayLabels) { taka(it, p) }
        }

        // 3. Six-month comparison (always monthly).
        ReportCard(
            title = tr("মাসিক তুলনা · গত ৬ মাস", "Monthly comparison · last 6 months"),
            legend = { ReportLegend(tr("আয়", "Income"), incomeTone(), tr("ব্যয়", "Expense"), expenseTone()) }
        ) {
            PairedBars(monthIncome, monthExpense, monthLabels) { taka(it, p) }
        }

        // 4. Expense by category (existing) and 5. income by category (same style).
        ReportCard(title = tr("ক্যাটাগরি অনুযায়ী ব্যয়", "Expense by category")) {
            CategoryBars(expCats, expense, p)
        }
        ReportCard(title = tr("ক্যাটাগরি অনুযায়ী আয়", "Income by category")) {
            CategoryBars(incCats, income, p, color = Emerald500)
        }

        // 6. Cumulative net savings over the range.
        ReportCard(title = tr("নিট সঞ্চয় ধারা", "Net savings over time")) {
            CumulativeChart(cumulative, dayLabels) { taka(it, p) }
        }

        // 7. Owed and owe by person, largest first.
        ReportCard(
            title = tr("পাওনা-দেনা", "Owed and owe"),
            legend = { ReportLegend(tr("পাওনা", "Owed"), incomeTone(), tr("দেনা", "I owe"), expenseTone()) }
        ) {
            DebtBars(personRows, p)
        }

        // 8. Credit due by shop.
        ReportCard(title = tr("বাকি · দোকান অনুযায়ী", "Credit due by shop")) {
            CategoryBars(shopRows, shopRows.sumOf { it.second }, p, color = RptAmber)
        }

        // Owed, owe, and credit due totals (current balances).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TotalTile(tr("মোট পাওনা", "Owed to me"), taka(owed, p), Emerald500, Modifier.weight(1f))
            TotalTile(tr("মোট দেনা", "I owe"), taka(owe, p), Rose500, Modifier.weight(1f))
            TotalTile(tr("মোট বাকি", "Credit due"), taka(credit, p), RptAmber, Modifier.weight(1f))
        }

        Button(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RptCyan, contentColor = Color(0xFF0B1220))
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("এক্সপোর্ট সেন্টার", "Export center"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/* ---------- Data helpers ---------- */

private fun hasAmounts(a: List<Double>, b: List<Double>): Boolean = a.any { it != 0.0 } || b.any { it != 0.0 }

/** Totals per category for one type, largest first. */
private fun categoryTotals(txs: List<JsonObject>, type: String): List<Pair<String, Double>> =
    txs.filter { it.str("type") == type }
        .groupBy { it.str("category") }
        .map { (k, v) -> k to v.sumOf { it.num("amount") } }
        .sortedByDescending { it.second }

/** Each day in the range; with no bounds, from the first transaction to today (at most 366 days). */
private fun rangeDays(txs: List<JsonObject>, range: DateRange): List<LocalDate> {
    val earliest = txs.mapNotNull { runCatching { LocalDate.parse(dateOf(it, "tx_date")) }.getOrNull() }.minOrNull()
    val start = range.from ?: earliest ?: LocalDate.now(ZoneId.systemDefault())
    val end = range.to ?: LocalDate.now(ZoneId.systemDefault())
    return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList().takeLast(366)
}

/* ---------- Shared pieces ---------- */

@Composable
private fun ReportCard(title: String, legend: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(faint(0.05f))
            .border(1.dp, faint(0.1f), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            legend?.invoke()
        }
        content()
    }
}

@Composable
private fun ReportLegend(a: String, ac: Color, b: String, bc: Color) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(ac))
        Text(" $a   ", fontSize = 12.sp, color = muted)
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(bc))
        Text(" $b", fontSize = 12.sp, color = muted)
    }
}

@Composable
private fun ReportEmpty() {
    Text(tr("কোনো তথ্য নেই", "No data"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

/** 0 -> 1 once on first composition, so bars grow when the card appears. */
@Composable
private fun growFraction(): Float {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    return animateFloatAsState(if (shown) 1f else 0f, animationSpec = tween(700), label = "report-grow").value
}

@Composable
private fun TotalTile(label: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(faint(0.05f))
            .border(1.dp, faint(0.1f), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

/** Paired income and expense bars with tap-to-read values. */
@Composable
private fun PairedBars(first: List<Double>, second: List<Double>, labels: List<String>, format: (Double) -> String) {
    if (!hasAmounts(first, second)) {
        ReportEmpty()
        return
    }
    val n = maxOf(first.size, second.size, labels.size).coerceAtLeast(1)
    val maxV = maxOf(first.maxOrNull() ?: 0.0, second.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val grow = growFraction()
    var sel by remember(first, second) { mutableStateOf(-1) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val incC = incomeTone()
    val expC = expenseTone()

    Canvas(
        Modifier.fillMaxWidth().height(150.dp).pointerInput(n) {
            detectTapGestures(onTap = { o -> sel = (o.x / (size.width / n)).toInt().coerceIn(0, n - 1) })
        }
    ) {
        val slot = size.width / n
        val barW = (slot * 0.36f).coerceAtMost(12.dp.toPx())
        val baseY = size.height - 2.dp.toPx()
        val maxH = size.height - 6.dp.toPx()
        for (i in 0 until n) {
            val cx = i * slot + slot / 2f
            if (sel == i) drawRect(muted.copy(alpha = 0.12f), Offset(i * slot, 0f), Size(slot, size.height))
            val ih = (first.getOrElse(i) { 0.0 } / maxV).toFloat() * maxH * grow
            val eh = (second.getOrElse(i) { 0.0 } / maxV).toFloat() * maxH * grow
            if (ih > 0f) drawRect(incC, Offset(cx - barW, baseY - ih), Size(barW, ih))
            if (eh > 0f) drawRect(expC, Offset(cx, baseY - eh), Size(barW, eh))
        }
        drawLine(muted.copy(alpha = 0.3f), Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = 1.dp.toPx())
    }
    if (sel in 0 until n) {
        Text(
            "${labels.getOrElse(sel) { "" }} · ${tr("আয়", "Income")} ${format(first.getOrElse(sel) { 0.0 })} · ${tr("ব্যয়", "Expense")} ${format(second.getOrElse(sel) { 0.0 })}",
            fontSize = 12.sp
        )
    }
    val step = ceil(n / 6.0).toInt().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth()) {
        for (i in 0 until n) {
            Text(
                if (i % step == 0) labels.getOrElse(i) { "" } else "",
                fontSize = 10.sp, color = muted, modifier = Modifier.weight(1f), maxLines = 1, textAlign = TextAlign.Center
            )
        }
    }
}

/** Cumulative net savings line; tap a point to read its value. */
@Composable
private fun CumulativeChart(values: List<Double>, labels: List<String>, format: (Double) -> String) {
    if (!hasAmounts(values, emptyList())) {
        ReportEmpty()
        return
    }
    val n = values.size
    var sel by remember(values) { mutableStateOf(-1) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().pointerInput(n) {
                detectTapGestures(onTap = { o ->
                    sel = if (n == 1) 0 else Math.round(o.x / size.width * (n - 1)).coerceIn(0, n - 1)
                })
            }
        ) {
            LineChart(values, RptCyan)
        }
        Text(
            if (sel in 0 until n) "${labels.getOrElse(sel) { "" }} · ${format(values[sel])}"
            else "${tr("শেষ", "Latest")}: ${format(values.last())}",
            fontSize = 12.sp, color = muted
        )
    }
}

/** Ranked bars; [color] null means the report palette, one colour per row. */
@Composable
private fun CategoryBars(items: List<Pair<String, Double>>, total: Double, p: Boolean, color: Color? = null) {
    if (items.isEmpty()) {
        ReportEmpty()
        return
    }
    val grow = growFraction()
    items.forEachIndexed { i, (name, v) ->
        val c = color ?: RptPalette[i % RptPalette.size]
        val frac = if (total > 0) (v / total).toFloat().coerceIn(0f, 1f) * grow else 0f
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c), contentAlignment = Alignment.Center) {
                Text(name.take(2), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                ThinBar(frac, c)
            }
            Text(taka(v, p), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

/** Per person: owed (green) and owe (rose) bars, scaled to the largest value. */
@Composable
private fun DebtBars(rows: List<Triple<String, Double, Double>>, p: Boolean) {
    if (rows.isEmpty()) {
        ReportEmpty()
        return
    }
    val grow = growFraction()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val maxV = rows.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(1.0)
    rows.forEach { (name, owed, owe) ->
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            ThinBar((owed / maxV).toFloat() * grow, incomeTone())
            ThinBar((owe / maxV).toFloat() * grow, expenseTone())
            Text(
                "${tr("পাওনা", "Owed")} ${taka(owed, p)} · ${tr("দেনা", "I owe")} ${taka(owe, p)}",
                fontSize = 12.sp, color = muted
            )
        }
    }
}

@Composable
private fun ThinBar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(faint(0.08f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
    }
}
