package com.fintracks.app

import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.ui.BIKE_CAT
import com.fintracks.app.ui.BikeLink
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeLinkTest {
    private val label: (String) -> String = { it }

    private fun tx(id: String, amount: Double = 500.0, cat: String = BIKE_CAT, source: String? = null, sourceRef: String? = null) =
        row(
            "id" to id, "type" to "Expense", "amount" to amount, "category" to cat, "tx_date" to "2026-11-05",
            "note" to "Pump · n", "source" to source, "source_ref" to sourceRef
        )

    private fun fuel(id: String, txId: String?) = row(
        "id" to id, "tx_date" to "2026-11-10", "odo" to 40000.0, "total" to 800.0, "station" to "Pump",
        "note" to "n", "full_tank" to true, "tx_id" to txId
    )

    private fun maint(id: String, txId: String?) = row(
        "id" to id, "tx_date" to "2026-11-12", "kind" to "mobil", "item" to "", "cost" to 1150.0, "tx_id" to txId
    )

    private fun JsonObject.rowOf(): JsonObject = this["row"] as JsonObject

    @Test fun manualBikeExpenseGetsFuelEntryLinkedBothWays() {
        val ops = BikeLink.backfill(listOf(tx("t1")), emptyList(), emptyList(), "BDT", label)
        assertEquals(2, ops.size)
        assertEquals("insert", ops[0].str("op"))
        assertEquals("bike_fuel_entries", ops[0].str("table"))
        assertEquals(BikeLink.fuelIdForTx("t1"), ops[0].str("id"))
        assertEquals(500.0, ops[0].rowOf().num("total"), 0.001)
        assertEquals("", ops[0].rowOf().str("odo"))
        assertEquals("t1", ops[0].rowOf().str("tx_id"))
        assertEquals("transactions", ops[1].str("table"))
        assertEquals("bike", ops[1].rowOf().str("source"))
        assertEquals(BikeLink.fuelIdForTx("t1"), ops[1].rowOf().str("source_ref"))
    }

    @Test fun backfillOfLinkedRowsDoesNothing() {
        val linkedTx = tx("t1", source = "bike", sourceRef = "f1")
        val ops = BikeLink.backfill(listOf(linkedTx), listOf(fuel("f1", "t1")), listOf(maint("m1", "t2")), "BDT", label)
        assertTrue(ops.isEmpty())
    }

    @Test fun otherCategoriesAndSourcesAreIgnored() {
        val ops = BikeLink.backfill(
            listOf(tx("a", cat = "খাবার"), tx("b", source = "debt_payment")),
            emptyList(), emptyList(), "BDT", label
        )
        assertTrue(ops.isEmpty())
    }

    @Test fun unlinkedEntriesGetExpensesWithStableIds() {
        val first = BikeLink.backfill(emptyList(), listOf(fuel("f1", null)), listOf(maint("m1", null)), "BDT", label)
        val second = BikeLink.backfill(emptyList(), listOf(fuel("f1", null)), listOf(maint("m1", null)), "BDT", label)
        assertEquals(first, second)
        assertEquals(4, first.size)
        val fuelTxId = BikeLink.txIdForEntry("f1")
        assertEquals("transactions", first[0].str("table"))
        assertEquals(fuelTxId, first[0].str("id"))
        assertEquals("bike", first[0].rowOf().str("source"))
        assertEquals("f1", first[0].rowOf().str("source_ref"))
        assertEquals(fuelTxId, first[1].rowOf().str("tx_id"))
        val maintTxId = BikeLink.txIdForEntry("m1")
        assertEquals(maintTxId, first[3].rowOf().str("tx_id"))
        assertEquals(1150.0, first[2].rowOf().num("amount"), 0.001)
        assertEquals("mobil", first[2].rowOf().str("note"))
    }

    @Test fun expenseEditFollowsIntoEntry() {
        val entry = fuel("f1", "t1")
        val changed = row("amount" to 900.0, "tx_date" to "2026-11-20", "note" to "Pump · n")
        val f = BikeLink.entryFieldsFromTx("bike_fuel_entries", changed, entry, label)
        assertEquals(900.0, f.num("total"), 0.001)
        assertEquals("2026-11-20", f.str("tx_date"))
        assertNull(f["note"])
    }

    @Test fun changedNoteFollowsIntoEntry() {
        val changed = row("amount" to 800.0, "tx_date" to "2026-11-10", "note" to "Changed")
        val f = BikeLink.entryFieldsFromTx("bike_fuel_entries", changed, fuel("f1", "t1"), label)
        assertEquals("Changed", f.str("note"))
    }

    @Test fun maintenanceEditFollowsIntoEntryItem() {
        val changed = row("amount" to 1500.0, "tx_date" to "2026-11-12", "note" to "Oil change")
        val f = BikeLink.entryFieldsFromTx("bike_maintenance_entries", changed, maint("m1", "t1"), label)
        assertEquals(1500.0, f.num("cost"), 0.001)
        assertEquals("Oil change", f.str("item"))
    }

    @Test fun entryEditFollowsIntoExpense() {
        val merged = fuel("f1", "t1")
        val t = BikeLink.txFieldsOf("bike_fuel_entries", merged, label)
        assertEquals(800.0, t.num("amount"), 0.001)
        assertEquals("Pump · n", t.str("note"))
        assertEquals("2026-11-10", t.str("tx_date"))
    }
}
