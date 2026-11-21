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
    fun `lines groups directions and keeps loop labels`() {
        val doc = JsonTransit.parse(sample)
        val lines = JsonTransit.lines(doc, "test")
        assertEquals(2, lines.size)

        val metro13 = lines.first { it.name == "13号线" }
        assertTrue(metro13.isSubway)
        assertEquals(2, metro13.dirs.size)
        assertEquals("", metro13.dirs.first().label)

        val loop = lines.first { it.name == "11号线" }
        assertEquals(listOf("内环", "外环"), loop.dirs.map { it.label })
    }

    @Test
    fun `line color accepts color or amap uicolor`() {
        val doc = JsonTransit.parse(
            """
            { "lines": [
              { "id": "c1", "name": "11号线", "subway": true, "uicolor": "F9BE58",
                "stops": [{"name":"A","lat":23.0,"lng":113.0},{"name":"B","lat":23.01,"lng":113.0}] },
              { "id": "c2", "name": "13号线", "subway": true, "color": "#818530",
                "stops": [{"name":"A","lat":23.0,"lng":113.0},{"name":"B","lat":23.01,"lng":113.0}] }
            ] }
            """.trimIndent(),
        )
        assertEquals(listOf("F9BE58", "818530"), doc.lines.map { it.color })
        assertEquals("F9BE58", JsonTransit.lineDetail(doc, "c1", "").colorHex)
        val colors = JsonTransit.subwayDirs(doc, 23.0, 113.0, "A", "").associate { it.line to it.colorHex }
        assertEquals(mapOf("11号线" to "F9BE58", "13号线" to "818530"), colors)
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
    fun `lineDetail carries hopSec for ride estimate`() {
        val doc = JsonTransit.parse(sample)
        assertEquals(120, JsonTransit.lineDetail(doc, "l13e", "test").hopSec)
        assertEquals(140, JsonTransit.lineDetail(doc, "l11i", "test").hopSec)
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

    @Test
    fun `service bands parse and weekend can be omitted`() {
        val json = """{ "lines": [ { "id": "x", "name": "13号线", "subway": true,
            "first": "06:00", "last": "22:30", "hopSec": 120,
            "service": { "weekday": [
                { "from": "06:00", "to": "09:00", "headwaySec": 360 },
                { "from": "09:00", "to": "17:00", "headwaySec": 420 }
            ] },
            "stops": [ { "name": "A", "lat": 1.0, "lng": 1.0 }, { "name": "B", "lat": 1.1, "lng": 1.1 } ] } ] }"""
        val line = JsonTransit.parse(json).lines.single()
        assertEquals(listOf(360, 420), line.weekdayBands.map { it.headwaySec })
        assertTrue(line.weekendBands.isEmpty())

        val c = Candidate(
            Candidate.Type.METRO, "13号线", "A", 1.0, 1.0,
            weekdayBands = line.weekdayBands, weekendBands = line.weekendBands,
        )
        assertEquals(360, c.headwaySec(7 * 60, weekend = false))
        assertEquals(360, c.headwaySec(7 * 60, weekend = true))
    }

    @Test
    fun `invalid bands are skipped`() {
        val json = """{ "lines": [ { "id": "x", "name": "X", "subway": true,
            "service": { "weekday": [
                { "from": "09:00", "to": "06:00", "headwaySec": 360 },
                { "from": "06:00", "to": "09:00", "headwaySec": 5 },
                { "from": "06:00", "to": "09:00", "headwaySec": 360 }
            ] },
            "stops": [ { "name": "A", "lat": 1.0, "lng": 1.0 }, { "name": "B", "lat": 1.1, "lng": 1.1 } ] } ] }"""
        assertEquals(listOf(360), JsonTransit.parse(json).lines.single().weekdayBands.map { it.headwaySec })
    }

    @Test
    fun `station first and last are offset by station order`() {
        val doc = JsonTransit.parse(sample)
        val east = JsonTransit.subwayDirs(doc, 23.020, 113.000, "C", "test").first { it.dest == "D" }
        assertEquals(360 + 2 * 2, east.firstMin)
        assertEquals(1380 + 2 * 2, east.lastMin)
    }
}
