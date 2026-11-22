package com.fintracks.app.ui

import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.bool
import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.YearMonth

/** Local, deterministic money insights. No AI is used for the numbers. */
object Insights {
    private fun dayOf(t: JsonObject): LocalDate? = runCatching { LocalDate.parse(dateOf(t, "tx_date")) }.getOrNull()

    /** Expense per day for the last [days] days, oldest first. */
    fun dailyExpense(txs: List<JsonObject>, days: Int): List<Double> {
        val today = LocalDate.now()
        val byDay = HashMap<LocalDate, Double>()
        txs.filter { it.str("type") == "Expense" }.forEach { t ->
            dayOf(t)?.let { byDay[it] = (byDay[it] ?: 0.0) + t.num("amount") }
        }
        return (days - 1 downTo 0).map { byDay[today.minusDays(it.toLong())] ?: 0.0 }
    }

    /**
     * Cash-flow projection: current balance plus the average daily net of the last 90 days,
     * over the next [days] days. Scheduled recurring rows are already part of that history.
     */
    fun forecast(txs: List<JsonObject>, balance: Double, days: Int): Double {
        val today = LocalDate.now()
        val from = today.minusDays(90)
        val net = txs.filter { t -> dayOf(t)?.let { !it.isBefore(from) && !it.isAfter(today) } == true }
            .sumOf { t ->
                when (t.str("type")) {
                    "Income" -> t.num("amount")
                    "Expense" -> -t.num("amount")
                    else -> 0.0
                }
            }
        return balance + net / 90.0 * days
    }

    data class Unusual(val category: String, val thisMonth: Double, val average: Double)

    /** Categories whose spend this month is more than 40% above their 3-month average (and at least ৳500). */
    fun unusual(txs: List<JsonObject>): List<Unusual> {
        val now = YearMonth.now()
        val expenses = txs.filter { it.str("type") == "Expense" }
        fun sumFor(ym: YearMonth, cat: String) = expenses
            .filter { it.str("category") == cat && dateOf(it, "tx_date").startsWith(ym.toString()) }
            .sumOf { it.num("amount") }
        val cats = expenses.map { it.str("category") }.distinct()
        return cats.mapNotNull { cat ->
            val thisMonth = sumFor(now, cat)
            val avg = (1..3).sumOf { sumFor(now.minusMonths(it.toLong()), cat) } / 3.0
            if (thisMonth >= 500 && avg > 0 && thisMonth > avg * 1.4) Unusual(cat, thisMonth, avg) else null
        }.sortedByDescending { it.thisMonth - it.average }
    }

    /** Assets (account balances + investment value) minus liabilities (unsettled debts + loan balance). */
    fun netWorth(state: UiState): Double {
        val txs = state.rows("transactions")
        val accounts = state.rows("accounts")
        val cash = if (accounts.isEmpty()) {
            txs.sumOf { if (it.str("type") == "Income") it.num("amount") else if (it.str("type") == "Expense") -it.num("amount") else 0.0 }
        } else accounts.sumOf { accountBalance(it, txs) }
        val investments = state.rows("investments").sumOf { it.num("current_value") }
        val owe = debtsRemaining(state, "owe")
        val loans = state.rows("loans").sumOf { (it.num("principal") - it.num("paid")).coerceAtLeast(0.0) }
        return cash + investments - owe - loans
    }
}

