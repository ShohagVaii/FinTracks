package com.fintracks.app.ui

import java.time.LocalDate
import java.util.Locale

/** Formats an amount in taka. In privacy mode the amount is masked. */
fun taka(value: Double, privacy: Boolean = false): String {
    if (privacy) return "৳ ••••"
    val whole = value % 1.0 == 0.0
    return "৳ " + String.format(Locale.US, if (whole) "%,.0f" else "%,.2f", value)
}

/** Shows 2 not 2.0 for whole numbers. */
fun num(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

fun isValidIsoDate(text: String): Boolean =
    runCatching { LocalDate.parse(text.trim()) }.isSuccess

fun todayIso(): String = LocalDate.now().toString()

fun currentMonthPrefix(): String = LocalDate.now().toString().take(7)
