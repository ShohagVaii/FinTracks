package com.fintracks.app.ui

import com.fintracks.app.data.id
import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Keeps a bike entry and its expense transaction in step, one to one.
 * The entry keeps tx_id; the transaction keeps source = "bike" and source_ref = entry id.
 * Pure: every function returns outbox changes and reads only the rows it is given.
 */
internal object BikeLink {
    const val FUEL = "bike_fuel_entries"
    const val MAINT = "bike_maintenance_entries"
    const val TX = "transactions"

    private fun det(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    /** Stable ids: the same transaction always maps to the same entry id, on every phone. */
    fun fuelIdForTx(txId: String): String = det("bike-fuel:$txId")
    fun txIdForEntry(entryId: String): String = det("bike-tx:$entryId")

    /** A plain expense in the bike category with no source yet (typed in the + form or before the link). */
    fun isManualBikeTx(tx: JsonObject): Boolean =
        tx.str("type") == "Expense" && tx.str("category") == BIKE_CAT && tx.str("source").isEmpty()

    fun insertOp(table: String, r: JsonObject, group: String = ""): JsonObject =
        LocalOutbox.op("insert", table, r.id(), r, r, group)

    fun updateOp(table: String, existing: JsonObject, body: JsonObject, group: String = ""): JsonObject =
        LocalOutbox.op("update", table, existing.id(), body, JsonObject(existing + body), group)

    /** The expense transaction of a bike entry. */
    fun txRow(currency: String, txId: String, amount: Double, date: String, note: String, entryId: String): JsonObject =
        row(
            "id" to txId, "type" to "Expense", "amount" to amount, "currency" to currency,
            "category" to BIKE_CAT, "note" to note, "tx_date" to date, "payment_mode" to "Cash", "mood" to "",
            "account_id" to JsonNull, "to_account_id" to JsonNull, "is_recurring" to false,
            "tags" to JsonArray(emptyList()), "source" to "bike", "source_ref" to entryId
        )

    /** Amount, date and note an entry's transaction shows. Fuel note = station · note; maintenance note = item (or kind). */
    fun txFieldsOf(table: String, entry: JsonObject, kindLabel: (String) -> String): JsonObject {
        val fuel = table == FUEL
        val amount = if (fuel) entry.num("total") else entry.num("cost")
        val note = if (fuel) fuelNote(entry) else entry.str("item").ifBlank { kindLabel(entry.str("kind")) }
        return row("amount" to amount, "tx_date" to entry.str("tx_date").take(10), "note" to note)
    }

    private fun fuelNote(entry: JsonObject): String =
        listOf(entry.str("station"), entry.str("note")).filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * Entry fields that follow an edited linked transaction: amount and date always;
     * the note only when it differs from the generated note (so a station is not lost by accident).
     */
    fun entryFieldsFromTx(table: String, tx: JsonObject, entry: JsonObject, kindLabel: (String) -> String): JsonObject {
        val fuel = table == FUEL
        val base = row(
            (if (fuel) "total" else "cost") to tx.num("amount"),
            "tx_date" to tx.str("tx_date").take(10)
        )
        val generated = txFieldsOf(table, entry, kindLabel).str("note")
        if (tx.str("note") == generated) return base
        val noteKey = if (fuel) "note" else "item"
        return JsonObject(base + mapOf(noteKey to JsonPrimitive(tx.str("note"))))
    }

    /**
     * Changes that link what is not linked yet, both ways:
     * a manual bike expense gets a fuel entry (no odometer, no litres); a bike entry with no tx_id gets its expense.
     * Ids are derived from the other row's id, so a second run (or another phone) makes the same ids and nothing doubles.
     */
    fun backfill(
        txs: List<JsonObject>, fuel: List<JsonObject>, maint: List<JsonObject>,
        currency: String, kindLabel: (String) -> String
    ): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val linked = (fuel + maint).map { it.str("tx_id") }.filter { it.isNotEmpty() }.toSet()
        for (tx in txs) {
            if (!isManualBikeTx(tx) || tx.id() in linked) continue
            val entryId = fuelIdForTx(tx.id())
            val entry = fuelRowFor(entryId, tx.str("tx_date").take(10), tx.num("amount"), "", tx.str("note"), tx.id())
            val group = "bike-link-$entryId"
            out += insertOp(FUEL, entry, group)
            out += updateOp(TX, tx, row("source" to "bike", "source_ref" to entryId), group)
        }
        for (e in fuel) {
            if (e.str("tx_id").isNotEmpty()) continue
            val txId = txIdForEntry(e.id())
            val group = "bike-link-$txId"
            out += insertOp(TX, txRow(currency, txId, e.num("total"), e.str("tx_date").take(10), fuelNote(e), e.id()), group)
            out += updateOp(FUEL, e, row("tx_id" to txId), group)
        }
        for (m in maint) {
            if (m.str("tx_id").isNotEmpty()) continue
            val txId = txIdForEntry(m.id())
            val group = "bike-link-$txId"
            val note = txFieldsOf(MAINT, m, kindLabel).str("note")
            out += insertOp(TX, txRow(currency, txId, m.num("cost"), m.str("tx_date").take(10), note, m.id()), group)
            out += updateOp(MAINT, m, row("tx_id" to txId), group)
        }
        return out
    }

    /** A fuel entry made from a transaction: amount, date, note; no odometer, no litres, not a full-tank pair. */
    fun fuelRowFor(entryId: String, date: String, amount: Double, station: String, note: String, txId: String): JsonObject =
        fuelRow(entryId, date, null, amount, null, null, station, false, note, txId)
}
