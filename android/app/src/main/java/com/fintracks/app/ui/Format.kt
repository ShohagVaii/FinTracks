package com.fintracks.app.ui

import java.time.LocalDate
import java.util.Locale

/** Money in taka. Privacy mode masks the amount, like the web app. */
fun taka(value: Double, privacy: Boolean = false): String {
    if (privacy) return AppPrefs.symbol + " ••••"
    val whole = value % 1.0 == 0.0
    val body = String.format(Locale.US, if (whole) "%,.0f" else "%,.2f", value)
    return AppPrefs.symbol + " " + (if (AppPrefs.english) body else bnDigits(body))
}

/** 2 instead of 2.0 for whole numbers. */
fun fmtNum(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.2f", value)

fun isValidIsoDate(text: String): Boolean = runCatching { LocalDate.parse(text.trim()) }.isSuccess
fun todayIso(): String = LocalDate.now().toString()

// Category keys are the same Bengali names the web app uses, with the same icons.
data class Cat(val name: String, val icon: String)
val EXPENSE_CATS = listOf(Cat("খাদ্য", "🍔"), Cat("যাতায়াত", "🚗"), Cat("শপিং", "🛍️"), Cat("বিল", "📜"), Cat("অন্যান্য", "📦"))
val INCOME_CATS = listOf(Cat("বেতন", "💰"), Cat("অন্যান্য", "📦"))
val ALL_CATS = (EXPENSE_CATS + INCOME_CATS).distinctBy { it.name }
/**
 * Default category names are stored in the database in Bengali (the same keys as the web app), so the
 * stored value never changes. Only the text shown on screen follows the language switch.
 */
private val DEFAULT_CAT_EN = mapOf(
    "খাদ্য" to "Food", "যাতায়াত" to "Transport", "শপিং" to "Shopping", "বিল" to "Bills",
    "অন্যান্য" to "Other", "বেতন" to "Salary", "ট্রান্সফার" to "Transfer", "বাকি পরিশোধ" to "Credit payment"
)

/** Display name for a category. Custom (user-typed) names are returned unchanged. */
fun catLabel(name: String): String = if (AppPrefs.english) DEFAULT_CAT_EN[name] ?: name else name

fun catIcon(name: String): String =
    (ALL_CATS + AppPrefs.customCats.map { Cat(it.name, it.icon) }).firstOrNull { it.name == name }?.icon ?: "📦"

val PAYMENT_MODES = listOf("Cash", "Card", "Bkash", "Nagad", "Rocket", "Bank")
val MOODS = listOf("😊", "😐", "😞", "😡")

/** Parses user input like "১২০০" or "1,200.50". Returns null when it is not a number. */
fun parseAmount(text: String): Double? = text.replace(",", "").trim().toDoubleOrNull()
