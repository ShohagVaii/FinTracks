package com.fintracks.app

import com.fintracks.app.ui.DuPeriod
import com.fintracks.app.ui.DuMedium
import com.fintracks.app.ui.DuRange
import com.fintracks.app.ui.DuSlot
import com.fintracks.app.ui.DuSlotUsage
import com.fintracks.app.ui.duRangeFor
import com.fintracks.app.ui.duRangeLabel
import com.fintracks.app.ui.duShift
import com.fintracks.app.ui.duSlots
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DataUsagePeriodTest {
    private val utc = ZoneId.of("UTC")
    private val d25 = LocalDate.of(2026, 11, 25)
    private val any = LocalDate.of(2000, 1, 1)

    @Test fun dayWeekMonthRanges() {
        assertEquals(DuRange(d25, d25), duRangeFor(DuPeriod.DAY, d25, any, any))
        assertEquals(DuRange(LocalDate.of(2026, 11, 19), d25), duRangeFor(DuPeriod.WEEK, d25, any, any))
        val month = duRangeFor(DuPeriod.MONTH, d25, any, any)
        assertEquals(LocalDate.of(2026, 11, 1), month.from)
        assertEquals(LocalDate.of(2026, 11, 30), month.to)
        assertEquals(30L, month.days)
    }

    @Test fun customRangeIsOrdered() {
        val r = duRangeFor(DuPeriod.CUSTOM, d25, LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 20))
        assertEquals(DuRange(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 25)), r)
    }

    @Test fun shiftMovesWholePeriods() {
        val nov = duRangeFor(DuPeriod.MONTH, LocalDate.of(2026, 12, 15), any, any)
        assertEquals(LocalDate.of(2026, 11, 1), duShift(DuPeriod.MONTH, nov, -1).from)
        val dec = duShift(DuPeriod.MONTH, nov, 1)
        assertEquals(LocalDate.of(2026, 12, 31), dec.to)
        val jan = duShift(DuPeriod.MONTH, dec, 1)
        assertEquals(DuRange(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)), jan)
        val feb = duShift(DuPeriod.MONTH, jan, 1)
        assertEquals(LocalDate.of(2027, 2, 28), feb.to)
        assertEquals(LocalDate.of(2028, 2, 29), duShift(DuPeriod.MONTH, DuRange(LocalDate.of(2028, 1, 1), LocalDate.of(2028, 1, 31)), 1).to)
        val week = duShift(DuPeriod.WEEK, DuRange(LocalDate.of(2026, 11, 19), d25), 1)
        assertEquals(DuRange(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 2)), week)
        val custom = duShift(DuPeriod.CUSTOM, DuRange(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 24)), -1)
        assertEquals(DuRange(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 19)), custom)
    }

    @Test fun slotCounts() {
        assertEquals(24, duSlots(DuPeriod.DAY, DuRange(d25, d25), utc).size)
        assertEquals(7, duSlots(DuPeriod.WEEK, duRangeFor(DuPeriod.WEEK, d25, any, any), utc).size)
        assertEquals(3, duSlots(DuPeriod.CUSTOM, DuRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)), utc).size)
        val hours = duSlots(DuPeriod.DAY, DuRange(d25, d25), utc)
        assertEquals(3_600_000L, hours[5].end - hours[5].start)
        assertEquals(hours[0].start + 86_400_000L, hours[23].end)
    }

    @Test fun labelsAreBengali() {
        assertEquals("২৫ নভেম্বর", duRangeLabel(DuPeriod.DAY, DuRange(d25, d25)))
        assertEquals("নভেম্বর ২০২৬", duRangeLabel(DuPeriod.MONTH, duRangeFor(DuPeriod.MONTH, d25, any, any)))
        assertEquals("১৯–২৫ নভেম্বর", duRangeLabel(DuPeriod.WEEK, DuRange(LocalDate.of(2026, 11, 19), d25)))
        assertEquals("২৮ অক্টোবর – ৩ নভেম্বর", duRangeLabel(DuPeriod.CUSTOM, DuRange(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 3))))
        assertEquals("25 Nov", duRangeLabel(DuPeriod.DAY, DuRange(d25, d25), english = true))
    }

    @Test fun mediumFilterPicksNetworks() {
        val slot = DuSlot(0L, 1L, "০০", d25)
        val u = DuSlotUsage(slot, mRx = 100, mTx = 10, wRx = 1000, wTx = 50)
        assertEquals(110L, u.total(DuMedium.MOBILE))
        assertEquals(1050L, u.total(DuMedium.WIFI))
        assertEquals(1160L, u.total(DuMedium.BOTH))
        assertEquals(1100L, u.rx(DuMedium.BOTH))
        assertEquals(60L, u.tx(DuMedium.BOTH))
    }
}
