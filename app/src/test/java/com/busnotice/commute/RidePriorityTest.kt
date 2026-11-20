package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Test

/** 每段乘车时长统一取值：实测 > 手填 > 地铁按站数估算 > 兜底 */
class RidePriorityTest {
    private fun leg(rideMin: Int, manual: Boolean) =
        Leg(listOf(Candidate(Candidate.Type.METRO, "13号线", "鱼珠", 0.0, 0.0)), rideMin = rideMin, rideMinManual = manual)

    @Test
    fun `learned median overrides manual value`() {
        val r = Learn.resolve(7, leg(9, manual = true), 720)
        assertEquals(7, r.minutes)
        assertEquals(Learn.Ride.Source.LEARNED, r.source)
    }

    @Test
    fun `manual value beats station estimate`() {
        val r = Learn.resolve(null, leg(9, manual = true), 720)
        assertEquals(9, r.minutes)
        assertEquals(Learn.Ride.Source.MANUAL, r.source)
    }

    @Test
    fun `station estimate beats untouched default`() {
        val r = Learn.resolve(null, leg(10, manual = false), 720)
        assertEquals(12, r.minutes)
        assertEquals(Learn.Ride.Source.ESTIMATE, r.source)
    }

    @Test
    fun `falls back to ride minutes when station count is unknown`() {
        val r = Learn.resolve(null, leg(10, manual = false), null)
        assertEquals(10, r.minutes)
        assertEquals(Learn.Ride.Source.DEFAULT, r.source)
    }

    @Test
    fun `learned value is scaled for partial join`() {
        assertEquals(10, Learn.resolve(20, leg(10, manual = true), null, learnScale = 0.5).minutes)
    }
}
