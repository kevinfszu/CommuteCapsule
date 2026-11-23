package com.busnotice.commute

/**
 * 逐级重算的纯投影：不依赖 Context，不做 IO。
 *
 * 从最近一次人工锚点（站点打卡 / 节点打点 / 上车）出发，一次算出四层时刻：
 *  L0 当前段后面每一站的到站时刻；
 *  L1 后面每一个关键节点的到达时刻（换乘下车、换乘上车、终点下车、出站、到达）；
 *  L2 当前行程段的结束时刻；
 *  L3 整趟行程的结束时刻。
 *
 * 任何一次人工打卡后，服务重新调用 [compute] 并发布结果，整条链会一起向前收敛。
 */
object TimelineProjection {

    data class StopEta(val idx: Int, val name: String, val at: Long)

    data class NodeEta(val kind: NodeLog.Kind, val leg: Int, val at: Long)

    data class Result(
        val stops: List<StopEta>,
        val nodes: List<NodeEta>,
        val legEndAt: Long?,
        val tripEndAt: Long?,
    )

    /**
     * @param riding 已上当前段车
     * @param alighted 当前段已经下车（正在换乘 / 出站 / 走向终点），此时不再预测本段上车
     * @param stopNames 当前段「上车站 → 下车站」完整站名；不足两站时为 null
     * @param marks 当前段到站打卡（idx 为站序号，0 = 上车站）
     * @param legEta 现有算法给出的本段下车站 ETA，作为无逐站数据时的兜底
     * @param nodeAt 已记录节点时刻；没有返回 null
     * @param pairSec 学到的相邻两站行车秒数
     * @param hopFallbackSec 没有区间样本时每站秒数
     * @param rideSec 某段乘车时长（毫秒）
     * @param boardAt (段, 基准时刻) → 该段上车时刻；换乘段会融合学到的历史上车时刻
     * @param exitWalkSec 某段下车 → 出站时长（毫秒）
     * @param lastWalkSec 出站/下车 → 到达时长（毫秒）
     * @param exitLeg 需要出站的最后一段（地铁）；没有出站节点时为 null
     */
    data class Input(
        val trip: Trip,
        val legIdx: Int,
        val now: Long,
        val riding: Boolean,
        val alighted: Boolean,
        val boardedAt: Long,
        val stopNames: List<String>?,
        val marks: List<StopMark>,
        val legEta: Long?,
        val nodeAt: (NodeLog.Kind, Int) -> Long?,
        val pairSec: (String, String) -> Int?,
        val hopFallbackSec: Int,
        val rideSec: (Int) -> Long,
        val boardAt: (Int, Long) -> Long,
        val exitWalkSec: (Int) -> Long,
        val lastWalkSec: Long,
        val exitLeg: Int?,
    )

    fun compute(i: Input): Result {
        val legs = i.trip.legs
        if (i.legIdx !in legs.indices) return Result(emptyList(), emptyList(), null, null)
        val nodes = ArrayList<NodeEta>()

        // L0：当前段逐站到站时刻
        val names = i.stopNames
        val stops = ArrayList<StopEta>()
        var projectedLegEnd: Long? = null
        if (i.riding && !i.alighted && names != null && names.size >= 2) {
            val hops = names.size - 1
            val anchor = i.marks.filter { it.idx in 0..hops && it.at > 0 }.maxByOrNull { it.idx }
            var at = anchor?.at ?: i.boardedAt.takeIf { it > 0 } ?: i.now
            val start = (anchor?.idx ?: 0).coerceIn(0, hops)
            for (k in start until hops) {
                val from = names[k]
                val to = names.getOrNull(k + 1) ?: continue
                val sec = (i.pairSec(from, to) ?: i.hopFallbackSec).coerceIn(30, 600)
                at += sec * 1000L
                stops += StopEta(k + 1, to, at)
            }
            // 没有任何人工锚点时，按「上车 + 均分」推出来的早于当前时刻的站直接丢掉，
            // 避免把已经过去的站当成「后续各站」展示
            if (anchor == null) stops.removeAll { it.at <= i.now }
            if (at > 0 && hops > 0) projectedLegEnd = at
        }

        var legEnd: Long?
        var cursor: Long
        var nextLeg: Int
        if (!i.alighted) {
            if (i.riding) {
                val end = projectedLegEnd ?: i.legEta ?: i.now
                legEnd = end
                cursor = end
                nodes += NodeEta(NodeLog.Kind.ALIGHT, i.legIdx, cursor)
            } else {
                val prevAlight = if (i.legIdx > 0) i.nodeAt(NodeLog.Kind.ALIGHT, i.legIdx - 1) else null
                val base = prevAlight ?: i.now
                val board = i.nodeAt(NodeLog.Kind.BOARD, i.legIdx) ?: i.boardAt(i.legIdx, base)
                val alight = i.nodeAt(NodeLog.Kind.ALIGHT, i.legIdx) ?: (board + i.rideSec(i.legIdx))
                nodes += NodeEta(NodeLog.Kind.BOARD, i.legIdx, board)
                nodes += NodeEta(NodeLog.Kind.ALIGHT, i.legIdx, alight)
                legEnd = alight
                cursor = alight
            }
            nextLeg = i.legIdx + 1
        } else {
            legEnd = i.nodeAt(NodeLog.Kind.ALIGHT, i.legIdx) ?: i.legEta
            cursor = legEnd ?: i.now
            nextLeg = i.legIdx + 1
        }

        // L1：后续所有关键节点
        val last = legs.lastIndex
        for (j in nextLeg..last) {
            val board = i.nodeAt(NodeLog.Kind.BOARD, j) ?: i.boardAt(j, cursor)
            val alight = i.nodeAt(NodeLog.Kind.ALIGHT, j) ?: (board + i.rideSec(j))
            nodes += NodeEta(NodeLog.Kind.BOARD, j, board)
            nodes += NodeEta(NodeLog.Kind.ALIGHT, j, alight)
            cursor = alight
        }

        // L1 尾段：出站 → 到达
        if (i.exitLeg == last && last >= 0) {
            val exit = i.nodeAt(NodeLog.Kind.EXIT, last) ?: (cursor + i.exitWalkSec(last))
            nodes += NodeEta(NodeLog.Kind.EXIT, last, exit)
            cursor = exit
        }
        // 换乘型行程（前一段出站）中间也可能有出站节点
        for (j in 0..last) {
            if (j == i.legIdx && !i.alighted) continue
            if (j == last && i.exitLeg == last) continue
            val exit = i.nodeAt(NodeLog.Kind.EXIT, j) ?: continue
            if (nodes.any { it.kind == NodeLog.Kind.EXIT && it.leg == j }) continue
            nodes += NodeEta(NodeLog.Kind.EXIT, j, exit)
        }
        val arrive = i.nodeAt(NodeLog.Kind.ARRIVE, 0) ?: (cursor + i.lastWalkSec)
        nodes += NodeEta(NodeLog.Kind.ARRIVE, 0, arrive)

        // 去掉 1 分钟内的重复节点（同一 kind+leg 只留最后一个）
        val dedup = LinkedHashMap<Pair<NodeLog.Kind, Int>, NodeEta>()
        nodes.forEach { dedup[it.kind to it.leg] = it }

        return Result(
            stops = stops,
            nodes = dedup.values.filter { it.at > 0 }.toList(),
            legEndAt = legEnd,
            tripEndAt = arrive,
        )
    }
}
