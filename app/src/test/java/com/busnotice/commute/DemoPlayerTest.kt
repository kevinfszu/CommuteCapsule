package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoPlayerTest {
    private fun at(h: Int, m: Int) = h * 3600 + m * 60

    @Test fun tripIsMorningCommute() {
        val legs = DemoPlayer.trip.legs
        assertEquals(2, legs.size)
        assertEquals("11号线", legs[0].title())
        assertEquals("5号线", legs[1].title())
        assertEquals("逸景路", legs[0].candidates[0].stopName)
        assertEquals("员村", legs[0].candidates[0].alightName)
        assertEquals("员村", legs[1].candidates[0].stopName)
        assertEquals("科韵路", legs[1].candidates[0].alightName)
        assertEquals(2, DemoPlayer.showTrips.size)
    }

    @Test fun frameBeforeDeparture() {
        assertEquals("3 分钟后出发", DemoPlayer.frame(at(8, 9)).first.headline)
    }

    @Test fun frameWalkingToStation() {
        assertEquals("约 4 分", DemoPlayer.frame(at(8, 12)).first.headline)
    }

    @Test fun frameRideBeforeLastStop() {
        val s = DemoPlayer.frame(at(8, 26)).first
        assertEquals("约 2 分", s.headline)
        assertFalse(s.alightDue)
        assertEquals(4, s.stopsPassed)
    }

    @Test fun frameAlightDueAtLastStop() {
        val s = DemoPlayer.frame(at(8, 27)).first
        assertEquals("即将到达", s.headline)
        assertTrue(s.alightDue)
        assertEquals(5, s.stopsPassed)
        assertEquals("琶洲", s.curStop)
        assertEquals("员村", s.toStop)
    }

    @Test fun frameTransferAtYuancun() {
        assertEquals("约 3 分", DemoPlayer.frame(at(8, 28)).first.headline)
    }

    @Test fun frameFinalLegOneStop() {
        val s = DemoPlayer.frame(at(8, 32)).first
        assertEquals("即将到达", s.headline)
        assertTrue(s.alightDue)
        assertEquals(1, s.stopsTotal)
    }

    @Test fun frameArrived() {
        assertEquals("已到达", DemoPlayer.frame(at(8, 37)).first.headline)
    }
}
