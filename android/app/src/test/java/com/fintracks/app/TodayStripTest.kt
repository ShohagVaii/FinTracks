package com.fintracks.app

import com.fintracks.app.ui.dayTotals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TodayStripTest {
    private fun tx(date: String, type: String, amount: Double) = JsonObject(
        mapOf("date" to JsonPrimitive(date), "type" to JsonPrimitive(type), "amount" to JsonPrimitive(amount))
    )

    @Test fun onlyTodayCounts() {
        val day = LocalDate.of(2026, 11, 23)
        val txs = listOf(
            tx("2026-11-23", "Income", 5000.0),
            tx("2026-11-23", "Expense", 1200.0),
            tx("2026-11-23", "Expense", 300.0),
            tx("2026-11-22", "Income", 9999.0),
            tx("2026-11-24", "Expense", 9999.0)
        )
        val t = dayTotals(txs, day)
        assertEquals(5000.0, t.income, 0.0001)
        assertEquals(1500.0, t.expense, 0.0001)
        assertEquals(3500.0, t.net, 0.0001)
    }

    @Test fun emptyDayIsZero() {
        val t = dayTotals(emptyList(), LocalDate.of(2026, 11, 23))
        assertEquals(0.0, t.net, 0.0001)
    }
}
