package com.busnotice.commute

import com.busnotice.commute.NodeLog.Kind
import com.busnotice.commute.NodeLog.Src
import com.busnotice.commute.NodeLog.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 节点补算规则：只从已有锚点推导，推算与补录不覆盖人工值 */
class NodeLogTest {

    private fun cand(name: String = "A站") = Candidate(Candidate.Type.METRO, "1号线", name, 0.0, 0.0)

    private fun metroTrip(vararg rideMin: Int) = Trip(
        id = "t", name = "上班",
        legs = rideMin.map { Leg(candidates = listOf(cand()), rideMin = it) },
    )

    private fun anchors(now: Long = 4_000_000L, vararg transfer: Int) = NodeLog.Anchors(
        startedAt = 900_000L, now = now,
        boardEstimateSec = 180, alightEstimateSec = 120,
        transferWalkSec = transfer.toList(), rideSec = listOf(600, 400),
        lastWalkSec = 480,
    )

    private fun at(nodes: List<Stamp>, kind: Kind, leg: Int = 0) =
        nodes.firstOrNull { it.kind == kind && it.leg == leg }?.at

    private fun src(nodes: List<Stamp>, kind: Kind, leg: Int = 0) =
        nodes.firstOrNull { it.kind == kind && it.leg == leg }?.src

    @Test fun metroBoardFromEnter() {
        val raw = listOf(Stamp(Kind.ENTER, 0, 1_000_000L, Src.TAP))
        val n = NodeLog.resolve(metroTrip(10), raw, anchors(), aborted = false)
        assertEquals(1_180_000L, at(n, Kind.BOARD))
        assertEquals(Src.ESTIMATED, src(n, Kind.BOARD))
        assertEquals(900_000L, at(n, Kind.DEPART))
    }

    @Test fun noExitAndNoArriveWithoutTap() {
        val raw = listOf(
            Stamp(Kind.ENTER, 0, 1_000_000L, Src.TAP),
            Stamp(Kind.BOARD, 0, 1_180_000L, Src.TAP),
        )
        val n = NodeLog.resolve(metroTrip(10), raw, anchors(), aborted = false)
        assertEquals(1_780_000L, at(n, Kind.ALIGHT))
        // 出站只来自刷卡/基站，不能用下车时间推算
        assertNull(at(n, Kind.EXIT))
        assertNull(at(n, Kind.ARRIVE))
    }

    @Test fun estimatedExitNeverCreated() {
        val raw = listOf(Stamp(Kind.ENTER, 0, 1_000_000L, Src.TAP))
        val n = NodeLog.resolve(metroTrip(10), raw, anchors(), aborted = false)
        assertNull(at(n, Kind.EXIT))
        assertNull(src(n, Kind.EXIT))
    }

    @Test fun transferBoardFromPrevAlight() {
        val raw = listOf(
            Stamp(Kind.ENTER, 0, 1_000_000L, Src.TAP),
            Stamp(Kind.BOARD, 0, 1_180_000L, Src.TAP),
            Stamp(Kind.ALIGHT, 0, 1_780_000L, Src.TAP),
        )
        val n = NodeLog.resolve(metroTrip(10, 8), raw, anchors(4_000_000L, 180, 240), aborted = false)
        assertEquals(2_020_000L, at(n, Kind.BOARD, 1))
        assertEquals(2_420_000L, at(n, Kind.ALIGHT, 1))
    }

    @Test fun terminalAlightBeforeExit() {
        val raw = listOf(
            Stamp(Kind.ENTER, 0, 1_000_000L, Src.TAP),
            Stamp(Kind.BOARD, 0, 1_180_000L, Src.TAP),
            Stamp(Kind.EXIT, 0, 2_000_000L, Src.TAP),
        )
        val n = NodeLog.resolve(metroTrip(10), raw, anchors(), aborted = false)
        assertEquals(1_880_000L, at(n, Kind.ALIGHT))
        assertEquals(Src.TAP, src(n, Kind.EXIT))
    }

    @Test fun alightBackwardUsesProvidedLead() {
        val raw = listOf(
            Stamp(Kind.BOARD, 0, 1_200_000L, Src.TAP),
            Stamp(Kind.EXIT, 0, 2_000_000L, Src.TAP),
        )
        val a = NodeLog.Anchors(
            startedAt = 900_000L, now = 4_000_000L,
            alightEstimateSec = 90, rideSec = listOf(600),
        )
        val n = NodeLog.resolve(metroTrip(10), raw, a, aborted = false)
        assertEquals(1_910_000L, at(n, Kind.ALIGHT))
    }

    @Test fun abortedDoesNotInventFuture() {
        val raw = listOf(Stamp(Kind.ENTER, 0, 1_000_000L, Src.TAP))
        val n = NodeLog.resolve(metroTrip(10), raw, anchors(), aborted = true)
        assertNull(at(n, Kind.BOARD))
        assertNull(at(n, Kind.ALIGHT))
        assertNull(at(n, Kind.EXIT))
        assertNull(at(n, Kind.ARRIVE))
    }

    @Test fun laterStampWinsSoBackfillRecalculates() {
        val raw = listOf(
            Stamp(Kind.ENTER, 0, 1_000_000L, Src.TAP),
            Stamp(Kind.BOARD, 0, 1_180_000L, Src.ESTIMATED),
            Stamp(Kind.BOARD, 0, 1_380_000L, Src.BACKFILL),
        )
        val n = NodeLog.resolve(metroTrip(10), raw, anchors(), aborted = false)
        assertEquals(1_380_000L, at(n, Kind.BOARD))
        assertEquals(Src.BACKFILL, src(n, Kind.BOARD))
        assertEquals(1_980_000L, at(n, Kind.ALIGHT))
    }

    @Test fun estimatedNeverOverwritesReal() {
        val raw = listOf(
            Stamp(Kind.ENTER, 0, 1_000_000L, Src.TAP),
            Stamp(Kind.BOARD, 0, 1_500_000L, Src.MANUAL),
        )
        val n = NodeLog.resolve(metroTrip(10), raw, anchors(), aborted = false)
        assertEquals(1_500_000L, at(n, Kind.BOARD))
        assertEquals(Src.MANUAL, src(n, Kind.BOARD))
    }
}
