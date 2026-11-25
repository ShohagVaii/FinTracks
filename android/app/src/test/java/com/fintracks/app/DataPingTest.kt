package com.fintracks.app

import com.fintracks.app.ui.parsePingMs
import com.fintracks.app.ui.pingStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataPingTest {
    @Test fun parsesReplyTime() {
        assertEquals(12.4, parsePingMs("64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=12.4 ms")!!, 0.001)
        assertEquals(1.0, parsePingMs("reply time<1 ms")!!, 0.001)
    }

    @Test fun noReplyIsNull() {
        assertNull(parsePingMs("Request timeout for icmp_seq 1"))
    }

    @Test fun statsSkipLosses() {
        val s = pingStat(listOf(10.0, null, 20.0, 30.0))
        assertEquals(20.0, s.avg!!, 0.001)
        assertEquals(10.0, s.min!!, 0.001)
        assertEquals(30.0, s.max!!, 0.001)
        assertEquals(25, s.lossPct)
    }

    @Test fun allLostHasNoAverage() {
        val s = pingStat(listOf(null, null))
        assertNull(s.avg)
        assertEquals(100, s.lossPct)
    }
}
