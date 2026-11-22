package com.fintracks.app

import com.fintracks.app.data.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Real-format sample messages (bKash, Nagad, bank; English and Bengali digits).
 * The provider comes from the brand name in the text. The English bKash samples start with "bKash:" (the sender
 * header, as pasted). A message with no brand name is saved with the provider "Cash".
 * Each one checks amount, type (Income/Expense), provider and the date read from the text.
 */
class SmsParserTest {

    private fun check(sms: String, amount: Double, type: String, provider: String, date: String) {
        val s = SmsParser.parse(sms)
        assertEquals("parse failed: $sms", true, s != null)
        s!!
        assertEquals("amount: $sms", amount, s.amount, 0.001)
        assertEquals("type: $sms", type, s.type)
        assertEquals("provider: $sms", provider, s.provider)
        assertEquals("date: $sms", date, s.date)
    }

    @Test fun bkashSendEnglish() = check(
        "bKash: You have sent Tk 500.00 to 017XXXXXXXX. Fee Tk 5.00. Balance Tk 1,234.56. TrxID 8N7A1B2C3D at 19/11/2026 10:45.",
        500.0, "Expense", "Bkash", "2026-11-19"
    )

    @Test fun bkashReceivedEnglish() = check(
        "bKash: You have received Tk 1,200.00 from 01812345678. Fee Tk 0.00. Balance Tk 3,000.00. TrxID 9K2L3M4N5P on 18/11/2026 at 09:12.",
        1200.0, "Income", "Bkash", "2026-11-18"
    )

    @Test fun bkashBengaliDigits() = check(
        "বিকাশ: আপনি ০১৭১২৩৪৫৬৭৮ নম্বরে ৳৫০০.০০ পাঠিয়েছেন। ফি ৳৫.০০। ব্যালেন্স ৳১২৩৪.৫৬। ১৯/১১/২০২৬ ১০:৪৫",
        500.0, "Expense", "Bkash", "2026-11-19"
    )

    @Test fun nagadReceived() = check(
        "Nagad: You have received Tk 2,000.00 from 01XXXXXXXXX. Balance Tk 5,000.00. TxnID 7ABC on 17-Nov-2026.",
        2000.0, "Income", "Nagad", "2026-11-17"
    )

    @Test fun bankDebitedBdt() = check(
        "Dear Customer, BDT 4,500.00 has been debited from your A/C ****1234 on 16/11/2026. Balance BDT 20,000.00.",
        4500.0, "Expense", "Bank", "2026-11-16"
    )

    @Test fun bankCreditedTk() = check(
        "Your A/C 1234 credited with Tk 15,000 on 15-11-2026.",
        15000.0, "Income", "Bank", "2026-11-15"
    )

    @Test fun bankBengaliSuffixAmount() = check(
        "আপনার হিসাবে ৫০০০ টাকা জমা হয়েছে। ব্যালেন্স ১০০০০ টাকা।",
        5000.0, "Income", "Cash", ""
    )

    @Test fun notATransactionIsIgnored() {
        assertNull(SmsParser.parse("Your OTP is 123456. Do not share it."))
        assertNull(SmsParser.parse("Tk 500 offer: visit our shop this weekend."))
    }
}
