package com.fintracks.app

import com.fintracks.app.ui.NavHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class NavHistoryTest {
    private val home = NavHistory.HOME

    @Test
    fun ownerChain_backWalksTheSamePath() {
        // home -> transactions -> shared -> settings -> categories
        var s = listOf(home)
        s = NavHistory.push(s, "tab:transactions")
        s = NavHistory.push(s, "tab:shared")
        s = NavHistory.push(s, "ov:SETTINGS")
        s = NavHistory.push(s, "ov:SETTINGS/categories")
        assertEquals(listOf(home, "tab:transactions", "tab:shared", "ov:SETTINGS", "ov:SETTINGS/categories"), s)

        s = NavHistory.pop(s); assertEquals("ov:SETTINGS", s.last())
        s = NavHistory.pop(s); assertEquals("tab:shared", s.last())
        s = NavHistory.pop(s); assertEquals("tab:transactions", s.last())
        s = NavHistory.pop(s); assertEquals(home, s.last())
        // At home the stack is empty of extra entries: the screen asks to exit instead of popping.
        assertEquals(listOf(home), NavHistory.pop(s))
    }

    @Test
    fun toolsChain_backReturnsToToolsSection() {
        var s = listOf(home)
        s = NavHistory.push(s, "tab:tools")
        s = NavHistory.push(s, "ov:ZAKAT")
        assertEquals("tab:tools", NavHistory.pop(s).last())
    }

    @Test
    fun samePageTwiceInARow_isOneEntry() {
        val s = NavHistory.push(listOf(home, "tab:transactions"), "tab:transactions")
        assertEquals(listOf(home, "tab:transactions"), s)
    }

    @Test
    fun dialogRoutes_doNotChangeTheScreenUnderneath() {
        val s = listOf(home, "tab:transactions", "rcpt:abc")
        assertEquals("tab:transactions", NavHistory.base(s))
        assertEquals("tab:transactions", NavHistory.base(NavHistory.pop(s)))
    }

    @Test
    fun overlayParsing_splitsSubPage() {
        assertEquals("SETTINGS" to "login", NavHistory.overlay("ov:SETTINGS/login"))
        assertEquals("INBOX" to "", NavHistory.overlay("ov:INBOX"))
        assertEquals(null, NavHistory.overlay("tab:shared"))
    }
}
