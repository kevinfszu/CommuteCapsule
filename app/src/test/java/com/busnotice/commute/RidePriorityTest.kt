package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Test

/** 每段乘车时长优先级：手填 > 地铁按站数估算 > rideMin 默认值 */
class RidePriorityTest {
    private fun leg(rideMin: Int, manual: Boolean) =
        Leg(listOf(Candidate(Candidate.Type.METRO, "13号线", "鱼珠", 0.0, 0.0)), rideMin = rideMin, rideMinManual = manual)

    @Test
    fun `manual ride minutes beat station estimate`() {
        assertEquals(9 * 60, leg(9, manual = true).rideSec(720))
    }

    @Test
    fun `station estimate beats untouched default`() {
        assertEquals(720, leg(10, manual = false).rideSec(720))
    }

    @Test
    fun `falls back to ride minutes when station count is unknown`() {
        assertEquals(10 * 60, leg(10, manual = false).rideSec(null))
    }
}
