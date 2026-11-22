package com.fintracks.app

import com.fintracks.app.ui.CalcParser
import com.fintracks.app.ui.CalcResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calculator rules: an unfinished expression keeps the last complete value; only real mistakes are errors. */
class CalcParserTest {

    @Test
    fun completeExpressionsGiveTheirValue() {
        assertEquals(24.0, CalcParser.eval("12+12")!!, 1e-9)
        assertEquals(14.0, CalcParser.eval("2+3*4")!!, 1e-9)
        assertEquals(20.0, CalcParser.eval("(2+3)*4")!!, 1e-9)
        assertEquals(0.5, CalcParser.eval("50%")!!, 1e-9)
    }

    @Test
    fun incompleteExpressionShowsLastCompleteValue() {
        val s = CalcParser.show("12+")
        assertEquals(12.0, s.value!!, 1e-9)
        assertTrue(s.lastComplete)
        assertTrue(!s.invalid)
    }

    @Test
    fun resultChangesOnlyWhenExpressionIsComplete() {
        assertEquals(12.0, CalcParser.show("12+").value!!, 1e-9)
        assertEquals(24.0, CalcParser.show("12+12").value!!, 1e-9)
    }

    @Test
    fun openParenthesisIsIncompleteNotInvalid() {
        assertEquals(CalcResult.Incomplete, CalcParser.evaluate("(3"))
        assertEquals(3.0, CalcParser.show("(3").value!!, 1e-9)
    }

    @Test
    fun divisionByZeroIsInvalid() {
        assertEquals(CalcResult.Invalid, CalcParser.evaluate("12/0"))
        assertTrue(CalcParser.show("12/0").invalid)
    }

    @Test
    fun garbageIsInvalid() {
        assertEquals(CalcResult.Invalid, CalcParser.evaluate("1+)"))
        assertEquals(CalcResult.Invalid, CalcParser.evaluate("abc"))
    }

    @Test
    fun symbolsFromKeypadAreAccepted() {
        assertEquals(6.0, CalcParser.eval("2×3")!!, 1e-9)
        assertEquals(3.0, CalcParser.eval("6÷2")!!, 1e-9)
        assertEquals(1.0, CalcParser.eval("3−2")!!, 1e-9)
    }
}
