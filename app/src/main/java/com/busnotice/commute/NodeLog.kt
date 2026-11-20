package com.busnotice.commute

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一趟行程的节点时间轴：出发 → 进站 → 上车 → 下车 →（换乘重复）→ 出站 → 到达。
 *
 * 记录按「追加」保存，同一个节点可以先后有自动值与人工值；展示时取最后一次。
 * 这样撤销一次人工打点后，之前的自动值会自动重新出现。
 */
object NodeLog {

    enum class Kind { DEPART, ENTER, BOARD, ALIGHT, EXIT, ARRIVE }

    /** 节点时间的来源。补录不参与学习；推算值只用于「进站→上车」「下车→出站」这类固定差。 */
    enum class Src { AUTO, TAP, MANUAL, ESTIMATED, BACKFILL }

    data class Stamp(val kind: Kind, val leg: Int, val at: Long, val src: Src)

    data class NodeRef(val kind: Kind, val leg: Int)

    /** 补算时需要的锚点，由调用方（服务/学习模块）提供。 */
    data class Anchors(
        val startedAt: Long,
        val now: Long,
        val boardEstimateSec: Int = 180,
        /** 每段：enter 之后多少秒上车（由 [BoardEst] 按相位/间隔/实测算出） */
        val boardOffsetSec: List<Int> = emptyList(),
        val alightEstimateSec: Int = 120,
        val transferWalkSec: List<Int> = emptyList(),
        val rideSec: List<Int> = emptyList(),
        val exitWalkSec: Int = 180,
        val lastWalkSec: Int = 480,
    )

    private const val PREFS = "run_nodes"
    private const val ACTIVE = "active"
    private const val LAST = "last"
    private const val LAST_KEEP_MS = 7L * 24 * 3600_000L

    /** 进行中的节点（UI 直接收集）。 */
    val flow = MutableStateFlow<List<Stamp>>(emptyList())

    /** 当前建议打点的节点（由服务根据状态机维护）。 */
    val current = MutableStateFlow<NodeRef?>(null)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(tripId: String, startAt: Long) = "$tripId:$startAt"

    fun begin(ctx: Context, tripId: String, startAt: Long) {
        prefs(ctx).edit()
            .putString(ACTIVE, key(tripId, startAt))
            .putString(key(tripId, startAt), "[]")
            .apply()
        flow.value = emptyList()
        current.value = null
    }

    /** 服务从进程恢复时调用：重新读回进行中的节点。 */
    fun loadActive(ctx: Context) {
        val k = prefs(ctx).getString(ACTIVE, null) ?: return
        val raw = prefs(ctx).getString(k, null) ?: return
        flow.value = decode(raw)
    }

    fun stamp(ctx: Context, tripId: String, startAt: Long, kind: Kind, leg: Int, at: Long, src: Src) {
        if (at <= 0) return
        val k = key(tripId, startAt)
        val p = prefs(ctx)
        if (p.getString(ACTIVE, null) == null) p.edit().putString(ACTIVE, k).apply()
        val list = decode(p.getString(k, null)) + Stamp(kind, leg, at, src)
        p.edit().putString(k, encode(list.takeLast(200))).apply()
        flow.value = list
        TraceLog.event("node", "k" to kind.name, "leg" to leg, "at" to at, "src" to src.name)
    }

    /** 撤销该节点最后一次追加（通常是一次人工打点）。 */
    fun removeLast(ctx: Context, tripId: String, startAt: Long, kind: Kind, leg: Int): Boolean {
        val k = key(tripId, startAt)
        val p = prefs(ctx)
        val list = decode(p.getString(k, null))
        val idx = list.indexOfLast { it.kind == kind && it.leg == leg }
        if (idx < 0) return false
        val next = list.toMutableList().apply { removeAt(idx) }
        p.edit().putString(k, encode(next)).apply()
        flow.value = next
        return true
    }

    /** 当前进行中行程的全部记录（含已被覆盖的历史）。 */
    fun raw(ctx: Context, tripId: String, startAt: Long): List<Stamp> =
        decode(prefs(ctx).getString(key(tripId, startAt), null))

    /** 每个节点取最后一次记录。 */
    fun effective(raw: List<Stamp>): Map<NodeRef, Stamp> {
        val out = LinkedHashMap<NodeRef, Stamp>()
        raw.forEach { out[NodeRef(it.kind, it.leg)] = it }
        return out
    }

    /** UI 用：节点条顺序。 */
    fun timeline(trip: Trip): List<NodeRef> {
        val out = ArrayList<NodeRef>()
        out += NodeRef(Kind.DEPART, 0)
        trip.legs.forEachIndexed { i, leg ->
            if (leg.isMetroOnly()) out += NodeRef(Kind.ENTER, i)
            out += NodeRef(Kind.BOARD, i)
            out += NodeRef(Kind.ALIGHT, i)
            if (leg.isMetroOnly()) out += NodeRef(Kind.EXIT, i)
        }
        out += NodeRef(Kind.ARRIVE, 0)
        return out
    }

    fun label(kind: Kind): String = when (kind) {
        Kind.DEPART -> "出发"
        Kind.ENTER -> "进站"
        Kind.BOARD -> "上车"
        Kind.ALIGHT -> "下车"
        Kind.EXIT -> "出站"
        Kind.ARRIVE -> "到达"
    }

