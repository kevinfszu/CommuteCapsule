package com.busnotice.commute

import android.content.Context

/**
 * 一键重新学习：按历史行程记录重放可推导的学习数据。
 *
 * 只重建节间时长、上车时刻、每段乘车与上车习惯；站间打卡、基站、公交速度、
 * 步行与「提醒→确认下车」依赖实时数据，保留不动。
 */
object LearnRebuild {
    private const val JSEP = "\u001f"

    data class Result(val records: Int, val skipped: Int, val samples: Int)

    /** 用最近的历史记录重建；返回处理的记录数、跳过的记录数与写入样本数 */
    fun rebuild(ctx: Context): Result {
        val all = NodeLog.records(ctx)
        val recs = all.filter { !it.aborted }.sortedBy { it.savedAt }
        if (recs.isEmpty()) return Result(0, all.size, 0)

        Learn.clearForRebuild(ctx, recs.map { it.trip.id }.distinct())
        // 站间学习不在记录里存累计值，删除记录后无法逐条回滚；统一按剩余记录重算
        rebuildStops(ctx, recs)
        val learnedById = LinkedHashMap<String, List<String>>()
        var samples = 0
        for (rec in recs) {
            Learn.beginJournal()
            runCatching { replay(ctx, rec) }
            val entries = Learn.endJournal()
            samples += entries.size
            // 旧的节点/时刻/乘车/习惯日志已随数据清除，换成这次重放产生的
            val kept = rec.learned.filterNot { e ->
                e.startsWith("node$JSEP") || e.startsWith("clock$JSEP") ||
                    e.startsWith("ride$JSEP") || e.startsWith("habit$JSEP")
            }
            learnedById[rec.id] = kept + entries
        }
        NodeLog.setLearned(ctx, learnedById)
        return Result(recs.size, all.size - recs.size, samples)
    }

    /**
     * 站间学习整体重算：清空后按每条记录里保存的站序、上下车时刻与打卡重放。
     * 这样删掉一条记录，「线路」页就不会再留下那趟行程的站间数据或站序。
     */
    fun rebuildStops(
        ctx: Context,
        recs: List<NodeLog.RunRecord> = NodeLog.records(ctx).filter { !it.aborted }.sortedBy { it.savedAt },
    ) {
        Learn.clearStops(ctx)
        Learn.beginJournal()
        recs.forEach { r -> runCatching { replayStops(ctx, r) } }
        Learn.endJournal()
    }

    private fun replayStops(ctx: Context, rec: NodeLog.RunRecord) {
        val eff = NodeLog.effective(rec.nodes)
        rec.legStops.forEachIndexed { i, names ->
            if (names.size < 2) return@forEachIndexed
            val board = eff[NodeLog.NodeRef(NodeLog.Kind.BOARD, i)]?.at ?: return@forEachIndexed
            val alight = eff[NodeLog.NodeRef(NodeLog.Kind.ALIGHT, i)]?.at ?: return@forEachIndexed
            val leg = rec.trip.legs.getOrNull(i) ?: return@forEachIndexed
            val cand = leg.candidates.firstOrNull { it.isMetro() } ?: leg.candidates.firstOrNull()
                ?: return@forEachIndexed
            Learn.recordStops(
                ctx, RideStops.lineKey(cand), names, board, alight,
                rec.marks.filter { it.leg == i },
            )
        }
    }

    /**
     * 人工修正最近一条记录的时间：先回滚这条记录写过的节点/时刻/乘车/习惯样本，
     * 再按修正后的节点重放，保证修正值真正进入算法学习。
     */
    fun correct(ctx: Context, id: String, nodes: List<NodeLog.Stamp>): NodeLog.RunRecord? {
        val rec = NodeLog.record(ctx, id) ?: return null
        return applyCorrection(ctx, rec.copy(nodes = nodes))
    }

    fun applyCorrection(ctx: Context, rec: NodeLog.RunRecord): NodeLog.RunRecord? {
        // 中途结束：只改记录，不制造学习样本；手动开始的行程照常重放
        if (rec.aborted) {
            NodeLog.replace(ctx, rec)
            return rec
        }
        val isReplayable = { e: String ->
            e.startsWith("node$JSEP") || e.startsWith("clock$JSEP") ||
                e.startsWith("ride$JSEP") || e.startsWith("habit$JSEP")
        }
        val old = rec.learned.filter(isReplayable)
        if (old.isNotEmpty()) runCatching { Learn.rollback(ctx, old) }
        Learn.beginJournal()
        runCatching { replay(ctx, rec) }
        val fresh = Learn.endJournal()
        val updated = rec.copy(learned = rec.learned.filterNot(isReplayable) + fresh)
        NodeLog.replace(ctx, updated)
        return updated
    }

