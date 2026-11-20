package com.fintracks.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checksum rules for vouchers. The reference value was computed independently with SHA-256. */
class VoucherChecksumTest {

    @Test
    fun referenceExampleMatches() {
        // SHA-256("FA611E53|INCOME|1.00|2026-11-20|SHOHAG VAII") starts with BC9587FE60
        assertEquals(
            "BC9587FE60",
            Voucher.checksum("FA611E53", "INCOME", 1.0, "2026-11-20", "SHOHAG VAII")
        )
    }

    @Test
    fun amountIsTwoDecimals() {
        assertEquals("1.00", Voucher.amount2(1.0))
        assertEquals("2002.50", Voucher.amount2(2002.5))
    }

    @Test
    fun summaryChecksumDoesNotDependOnCreationDate() {
        val a = Voucher.summaryChecksum(2002.0, 2000.0, "2026-11-01..2026-11-30")
        val b = Voucher.summaryChecksum(2002.0, 2000.0, "2026-11-01..2026-11-30")
        assertEquals(a, b)
        assertEquals(10, a.length)
        // SHA-256("BATCH|SUMMARY|2002.00|2000.00|2026-11-01..2026-11-30") starts with 4C7765FC0D
        assertEquals("4C7765FC0D", a)
    }
}