    /**
     * 结束时补算缺失节点。只从已有锚点推导，不臆造；用户提前结束时不补算未来节点。
     */
    fun resolve(trip: Trip, raw: List<Stamp>, a: Anchors, aborted: Boolean): List<Stamp> {
        val eff = effective(raw).toMutableMap()
        fun get(kind: Kind, leg: Int) = eff[NodeRef(kind, leg)]?.at
        fun putIfMissing(kind: Kind, leg: Int, at: Long?, lower: Long?, upper: Long?) {
            if (at == null || at <= 0) return
            if (eff.containsKey(NodeRef(kind, leg))) return
            if (lower != null && at < lower) return
            if (upper != null && at > upper) return
            eff[NodeRef(kind, leg)] = Stamp(kind, leg, at, Src.ESTIMATED)
        }

        if (get(Kind.DEPART, 0) == null) {
            putIfMissing(Kind.DEPART, 0, a.startedAt, null, a.now)
        }

        trip.legs.forEachIndexed { i, leg ->
            val metro = leg.isMetroOnly()
            val enter = get(Kind.ENTER, i)
            val board = get(Kind.BOARD, i)
            val alight = get(Kind.ALIGHT, i)
            val exit = get(Kind.EXIT, i)

            if (!aborted && board == null) {
                when {
                    !aborted && enter != null -> putIfMissing(
                        Kind.BOARD, i,
                        enter + (a.boardOffsetSec.getOrNull(i) ?: a.boardEstimateSec) * 1000L,
                        enter, alight ?: exit,
                    )
                    i > 0 && get(Kind.ALIGHT, i - 1) != null -> {
                        val prev = get(Kind.ALIGHT, i - 1)!!
                        putIfMissing(Kind.BOARD, i, prev + walk(a, i) * 1000L, prev, alight)
                    }
                    alight != null && ride(a, i) > 0 -> putIfMissing(
                        Kind.BOARD, i, alight - ride(a, i) * 1000L, enter, alight
                    )
                }
            }

            if (alight == null) {
                val b = get(Kind.BOARD, i)
                when {
                    !aborted && metro && exit != null -> putIfMissing(
                        Kind.ALIGHT, i, exit - a.alightEstimateSec * 1000L, b ?: enter, exit
                    )
                    i + 1 < trip.legs.size && get(Kind.BOARD, i + 1) != null -> {
                        val next = get(Kind.BOARD, i + 1)!!
                        putIfMissing(Kind.ALIGHT, i, next - walk(a, i + 1) * 1000L, b ?: enter, next)
                    }
                    b != null && ride(a, i) > 0 -> putIfMissing(
                        Kind.ALIGHT, i, b + ride(a, i) * 1000L, b, exit
                    )
                }
            }

            if (!aborted && metro && exit == null) {
                val al = get(Kind.ALIGHT, i)
                if (al != null) putIfMissing(Kind.EXIT, i, al + a.exitWalkSec * 1000L, al, null)
            }
        }

        if (!aborted && get(Kind.ARRIVE, 0) == null) {
            val last = trip.legs.lastOrNull()
            val lastIdx = (trip.legs.size - 1).coerceAtLeast(0)
            val anchor = if (last?.isMetroOnly() == true) get(Kind.EXIT, lastIdx) else get(Kind.ALIGHT, lastIdx)
            if (anchor != null) putIfMissing(Kind.ARRIVE, 0, anchor + a.lastWalkSec * 1000L, anchor, a.now)
        }

        return timeline(trip).mapNotNull { eff[it] }
    }

    private fun walk(a: Anchors, leg: Int): Int =
        a.transferWalkSec.getOrNull(leg)?.coerceIn(60, 20 * 60) ?: 180

    private fun ride(a: Anchors, leg: Int): Int =
        a.rideSec.getOrNull(leg)?.coerceIn(0, 3 * 3600) ?: 0

    // ---------- 最近一趟（补录用，保留 7 天） ----------

    fun saveLastRun(ctx: Context, trip: Trip, nodes: List<Stamp>, at: Long = System.currentTimeMillis()) {
        val o = JSONObject()
            .put("saved", at)
            .put("trip", trip.toJson())
            .put("nodes", encode(nodes))
        prefs(ctx).edit().putString(LAST, o.toString()).apply()
    }

    fun lastRun(ctx: Context, now: Long = System.currentTimeMillis()): Pair<Trip, List<Stamp>>? {
        val raw = prefs(ctx).getString(LAST, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            if (now - o.optLong("saved") > LAST_KEEP_MS) return null
            val trip = Trip.fromJson(o.getJSONObject("trip"))
            trip to decode(o.optString("nodes"))
        }.getOrNull()
    }

    fun updateLast(ctx: Context, nodes: List<Stamp>) {
        val raw = prefs(ctx).getString(LAST, null) ?: return
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        o.put("nodes", encode(nodes))
        prefs(ctx).edit().putString(LAST, o.toString()).apply()
    }

    fun clearActive(ctx: Context) {
        val p = prefs(ctx)
        val k = p.getString(ACTIVE, null)
        val e = p.edit().remove(ACTIVE)
        if (k != null) e.remove(k)
        e.apply()
        flow.value = emptyList()
        current.value = null
    }

    // ---------- 序列化 ----------

    fun encode(list: List<Stamp>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONArray().put(it.kind.name).put(it.leg).put(it.at).put(it.src.name))
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<Stamp> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONArray(i) ?: return@mapNotNull null
                val kind = runCatching { Kind.valueOf(e.getString(0)) }.getOrNull() ?: return@mapNotNull null
                val src = runCatching { Src.valueOf(e.getString(3)) }.getOrNull() ?: Src.AUTO
                Stamp(kind, e.optInt(1), e.optLong(2), src)
            }
        }.getOrDefault(emptyList())
    }
}
