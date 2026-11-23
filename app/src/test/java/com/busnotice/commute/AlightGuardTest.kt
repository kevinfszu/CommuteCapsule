package com.busnotice.commute

import com.busnotice.commute.AlightGuard.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 到站阶段判定：过完最后一个中间站才提醒，换乘站与终点站共用 */
class AlightGuardTest {

    private fun input(
        remaining: Int = 5,
        atStation: Boolean = false,
        acked: Boolean = false,
        overshoot: Boolean = false,
        far: Boolean = false,
    ) = AlightGuard.Input(remaining, atStation, acked, overshoot, far)

    @Test fun twoStopsAheadIsQuiet() = assertEquals(Stage.NONE, AlightGuard.stage(input(remaining = 2)))

    @Test fun oneStopAhead() = assertEquals(Stage.T1, AlightGuard.stage(input(remaining = 1)))

    @Test fun farAwayIsQuiet() = assertEquals(Stage.NONE, AlightGuard.stage(input(remaining = 4)))

    @Test fun noRouteDataIsQuiet() = assertEquals(Stage.NONE, AlightGuard.stage(input(remaining = -1)))

    @Test fun atStation() = assertEquals(Stage.AT, AlightGuard.stage(input(remaining = 3, atStation = true)))

    @Test fun ackStopsEverything() = assertEquals(Stage.NONE, AlightGuard.stage(input(atStation = true, acked = true)))

    @Test fun overshootWins() = assertEquals(Stage.OVERSHOOT, AlightGuard.stage(input(overshoot = true)))

    @Test fun farLocationSuppresses() = assertEquals(Stage.NONE, AlightGuard.stage(input(remaining = 1, far = true)))

    @Test fun ringingCaps() {
        assertTrue(AlightGuard.shouldRing(Stage.T1))
        assertTrue(AlightGuard.shouldRing(Stage.AT))
        assertTrue(AlightGuard.shouldRing(Stage.OVERSHOOT))
        assertFalse(AlightGuard.shouldRing(Stage.NONE))
        assertEquals(8, AlightGuard.maxRings(Stage.T1))
        assertEquals(8, AlightGuard.maxRings(Stage.AT))
        assertEquals(3, AlightGuard.maxRings(Stage.OVERSHOOT))
    }
}
