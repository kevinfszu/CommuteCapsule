package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTransitTest {
    private val sample = """
        {
          "region": "test",
          "lines": [
            {
              "id": "l13e", "name": "13号线", "subway": true, "reverseId": "l13w",
              "first": "06:00", "last": "23:00", "hopSec": 120,
              "stops": [
                { "name": "A", "lat": 23.000, "lng": 113.000 },
                { "name": "B", "lat": 23.010, "lng": 113.000 },
                { "name": "C", "lat": 23.020, "lng": 113.000 },
                { "name": "D", "lat": 23.030, "lng": 113.000 }
              ]
            },
            {
              "id": "l13w", "name": "13号线", "subway": true, "reverseId": "l13e",
              "first": "06:00", "last": "23:00", "hopSec": 120,
              "stops": [
                { "name": "D", "lat": 23.030, "lng": 113.000 },
                { "name": "C", "lat": 23.020, "lng": 113.000 },
                { "name": "B", "lat": 23.010, "lng": 113.000 },
                { "name": "A", "lat": 23.000, "lng": 113.000 }
              ]
            },
            {
              "id": "l11i", "name": "11号线", "subway": true, "loop": true, "dirLabel": "内环",
              "reverseId": "l11o", "first": "06:00", "last": "23:30", "hopSec": 140,
              "stops": [
                { "name": "P", "lat": 23.100, "lng": 113.100 },
                { "name": "Q", "lat": 23.110, "lng": 113.100 },
                { "name": "R", "lat": 23.120, "lng": 113.100 },
                { "name": "S", "lat": 23.130, "lng": 113.100 },
                { "name": "T", "lat": 23.140, "lng": 113.100 },
                { "name": "P", "lat": 23.100, "lng": 113.100 }
              ]
            },
            {
              "id": "l11o", "name": "11号线", "subway": true, "loop": true, "dirLabel": "外环",
              "reverseId": "l11i", "first": "06:00", "last": "23:30", "hopSec": 140,
              "stops": [
                { "name": "T", "lat": 23.140, "lng": 113.100 },
                { "name": "S", "lat": 23.130, "lng": 113.100 },
                { "name": "R", "lat": 23.120, "lng": 113.100 },
                { "name": "Q", "lat": 23.110, "lng": 113.100 },
                { "name": "P", "lat": 23.100, "lng": 113.100 }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parse drops loop closure and counts lines and stations`() {
        val doc = JsonTransit.parse(sample)
        assertEquals("test", doc.region)
        assertEquals(2, doc.lineCount)
        assertEquals(9, doc.stationCount)
        assertEquals(5, doc.lines.first { it.id == "l11i" }.stops.size)
    }

    @Test
    fun `search finds line by short key`() {
        val doc = JsonTransit.parse(sample)
        val result = JsonTransit.search(doc, "13", "test")
        assertEquals(1, result.lines.size)
        assertEquals("13号线", result.lines.first().name)
        assertTrue(result.lines.first().isSubway)
    }

    @Test
    fun `subwayDirs hides direction that terminates at this station`() {
        val doc = JsonTransit.parse(sample)
        val atB = JsonTransit.subwayDirs(doc, 23.010, 113.000, "B", "test")
        assertEquals(2, atB.size)
        assertEquals(setOf("A", "D"), atB.map { it.dest }.toSet())

        val atD = JsonTransit.subwayDirs(doc, 23.030, 113.000, "D", "test")
        assertEquals(1, atD.size)
        assertEquals("A", atD.single().dest)
    }

    @Test
    fun `linear route picks the forward direction`() {
        val doc = JsonTransit.parse(sample)
        val route = JsonTransit.metroRoute(
            doc,
            MetroQuery("test", "13号线", "B", 23.010, 113.000, "D", 23.030, 113.000),
        )
        assertNotNull(route)
        assertEquals(2, route!!.hops)
        assertEquals(listOf("B", "C", "D"), route.stations.map { it.name })
        assertEquals(240, route.durSec)
    }

    @Test
    fun `loop route follows the chosen direction`() {
        val doc = JsonTransit.parse(sample)
        val inner = JsonTransit.metroRoute(
            doc,
            MetroQuery("test", "11号线", "Q", 23.110, 113.100, "S", 23.130, 113.100, dir = "内环"),
        )
        assertNotNull(inner)
        assertEquals(listOf("Q", "R", "S"), inner!!.stations.map { it.name })

        val outer = JsonTransit.metroRoute(
            doc,
            MetroQuery("test", "11号线", "Q", 23.110, 113.100, "S", 23.130, 113.100, dir = "外环"),
        )
        assertNotNull(outer)
        assertEquals(listOf("Q", "P", "T", "S"), outer!!.stations.map { it.name })
    }

    @Test
    fun `metroStops takes the shortest direction`() {
        val doc = JsonTransit.parse(sample)
        val dirs = JsonTransit.subwayDirs(doc, 23.110, 113.100, "Q", "test")
        assertEquals(2, dirs.size)
        assertEquals(2, JsonTransit.metroStops(doc, dirs, 23.130, 113.100, "test"))
    }

    @Test
    fun `route falls back to null when target is too far`() {
        val doc = JsonTransit.parse(sample)
        assertNull(
            JsonTransit.metroRoute(
                doc,
                MetroQuery("test", "13号线", "A", 23.000, 113.000, "", 24.500, 113.000),
            ),
        )
    }

    @Test
    fun `loop without dirLabel is rejected`() {
        val bad = """{ "lines": [ { "id": "x", "name": "X", "loop": true,
            "stops": [ { "name": "A", "lat": 1.0, "lng": 1.0 }, { "name": "B", "lat": 1.1, "lng": 1.1 } ] } ] }"""
        val error = runCatching { JsonTransit.parse(bad) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("dirLabel"))
    }

    @Test
    fun `old template without subway fields still parses`() {
        val old = """{ "lines": [ { "id": "b1", "name": "1路",
            "stops": [ { "name": "甲", "lat": 23.0, "lng": 113.0 }, { "name": "乙", "lat": 23.1, "lng": 113.1 } ] } ] }"""
        val doc = JsonTransit.parse(old)
        val line = doc.lines.single()
        assertEquals(false, line.subway)
        assertEquals(90, line.hopSec)
        assertEquals("1路", JsonTransit.search(doc, "1路", "test").lines.single().name)
        assertEquals("甲", JsonTransit.search(doc, "甲", "test").stations.single().name)
    }
}
