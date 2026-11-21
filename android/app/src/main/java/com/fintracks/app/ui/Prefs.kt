package com.fintracks.app.ui

import com.fintracks.app.data.str
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Display settings that every screen reads: currency symbol, language, date style, accent and custom categories.
 * They come from the profile, and [apply] runs on every composition of the app root, so a change in Settings
 * reaches all screens on the next frame.
 */
object AppPrefs {
    @Volatile var symbol: String = "৳"
    @Volatile var english: Boolean = false
    /** Default matches the profile default date_format 'DD-MM-YYYY' (English). */
    @Volatile var dateBn: Boolean = false
    @Volatile var accent: AccentColor = AccentColor.SKY
    @Volatile var customCats: List<CustomCat> = emptyList()

    fun apply(state: UiState) {
        symbol = currencySymbol(state.currency)
        english = state.profile?.str("language") == "en"
        dateBn = state.profile?.str("date_format") == "bn"
        accent = state.accent
        customCats = state.rows("categories").map {
            CustomCat(it.str("name"), it.str("icon").ifEmpty { "📦" }, it.str("type"))
        }
    }
}

data class CustomCat(val name: String, val icon: String, val type: String)

fun currencySymbol(code: String): String = when (code) {
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    else -> "৳"
}

/** Bengali when the language is Bengali, English otherwise. Only the strings that call this follow the setting. */
fun tr(bn: String, en: String): String = if (AppPrefs.english) en else bn

/** Default categories plus the user's own ones for one type (Income / Expense). */
fun categoriesFor(type: String): List<Cat> {
    val defaults = if (type == "Income") INCOME_CATS else EXPENSE_CATS
    val custom = AppPrefs.customCats.filter { it.type == type }.map { Cat(it.name, it.icon) }
    return defaults + custom.filter { c -> defaults.none { it.name == c.name } }
}

/**
 * Shows a yyyy-MM-dd date in the chosen style: "১৯ নভেম্বর ২০২৬" (বাংলা) or "19 Nov 2026" (English).
 * Anything that is not a valid date is returned unchanged.
 */
fun displayDate(iso: String): String {
    val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
    return if (AppPrefs.dateBn) {
        bnDigits("${d.dayOfMonth} ${BN_MONTHS[d.monthValue - 1]} ${d.year}")
    } else {
        String.format(Locale.ENGLISH, "%02d-%02d-%04d", d.dayOfMonth, d.monthValue, d.year)
    }
}

/** Month and year for period labels, in the chosen date style. */
fun monthYearLabel(year: Int, month: Int): String =
    if (AppPrefs.dateBn) {
        "${BN_MONTHS[month - 1]} ${bnDigits(year.toString())}"
    } else {
        "${java.time.Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $year"
    }

/** Year alone, in the chosen date style. */
fun yearLabel(year: Int): String = if (AppPrefs.dateBn) bnDigits(year.toString()) else year.toString()
