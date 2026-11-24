package com.fintracks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import kotlin.math.abs

data class DayTotals(val income: Double, val expense: Double) {
    val net: Double get() = income - expense
}

/** Totals of transactions dated exactly [day]. Independent of the home period selector. */
fun dayTotals(txs: List<JsonObject>, day: LocalDate): DayTotals {
    val key = day.toString()
    val today = txs.filter { it.str("date").take(10) == key }
    return DayTotals(
        income = today.filter { it.str("type") == "Income" }.sumOf { it.num("amount") },
        expense = today.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
    )
}

/** Compact one-row card on the home screen: today's income, expense and net. */
@Composable
fun TodayStrip(txs: List<JsonObject>, privacy: Boolean) {
    val t = dayTotals(txs, LocalDate.now())
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(faint(0.05f))
            .border(1.dp, faint(0.1f), RoundedCornerShape(16.dp)).padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TodayCell(tr("আজকের আয়", "Today's income"), taka(t.income, privacy), Emerald500, Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(28.dp).background(faint(0.1f)))
        TodayCell(tr("আজকের ব্যয়", "Today's expense"), taka(t.expense, privacy), Rose500, Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(28.dp).background(faint(0.1f)))
        val sign = if (t.net < 0) "−" else "+"
        TodayCell(tr("নিট", "Net"), sign + taka(abs(t.net), privacy), netTone(t.net), Modifier.weight(1f))
    }
}

@Composable
private fun TodayCell(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}
