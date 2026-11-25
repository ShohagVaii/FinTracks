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
 * The entry keeps tx_id. A bike expense also keeps source = "bike" and source_ref = entry id;
 * an expense that came from another source (SMS, etc.) keeps its source and is linked by the entry's tx_id only.
 * Pure: every function returns outbox changes or reads only the rows it is given.
 */
internal object BikeLink {
    const val FUEL = "bike_fuel_entries"
    const val MAINT = "bike_maintenance_entries"
    const val TX = "transactions"

    /** Sources that belong to another feature and are never turned into bike entries. */
    private val OWNED_SOURCES = setOf("bike", "debt_payment", "personal_debt_payment")

    private fun det(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    /** Stable ids: the same transaction always maps to the same entry id, on every phone. */
    fun fuelIdForTx(txId: String): String = det("bike-fuel:$txId")
    fun txIdForEntry(entryId: String): String = det("bike-tx:$entryId")

    /** A plain expense typed in the + form (no source yet). Saving it creates its fuel entry. */
    fun isManualBikeTx(tx: JsonObject): Boolean =
        tx.str("type") == "Expense" && tx.str("category") == BIKE_CAT && tx.str("source").isEmpty()

    /** A bike-category expense that is not a bike entry or another feature's payment. */
    fun isBikeExpense(tx: JsonObject): Boolean =
        tx.str("type") == "Expense" && tx.str("category") == BIKE_CAT && tx.str("source") !in OWNED_SOURCES

    fun insertOp(table: String, r: JsonObject, group: String = ""): JsonObject =
        LocalOutbox.op("insert", table, r.id(), r, r, group)

    fun updateOp(table: String, existing: JsonObject, body: JsonObject, group: String = ""): JsonObject =
        LocalOutbox.op("update", table, existing.id(), body, JsonObject(existing + body), group)

    /** The expense transaction of a bike entry. */
    fun txRow(currency: String, txId: String, amount: Double, date: String, note: String, entryId: String,
              paymentMode: String = "Cash"): JsonObject =
        row(
            "id" to txId, "type" to "Expense", "amount" to amount, "currency" to currency,
            "category" to BIKE_CAT, "note" to note, "tx_date" to date, "payment_mode" to paymentMode, "mood" to "",
            "account_id" to JsonNull, "to_account_id" to JsonNull, "is_recurring" to false,
            "tags" to JsonArray(emptyList()), "source" to "bike", "source_ref" to entryId
        )

    /** Amount, date, note (and payment method when given) that an entry's transaction shows. Fuel note = station · note. */
    fun txFieldsOf(table: String, entry: JsonObject, kindLabel: (String) -> String, paymentMode: String? = null): JsonObject {
        val fuel = table == FUEL
        val amount = if (fuel) entry.num("total") else entry.num("cost")
        val note = if (fuel) fuelNote(entry) else entry.str("item").ifBlank { kindLabel(entry.str("kind")) }
        return row(
            "amount" to amount, "tx_date" to entry.str("tx_date").take(10), "note" to note,
            "payment_mode" to paymentMode
        )
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

    /** Bike entries linked to a transaction: by source_ref (bike expenses) or by the entry's tx_id (any source). */
    fun entriesForTx(tx: JsonObject, fuel: List<JsonObject>, maint: List<JsonObject>): List<Pair<String, JsonObject>> {
        val ref = if (tx.str("source") == "bike") tx.str("source_ref") else ""
        val all = fuel.map { FUEL to it } + maint.map { MAINT to it }
        return all.filter { (_, e) ->
            (ref.isNotEmpty() && e.id() == ref) || (e.str("tx_id").isNotEmpty() && e.str("tx_id") == tx.id())
        }.distinctBy { it.second.id() }
    }

    /** Transactions linked to an entry: its tx_id, and any bike expense whose source_ref is the entry. */
    fun txIdsForEntry(entry: JsonObject, txs: List<JsonObject>): List<String> =
        txs.filter { t ->
            (t.str("source") == "bike" && t.str("source_ref") == entry.id()) ||
                (entry.str("tx_id").isNotEmpty() && t.id() == entry.str("tx_id"))
        }.map { it.id() }.distinct()

    /**
     * Changes that link what is not linked yet, both ways:
     * a bike expense with no entry gets a fuel entry (no odometer, no litres); a bike entry with no tx_id gets its
     * expense, or the bike expense that already points at it. Ids come from the other row, so a second run
     * (or another phone) makes the same ids; inserts are upserts on the server, so nothing doubles.
     */
    fun backfill(
        txs: List<JsonObject>, fuel: List<JsonObject>, maint: List<JsonObject>,
        currency: String, kindLabel: (String) -> String
    ): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val linked = (fuel + maint).map { it.str("tx_id") }.filter { it.isNotEmpty() }.toSet()
        val entryIds = (fuel + maint).map { it.id() }.toSet()
        for (tx in txs) {
            if (!isBikeExpense(tx) || tx.id() in linked) continue
            // A bike expense that already names an existing entry is linked by the entry loop below, not duplicated.
            if (tx.str("source") == "bike" && tx.str("source_ref") in entryIds) continue
            val entryId = fuelIdForTx(tx.id())
            val group = "bike-link-$entryId"
            out += insertOp(FUEL, fuelRowFor(entryId, tx.str("tx_date").take(10), tx.num("amount"), "", tx.str("note"), tx.id()), group)
            // Only a plain expense gets source = bike; another source keeps its own and is linked by tx_id.
            if (tx.str("source").isEmpty()) out += updateOp(TX, tx, row("source" to "bike", "source_ref" to entryId), group)
        }
        for ((table, list) in listOf(FUEL to fuel, MAINT to maint)) {
            for (e in list) {
                if (e.str("tx_id").isNotEmpty()) continue
                val pointing = txs.firstOrNull { it.str("source") == "bike" && it.str("source_ref") == e.id() }
                if (pointing != null) {
                    out += updateOp(table, e, row("tx_id" to pointing.id()))
                    continue
                }
                val txId = txIdForEntry(e.id())
                val group = "bike-link-$txId"
                val amount = if (table == FUEL) e.num("total") else e.num("cost")
                val note = txFieldsOf(table, e, kindLabel).str("note")
                out += insertOp(TX, txRow(currency, txId, amount, e.str("tx_date").take(10), note, e.id()), group)
                out += updateOp(table, e, row("tx_id" to txId), group)
            }
        }
        return out
    }

    /** A fuel entry made from a transaction: amount, date, note; no odometer, no litres. */
    fun fuelRowFor(entryId: String, date: String, amount: Double, station: String, note: String, txId: String): JsonObject =
        fuelRow(entryId, date, null, amount, null, null, station, false, note, txId)
}
