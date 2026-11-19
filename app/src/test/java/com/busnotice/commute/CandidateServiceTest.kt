package com.busnotice.commute

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateServiceTest {
    @Test
    fun `bands survive json round trip`() {
        val c = Candidate(
            Candidate.Type.METRO, "13号线", "新沙", 0.0, 0.0,
            weekdayBands = listOf(HeadwayBand(360, 540, 360), HeadwayBand(540, 1020, 420)),
            weekendBands = listOf(HeadwayBand(360, 600, 480)),
        )
        val back = Candidate.fromJson(c.toJson())
        assertEquals(c.weekdayBands, back.weekdayBands)
        assertEquals(c.weekendBands, back.weekendBands)
        assertEquals(420, back.headwaySec(12 * 60))
    }

    @Test
    fun `old record without bands falls back to minute defaults`() {
        val back = Candidate.fromJson(
            JSONObject("""{"type":"METRO","name":"4号线","stopName":"D","lat":0,"lng":0,"headwayMin":6}"""),
        )
        assertEquals(360, back.peakHwSec())
        assertEquals(540, back.offPeakHwSec())
        assertEquals(360, back.headwaySec(8 * 60))
        assertEquals(540, back.headwaySec(12 * 60))
    }

    @Test
    fun `schedule text summarizes bands`() {
        val c = Candidate(
            Candidate.Type.METRO, "13号线", "新沙", 0.0, 0.0,
            weekdayBands = listOf(
                HeadwayBand(360, 540, 360), HeadwayBand(540, 1020, 420),
                HeadwayBand(1020, 1200, 540), HeadwayBand(1200, 1350, 660),
            ),
            weekendBands = listOf(HeadwayBand(360, 600, 480), HeadwayBand(600, 1200, 360)),
        )
        assertEquals("工作日 6–11 分钟 · 周末 6–8 分钟", c.scheduleText())
    }
}
