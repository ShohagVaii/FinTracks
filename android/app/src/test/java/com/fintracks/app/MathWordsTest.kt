package com.fintracks.app

import com.fintracks.app.ui.bnWords
import com.fintracks.app.ui.fmtKg
import com.fintracks.app.ui.kgToMaund
import com.fintracks.app.ui.maundToKg
import org.junit.Assert.assertEquals
import org.junit.Test

class MathWordsTest {
    @Test fun maundFromKg() {
        val a = kgToMaund(80.0)
        assertEquals(2L, a.maund); assertEquals(0.0, a.kg, 0.0001)
        val b = kgToMaund(115.0)
        assertEquals(2L, b.maund); assertEquals(35.0, b.kg, 0.0001)
    }

    @Test fun kgFromMaund() {
        assertEquals(75.0, maundToKg(1, 35.0), 0.0001)
        assertEquals("115", fmtKg(maundToKg(2, 35.0)))
    }

    @Test fun wordsExampleFiveThousandOneEighty() {
        assertEquals("পাঁচ হাজার একশো আশি", bnWords(5180))
    }

    @Test fun wordsTakaSuffix() {
        assertEquals("পাঁচ হাজার একশো আশি টাকা", bnWords(5180, taka = true))
    }

    @Test fun wordsLakhCroreGrouping() {
        assertEquals("এক লাখ", bnWords(100_000))
        assertEquals("এক কোটি", bnWords(10_000_000))
        assertEquals("এক কোটি তেইশ লাখ পঁয়তাল্লিশ হাজার ছয়শো আটাত্তর", bnWords(12_345_678))
        assertEquals("শূন্য", bnWords(0))
    }
}