/** Cash-flow forecast (30/60/90 days), unusual spending, and net worth. */
@Composable
fun InsightsCards(state: UiState) {
    val txs = state.rows("transactions")
    val accounts = state.rows("accounts")
    val balance = if (accounts.isEmpty()) {
        txs.sumOf { if (it.str("type") == "Income") it.num("amount") else if (it.str("type") == "Expense") -it.num("amount") else 0.0 }
    } else accounts.sumOf { accountBalance(it, txs) }
    val p = state.privacy
    val unusual = Insights.unusual(txs)

    AppCard {
        Text(tr("ক্যাশফ্লো পূর্বাভাস", "Cash-flow forecast"), fontWeight = FontWeight.Bold)
        Text(tr("গত ৯০ দিনের গড় অনুযায়ী আনুমানিক ব্যালেন্স। এটি একটি অনুমান, নিশ্চিত হিসাব নয়।", "Estimated balance from the last 90 days' average. This is an estimate, not a confirmed figure."), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(30, 60, 90).forEach { d ->
            KeyValue(tr("$d দিন পর", "in $d days"), taka(Insights.forecast(txs, balance, d), p))
        }
    }
    AppCard {
        Text(tr("অস্বাভাবিক খরচ", "Unusual spending"), fontWeight = FontWeight.Bold)
        if (unusual.isEmpty()) {
            Text(tr("এই মাসে কোনো ক্যাটাগরি স্বাভাবিকের চেয়ে বেশি খরচ হয়নি।", "No category spent above normal this month."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            unusual.take(5).forEach { u ->
                val pct = ((u.thisMonth - u.average) / u.average * 100).toInt()
                Text(tr("${catIcon(u.category)} ${catLabel(u.category)}: এই মাসে ${taka(u.thisMonth, p)} (গড়ের চেয়ে +$pct%)", "${catIcon(u.category)} ${catLabel(u.category)}: this month ${taka(u.thisMonth, p)} (+$pct% vs average)"), fontSize = 13.sp, color = readableOn(Amber500))
            }
        }
    }
    AppCard {
        Text(tr("নেট ওয়ার্থ", "Net worth"), fontWeight = FontWeight.Bold)
        KeyValue(tr("মোট সম্পদ − দেনা", "Total assets − debts"), taka(Insights.netWorth(state), p), netTone(Insights.netWorth(state)))
        Text(tr("সম্পদ: অ্যাকাউন্ট ব্যালেন্স + বিনিয়োগ। দেনা: অপরিশোধিত ধার ও লোনের বাকি।", "Assets: account balances + investments. Debts: unpaid loans and dues."), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Monthly expense calendar for [ym]. ‹ › change the month; the caller keeps the home timeline in sync.
 * Only the days of [ym] are drawn; leading cells before day 1 stay empty, so no other month's data appears.
 */
@Composable
fun HeatmapCard(txs: List<JsonObject>, privacy: Boolean, ym: YearMonth, onPrev: () -> Unit, onNext: (() -> Unit)?) {
    val first = ym.atDay(1)
    val days = ym.lengthOfMonth()
    val prefix = ym.toString() // yyyy-MM
    val perDay = DoubleArray(days + 1)
    txs.forEach { t ->
        if (t.str("type") != "Expense") return@forEach
        val d = dateOf(t, "tx_date")
        if (!d.startsWith(prefix)) return@forEach
        val day = d.substring(8).toIntOrNull() ?: return@forEach
        if (day in 1..days) perDay[day] += t.num("amount")
    }
    val max = (perDay.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    // Dark theme: rose on a dark cell, white numbers. Light theme: a deeper rose on a pale cell, its own
    // intensity scale (0.10 to 1.0), and dark numbers on pale cells, white numbers only on dense cells.
    val dark = isDarkTheme()
    val base = if (dark) Rose500 else Rose700
    val lead = first.dayOfWeek.value % 7 // Sunday = 0
    val cells: List<Int?> = List(lead) { null } + (1..days).map { it }
    fun shade(day: Int?): Float {
        val v = if (day == null) 0.0 else perDay[day]
        val r = (v / max).toFloat()
        return when {
            v <= 0.0 -> if (dark) 0.06f else 0.08f
            dark -> (0.25f + 0.75f * r).coerceAtMost(1f)
            else -> (0.10f + 0.90f * r).coerceAtMost(1f)
        }
    }
    fun numberColor(alpha: Float): Color = when {
        dark -> Color.White.copy(alpha = 0.85f)
        alpha >= 0.75f -> Color.White
        else -> Slate900
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ChevronLeft, contentDescription = tr("আগের মাস", "Previous month"))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("এক্সপেন্স হিটম্যাপ", "Expense heatmap"), fontWeight = FontWeight.Bold)
                Text(
                    monthYearLabel(ym.year, ym.monthValue),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ChevronRight, contentDescription = tr("পরের মাস", "Next month"))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (day == null) Color.Transparent else base.copy(alpha = shade(day))),
                        contentAlignment = Alignment.Center
                    ) {
                        if (day != null) Text(bnDigits(day.toString()), fontSize = 12.sp, color = numberColor(shade(day)))
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(tr("মোট ব্যয় (এই মাস)", "Total expense (this month)") + ": ${taka(perDay.sum(), privacy)}", fontSize = 12.sp)
    }
}

// ======================= Expense trend (report) =======================

private data class TrendBucket(val short: String, val full: String, val total: Double)

/**
 * Expense trend with its own tabs: সাপ্তাহিক = last 7 days, মাসিক = last 30 days (daily),
 * বাৎসরিক = last 12 months (monthly). Ignores the report's period picker on purpose: these are fixed windows.
 */
@Composable
fun ExpenseTrendCard(txs: List<JsonObject>, privacy: Boolean) {
    var view by remember { mutableStateOf("month") }
    val today = LocalDate.now()
    val expenses = txs.filter { it.str("type") == "Expense" }
    val byDay = HashMap<String, Double>()
    val byMonth = HashMap<String, Double>()
    expenses.forEach { t ->
        val d = dateOf(t, "tx_date")
        if (d.length >= 7) {
            byDay[d] = (byDay[d] ?: 0.0) + t.num("amount")
            byMonth[d.substring(0, 7)] = (byMonth[d.substring(0, 7)] ?: 0.0) + t.num("amount")
        }
    }
    val buckets: List<TrendBucket> = when (view) {
        "week", "month" -> {
            val days = if (view == "week") 7 else 30
            (days - 1 downTo 0).map { k ->
                val d = today.minusDays(k.toLong())
                TrendBucket(
                    short = bnDigits(d.dayOfMonth.toString()),
                    full = displayDate(d.toString()),
                    total = byDay[d.toString()] ?: 0.0
                )
            }
        }
        else -> (11 downTo 0).map { k ->
            val ym = YearMonth.from(today).minusMonths(k.toLong())
            TrendBucket(
                short = bnDigits(ym.monthValue.toString()),
                full = monthYearLabel(ym.year, ym.monthValue),
                total = byMonth[ym.toString()] ?: 0.0
            )
        }
    }
    val max = buckets.maxOfOrNull { it.total } ?: 0.0
    val peakIndex = if (max > 0) buckets.indexOfFirst { it.total == max } else -1
    val average = if (buckets.isNotEmpty()) buckets.sumOf { it.total } / buckets.size else 0.0

    AppCard {
        Text(tr("ব্যয়ের ট্রেন্ড", "Expense trend"), fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("week" to tr("সাপ্তাহিক", "Weekly"), "month" to tr("মাসিক", "Monthly"), "year" to tr("বাৎসরিক", "Yearly")).forEach { (key, label) ->
                val on = view == key
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) Sky500.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                        .clickable { view = key }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) Sky400 else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (max <= 0) {
            EmptyHint(tr("এই সময়ে কোনো ব্যয় নেই", "No expenses in this period"))
        } else {
            Row(Modifier.fillMaxWidth().height(150.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                buckets.forEachIndexed { i, b ->
                    val frac = if (b.total <= 0) 0f else maxOf((b.total / max).toFloat(), 0.03f)
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(frac)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (i == peakIndex) Rose500 else Sky500.copy(alpha = 0.55f))
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                buckets.forEachIndexed { i, b ->
                    val showLabel = when (view) {
                        "month" -> i % 5 == 0 || i == buckets.lastIndex
                        else -> true
                    }
                    Text(
                        if (showLabel) b.short else "",
                        modifier = Modifier.weight(1f),
                        fontSize = 9.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (peakIndex >= 0) {
                Text(
                    tr("সবচেয়ে বেশি খরচ: ${buckets[peakIndex].full} · ${taka(max, privacy)}", "Highest spending: ${buckets[peakIndex].full} · ${taka(max, privacy)}"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Rose500
                )
            }
            Text("গড় (প্রতি ${if (view == "year") tr("মাসে", "per month") else tr("দিনে", "per day")}): ${taka(average, privacy)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Income and expense of the selected report period as two bars on a shared scale. */
@Composable
fun IncomeExpenseBars(income: Double, expense: Double, privacy: Boolean) {
    val max = maxOf(income, expense)
    val rows = listOf(Triple(tr("আয়", "Income"), income, Emerald500), Triple(tr("ব্যয়", "Expense"), expense, Rose500))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (label, value, color) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, fontSize = 13.sp)
                    Text(taka(value, privacy), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
                }
                Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.06f))) {
                    val frac = if (max > 0) (value / max).toFloat() else 0f
                    Box(Modifier.fillMaxWidth(frac).height(12.dp).clip(RoundedCornerShape(6.dp)).background(color))
                }
            }
        }
        Text(tr("নিট: ${taka(income - expense, privacy)}", "Net: ${taka(income - expense, privacy)}"), fontSize = 12.sp, color = netTone(income - expense))
    }
}
