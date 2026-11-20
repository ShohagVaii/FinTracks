package com.fintracks.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/** Date periods shared by the transaction list, reports, and exports. */
enum class Period(val label: String) {
    TODAY("আজ"), WEEK("সপ্তাহ"), MONTH("মাস"), YEAR("বছর"), CUSTOM("কাস্টম"), ALL("সব")
}

/** A resolved date range (inclusive). Null bounds mean open-ended. */
data class DateRange(val from: LocalDate?, val to: LocalDate?, val label: String) {
    fun contains(iso: String): Boolean {
        val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return false
        return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to))
    }
}

fun rangeFor(period: Period, customFrom: String, customTo: String): DateRange {
    val today = LocalDate.now()
    return when (period) {
        Period.TODAY -> DateRange(today, today, "আজ (${today})")
        Period.WEEK -> DateRange(today.minusDays(6), today, "গত ৭ দিন")
        Period.MONTH -> DateRange(today.withDayOfMonth(1), today, "এই মাস (${today.monthValue}/${today.year})")
        Period.YEAR -> DateRange(today.withDayOfYear(1), today, "এই বছর (${today.year})")
        Period.CUSTOM -> {
            val f = runCatching { LocalDate.parse(customFrom.trim()) }.getOrNull()
            val t = runCatching { LocalDate.parse(customTo.trim()) }.getOrNull()
            DateRange(f, t, "কাস্টম: ${customFrom.ifBlank { "শুরু" }} থেকে ${customTo.ifBlank { "আজ" }}")
        }
        Period.ALL -> DateRange(null, null, "সব সময়")
    }
}

/** Period chips plus, for custom ranges, from/to fields in YYYY-MM-DD. */
@Composable
fun PeriodPicker(period: Period, from: String, to: String, onPeriod: (Period) -> Unit, onFrom: (String) -> Unit, onTo: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChips(Period.entries.map { it.name to it.label }, period.name) { onPeriod(Period.valueOf(it)) }
        if (period == Period.CUSTOM) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextIn("শুরু (YYYY-MM-DD)", from, onFrom, modifier = Modifier.weight(1f))
                TextIn("শেষ (YYYY-MM-DD)", to, onTo, modifier = Modifier.weight(1f))
            }
        }
    }
}

fun filterByRange(txs: List<JsonObject>, range: DateRange): List<JsonObject> =
    txs.filter { range.contains(it.str("tx_date")) }
