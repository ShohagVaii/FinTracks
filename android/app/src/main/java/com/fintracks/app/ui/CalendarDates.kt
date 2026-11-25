package com.fintracks.app.ui

import java.time.LocalDate

/**
 * Hijri and Bangla calendar conversions, shared by the calendar tool and birthday items.
 *
 * Hijri uses the tabular Islamic civil calendar. Bangladesh's moon sighting runs one day later than that
 * arithmetic, so the result is shifted by [HIJRI_OFFSET_DAYS]. Unit tests pin 1 Apr 2005 = 22 Safar 1426 and
 * 25 Nov 2026 = 15 Jumada al-Thani 1448.
 */
const val HIJRI_OFFSET_DAYS = 1L

val CAL_HIJRI_MONTHS = listOf(
    "মুহাররম", "সফর", "রবিউল আউয়াল", "রবিউস সানি", "জমাদিউল আউয়াল", "জমাদিউস সানি",
    "রজব", "শাবান", "রমজান", "শাওয়াল", "জিলকদ", "জিলহজ"
)

val CAL_BN_MONTHS = listOf(
    "বৈশাখ", "জ্যৈষ্ঠ", "আষাঢ়", "শ্রাবণ", "ভাদ্র", "আশ্বিন",
    "কার্তিক", "অগ্রহায়ণ", "পৌষ", "মাঘ", "ফাল্গুন", "চৈত্র"
)

data class HijriDate(val day: Int, val month: Int, val year: Int)

/** Bangla date. [month] is 0-based (0 = বৈশাখ). */
data class BnDate(val day: Int, val month: Int, val year: Int)

private fun hijriEpochDay(y: Long, m: Long, d: Long): Long =
    d + (59 * (m - 1) + 1) / 2 + (y - 1) * 354 + (3 + 11 * y) / 30 + 1948439L - 2440588L

fun hijriOf(date: LocalDate): HijriDate {
    val jdn = date.toEpochDay() + HIJRI_OFFSET_DAYS
    val jd = jdn + 2440588L
    var y = ((jd - 1948440L) / 354.367).toLong() + 1
    while (hijriEpochDay(y, 1, 1) + 2440588L > jd) y--
    while (hijriEpochDay(y + 1, 1, 1) + 2440588L <= jd) y++
    var m = 12L
    while (hijriEpochDay(y, m, 1) + 2440588L > jd) m--
    val d = jd - (hijriEpochDay(y, m, 1) + 2440588L) + 1
    return HijriDate(d.toInt(), m.toInt(), y.toInt())
}

/** Month starts (month, day) in Gregorian, Boishakh first. Months 9..11 fall in the following Gregorian year. */
private val BN_STARTS = listOf(4 to 14, 5 to 15, 6 to 15, 7 to 16, 8 to 16, 9 to 16, 10 to 16, 11 to 16, 12 to 16, 1 to 14, 2 to 13, 3 to 15)

fun bengaliOf(date: LocalDate): BnDate {
    var best: LocalDate? = null
    var idx = 0
    for (y in date.year - 1..date.year + 1) {
        for (i in BN_STARTS.indices) {
            val (m, d) = BN_STARTS[i]
            val s = LocalDate.of(y, m, d)
            if (!s.isAfter(date) && (best == null || s.isAfter(best))) {
                best = s
                idx = i
            }
        }
    }
    val s = best!!
    val year = s.year - 593 - if (idx >= 9) 1 else 0
    return BnDate((date.toEpochDay() - s.toEpochDay()).toInt() + 1, idx, year)
}

/** e.g. "১০ অগ্রহায়ণ ১৪৩৩" (Bengali digits applied by the caller through bnDigits). */
fun bengaliText(date: LocalDate): String {
    val b = bengaliOf(date)
    return "${b.day} ${CAL_BN_MONTHS[b.month]} ${b.year}"
}

/** e.g. "১৫ জমাদিউস সানি ১৪৪৮". */
fun hijriText(date: LocalDate): String {
    val h = hijriOf(date)
    return "${h.day} ${CAL_HIJRI_MONTHS[h.month - 1]} ${h.year}"
}
