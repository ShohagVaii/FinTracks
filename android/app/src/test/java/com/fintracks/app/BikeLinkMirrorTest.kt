package com.fintracks.app

import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.ui.BIKE_CAT
import com.fintracks.app.ui.BikeLink
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeLinkMirrorTest {
    private val label: (String) -> String = { it }

    private fun tx(id: String, source: String? = null, sourceRef: String? = null, cat: String = BIKE_CAT) = row(
        "id" to id, "type" to "Expense", "amount" to 500.0, "category" to cat, "tx_date" to "2026-11-05",
        "note" to "n", "source" to source, "source_ref" to sourceRef
    )

    private fun fuel(id: String, txId: String?) = row(
        "id" to id, "tx_date" to "2026-11-10", "odo" to 40000.0, "total" to 800.0, "station" to "Pump",
        "note" to "n", "full_tank" to true, "tx_id" to txId
    )

    private fun maint(id: String, txId: String?) = row(
        "id" to id, "tx_date" to "2026-11-12", "kind" to "mobil", "item" to "", "cost" to 1150.0, "tx_id" to txId
    )

    @Test fun deletingBikeExpenseFindsItsFuelEntry() {
        val entries = BikeLink.entriesForTx(tx("t1", "bike", "f1"), listOf(fuel("f1", "t1")), emptyList())
        assertEquals(1, entries.size)
        assertEquals("bike_fuel_entries", entries[0].first)
        assertEquals("f1", entries[0].second.id())
    }

    @Test fun deletingFuelEntryFindsItsExpense() {
        assertEquals(listOf("t1"), BikeLink.txIdsForEntry(fuel("f1", "t1"), listOf(tx("t1", "bike", "f1"))))
    }

    @Test fun deletingMaintenanceEntryFindsItsExpenseOnlyWhenLinked() {
        assertEquals(listOf("t2"), BikeLink.txIdsForEntry(maint("m1", "t2"), listOf(tx("t2"))))
        assertTrue(BikeLink.txIdsForEntry(maint("m1", null), listOf(tx("t2"))).isEmpty())
    }

    @Test fun unlinkedExpenseDeletesNothingElse() {
        assertTrue(BikeLink.entriesForTx(tx("t9"), listOf(fuel("f1", "t1")), listOf(maint("m1", null))).isEmpty())
    }

    @Test fun smsExpenseLinkedByEntryTxIdKeepsItsSource() {
        val s = tx("s1", source = "sms")
        assertEquals("f1", BikeLink.entriesForTx(s, listOf(fuel("f1", "s1")), emptyList()).single().second.id())
        assertEquals(listOf("s1"), BikeLink.txIdsForEntry(fuel("f1", "s1"), listOf(s)))
    }

    @Test fun backfillOfSmsExpenseMakesFuelEntryWithoutChangingSource() {
        val ops = BikeLink.backfill(listOf(tx("s1", source = "sms")), emptyList(), emptyList(), "BDT", label)
        assertEquals(1, ops.size)
        assertEquals("bike_fuel_entries", ops[0].str("table"))
        assertEquals("s1", (ops[0]["body"] as JsonObject).str("tx_id"))
    }

    @Test fun backfillLinksEntryThatAlreadyPointsAtBikeExpense() {
        val ops = BikeLink.backfill(listOf(tx("t9", "bike", "m1")), emptyList(), listOf(maint("m1", null)), "BDT", label)
        assertEquals(1, ops.size)
        assertEquals("bike_maintenance_entries", ops[0].str("table"))
        assertEquals("t9", (ops[0]["body"] as JsonObject).str("tx_id"))
    }

    @Test fun editOfEntryFollowsIntoExpenseAndBack() {
        val f = fuel("f1", "t1")
        val body = BikeLink.txFieldsOf("bike_fuel_entries", row("total" to 900.0, "tx_date" to "2026-11-11", "station" to "Pump", "note" to "n"), label)
        assertEquals(900.0, body.num("amount"), 0.001)
        assertEquals("2026-11-11", body.str("tx_date"))
        val back = BikeLink.entryFieldsFromTx("bike_fuel_entries", tx("t1", "bike", "f1").let { JsonObject(it + mapOf("amount" to kotlinx.serialization.json.JsonPrimitive(700.0))) }, f, label)
        assertEquals(700.0, back.num("total"), 0.001)
    }

    @Test fun paymentMethodGoesOnTheExpense() {
        assertEquals("bKash", BikeLink.txRow("BDT", "t", 1.0, "2026-11-01", "", "f", "bKash").str("payment_mode"))
        assertEquals("Cash", BikeLink.txRow("BDT", "t", 1.0, "2026-11-01", "", "f").str("payment_mode"))
        assertEquals("", BikeLink.txFieldsOf("bike_fuel_entries", fuel("f", "t"), label).str("payment_mode"))
        assertEquals(800.0, BikeLink.txFieldsOf("bike_fuel_entries", fuel("f", "t"), label, "Cash").num("amount"), 0.001)
    }
}
