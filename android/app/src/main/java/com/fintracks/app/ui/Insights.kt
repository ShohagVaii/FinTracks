package com.fintracks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        val owe = state.rows("debts").filter { !it.bool("settled") && it.str("direction") == "owe" }.sumOf { it.num("amount") }
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
        Text("ক্যাশফ্লো পূর্বাভাস", fontWeight = FontWeight.Bold)
        Text("গত ৯০ দিনের গড় অনুযায়ী আনুমানিক ব্যালেন্স। এটি একটি অনুমান, নিশ্চিত হিসাব নয়।", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(30, 60, 90).forEach { d ->
            KeyValue("$d দিন পর", taka(Insights.forecast(txs, balance, d), p))
        }
    }
    AppCard {
        Text("অস্বাভাবিক খরচ", fontWeight = FontWeight.Bold)
        if (unusual.isEmpty()) {
            Text("এই মাসে কোনো ক্যাটাগরি স্বাভাবিকের চেয়ে বেশি খরচ হয়নি।", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            unusual.take(5).forEach { u ->
                val pct = ((u.thisMonth - u.average) / u.average * 100).toInt()
                Text("${catIcon(u.category)} ${u.category}: এই মাসে ${taka(u.thisMonth, p)} (গড়ের চেয়ে +$pct%)", fontSize = 13.sp, color = Color(0xFFF59E0B))
            }
        }
    }
    AppCard {
        Text("নেট ওয়ার্থ", fontWeight = FontWeight.Bold)
        KeyValue("মোট সম্পদ − দেনা", taka(Insights.netWorth(state), p), Color(0xFF38BDF8))
        Text("সম্পদ: অ্যাকাউন্ট ব্যালেন্স + বিনিয়োগ। দেনা: অপরিশোধিত ধার ও লোনের বাকি।", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 30-day spending heatmap: one cell per day, darker = more spent. */
@Composable
fun HeatmapCard(txs: List<JsonObject>, privacy: Boolean) {
    val values = Insights.dailyExpense(txs, 30)
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val base = Color(0xFFF43F5E)
    AppCard {
        Text("গত ৩০ দিনের খরচের হিটম্যাপ", fontWeight = FontWeight.Bold)
        Text("ঘর যত গাঢ়, সেদিন তত বেশি খরচ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val rows = values.chunked(7)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { v ->
                        val a = if (v <= 0.0) 0.08f else (0.2f + 0.8f * (v / max).toFloat()).coerceAtMost(1f)
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(base.copy(alpha = a), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) { }
                    }
                    repeat(7 - week.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        Text("মোট (৩০ দিন): ${taka(values.sum(), privacy)}", fontSize = 12.sp)
    }
}
