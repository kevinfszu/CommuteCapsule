package com.busnotice.commute

import com.busnotice.commute.AlightGuard.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 到站阶段判定：换乘站与终点站共用，确认后不再响 */
class AlightGuardTest {

    private fun input(
        remaining: Int = 5,
        atStation: Boolean = false,
        etaKnown: Boolean = true,
        now: Long = 1_000_000L,
        eta: Long = 2_000_000L,
        leadMs: Long = 90_000L,
        acked: Boolean = false,
        overshoot: Boolean = false,
        far: Boolean = false,
    ) = AlightGuard.Input(remaining, atStation, etaKnown, now, eta, leadMs, acked, overshoot, far)

    @Test fun twoStopsAhead() = assertEquals(Stage.T2, AlightGuard.stage(input(remaining = 2)))

    @Test fun oneStopAhead() = assertEquals(Stage.T1, AlightGuard.stage(input(remaining = 1)))

    @Test fun farAwayIsQuiet() = assertEquals(Stage.NONE, AlightGuard.stage(input(remaining = 4)))

    @Test fun timeOnlyWhenEtaKnown() {
        assertEquals(Stage.T1, AlightGuard.stage(input(remaining = 5, eta = 1_060_000L)))
        assertEquals(Stage.NONE, AlightGuard.stage(input(remaining = 5, eta = 1_060_000L, etaKnown = false)))
    }

    @Test fun atStationBeatsTime() = assertEquals(Stage.AT, AlightGuard.stage(input(remaining = 3, atStation = true)))

    @Test fun ackStopsEverything() = assertEquals(Stage.NONE, AlightGuard.stage(input(atStation = true, acked = true)))

    @Test fun overshootWins() = assertEquals(Stage.OVERSHOOT, AlightGuard.stage(input(overshoot = true)))

    @Test fun farLocationSuppresses() = assertEquals(Stage.NONE, AlightGuard.stage(input(remaining = 1, far = true)))

    @Test fun ringingCaps() {
        assertTrue(AlightGuard.shouldRing(Stage.AT))
        assertTrue(AlightGuard.shouldRing(Stage.OVERSHOOT))
        assertFalse(AlightGuard.shouldRing(Stage.T1))
        assertEquals(6, AlightGuard.maxRings(Stage.AT))
        assertEquals(3, AlightGuard.maxRings(Stage.OVERSHOOT))
    }
}
