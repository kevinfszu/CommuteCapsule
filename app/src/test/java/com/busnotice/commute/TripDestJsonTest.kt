package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDestJsonTest {

    @Test
    fun `destination survives json round trip`() {
        val trip = Trip(name = "回家", destLat = 23.1234, destLng = 113.5678, destName = "华师")
        val back = Trip.fromJson(trip.toJson())
        assertTrue(back.destMode())
        assertEquals(23.1234, back.destLat, 0.0)
        assertEquals(113.5678, back.destLng, 0.0)
        assertEquals("华师", back.destName)
    }

    @Test
    fun `trip without destination stays unset`() {
        val back = Trip.fromJson(Trip(name = "上班").toJson())
        assertFalse(back.destMode())
        assertTrue(back.destLat.isNaN())
        assertTrue(back.destLng.isNaN())
        assertEquals("", back.destName)
    }
}
