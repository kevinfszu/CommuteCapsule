package com.busnotice.commute

import org.junit.Assert.assertEquals
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
        assertEquals("3 分钟后出发", DemoPlayer.frame(at(8, 0)).first.headline)
    }

    @Test fun frameWalkingToStation() {
        assertEquals("约 5 分", DemoPlayer.frame(at(8, 3)).first.headline)
    }

    @Test fun frameTransferAtYuancun() {
        assertEquals("换乘 5号线", DemoPlayer.frame(at(8, 24)).first.headline)
    }

    @Test fun frameArrived() {
        assertEquals("已到达", DemoPlayer.frame(at(8, 50)).first.headline)
    }
}
