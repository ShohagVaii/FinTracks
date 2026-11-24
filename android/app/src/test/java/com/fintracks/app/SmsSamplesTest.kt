package com.fintracks.app

import com.fintracks.app.data.SmsParseResult
import com.fintracks.app.data.SmsParser
import com.fintracks.app.data.SmsSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 11 owner samples (bKash 5, Nagad 3, Rocket 1, Dutch-Bangla Bank 2). Every field must come out right. */
class SmsSamplesTest {

    private fun ok(sms: String): SmsSuggestion {
        val r = SmsParser.parseResult(sms)
        assertTrue("parse failed: $sms -> $r", r is SmsParseResult.Ok)
        return (r as SmsParseResult.Ok).suggestion
    }

    private fun check(
        sms: String, amount: Double, type: String, provider: String, date: String,
        time: String, balance: Double, ref: String
    ) {
        val s = ok(sms)
        assertEquals("amount: $sms", amount, s.amount, 0.001)
        assertEquals("type: $sms", type, s.type)
        assertEquals("provider: $sms", provider, s.provider)
        assertEquals("date: $sms", date, s.date)
        assertEquals("time: $sms", time, s.time)
        assertEquals("balance: $sms", balance, s.balance ?: -1.0, 0.001)
        assertEquals("ref: $sms", ref, s.ref)
    }

    @Test fun bkash1() = check(
        "You have received Tk 1,500.00 from 01712345678. Balance Tk 5,250.50. TrxID 9HX7K2P1AB at 24/11/2026 05:30 AM",
        1500.0, "Income", "Bkash", "2026-11-24", "05:30", 5250.50, "9HX7K2P1AB"
    )

    @Test fun bkash2() = check(
        "You have made a payment of Tk 500.00 to 01812345678. Balance Tk 4,750.50. TrxID 9HY3M8Q2CD at 24/11/2026 05:45 AM",
        500.0, "Expense", "Bkash", "2026-11-24", "05:45", 4750.50, "9HY3M8Q2CD"
    )

    @Test fun bkash3() = check(
        "Cash Out Tk 2,000.00 from 01912345678 successful. Fee Tk 37.00. Balance Tk 2,713.50. TrxID 9HZ1A3B4EF at 24/11/2026 11:20 AM",
        2000.0, "Expense", "Bkash", "2026-11-24", "11:20", 2713.50, "9HZ1A3B4EF"
    )

    @Test fun bkash4() = check(
        "Cash In Tk 3,000.00 from 01612345678 successful. Balance Tk 5,713.50. TrxID 9J02C5D6GH at 24/11/2026 12:05 PM",
        3000.0, "Income", "Bkash", "2026-11-24", "12:05", 5713.50, "9J02C5D6GH"
    )

    @Test fun bkash5() = check(
        "You have sent Tk 1,000.00 to 01512345678 successfully. Balance Tk 4,713.50. TrxID 9J14E7F8JK at 24/11/2026 01:15 PM",
        1000.0, "Expense", "Bkash", "2026-11-24", "13:15", 4713.50, "9J14E7F8JK"
    )

    @Test fun nagad1() = check(
        "You have received Tk 2,000.00 from 01912345678. Your Nagad balance is Tk 7,300.00. TxnID: 71ABX9K2 at 24/11/2026 06:10 AM",
        2000.0, "Income", "Nagad", "2026-11-24", "06:10", 7300.0, "71ABX9K2"
    )

    @Test fun nagad2() = check(
        "Your payment of Tk 750.00 to 01712345678 is successful. Your Nagad balance is Tk 6,550.00. TxnID: 72CDY3M4 at 24/11/2026 07:25 AM",
        750.0, "Expense", "Nagad", "2026-11-24", "07:25", 6550.0, "72CDY3M4"
    )

    @Test fun nagad3() = check(
        "Cash out Tk 1,500.00 to 01812345678 successful. Charge Tk 22.50. Your Nagad balance is Tk 5,027.50. TxnID: 73EFZ5N6 at 24/11/2026 08:40 AM",
        1500.0, "Expense", "Nagad", "2026-11-24", "08:40", 5027.50, "73EFZ5N6"
    )

    @Test fun rocket1() = check(
        "Your Rocket account 017123456789 has been credited by Tk 1,200.00 from 01812345678. Current balance Tk 3,860.00. Txn ID: 4589213670. Date: 24-Nov-2026 09:15",
        1200.0, "Income", "Rocket", "2026-11-24", "09:15", 3860.0, "4589213670"
    )

    @Test fun bankDebit() = check(
        "Your A/C **1234 has been debited by BDT 5,000.00 on 24-NOV-2026. Balance: BDT 25,430.75. -Dutch-Bangla Bank",
        5000.0, "Expense", "Bank", "2026-11-24", "", 25430.75, "A/C 1234|2026-11-24|5000.0|25430.75"
    )

    @Test fun bankCredit() = check(
        "Your A/C **1234 has been credited by BDT 12,000.00 on 24-NOV-2026 (EFT). Balance: BDT 37,430.75. -Dutch-Bangla Bank",
        12000.0, "Income", "Bank", "2026-11-24", "", 37430.75, "A/C 1234|2026-11-24|12000.0|37430.75"
    )

    @Test fun nonTransactionReportsWhatIsMissing() {
        val r = SmsParser.parseResult("Your OTP is 123456. Do not share it.")
        assertTrue(r is SmsParseResult.Fail)
        assertTrue((r as SmsParseResult.Fail).missing.contains(com.fintracks.app.data.SmsPart.AMOUNT))
    }
}
