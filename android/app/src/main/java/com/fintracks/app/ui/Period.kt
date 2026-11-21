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
enum class Period(private val bnLabel: String, private val enLabel: String) {
    TODAY("আজ", "Today"), WEEK("সপ্তাহ", "Week"), MONTH("মাস", "Month"), YEAR("বছর", "Year"), CUSTOM("কাস্টম", "Custom"), ALL("সব", "All");

    /** Read on every use, so a language change applies without a restart. */
    val label: String get() = tr(bnLabel, enLabel)
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
        Period.TODAY -> DateRange(today, today, tr("আজ", "Today") + " (${displayDate(today.toString())})")
        Period.WEEK -> DateRange(today.minusDays(6), today, tr("গত ৭ দিন", "Last 7 days"))
        Period.MONTH -> DateRange(today.withDayOfMonth(1), today, tr("এই মাস", "This month") + " (${monthYearLabel(today.year, today.monthValue)})")
        Period.YEAR -> DateRange(today.withDayOfYear(1), today, tr("এই বছর", "This year") + " (${yearLabel(today.year)})")
        Period.CUSTOM -> {
            val f = runCatching { LocalDate.parse(customFrom.trim()) }.getOrNull()
            val t = runCatching { LocalDate.parse(customTo.trim()) }.getOrNull()
            val fromTxt = if (customFrom.isBlank()) tr("শুরু", "start") else displayDate(customFrom)
            val toTxt = if (customTo.isBlank()) tr("আজ", "today") else displayDate(customTo)
            DateRange(f, t, tr("কাস্টম", "Custom") + ": $fromTxt " + tr("থেকে", "to") + " $toTxt")
        }
        Period.ALL -> DateRange(null, null, tr("সব সময়", "All time"))
    }
}

/** Period chips plus, for custom ranges, from/to fields in YYYY-MM-DD. */
@Composable
fun PeriodPicker(period: Period, from: String, to: String, onPeriod: (Period) -> Unit, onFrom: (String) -> Unit, onTo: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChips(Period.entries.map { it.name to it.label }, period.name) { onPeriod(Period.valueOf(it)) }
        if (period == Period.CUSTOM) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(tr("শুরু", "From"), from, onFrom, modifier = Modifier.weight(1f))
                DateField(tr("শেষ", "To"), to, onTo, modifier = Modifier.weight(1f))
            }
        }
    }
}

fun filterByRange(txs: List<JsonObject>, range: DateRange): List<JsonObject> =
    txs.filter { range.contains(it.str("tx_date")) }
