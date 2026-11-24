package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 到站打卡的纯逻辑：每站用时、打卡后推进、区间切分 */
class RideStopsTest {
    private val t0 = 1_700_000_000_000L

    @Test
    fun `perHopSec 没有打卡返回 null`() {
        assertNull(RideStops.perHopSec(t0, emptyList()))
    }

    @Test
    fun `perHopSec 只打了上车站返回 null`() {
        assertNull(RideStops.perHopSec(t0, listOf(StopMark(0, t0 + 120_000))))
    }

    @Test
    fun `perHopSec 用累计平均`() {
        val marks = listOf(StopMark(1, t0 + 150_000), StopMark(3, t0 + 450_000))
        assertEquals(150, RideStops.perHopSec(t0, marks))
    }

    @Test
    fun `perHopSec 时间倒挂返回 null`() {
        assertNull(RideStops.perHopSec(t0, listOf(StopMark(1, t0 - 10_000))))
    }

    @Test
    fun `perHopSec 太快太慢都夹取`() {
        assertEquals(45, RideStops.perHopSec(t0, listOf(StopMark(1, t0 + 10_000))))
        assertEquals(300, RideStops.perHopSec(t0, listOf(StopMark(1, t0 + 900_000))))
    }

    @Test
    fun `progress 从打卡站往后推`() {
        val marks = listOf(StopMark(1, t0 + 150_000), StopMark(2, t0 + 300_000))
        val (passed, eta) = RideStops.progress(t0, marks, 6, t0 + 300_000, 140)
        assertEquals(2, passed)
        assertEquals(t0 + 900_000, eta)   // 还剩 4 站 × 150 秒
    }

    @Test
    fun `progress 时间推进继续加站`() {
        val marks = listOf(StopMark(2, t0 + 300_000))
        val (passed, _) = RideStops.progress(t0, marks, 6, t0 + 450_000, 140)
        assertEquals(3, passed)
    }

    @Test
    fun `progress 再久也封顶到倒数一站`() {
        val marks = listOf(StopMark(2, t0 + 300_000))
        val (passed, _) = RideStops.progress(t0, marks, 6, t0 + 10 * 3_600_000L, 140)
        assertEquals(5, passed)
    }

    @Test
    fun `intervals 相邻打卡是精确区间`() {
        val iv = RideStops.intervals(t0, listOf(StopMark(1, t0 + 150_000), StopMark(2, t0 + 300_000)))
        assertEquals(2, iv.size)
        assertEquals(RideStops.Interval(0, 1, 150, 1), iv[0])
        assertEquals(RideStops.Interval(1, 2, 150, 1), iv[1])
    }

    @Test
    fun `intervals 跨站给平均每站`() {
        val iv = RideStops.intervals(t0, listOf(StopMark(2, t0 + 300_000), StopMark(5, t0 + 750_000)))
        assertEquals(RideStops.Interval(0, 2, 150, 2), iv[0])
        assertEquals(RideStops.Interval(2, 5, 150, 3), iv[1])
    }

    @Test
    fun `intervals 离谱时长丢弃`() {
        val iv = RideStops.intervals(t0, listOf(StopMark(1, t0 + 10_000), StopMark(2, t0 + 610_000)))
        assertEquals(listOf(RideStops.Interval(1, 2, 600, 1)), iv)
    }

    @Test
    fun `StopOrder 重叠序列合并成一条完整站序`() {
        val seq = StopOrder.merge(listOf(listOf("A", "B", "C"), listOf("B", "C", "D")))
        assertEquals(listOf("A", "B", "C", "D"), seq)
    }

    @Test
    fun `StopOrder 逆序输入仍按行车方向排列`() {
        val seq = StopOrder.merge(listOf(listOf("D", "C"), listOf("C", "B"), listOf("B", "A")))
        assertEquals(listOf("D", "C", "B", "A"), seq)
    }

    @Test
    fun `StopOrder 冲突边以先出现的为准`() {
        // A→B 与 B→A 冲突：先到先得，避免出现环
        val seq = StopOrder.merge(listOf(listOf("A", "B"), listOf("B", "A")))
        assertEquals(listOf("A", "B"), seq)
    }
}
