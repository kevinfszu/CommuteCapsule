package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 上车时刻推算：相位排班、半间隔兜底、模糊带、实测融合 */
class BoardEstTest {

    // 2026-11-20 是周五，weekday = true
    private fun at(h: Int, m: Int, s: Int = 0): Long =
        LocalDate.of(2026, 11, 20).atTime(h, m, s)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun sec(from: Long, to: Long) = ((to - from) / 1000).toInt()

    @Test fun noPhaseUsesHalfHeadway() {
        val enter = at(7, 15)
        val r = BoardEst.estimate(BoardEst.Input(enter = enter, headwaySec = 180))
        assertFalse(r.phased)
        assertFalse(r.sure)
        assertEquals(150, sec(enter, r.at))   // 60 步行 + 90 半间隔
        assertEquals(60, sec(enter, r.lo))
        assertEquals(330, sec(enter, r.hi))
    }

    @Test fun phaseCatchesNextTrain() {
        // 首班 05:30，每 180 秒一班 → 班次落在 :00 :03 :06 … 07:18:00
        val enter = at(7, 15)
        val r = BoardEst.estimate(BoardEst.Input(enter, 180, firstMin = 330, lastMin = 23 * 60))
        assertTrue(r.phased)
        assertTrue(r.sure)
        assertEquals(180, sec(enter, r.at))
        assertEquals(at(7, 18), r.at)
    }

    @Test fun phaseNearMissIsBlurred() {
        // 到站台 07:17:40，离 07:18:00 只剩 20 秒 → 落在模糊带里
        val enter = at(7, 16, 40)
        val r = BoardEst.estimate(BoardEst.Input(enter, 180, firstMin = 330, lastMin = 23 * 60))
        assertTrue(r.phased)
        assertFalse(r.sure)
        assertEquals(at(7, 18), r.at)
        assertEquals(at(7, 21), r.hi)        // 也可能没赶上，多等一班
    }

    @Test fun offsetShiftsPhase() {
        // 学到的偏差：实际比推算晚 30 秒
        val enter = at(7, 15)
        val r = BoardEst.estimate(
            BoardEst.Input(enter, 180, firstMin = 330, lastMin = 23 * 60, offsetSec = 30)
        )
        assertEquals(at(7, 18, 30), r.at)
    }

    @Test fun lastTrainPassedFallsBack() {
        val enter = at(7, 15)
        val r = BoardEst.estimate(BoardEst.Input(enter, 180, firstMin = 330, lastMin = 340))
        assertFalse(r.phased)
    }

    @Test fun samplesPullEstimate() {
        val enter = at(7, 15)
        val r = BoardEst.estimate(
            BoardEst.Input(enter, 300, waitSamples = listOf(200, 200, 200))
        )
        // 模型 210 秒（60 + 150），实测 200 秒 → 落在两者之间
        val d = sec(enter, r.at)
        assertTrue("d=$d", d in 195..210)
    }

    @Test fun walkFromWait() {
        assertEquals(60, BoardEst.walkFromWait(listOf(150, 150, 150), 180))
        assertEquals(90, BoardEst.walkFromWait(listOf(150), 120))
        assertNull(BoardEst.walkFromWait(emptyList(), 180))
    }
}
