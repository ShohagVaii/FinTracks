package com.fintracks.app

import com.fintracks.app.ui.nextCycleStart
import com.fintracks.app.ui.quotaLevel
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DataPackCycleTest {
    private val gb = 1_073_741_824.0

    @Test fun renewalDayAheadInSameMonth() {
        assertEquals(LocalDate.of(2026, 11, 25), nextCycleStart(LocalDate.of(2026, 11, 19), 25))
    }

    @Test fun renewalDayPassedRollsToNextMonth() {
        assertEquals(LocalDate.of(2026, 12, 25), nextCycleStart(LocalDate.of(2026, 11, 26), 25))
    }

    @Test fun renewalDayTodayIsToday() {
        assertEquals(LocalDate.of(2026, 11, 25), nextCycleStart(LocalDate.of(2026, 11, 25), 25))
    }

    @Test fun day31ClampsToShortMonth() {
        assertEquals(LocalDate.of(2026, 11, 30), nextCycleStart(LocalDate.of(2026, 11, 19), 31))
        assertEquals(LocalDate.of(2027, 2, 28), nextCycleStart(LocalDate.of(2027, 2, 1), 31))
    }

    @Test fun dayOneRollsToNextMonth() {
        assertEquals(LocalDate.of(2026, 12, 1), nextCycleStart(LocalDate.of(2026, 11, 19), 1))
    }

    @Test fun quotaLevels() {
        assertEquals(0, quotaLevel(0L, 0.0))
        assertEquals(0, quotaLevel(5L * gb.toLong(), 10 * gb))
        assertEquals(1, quotaLevel((8 * gb).toLong(), 10 * gb))
        assertEquals(2, quotaLevel((10 * gb).toLong(), 10 * gb))
    }
}
