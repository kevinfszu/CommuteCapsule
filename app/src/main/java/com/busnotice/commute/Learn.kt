package com.busnotice.commute

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/**
 * 学下班（或任何不固定）出发时间：每次第一段上车记一笔「几点上的车」，
 * 攒够几次后给出建议的时间窗。只存在本机。
 */
object Learn {
    private const val PREFS = "learn"
    private const val KEEP = 30
    const val MIN_SAMPLES = 6
    private const val MIN_DAYS = 4

    // v3：每条是「哪天:几点上车」，一天只留一条；旧数据混了大量测试样本，整体作废
    private fun key(tripId: String) = "v3:$tripId"

    private data class Entry(val day: Long, val minute: Int)

    private fun entries(ctx: Context, tripId: String): List<Entry> =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(tripId), "") ?: "")
            .split(',').mapNotNull { e ->
                val (d, m) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                Entry(d.toLongOrNull() ?: return@mapNotNull null, m.toIntOrNull() ?: return@mapNotNull null)
            }

    /** 只在「确认是真坐车」后调用（见 CommuteService.commitBoard）；同一天重复记只保留第一次 */
    fun record(ctx: Context, tripId: String, millis: Long) {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val day = t.toLocalDate().toEpochDay()
        val list = entries(ctx, tripId)
        if (list.any { it.day == day }) return
        val out = (list + Entry(day, t.hour * 60 + t.minute)).takeLast(KEEP)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(tripId), out.joinToString(",") { "${it.day}:${it.minute}" }).apply()
    }

    /**
     * 这天通常几点上第一段车：同一个星期几攒够 3 天用它的中位数，
     * 否则全部攒够 5 天用总体中位数；都不够返回 null。
     */
    fun typicalBoard(ctx: Context, tripId: String, dow: Int): Int? {
        val all = entries(ctx, tripId)
        val day = all.filter { java.time.LocalDate.ofEpochDay(it.day).dayOfWeek.value == dow }.map { it.minute }
        if (day.size >= 3) return day.sorted()[day.size / 2]
        return if (all.size >= 5) all.map { it.minute }.sorted()[all.size / 2] else null
    }

    /** 按习惯推迟自动弹出：通常上车前 40 分钟弹，但不早于时间窗开始、不晚于结束前 15 分钟 */
    fun learnedStart(ctx: Context, trip: Trip, dow: Int): Int? {
        val b = typicalBoard(ctx, trip.id, dow) ?: return null
        val s = (b - 40).coerceAtMost(trip.endMinute - 15)
        return s.takeIf { it > trip.startMinute }
    }

    fun samples(ctx: Context, tripId: String): List<Int> = entries(ctx, tripId).map { it.minute }

    data class Suggestion(val start: Int, val end: Int, val typical: Int, val n: Int)

    /** 取 10%–90% 分位，前后各放 10 分钟，取整到 5 分钟；样本太少返回 null */
    fun suggest(minutes: List<Int>): Suggestion? {
        if (minutes.size < MIN_SAMPLES) return null
        // 去掉离中位数 90 分钟以外的（偶发样本），不让它们把时间窗撑大
        val med = minutes.sorted()[minutes.size / 2]
        val s = minutes.filter { kotlin.math.abs(it - med) <= 90 }.sorted()
        if (s.size < MIN_SAMPLES) return null
        fun q(f: Double) = s[((s.size - 1) * f).toInt()]
        val start = ((q(0.1) - 10) / 5 * 5).coerceAtLeast(0)
        val end = (((q(0.9) + 10) + 4) / 5 * 5).coerceAtMost(23 * 60 + 55)
        return Suggestion(start, end, q(0.5), s.size)
    }

    fun suggest(ctx: Context, trip: Trip): Suggestion? {
        val e = entries(ctx, trip.id)
        if (e.map { it.day }.distinct().size < MIN_DAYS) return null
        return suggest(e.map { it.minute })?.takeIf { it.start != trip.startMinute || it.end != trip.endMinute }
    }

    /**
     * 后台无感调整时间窗：样本够了就直接改，不弹建议。
     * 每次最多挪 30 分钟，避免一两次异常把窗口一下拉走。返回改过的行程或 null。
     */
    fun autoAdjust(ctx: Context, trip: Trip): Trip? {
        val trip = TripStore.get(ctx, trip.id) ?: trip
        val sg = suggest(ctx, trip) ?: return null
        fun step(from: Int, to: Int) = from + (to - from).coerceIn(-30, 30)
        val ns = step(trip.startMinute, sg.start)
        val ne = step(trip.endMinute, sg.end).coerceAtLeast(ns + 30)
        if (ns == trip.startMinute && ne == trip.endMinute) return null
        val nt = trip.copy(startMinute = ns, endMinute = ne)
        TripStore.upsert(ctx, nt)
        val t = java.time.LocalDate.now()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("adj:${trip.id}", "${t.monthValue}/${t.dayOfMonth} 按习惯调整为 %02d:%02d–%02d:%02d"
                .format(ns / 60, ns % 60, ne / 60, ne % 60)).apply()
        return nt
    }

    fun lastAdjust(ctx: Context, tripId: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("adj:$tripId", null)

    // ---------- 每段实际坐多久（从上车到走近下一段上车点，减掉换乘步行） ----------
    private const val RIDE = "learn_ride2"  // v2：旧样本的上车时刻记早了，作废

    fun recordRide(ctx: Context, tripId: String, legIdx: Int, minutes: Int) {
        if (minutes !in 1..180) return
        val p = ctx.getSharedPreferences(RIDE, Context.MODE_PRIVATE)
        val k = "$tripId#$legIdx"
        val list = (p.getString(k, "") ?: "").split(',').filter { it.isNotBlank() } + "$minutes"
        p.edit().putString(k, list.takeLast(10).joinToString(",")).apply()
    }

    /** 攒够 3 次后用中位数，否则 null */
    fun rideMin(ctx: Context, tripId: String, legIdx: Int): Int? {
        val l = (ctx.getSharedPreferences(RIDE, Context.MODE_PRIVATE).getString("$tripId#$legIdx", "") ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (l.size < 3) null else l.sorted()[l.size / 2]
    }

    fun rideSamples(ctx: Context, tripId: String, legIdx: Int): Int =
        (ctx.getSharedPreferences(RIDE, Context.MODE_PRIVATE).getString("$tripId#$legIdx", "") ?: "")
            .split(',').count { it.isNotBlank() }

    /** 清空某段实测乘车时长，之后重新累计 */
    fun resetRide(ctx: Context, tripId: String, legIdx: Int) {
        ctx.getSharedPreferences(RIDE, Context.MODE_PRIVATE).edit().remove("$tripId#$legIdx").apply()
    }

    /** 每段最终乘车时长：一个值供界面显示、到站推算、出发建议统一使用 */
    data class Ride(val minutes: Int, val source: Source) {
        enum class Source { LEARNED, MANUAL, ESTIMATE, DEFAULT }
    }

    /** 实测中位数（可按半路行程比例缩放）> 手填 > 地铁按站数估算 > rideMin 兜底 */
    fun resolve(learnedMin: Int?, leg: Leg, metroSec: Int?, learnScale: Double = 1.0): Ride {
        if (learnedMin != null) {
            return Ride((learnedMin * learnScale).toInt().coerceAtLeast(1), Ride.Source.LEARNED)
        }
        if (leg.rideMinManual) return Ride(leg.rideMin, Ride.Source.MANUAL)
        if (metroSec != null) return Ride(((metroSec + 59) / 60).coerceIn(1, 120), Ride.Source.ESTIMATE)
        return Ride(leg.rideMin, Ride.Source.DEFAULT)
    }

    fun ride(ctx: Context, trip: Trip, legIdx: Int, metroSec: Int? = null, learnScale: Double = 1.0): Ride =
        resolve(rideMin(ctx, trip.id, legIdx), trip.legs[legIdx], metroSec, learnScale)

    /** 编辑页等只有 tripId + 单段时用这个重载 */
    fun ride(ctx: Context, tripId: String, legIdx: Int, leg: Leg, metroSec: Int? = null): Ride =
        resolve(rideMin(ctx, tripId, legIdx), leg, metroSec)

    // ---------- 公交沿线平均车速（米/分钟，含停站）：每次按定位到了下车站，按沿线距离 / 用时更新 ----------
    private const val BUS_SPEED = "learn_bus_speed"

    fun busSpeed(ctx: Context, tripId: String, legIdx: Int): Double? =
        ctx.getSharedPreferences(BUS_SPEED, Context.MODE_PRIVATE).getFloat("$tripId#$legIdx", 0f)
            .takeIf { it > 0f }?.toDouble()

    /** 指数平均，新样本占 30%；离谱的样本（太慢/太快）不要 */
    fun recordBusSpeed(ctx: Context, tripId: String, legIdx: Int, mPerMin: Double) {
        if (mPerMin.isNaN() || mPerMin !in 80.0..700.0) return
        val p = ctx.getSharedPreferences(BUS_SPEED, Context.MODE_PRIVATE)
        val k = "$tripId#$legIdx"
        val old = p.getFloat(k, 0f)
        val v = if (old <= 0f) mPerMin else old * 0.7 + mPerMin * 0.3
        p.edit().putFloat(k, v.toFloat()).apply()
    }

    // ---------- 地铁推算的偏差：每次在地铁段手动点「已上车」，对比推算班次修正 ----------
    private const val METRO = "learn_metro"

    fun metroKey(c: Candidate, peak: Boolean) = "${c.stopName}|${c.name}|${c.metroDir}|${if (peak) "p" else "o"}"

    fun metroOffsetSec(ctx: Context, key: String): Int =
        ctx.getSharedPreferences(METRO, Context.MODE_PRIVATE).getInt(key, 0)

    /** residualSec：实际 - 推算（已折到 ±半个间隔）；指数平均，新样本占 30% */
    fun recordMetro(ctx: Context, key: String, residualSec: Int) {
        val p = ctx.getSharedPreferences(METRO, Context.MODE_PRIVATE)
        val old = p.getInt(key, 0)
        p.edit().putInt(key, (old * 0.7 + residualSec * 0.3).toInt()).apply()
    }

    fun resetMetro(ctx: Context, c: Candidate) {
        val p = ctx.getSharedPreferences(METRO, Context.MODE_PRIVATE)
        p.edit().remove(metroKey(c, true)).remove(metroKey(c, false)).apply()
    }

    // ---------- 节点派生时长：候车 / 换乘步行 / 站内步行 / 末段步行 / 反应时长 ----------
    // 按天去重，取中位数。补录样本一律不要；推算值只有候车与站内步行两个固定差可以用，
    // 由调用方决定是否接纳（见 CommuteService.learnNodes）。
    private const val NODES = "learn_nodes"

    enum class Metric(val minSamples: Int, val range: IntRange) {
        WAIT(5, 0..1200),        // 进站 → 上车
        TRANSFER(3, 0..1200),    // 下车 → 下一段上车
        EXIT_WALK(5, 0..900),    // 下车 → 出站
        LAST_WALK(3, 60..2400),  // 出站/下车 → 到达
        REACT(3, 10..300),       // 首次提醒 → 确认下车
    }

    private fun nodeKey(m: Metric, scope: String) = "${m.name}:$scope"

    private fun nodeEntries(ctx: Context, m: Metric, scope: String): List<Pair<Long, Int>> =
        (ctx.getSharedPreferences(NODES, Context.MODE_PRIVATE).getString(nodeKey(m, scope), "") ?: "")
            .split(',').mapNotNull { e ->
                val (d, s) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val day = d.toLongOrNull() ?: return@mapNotNull null
                val sec = s.toIntOrNull() ?: return@mapNotNull null
                day to sec
            }

    /** 同一天同一指标只留一条，避免一天跑两次把中位数拉偏 */
    fun recordNode(ctx: Context, metric: Metric, scope: String, sec: Int) {
        if (sec !in metric.range) return
        val day = java.time.LocalDate.now().toEpochDay()
        val p = ctx.getSharedPreferences(NODES, Context.MODE_PRIVATE)
        val list = nodeEntries(ctx, metric, scope)
        if (list.any { it.first == day }) return
        val out = (list + (day to sec)).takeLast(KEEP)
        p.edit().putString(nodeKey(metric, scope), out.joinToString(",") { "${it.first}:${it.second}" }).apply()
    }

    fun nodeSec(ctx: Context, metric: Metric, scope: String): Int? {
        val l = nodeEntries(ctx, metric, scope).map { it.second }.filter { it in metric.range }
        return if (l.size < metric.minSamples) null else l.sorted()[l.size / 2]
    }

    fun waitSamples(ctx: Context, tripId: String, legIdx: Int): List<Int> =
        nodeEntries(ctx, Metric.WAIT, "$tripId#$legIdx").map { it.second }

    fun waitSec(ctx: Context, tripId: String, legIdx: Int) = nodeSec(ctx, Metric.WAIT, "$tripId#$legIdx")

    fun transferWalkSec(ctx: Context, tripId: String, legIdx: Int) =
        nodeSec(ctx, Metric.TRANSFER, "$tripId#$legIdx")

    fun lastWalkSec(ctx: Context, tripId: String) = nodeSec(ctx, Metric.LAST_WALK, tripId)

    fun reactSec(ctx: Context, tripId: String, legIdx: Int) = nodeSec(ctx, Metric.REACT, "$tripId#$legIdx")

    /** T-1 提前量：按个人反应时长，夹在 60–240 秒；样本不足用 90 秒 */
    fun alertLeadMs(ctx: Context, tripId: String, legIdx: Int): Long =
        ((reactSec(ctx, tripId, legIdx) ?: 90).coerceIn(60, 240)) * 1000L
}
