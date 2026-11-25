package com.fintracks.app.ui

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Data usage periods: today, the 7 days ending on the anchor, a calendar month, or a custom range. */
enum class DuPeriod { DAY, WEEK, MONTH, CUSTOM }

/** Which networks count: both, Wi-Fi only, or mobile only. */
enum class DuMedium { BOTH, WIFI, MOBILE }

/** Inclusive date range. */
data class DuRange(val from: LocalDate, val to: LocalDate) {
    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1
}

/** One chart bar or history row: epoch-millis bounds plus its Bengali label. */
data class DuSlot(val start: Long, val end: Long, val label: String, val day: LocalDate)

/** Download and upload per network for one slot. */
data class DuSlotUsage(val slot: DuSlot, val mRx: Long, val mTx: Long, val wRx: Long, val wTx: Long) {
    fun rx(m: DuMedium): Long = when (m) {
        DuMedium.BOTH -> mRx + wRx
        DuMedium.WIFI -> wRx
        DuMedium.MOBILE -> mRx
    }

    fun tx(m: DuMedium): Long = when (m) {
        DuMedium.BOTH -> mTx + wTx
        DuMedium.WIFI -> wTx
        DuMedium.MOBILE -> mTx
    }

    fun total(m: DuMedium): Long = rx(m) + tx(m)
}

internal val DU_BN_MONTHS = listOf(
    "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
    "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"
)
internal val DU_EN_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Bengali digits for ASCII digits; other characters pass through. */
internal fun duBnDigits(s: String): String = s.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")

/** The range for a period. [anchor] is the day shown (today, or the day navigated to). */
internal fun duRangeFor(period: DuPeriod, anchor: LocalDate, customFrom: LocalDate, customTo: LocalDate): DuRange = when (period) {
    DuPeriod.DAY -> DuRange(anchor, anchor)
    DuPeriod.WEEK -> DuRange(anchor.minusDays(6), anchor)
    DuPeriod.MONTH -> DuRange(anchor.withDayOfMonth(1), anchor.withDayOfMonth(anchor.lengthOfMonth()))
    DuPeriod.CUSTOM -> if (customFrom <= customTo) DuRange(customFrom, customTo) else DuRange(customTo, customFrom)
}

/** The previous (dir = -1) or next (dir = +1) range of the same length. */
internal fun duShift(period: DuPeriod, range: DuRange, dir: Int): DuRange = when (period) {
    DuPeriod.DAY -> DuRange(range.from.plusDays(dir.toLong()), range.to.plusDays(dir.toLong()))
    DuPeriod.WEEK -> DuRange(range.from.plusDays(7L * dir), range.to.plusDays(7L * dir))
    DuPeriod.MONTH -> {
        val first = range.from.plusMonths(dir.toLong()).withDayOfMonth(1)
        DuRange(first, first.withDayOfMonth(first.lengthOfMonth()))
    }
    DuPeriod.CUSTOM -> {
        val n = range.days * dir
        DuRange(range.from.plusDays(n), range.to.plusDays(n))
    }
}

/** Header label: "২৫ নভেম্বর", "১৯–২৫ নভেম্বর", "২৮ অক্টোবর – ৩ নভেম্বর" or "নভেম্বর ২০২৬". */
internal fun duRangeLabel(period: DuPeriod, range: DuRange, english: Boolean = false): String {
    fun month(d: LocalDate) = if (english) DU_EN_MONTHS[d.monthValue - 1] else DU_BN_MONTHS[d.monthValue - 1]
    fun num(n: Int) = if (english) n.toString() else duBnDigits(n.toString())
    return when {
        period == DuPeriod.MONTH -> month(range.from) + " " + num(range.from.year)
        period == DuPeriod.DAY || range.from == range.to -> num(range.from.dayOfMonth) + " " + month(range.from)
        range.from.year == range.to.year && range.from.monthValue == range.to.monthValue ->
            num(range.from.dayOfMonth) + "–" + num(range.to.dayOfMonth) + " " + month(range.to)
        else -> num(range.from.dayOfMonth) + " " + month(range.from) + " – " + num(range.to.dayOfMonth) + " " + month(range.to)
    }
}

/**
 * Chart slots: 24 hours for the day view, otherwise one slot per day (the last 366 days at most).
 * Bounds are epoch millis in [zone]; the end is exclusive.
 */
internal fun duSlots(period: DuPeriod, range: DuRange, zone: ZoneId): List<DuSlot> {
    if (period == DuPeriod.DAY) {
        val day = range.from
        return (0 until 24).map { h ->
            val start = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
            val end = if (h == 23) day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            else day.atTime(h + 1, 0).atZone(zone).toInstant().toEpochMilli()
            DuSlot(start, end, duBnDigits("%02d".format(h)), day)
        }
    }
    return generateSequence(range.from) { it.plusDays(1) }
        .takeWhile { !it.isAfter(range.to) }
        .toList()
        .takeLast(366)
        .map { d ->
            DuSlot(
                start = d.atStartOfDay(zone).toInstant().toEpochMilli(),
                end = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                label = duBnDigits(d.dayOfMonth.toString()),
                day = d
            )
        }
}

/** Date for app info: "২৫ নভেম্বর ২০২৬" (English: "25 Nov 2026"). */
internal fun duDateLabel(d: LocalDate, english: Boolean = false): String =
    if (english) "${d.dayOfMonth} ${DU_EN_MONTHS[d.monthValue - 1]} ${d.year}"
    else duBnDigits("${d.dayOfMonth}") + " " + DU_BN_MONTHS[d.monthValue - 1] + " " + duBnDigits("${d.year}")

/** First renewal date on or after [from]: the [cycleDay] of a month, clamped to that month's length. */
internal fun nextCycleStart(from: LocalDate, cycleDay: Int): LocalDate {
    val day = cycleDay.coerceIn(1, 31)
    val thisMonth = YearMonth.from(from).let { it.atDay(minOf(day, it.lengthOfMonth())) }
    if (!thisMonth.isBefore(from)) return thisMonth
    val next = YearMonth.from(from).plusMonths(1)
    return next.atDay(minOf(day, next.lengthOfMonth()))
}

/** Daily quota level: 0 below 80%, 1 from 80%, 2 at or above 100%. 0 when no quota is set. */
internal fun quotaLevel(usedToday: Long, quotaBytes: Double): Int = when {
    quotaBytes <= 0.0 -> 0
    usedToday >= quotaBytes -> 2
    usedToday >= quotaBytes * 0.8 -> 1
    else -> 0
}
