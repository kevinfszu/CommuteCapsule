package com.busnotice.commute

/**
 * 一次到站打卡：leg = 行程段下标（-1 为旧数据），idx = 本段站点下标（0 = 上车站），at = 毫秒时间戳
 */
data class StopMark(val idx: Int, val at: Long, val leg: Int = -1)

/**
 * 到站打卡的纯逻辑（不依赖 Context，方便单元测试）。
 * 短期用它修正本次行程的到站推算，长期由 Learn 按「线路 + 区间」沉淀样本。
 */
object RideStops {
    /** 每站用时夹取范围（秒）：小于 45 秒基本是误点，大于 300 秒还不如按计划值 */
    private const val MIN_HOP_SEC = 45
    private const val MAX_HOP_SEC = 300

    /** 单站区间的合法秒数范围：太离谱的样本不学 */
    private const val MIN_SAMPLE_SEC = 40
    private const val MAX_SAMPLE_SEC = 600

    /** 线路学习键：地铁「名称|方向」，公交「名称|终点站」 */
    fun lineKey(c: Candidate): String =
        if (c.type == Candidate.Type.METRO) "${c.name}|${c.metroDir}" else "${c.name}|${c.lastStop}"

    /** 上车到最近打卡的累计平均每站用时（秒）；没有有效打卡返回 null */
    fun perHopSec(boardedAt: Long, marks: List<StopMark>): Int? {
        val last = marks.maxByOrNull { it.idx } ?: return null
        if (boardedAt <= 0 || last.idx <= 0 || last.at <= boardedAt) return null
        val sec = ((last.at - boardedAt) / 1000L / last.idx).toInt()
        return sec.coerceIn(MIN_HOP_SEC, MAX_HOP_SEC)
    }

    /**
     * 打卡后的已过站数 + 按「每站用时」外推的到下车站时刻。
     * 调用方保证 marks 非空；pass 封顶 hops-1，到站后交给现有的下车流程。
     */
    fun progress(boardedAt: Long, marks: List<StopMark>, hops: Int, now: Long, perHopFallbackSec: Int): Pair<Int, Long> {
        val h = hops.coerceAtLeast(1)
        val last = marks.maxByOrNull { it.idx } ?: return 0 to now
        val per = (perHopSec(boardedAt, marks) ?: perHopFallbackSec).coerceIn(MIN_HOP_SEC, MAX_HOP_SEC) * 1000L
        val extra = if (now > last.at) ((now - last.at) / per).toInt() else 0
        val passed = (last.idx + extra).coerceIn(0, h - 1)
        return passed to (last.at + (h - last.idx) * per)
    }

    /** 一次乘车的区间样本：span==1 是精确区间，span>1 是该线平均每站 */
    data class Interval(val fromIdx: Int, val toIdx: Int, val sec: Int, val span: Int)

    /** 把「上车时刻 + 打卡序列」切成区间；离谱的时长整段丢弃 */
    fun intervals(boardedAt: Long, marks: List<StopMark>): List<Interval> {
        if (boardedAt <= 0) return emptyList()
        val out = ArrayList<Interval>(marks.size)
        var prevIdx = 0
        var prevAt = boardedAt
        for (m in marks.sortedBy { it.idx }) {
            val span = m.idx - prevIdx
            if (span <= 0) continue
            val total = ((m.at - prevAt) / 1000L).toInt()
            if (total in MIN_SAMPLE_SEC * span..MAX_SAMPLE_SEC * span) {
                out += if (span == 1) Interval(prevIdx, m.idx, total, 1)
                else Interval(prevIdx, m.idx, (total + span / 2) / span, span)
            }
            prevIdx = m.idx; prevAt = m.at
        }
        return out
    }
}

/**
 * 站序合并：把多次乘车记录的站点序列（以及已知区间的 from→to 边）拼成一条行车方向的站序。
 * 只依赖序列本身，不依赖数据源；冲突边保留先出现的一条，环路不会死循环。
 */
object StopOrder {
    fun merge(sequences: List<List<String>>): List<String> {
        val next = LinkedHashMap<String, String>()
        val all = LinkedHashSet<String>()
        sequences.forEach { seq ->
            seq.filter { it.isNotBlank() }.forEach { all += it }
            seq.zipWithNext().forEach { (a, b) ->
                if (a.isNotBlank() && b.isNotBlank() && a != b) next.putIfAbsent(a, b)
            }
        }
        if (all.isEmpty()) return emptyList()

        val hasPrev = next.values.toHashSet()
        val out = ArrayList<String>(all.size)
        val used = HashSet<String>()

        fun walk(start: String) {
            var cur: String? = start
            while (cur != null && used.add(cur)) {
                out += cur
                cur = next[cur]
            }
        }

        all.filter { it !in hasPrev }.forEach { walk(it) }
        all.forEach { if (it !in used) walk(it) }   // 剩下的多是环路/断开片段，按出现顺序补齐
        return out
    }
}
