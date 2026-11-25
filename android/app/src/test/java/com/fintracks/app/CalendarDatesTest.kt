package com.fintracks.app

import com.fintracks.app.ui.HijriDate
import com.fintracks.app.ui.bengaliText
import com.fintracks.app.ui.hijriOf
import com.fintracks.app.ui.hijriText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CalendarDatesTest {
    @Test
    fun hijriMatchesBangladeshReferenceDates() {
        assertEquals(HijriDate(22, 2, 1426), hijriOf(LocalDate.of(2005, 4, 1)))
        assertEquals(HijriDate(15, 6, 1448), hijriOf(LocalDate.of(2026, 11, 25)))
        assertEquals("15 জমাদিউস সানি 1448", hijriText(LocalDate.of(2026, 11, 25)))
    }

    @Test
    fun banglaDatesMatchOwnerReferences() {
        assertEquals("18 চৈত্র 1411", bengaliText(LocalDate.of(2005, 4, 1)))
        assertEquals("10 অগ্রহায়ণ 1433", bengaliText(LocalDate.of(2026, 11, 25)))
        assertEquals("30 চৈত্র 1432", bengaliText(LocalDate.of(2026, 4, 13)))
        assertEquals("1 বৈশাখ 1433", bengaliText(LocalDate.of(2026, 4, 14)))
    }
}
