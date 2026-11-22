package com.fintracks.app.ui

import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject

/** Income and expense of one period, and what is left after them. */
data class FlowTotals(val income: Double, val expense: Double) {
    val net: Double get() = income - expense
}

/**
 * The one income/expense calculation. The Dashboard, the AI summary and the local answers all use it, so they always agree.
 * Only rows of type Income or Expense whose date (yyyy-MM-dd) is within [from, to] (inclusive) count.
 * Transfers are neither income nor expense. Personal debt and credit (ধার/বাকি) balances are not transactions and are never added here.
 * A debt payment is a real cash transaction and counts once, as an expense, like any other row.
 */
fun flowTotals(txs: List<JsonObject>, from: String, to: String): FlowTotals {
    var income = 0.0
    var expense = 0.0
    for (t in txs) {
        val d = dateOf(t, "tx_date")
        if (d < from || d > to) continue
        when (t.str("type")) {
            "Income" -> income += t.num("amount")
            "Expense" -> expense += t.num("amount")
        }
    }
    return FlowTotals(income, expense)
}

/** Totals for a calendar month given as yyyy-MM. Day 31 is a safe upper bound for every month. */
fun flowTotalsForMonth(txs: List<JsonObject>, month: String): FlowTotals = flowTotals(txs, "$month-01", "$month-31")
