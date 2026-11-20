package com.fintracks.app

import com.fintracks.app.data.num
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.ui.Backup
import com.fintracks.app.ui.CaptureParser
import com.fintracks.app.ui.UiState
import com.fintracks.app.ui.effectiveBudgetLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreLogicTest {

    // ---------- Voice: Bengali amounts ----------

    @Test fun bengaliWordsWithHundreds() {
        assertEquals(500.0, CaptureParser.findAmount("পাঁচশো টাকা বাজার করলাম")!!, 0.0)
    }

    @Test fun wordsWithThousandAndLakh() {
        assertEquals(2000.0, CaptureParser.findAmount("দুই হাজার টাকা")!!, 0.0)
        assertEquals(1500.0, CaptureParser.findAmount("দেড় হাজার টাকা")!!, 0.0)
        assertEquals(250000.0, CaptureParser.findAmount("দুই লাখ পঞ্চাশ হাজার")!!, 0.0)
        assertEquals(5500.0, CaptureParser.findAmount("পাঁচ হাজার পাঁচশো")!!, 0.0)
    }

    @Test fun digitsInBengaliAndLatin() {
        assertEquals(500.0, CaptureParser.findAmount("৫০০ টাকা বাজার")!!, 0.0)
        assertEquals(1500.0, CaptureParser.findAmount("1,500 taka")!!, 0.0)
    }

    @Test fun noAmountReturnsNull() {
        assertNull(CaptureParser.findAmount("আজ বাজার করলাম"))
    }

    @Test fun voiceExpenseGetsTransportCategory() {
        val v = CaptureParser.parseVoice("আজ ট্যাক্সিতে দুইশো টাকা")!!
        assertEquals(200.0, v.amount, 0.0)
        assertEquals("Expense", v.type)
        assertEquals("যাতায়াত", v.category)
    }

    @Test fun voiceSalaryIsIncome() {
        val v = CaptureParser.parseVoice("বেতন পেলাম পঁচিশ হাজার")!!
        assertEquals("Income", v.type)
        assertEquals(25000.0, v.amount, 0.0)
        assertEquals("বেতন", v.category)
    }

    // ---------- Receipt OCR text ----------

    @Test fun receiptPicksTotalNotSubtotal() {
        val raw = "Shop Name Mart\nDate: 19/11/2026\nSubtotal 450.00\nVAT 5.00\nTotal 455.00\nThank you"
        val r = CaptureParser.parseReceipt(raw)
        assertEquals(455.0, r.amount!!, 0.0)
        assertEquals("2026-11-19", r.date)
        assertEquals("Shop Name Mart", r.shop)
    }

    @Test fun receiptWithIsoDate() {
        val r = CaptureParser.parseReceipt("Grocery\n2026-03-07\nGrand Total: 1,250.50")
        assertEquals(1250.5, r.amount!!, 0.0)
        assertEquals("2026-03-07", r.date)
    }

    // ---------- Backup round-trip and validation ----------

    private val txId = "11111111-2222-3333-4444-555555555555"

    @Test fun backupRoundTripKeepsRows() {
        val tx = row("id" to txId, "type" to "Expense", "amount" to 500.0, "category" to "খাদ্য", "tx_date" to "2026-11-10")
        val state = UiState(checking = false, tables = mapOf("transactions" to listOf(tx)))
        val plan = Backup.parse(Backup.json(state))
        assertEquals(1, plan.total)
        val back = plan.tables["transactions"]!!.first()
        assertEquals(500.0, back.num("amount"), 0.0)
        assertEquals("খাদ্য", back.str("category"))
        assertEquals(txId, back.str("id"))
    }

    @Test fun backupNeverContainsPinOrLockData() {
        val state = UiState(checking = false, tables = mapOf("transactions" to listOf(row("id" to txId, "type" to "Income", "amount" to 1.0))))
        val json = Backup.json(state)
        assertTrue(!json.contains("fintrack_lock") && !json.contains("pin_hash") && !json.contains("\"pin\""))
    }

    @Test fun backupRejectsForeignFile() {
        try {
            Backup.parse("""{"app":"Other","format_version":1,"tables":{}}""")
            fail("should reject")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("FinTrack"))
        }
    }

    @Test fun backupRejectsUnknownTableAndBadId() {
        try {
            Backup.parse("""{"app":"FinTrack","format_version":1,"tables":{"secrets":[{"id":"$txId"}]}}""")
            fail("unknown table")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("অজানা টেবিল"))
        }
        try {
            Backup.parse("""{"app":"FinTrack","format_version":1,"tables":{"transactions":[{"id":"not-a-uuid"}]}}""")
            fail("bad id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ID"))
        }
    }

    // ---------- Budget rollover ----------

    @Test fun rolloverAddsUnusedMoney() {
        // Limit 1000, spent 300 in October only. Nothing spent in the 11 months before, so they carry over fully.
        val budget = row("category" to "খাদ্য", "monthly_limit" to 1000.0, "rollover" to true)
        val tx = row("type" to "Expense", "category" to "খাদ্য", "amount" to 300.0, "tx_date" to "2026-10-05")
        assertEquals(12700.0, effectiveBudgetLimit(budget, listOf(tx), "2026-11"), 0.0)
    }

    @Test fun withoutRolloverLimitIsFixed() {
        val budget = row("category" to "খাদ্য", "monthly_limit" to 1000.0, "rollover" to false)
        assertEquals(1000.0, effectiveBudgetLimit(budget, emptyList(), "2026-11"), 0.0)
    }
}
