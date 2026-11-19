package com.busnotice.commute.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RideEstimateTest {
    @Test
    fun `no hops means no estimate`() {
        assertEquals(0, estRideMin(0, 120, 120))
    }

    @Test
    fun `uses hop seconds rounded up`() {
        assertEquals(6, estRideMin(3, 120, 120))
        assertEquals(1, estRideMin(1, 30, 120))
    }

    @Test
    fun `falls back when source has no hop seconds`() {
        assertEquals(3, estRideMin(1, 0, 140))
        assertEquals(7, estRideMin(3, 0, 140))
    }

    @Test
    fun `clamps to one hundred twenty minutes`() {
        assertEquals(120, estRideMin(1000, 120, 120))
    }
}
