package com.fintracks.app

import com.fintracks.app.ui.ageStats
import com.fintracks.app.ui.bengaliText
import com.fintracks.app.ui.groupIndian
import com.fintracks.app.ui.hijriText
import com.fintracks.app.ui.zodiacBn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class AgeCalcTest {
    private val birth = LocalDate.of(2005, 4, 1)
    private val asOf = LocalDate.of(2026, 11, 25)

    @Test
    fun ageAndTotalsMatchOwnerCase() {
        val s = ageStats(birth, asOf)!!
        assertEquals(21, s.years)
        assertEquals(7, s.months)
        assertEquals(24, s.days)
        assertEquals(7908L, s.totalDays)
        assertEquals(1129L, s.weeks)
        assertEquals(259L, s.totalMonths)
        assertEquals(189792L, s.hours)
        assertEquals(11387520L, s.minutes)
        assertEquals(683251200L, s.seconds)
        assertEquals(DayOfWeek.FRIDAY, s.weekday)
    }

    @Test
    fun nextBirthdayAndProgressMatchOwnerCase() {
        val s = ageStats(birth, asOf)!!
        assertEquals(LocalDate.of(2027, 4, 1), s.next)
        assertEquals(127L, s.daysToNext)
        assertEquals(65, Math.round(s.progress * 100).toInt())
        assertFalse(s.isBirthdayToday)
    }

    @Test
    fun calendarDatesMatchOwnerCase() {
        assertEquals("18 চৈত্র 1411", bengaliText(birth))
        assertEquals("22 সফর 1426", hijriText(birth))
        assertEquals("10 অগ্রহায়ণ 1433", bengaliText(asOf))
        assertEquals("15 জমাদিউস সানি 1448", hijriText(asOf))
    }

    @Test
    fun indianGroupingAndZodiac() {
        assertEquals("7,906", groupIndian(7906))
        assertEquals("1,89,792", groupIndian(189792))
        assertEquals("1,13,87,520", groupIndian(11387520))
        assertEquals("68,32,51,200", groupIndian(683251200))
        assertEquals("মেষ", zodiacBn(birth))
    }

    @Test
    fun birthdayTodayAndFutureBirth() {
        val today = ageStats(LocalDate.of(2000, 11, 25), asOf)!!
        assertTrue(today.isBirthdayToday)
        assertEquals(0L, today.daysToNext)
        assertNull(ageStats(LocalDate.of(2027, 1, 1), asOf))
    }
}
