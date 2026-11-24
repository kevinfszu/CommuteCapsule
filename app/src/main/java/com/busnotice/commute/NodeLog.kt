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

    /** 节点时间的来源。补录不参与学习；推算值只用于「进站→上车」「出站→下车」这类固定差，出站本身从不推算。 */
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
        /** 下车比出站早多少秒；优先由实测中位数填充，样本不足时用编辑页预设 */
        val alightEstimateSec: Int = 120,
        val transferWalkSec: List<Int> = emptyList(),
        val rideSec: List<Int> = emptyList(),
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

    /** 行程记录有写入/修正时自增，首页据此重读「最近行程」。 */
    val revision = MutableStateFlow(0L)

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

    /** UI 用：节点条顺序。连续两段地铁是站内换乘，中间不进出站。 */
    fun timeline(trip: Trip): List<NodeRef> {
        val out = ArrayList<NodeRef>()
        out += NodeRef(Kind.DEPART, 0)
        trip.legs.forEachIndexed { i, leg ->
            val metro = leg.isMetroOnly()
            if (metro && trip.legs.getOrNull(i - 1)?.isMetroOnly() != true) out += NodeRef(Kind.ENTER, i)
            out += NodeRef(Kind.BOARD, i)
            out += NodeRef(Kind.ALIGHT, i)
            if (metro && trip.legs.getOrNull(i + 1)?.isMetroOnly() != true) out += NodeRef(Kind.EXIT, i)
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
     * 出站只来自刷卡或基站实测，从不使用下车时间反推；下车缺失时用「出站 − 实测差值」倒推（样本不足时用编辑页预设）。
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

    // ---------- 行程记录（最多 30 条，保留 30 天；固定的记录额外保留，删除时可回滚该次学习样本） ----------

    private const val HISTORY = "history"
    private const val MAX_RECORDS = 30
    private const val HISTORY_KEEP_MS = 30L * 24 * 3600_000L

    /** 一趟已结束的行程记录：节点时间轴 + 本次写入的学习样本日志 */
    data class RunRecord(
        val id: String,
        val savedAt: Long,
        val trip: Trip,
        val nodes: List<Stamp>,
        val aborted: Boolean = false,
        val test: Boolean = false,
        val marks: List<StopMark> = emptyList(),
        val markNames: List<String> = emptyList(),
        /** 每段可打卡的完整站点名，下标与行程段一致 */
        val legStops: List<List<String>> = emptyList(),
        /** 打卡记录所属的行程段下标，-1 表示旧记录未保存 */
        val markLeg: Int = -1,
        val learned: List<String> = emptyList(),
        /** 固定保留：不占 30 条名额，也不受 30 天过期清理 */
        val pinned: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("saved", savedAt)
            .put("trip", trip.toJson()).put("nodes", NodeLog.encode(nodes))
            .put("aborted", aborted).put("test", test)
            .put("marks", marks.joinToString(",") { "${it.leg}:${it.idx}:${it.at}" })
            .put("markNames", JSONArray().apply { markNames.forEach { put(it) } })
            .put("legStops", JSONArray().apply {
                legStops.forEach { leg -> put(JSONArray().apply { leg.forEach { put(it) } }) }
            })
            .put("markLeg", markLeg)
            .put("learned", JSONArray().apply { learned.forEach { put(it) } })
            .put("pinned", pinned)

        companion object {
            fun fromJson(o: JSONObject): RunRecord = RunRecord(
                id = o.optString("id").ifEmpty { "legacy" },
                savedAt = o.optLong("saved"),
                trip = Trip.fromJson(o.getJSONObject("trip")),
                nodes = NodeLog.decode(o.optString("nodes")),
                aborted = o.optBoolean("aborted"),
                test = o.optBoolean("test"),
                marks = o.optString("marks").split(',').mapNotNull { e ->
                    val kv = e.split(':')
                    val leg: Int
                    val idx: Int
                    val at: Long
                    if (kv.size == 3) {
                        leg = kv[0].toIntOrNull() ?: return@mapNotNull null
                        idx = kv[1].toIntOrNull() ?: return@mapNotNull null
                        at = kv[2].toLongOrNull() ?: return@mapNotNull null
                    } else if (kv.size == 2) {
                        leg = -1
                        idx = kv[0].toIntOrNull() ?: return@mapNotNull null
                        at = kv[1].toLongOrNull() ?: return@mapNotNull null
                    } else return@mapNotNull null
                    StopMark(idx, at, leg)
                },
                markNames = o.optJSONArray("markNames")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotEmpty) }
                } ?: emptyList(),
                legStops = o.optJSONArray("legStops")?.let { a ->
                    (0 until a.length()).map { li ->
                        val la = a.optJSONArray(li) ?: return@map emptyList()
                        (0 until la.length()).mapNotNull { la.optString(it).takeIf(String::isNotEmpty) }
                    }
                } ?: emptyList(),
                markLeg = o.optInt("markLeg", -1),
                learned = o.optJSONArray("learned")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotEmpty) }
                } ?: emptyList(),
                pinned = o.optBoolean("pinned"),
            )
        }
    }

    fun saveLastRun(
        ctx: Context, trip: Trip, nodes: List<Stamp>, at: Long = System.currentTimeMillis(),
        aborted: Boolean = false, test: Boolean = false,
        marks: List<StopMark> = emptyList(), markNames: List<String> = emptyList(),
        legStops: List<List<String>> = emptyList(), markLeg: Int = -1,
        learned: List<String> = emptyList(),
    ): String {
        val rec = RunRecord(
            id = java.util.UUID.randomUUID().toString(), savedAt = at, trip = trip, nodes = nodes,
            aborted = aborted, test = test, marks = marks, markNames = markNames,
            legStops = legStops, markLeg = markLeg, learned = learned,
        )
        // 固定的记录不参与 30 条上限的淘汰，作为额外记录长期保留
        val old = records(ctx, at)
        val pins = old.filter { it.pinned }
        val fresh = (listOf(rec) + old.filterNot { it.pinned }).take(MAX_RECORDS)
        writeHistory(ctx, (pins + fresh).distinctBy { it.id }.sortedByDescending { it.savedAt })
        return rec.id
    }

    /** 全部行程记录，新的在前（旧版本只存了一条 LAST，首次读取时迁移过来） */
    fun records(ctx: Context, now: Long = System.currentTimeMillis()): List<RunRecord> {
        val p = prefs(ctx)
        val raw = p.getString(HISTORY, null)
        if (raw == null) {
            val legacy = p.getString(LAST, null) ?: return emptyList()
            val o = runCatching { JSONObject(legacy) }.getOrNull() ?: return emptyList()
            if (now - o.optLong("saved") > HISTORY_KEEP_MS) return emptyList()
            val trip = runCatching { Trip.fromJson(o.getJSONObject("trip")) }.getOrNull() ?: return emptyList()
            return listOf(RunRecord("legacy", o.optLong("saved"), trip, decode(o.optString("nodes"))))
        }
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { RunRecord.fromJson(arr.getJSONObject(i)) }.getOrNull()
        }.filter { it.pinned || now - it.savedAt <= HISTORY_KEEP_MS }.sortedByDescending { it.savedAt }
    }

    fun record(ctx: Context, id: String): RunRecord? = records(ctx).firstOrNull { it.id == id }

    /** 固定保留 / 取消固定：固定后不占 30 条名额，也不受 30 天过期清理 */
    fun setPinned(ctx: Context, id: String, pinned: Boolean): Boolean {
        val all = records(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return false
        all[i] = all[i].copy(pinned = pinned)
        writeHistory(ctx, all)
        return true
    }

    /** 删除一条记录：该次行程写入的学习样本一并回滚 */
    fun deleteRecord(ctx: Context, id: String): Boolean {
        val all = records(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return false
        val rec = all.removeAt(i)
        if (rec.learned.isNotEmpty()) runCatching { Learn.rollback(ctx, rec.learned) }
        writeHistory(ctx, all)
        return true
    }

    fun lastRecord(ctx: Context, now: Long = System.currentTimeMillis()): RunRecord? =
        records(ctx, now).firstOrNull()?.takeIf { now - it.savedAt <= LAST_KEEP_MS }

    /** 重新学习后更新每条记录的样本日志，保证之后删除记录仍能精确回滚 */
    fun setLearned(ctx: Context, byId: Map<String, List<String>>) {
        if (byId.isEmpty()) return
        writeHistory(ctx, records(ctx).map { r -> byId[r.id]?.let { r.copy(learned = it) } ?: r })
    }

    /** 按 id 覆盖一条记录（尾程结束、人工修正时使用），保持记录数不变。 */
    fun updateRecord(ctx: Context, id: String, nodes: List<Stamp>, learned: List<String>? = null): Boolean {
        val all = records(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return false
        all[i] = all[i].copy(nodes = nodes, learned = learned ?: all[i].learned)
        writeHistory(ctx, all)
        return true
    }

    /** 整条替换（修正节点后重算学习样本），id 不存在时返回 false。 */
    fun replace(ctx: Context, rec: RunRecord): Boolean {
        val all = records(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == rec.id }
        if (i < 0) {
            if (rec.id != "legacy") return false
            // 旧版只有一条 LAST：首次修正时迁移成 HISTORY
            writeHistory(ctx, listOf(rec))
            return true
        }
        all[i] = rec
        writeHistory(ctx, all)
        return true
    }

    private fun writeHistory(ctx: Context, list: List<RunRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(HISTORY, arr.toString()).apply()
        revision.value++
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