    /** 与 CommuteService.learnNodes 同规则：人工/刷卡/补录优先，没有时用自动识别，排除推算 */
    private fun replay(ctx: Context, rec: NodeLog.RunRecord) {
        val trip = rec.trip
        val eff = NodeLog.effective(rec.nodes)
        fun manual(kind: NodeLog.Kind, leg: Int): Long? =
            eff[NodeLog.NodeRef(kind, leg)]
                ?.takeIf { it.src == NodeLog.Src.MANUAL || it.src == NodeLog.Src.TAP || it.src == NodeLog.Src.BACKFILL }?.at
        fun pick(kind: NodeLog.Kind, leg: Int): Long? =
            manual(kind, leg) ?: eff[NodeLog.NodeRef(kind, leg)]?.takeIf { it.src == NodeLog.Src.AUTO }?.at
        // 样本日期用区间结束时刻，保证重放后仍按行程当天归档
        fun node(metric: Learn.Metric, scope: String, from: Long, to: Long) {
            if (to > from) Learn.recordNode(ctx, metric, scope, ((to - from) / 1000).toInt(), to)
        }

        trip.legs.indices.forEach { i ->
            val enter = pick(NodeLog.Kind.ENTER, i)
            val board = pick(NodeLog.Kind.BOARD, i)
            val alight = pick(NodeLog.Kind.ALIGHT, i)
            val exit = pick(NodeLog.Kind.EXIT, i)
            if (i == 0) {
                val depart0 = pick(NodeLog.Kind.DEPART, 0)
                if (depart0 != null && enter != null) node(Learn.Metric.APPROACH, "${trip.id}#0", depart0, enter)
            }
            if (enter != null && board != null) node(Learn.Metric.WAIT, "${trip.id}#$i", enter, board)
            if (alight != null && exit != null) node(Learn.Metric.EXIT_WALK, "${trip.id}#$i", alight, exit)
            val anchor = if (i == 0) enter ?: pick(NodeLog.Kind.DEPART, 0) else pick(NodeLog.Kind.ALIGHT, i - 1)
            if (anchor != null && alight != null) node(Learn.Metric.LEG_SPAN, "${trip.id}#$i", anchor, alight)
            if (i > 0) {
                val prev = pick(NodeLog.Kind.ALIGHT, i - 1)
                if (prev != null && board != null) node(Learn.Metric.TRANSFER, "${trip.id}#$i", prev, board)
            }
            if (board != null && alight != null) {
                val minutes = ((alight - board) / 60_000L).toInt()
                if (minutes in 1..180) Learn.recordRide(ctx, trip.id, i, minutes)
                if (manual(NodeLog.Kind.BOARD, i) != null) Learn.recordBoardClock(ctx, trip.id, i, board)
            }
        }
        // 上车习惯：首段上车确认（含定位自动确认）就算一次
        pick(NodeLog.Kind.BOARD, 0)?.let { Learn.record(ctx, trip.id, it) }

        val last = trip.legs.lastIndex
        val arrive = pick(NodeLog.Kind.ARRIVE, 0)
        val anchor = if (trip.legs.getOrNull(last)?.isMetroOnly() == true)
            pick(NodeLog.Kind.EXIT, last) else pick(NodeLog.Kind.ALIGHT, last)
        if (anchor != null && arrive != null) node(Learn.Metric.LAST_WALK, trip.id, anchor, arrive)
        val depart = pick(NodeLog.Kind.DEPART, 0)
        if (depart != null && arrive != null) node(Learn.Metric.TRIP, trip.id, depart, arrive)
        val firstBoard = pick(NodeLog.Kind.BOARD, 0)
        val lastAlight = pick(NodeLog.Kind.ALIGHT, last)
        if (firstBoard != null && lastAlight != null) node(Learn.Metric.RIDE_SPAN, trip.id, firstBoard, lastAlight)
        val firstEnter = pick(NodeLog.Kind.ENTER, 0)
        val lastExit = pick(NodeLog.Kind.EXIT, last)
        if (firstEnter != null && lastExit != null) node(Learn.Metric.STATION_SPAN, trip.id, firstEnter, lastExit)
    }
}
