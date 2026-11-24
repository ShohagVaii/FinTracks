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
    val cats = txs.filter { it.str("type") == "Expense" }
        .groupBy { it.str("category") }
        .map { (k, v) -> k to v.sumOf { it.num("amount") } }
        .sortedByDescending { it.second }
    val owed = debtsRemaining(state, "owed")
    val owe = debtsRemaining(state, "owe")
    val credit = state.rows("credit_shops").sumOf { shopBalance(it.id(), state) }
    val p = state.privacy
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

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

        // Daily income and expense bars.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(faint(0.05f))
                .border(1.dp, faint(0.1f), RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("প্রতিদিনের আয়-ব্যয়", "Daily income and expense"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Emerald500))
                Text(" " + tr("আয়", "Income") + "   ", fontSize = 12.sp, color = muted)
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Rose500))
                Text(" " + tr("ব্যয়", "Expense"), fontSize = 12.sp, color = muted)
            }
            DailyBars(txs, range)
        }

        // Expense by category.
        Text(tr("ক্যাটাগরি অনুযায়ী ব্যয়", "Expense by category"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (cats.isEmpty()) Text(tr("কোনো ব্যয় নেই", "No expenses"), color = muted, fontSize = 13.sp)
        cats.forEachIndexed { i, (name, v) ->
            val color = RptPalette[i % RptPalette.size]
            val frac = if (expense > 0) (v / expense).toFloat().coerceIn(0f, 1f) else 0f
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(faint(0.05f))
                    .border(1.dp, faint(0.1f), RoundedCornerShape(16.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) {
                    Text(name.take(2), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(faint(0.08f))) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
                    }
                }
                Text(taka(v, p), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
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

/** Paired income (green) and expense (rose) bars per day across the range. */
@Composable
private fun DailyBars(txs: List<JsonObject>, range: DateRange) {
    val incomeBy = HashMap<String, Double>()
    val expenseBy = HashMap<String, Double>()
    txs.forEach { t ->
        val key = t.str("date").take(10)
        if (t.str("type") == "Income") incomeBy[key] = (incomeBy[key] ?: 0.0) + t.num("amount")
        if (t.str("type") == "Expense") expenseBy[key] = (expenseBy[key] ?: 0.0) + t.num("amount")
    }
    val earliest = txs.mapNotNull { runCatching { LocalDate.parse(it.str("date").take(10)) }.getOrNull() }.minOrNull()
    val start = range.from ?: earliest ?: LocalDate.now(ZoneId.systemDefault())
    val end = range.to ?: LocalDate.now(ZoneId.systemDefault())
    val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList().takeLast(366)
    val n = days.size.coerceAtLeast(1)
    val maxV = days.maxOfOrNull { maxOf(incomeBy[it.toString()] ?: 0.0, expenseBy[it.toString()] ?: 0.0) }?.coerceAtLeast(1.0) ?: 1.0
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val slot = size.width / n
        val barW = (slot * 0.36f).coerceAtMost(12.dp.toPx())
        val baseY = size.height - 2.dp.toPx()
        val maxH = size.height - 6.dp.toPx()
        days.forEachIndexed { i, d ->
            val cx = i * slot + slot / 2f
            val iv = incomeBy[d.toString()] ?: 0.0
            val ev = expenseBy[d.toString()] ?: 0.0
            val ih = (iv / maxV).toFloat() * maxH
            val eh = (ev / maxV).toFloat() * maxH
            if (ih > 0f) drawRect(Emerald500, Offset(cx - barW, baseY - ih), Size(barW, ih))
            if (eh > 0f) drawRect(Rose500, Offset(cx, baseY - eh), Size(barW, eh))
        }
        drawLine(muted.copy(alpha = 0.3f), Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = 1.dp.toPx())
    }
    val step = ceil(n / 6.0).toInt().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth()) {
        days.forEachIndexed { i, d ->
            Text(
                if (i % step == 0) bnDigits(d.dayOfMonth.toString()) else "",
                fontSize = 10.sp, color = muted, modifier = Modifier.weight(1f), maxLines = 1
            )
        }
    }
}
