package com.fintracks.app

import com.fintracks.app.ui.duDateLabel
import com.fintracks.app.ui.duUidDeltas
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataUsageLiveTest {
    @Test fun deltasKeepOnlyActiveUids() {
        val prev = mapOf(10039 to longArrayOf(100, 10), 10317 to longArrayOf(5, 5), 10253 to longArrayOf(-1, -1), 10258 to longArrayOf(0, 0))
        val now = mapOf(
            10039 to longArrayOf(160, 10), 10317 to longArrayOf(5, 5), 10253 to longArrayOf(-1, -1),
            10258 to longArrayOf(30, 0), 10243 to longArrayOf(1, 1)
        )
        val d = duUidDeltas(prev, now)
        assertEquals(setOf(10039, 10258), d.keys)
        assertEquals(60L, d[10039]!![0])
        assertEquals(0L, d[10039]!![1])
        assertEquals(30L, d[10258]!![0])
    }

    @Test fun counterResetIsNotNegative() {
        val d = duUidDeltas(mapOf(1 to longArrayOf(100, 100)), mapOf(1 to longArrayOf(10, 150)))
        assertEquals(0L, d[1]!![0])
        assertEquals(50L, d[1]!![1])
    }

    @Test fun unsupportedUidIsSkipped() {
        assertTrue(duUidDeltas(mapOf(7 to longArrayOf(-1, -1)), mapOf(7 to longArrayOf(9, 9))).isEmpty())
    }

    @Test fun dateLabelIsBengali() {
        assertEquals("২৫ নভেম্বর ২০২৬", duDateLabel(LocalDate.of(2026, 11, 25)))
        assertEquals("25 Nov 2026", duDateLabel(LocalDate.of(2026, 11, 25), english = true))
    }
}
