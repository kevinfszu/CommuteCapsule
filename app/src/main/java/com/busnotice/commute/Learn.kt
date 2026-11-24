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

    // ---------- 写入日志：一趟行程结束时把本次写入的样本条目存进行程记录，删除记录时可精确回滚 ----------
    private const val JSEP = "\u001f"
    private val journal = ArrayList<String>()

    /** 行程开始：清空日志 */
    fun beginJournal() = synchronized(journal) { journal.clear() }

    /** 行程结束：取走本次写入的样本条目 */
    fun endJournal(): List<String> = synchronized(journal) { journal.toList().also { journal.clear() } }

    private fun journalNote(entry: String) {
        synchronized(journal) { journal.add(entry) }
    }

    internal fun noteWalkJournal(tripId: String, kind: String, day: Long) =
        journalNote("walk$JSEP$tripId$JSEP$kind$JSEP$day")

    /**
     * 删除一条行程记录时调用：把该次行程写入的样本原样撤掉。
     * 只按日志定位，不回滚别的行程的样本；找不到对应样本时静默跳过。
     */
    fun rollback(ctx: Context, entries: List<String>) {
        entries.forEach { raw ->
            val p = raw.split(JSEP)
            if (p.size < 3) return@forEach
            when (p[0]) {
                "node" -> dropToken(ctx, NODES, p[1], p[2], last = false)
                "habit" -> dropToken(ctx, PREFS, p[1], p[2], last = false)
                "ride" -> dropToken(ctx, RIDE, p[1], p[2], last = true)
                "stop" -> dropToken(ctx, STOPS, p[1], p[2], last = true)
                "bus" -> restoreFloat(ctx, BUS_SPEED, p[1], p[2], p.getOrNull(3))
                "metro" -> restoreInt(ctx, METRO, p[1], p[2], p.getOrNull(3))
                "clock" -> dropToken(ctx, BOARD_CLOCK, p[1], p[2], last = false)
                "walk" -> if (p.size >= 4) p[3].toLongOrNull()?.let { WalkLearn.dropSample(ctx, p[1], p[2], it) }
            }
        }
    }

    private fun dropToken(ctx: Context, prefsName: String, key: String, token: String, last: Boolean) {
        val p = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val list = (p.getString(key, "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val i = if (last) list.indexOfLast { it == token } else list.indexOfFirst { it == token }
        if (i < 0) return
        list.removeAt(i)
        if (list.isEmpty()) p.edit().remove(key).apply()
        else p.edit().putString(key, list.joinToString(",")).apply()
    }

    private fun restoreFloat(ctx: Context, prefsName: String, key: String, oldToken: String, newToken: String?) {
        val old = oldToken.toFloatOrNull() ?: return
        val p = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        // 之后又学过新样本时不动当前值，避免把更新的数据覆盖掉
        val expected = newToken?.toFloatOrNull()
        if (expected != null && kotlin.math.abs(p.getFloat(key, 0f) - expected) > 1e-3f) return
        if (old <= 0f) p.edit().remove(key).apply() else p.edit().putFloat(key, old).apply()
    }

    private fun restoreInt(ctx: Context, prefsName: String, key: String, oldToken: String, newToken: String?) {
        val old = oldToken.toIntOrNull() ?: return
        val p = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val expected = newToken?.toIntOrNull()
        if (expected != null && p.getInt(key, 0) != expected) return
        if (old == 0) p.edit().remove(key).apply() else p.edit().putInt(key, old).apply()
    }

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
        val minute = t.hour * 60 + t.minute
        val out = (list + Entry(day, minute)).takeLast(KEEP)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(tripId), out.joinToString(",") { "${it.day}:${it.minute}" }).apply()
        journalNote("habit$JSEP${key(tripId)}$JSEP$day:$minute")
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
        journalNote("ride$JSEP$k$JSEP$minutes")
    }

    /** 第一次实测后就用中位数；样本越多越稳 */
    fun rideMin(ctx: Context, tripId: String, legIdx: Int): Int? {
        val l = (ctx.getSharedPreferences(RIDE, Context.MODE_PRIVATE).getString("$tripId#$legIdx", "") ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (l.isEmpty()) null else l.sorted()[l.size / 2]
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
        journalNote("bus$JSEP$k$JSEP$old$JSEP$v")
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
        val v = (old * 0.7 + residualSec * 0.3).toInt()
        p.edit().putInt(key, v).apply()
        journalNote("metro$JSEP$key$JSEP$old$JSEP$v")
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
        APPROACH(1, 10..3600),   // 出发 → 进站（步行到站）
        WAIT(1, 0..1200),        // 进站 → 上车
        TRANSFER(1, 0..1200),    // 下车 → 下一段上车（含换乘步行 + 候车）
        EXIT_WALK(1, 0..900),    // 下车 → 出站
        LAST_WALK(1, 60..2400),  // 出站/下车 → 到达
        REACT(1, 10..300),       // 首次提醒 → 确认下车
        LEG_SPAN(1, 30..7200),   // 行程段时长：进站/上一段下车 → 本段下车（换乘段含步行 + 候车）
        RIDE_SPAN(1, 60..14400), // 第一次上车 → 最后一次下车
        STATION_SPAN(1, 30..14400), // 进站 → 出站
        TRIP(1, 300..10800),     // 出发 → 到达（全程）
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

    /** 同一天同一指标只留一条，避免一天跑两次把中位数拉偏；[at] 用于按历史记录重放 */
    fun recordNode(ctx: Context, metric: Metric, scope: String, sec: Int, at: Long = System.currentTimeMillis()) {
        if (sec !in metric.range) return
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        val p = ctx.getSharedPreferences(NODES, Context.MODE_PRIVATE)
        val list = nodeEntries(ctx, metric, scope)
        if (list.any { it.first == day }) return
        val out = (list + (day to sec)).takeLast(KEEP)
        p.edit().putString(nodeKey(metric, scope), out.joinToString(",") { "${it.first}:${it.second}" }).apply()
        journalNote("node$JSEP${nodeKey(metric, scope)}$JSEP$day:$sec")
    }

    fun nodeSec(ctx: Context, metric: Metric, scope: String): Int? {
        val l = nodeEntries(ctx, metric, scope).map { it.second }.filter { it in metric.range }
        return if (l.size < metric.minSamples) null else l.sorted()[l.size / 2]
    }

    /** 可以由历史记录重算的节间指标；REACT 依赖实时提醒时刻，无法重算 */
    private val REBUILDABLE = setOf(
        Metric.APPROACH, Metric.WAIT, Metric.TRANSFER, Metric.EXIT_WALK, Metric.LAST_WALK,
        Metric.LEG_SPAN, Metric.RIDE_SPAN, Metric.STATION_SPAN, Metric.TRIP,
    )

    /** 清掉可重算的学习数据：节间（保留 REACT）、上车时刻、每段乘车、指定行程的上车习惯 */
    fun clearForRebuild(ctx: Context, tripIds: Collection<String>) {
        val p = ctx.getSharedPreferences(NODES, Context.MODE_PRIVATE)
        val e = p.edit()
        p.all.keys.filter { k -> REBUILDABLE.any { k.startsWith("${it.name}:") } }.forEach { e.remove(it) }
        e.apply()
        ctx.getSharedPreferences(BOARD_CLOCK, Context.MODE_PRIVATE).edit().clear().apply()
        ctx.getSharedPreferences(RIDE, Context.MODE_PRIVATE).edit().clear().apply()
        val lp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val le = lp.edit()
        tripIds.forEach { le.remove(key(it)) }
        le.apply()
    }

    fun waitSamples(ctx: Context, tripId: String, legIdx: Int): List<Int> =
        nodeEntries(ctx, Metric.WAIT, "$tripId#$legIdx").map { it.second }

    fun waitSec(ctx: Context, tripId: String, legIdx: Int) = nodeSec(ctx, Metric.WAIT, "$tripId#$legIdx")

    fun transferWalkSec(ctx: Context, tripId: String, legIdx: Int) =
        nodeSec(ctx, Metric.TRANSFER, "$tripId#$legIdx")

    /** 最后一段：下车比出站早多少秒；样本不足返回 null，由预设兜底 */
    fun exitWalkSec(ctx: Context, tripId: String, legIdx: Int) =
        nodeSec(ctx, Metric.EXIT_WALK, "$tripId#$legIdx")

    fun lastWalkSec(ctx: Context, tripId: String) = nodeSec(ctx, Metric.LAST_WALK, tripId)

    fun reactSec(ctx: Context, tripId: String, legIdx: Int) = nodeSec(ctx, Metric.REACT, "$tripId#$legIdx")

    // ---------- 换乘站「几点上车」：只记人工确认 / 刷卡的换乘上车时刻，一天一条 ----------
    private const val BOARD_CLOCK = "learn_board_clock"
    private const val BOARD_CLOCK_MIN_SAMPLES = 2

    private fun boardClockKey(tripId: String, legIdx: Int) = "$tripId#$legIdx"

    private fun boardClockEntries(ctx: Context, tripId: String, legIdx: Int): List<Pair<Long, Int>> =
        (ctx.getSharedPreferences(BOARD_CLOCK, Context.MODE_PRIVATE).getString(boardClockKey(tripId, legIdx), "") ?: "")
            .split(',').mapNotNull { e ->
                val (d, m) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val day = d.toLongOrNull() ?: return@mapNotNull null
                val minute = m.toIntOrNull() ?: return@mapNotNull null
                day to minute
            }

    /** 上车时刻（当天第几分钟）：始发站与换乘站都学；同一天只留第一条 */
    fun recordBoardClock(ctx: Context, tripId: String, legIdx: Int, at: Long) {
        val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val day = t.toLocalDate().toEpochDay()
        val minute = t.hour * 60 + t.minute
        if (minute !in 0..1439) return
        val list = boardClockEntries(ctx, tripId, legIdx)
        if (list.any { it.first == day }) return
        val out = (list + (day to minute)).takeLast(KEEP)
        ctx.getSharedPreferences(BOARD_CLOCK, Context.MODE_PRIVATE).edit()
            .putString(boardClockKey(tripId, legIdx), out.joinToString(",") { "${it.first}:${it.second}" }).apply()
        journalNote("clock$JSEP${boardClockKey(tripId, legIdx)}$JSEP$day:$minute")
    }

    /** 这个换乘站通常几点上车（中位数）；样本不足返回 null */
    fun boardClock(ctx: Context, tripId: String, legIdx: Int): Int? {
        val l = boardClockEntries(ctx, tripId, legIdx).map { it.second }
        return if (l.size < BOARD_CLOCK_MIN_SAMPLES) null else l.sorted()[l.size / 2]
    }

    fun boardClockStat(ctx: Context, tripId: String, legIdx: Int): Stat =
        Stat(boardClockEntries(ctx, tripId, legIdx).map { it.second }, BOARD_CLOCK_MIN_SAMPLES)

    /**
     * 用该段历史「几点上车」的样本，取今天还没到的最近一个时刻作为下一班到站时间。
     * 样本不足 2 天或今天已全部错过 → null（交给实时/时刻表兜底，不硬凑明天）。
     */
    fun nextBoardClockAt(ctx: Context, tripId: String, legIdx: Int, now: Long): Long? {
        val mins = boardClockEntries(ctx, tripId, legIdx).map { it.second }
        if (mins.size < BOARD_CLOCK_MIN_SAMPLES) return null
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return mins.sorted().asSequence()
            .map { day.atTime(it / 60, it % 60).atZone(zone).toInstant().toEpochMilli() }
            .firstOrNull { it > now }
    }

    /** T-1 提前量：按个人反应时长，夹在 60–240 秒；样本不足用 90 秒 */
    fun alertLeadMs(ctx: Context, tripId: String, legIdx: Int): Long =
        ((reactSec(ctx, tripId, legIdx) ?: 90).coerceIn(60, 240)) * 1000L

    // ---------- 站间行车时长：按「线路 + 区间」学习（到站打卡积累） ----------
    // 键：a|线路 → 每站秒数样本；p|线路|A|B → A→B 区间秒数样本（用站名，改名后大部分仍能对上）
    private const val STOPS = "learn_stops_v1"
    private const val STOP_AVG = "a|"
    private const val STOP_PAIR = "p|"
    private const val STOP_SEQ = "s|"
    private const val RS = "\u001f"
    private const val STOP_KEEP_AVG = 30
    private const val STOP_KEEP_PAIR = 8

    private fun stopSamples(p: android.content.SharedPreferences, k: String): List<Int> =
        (p.getString(k, "") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }

    private fun stopMedian(l: List<Int>): Int? = if (l.isEmpty()) null else l.sorted()[l.size / 2]

    /**
     * 一次乘车结束（下车 / 结束行程）时落库。marks 是撤销之后仍保留的打卡；
     * atAlight 晚于最后一次打卡时，补上「最后一站 → 下车站」这一段。
     * 没有站内打卡也允许写入：确认上车 → 我已下车 就是首末两站，中间只能按每站平均学习。
     */
    fun recordStops(ctx: Context, lineKey: String, names: List<String>, boardedAt: Long, atAlight: Long, marks: List<StopMark>) {
        if (lineKey.isEmpty() || names.size < 2 || boardedAt <= 0) return
        val hops = names.size - 1
        val list = marks.filter { it.idx in 0..hops && it.at > boardedAt }.sortedBy { it.idx }
        val full = if (atAlight > (list.lastOrNull()?.at ?: 0L)) list + StopMark(hops, atAlight) else list
        val intervals = RideStops.intervals(boardedAt, full)
        // 一次乘车同一个键可能有多条样本，先聚合再写，避免边写边读拿不到本次前一条
        val pairs = HashMap<String, MutableList<Int>>()
        val avgs = mutableListOf<Int>()
        for (iv in intervals) {
            if (iv.span == 1) {
                val from = names.getOrNull(iv.fromIdx) ?: continue
                val to = names.getOrNull(iv.toIdx) ?: continue
                pairs.getOrPut("$from|$to") { mutableListOf() }.add(iv.sec)
            } else {
                avgs.add(iv.sec)   // 跨站只能给出平均每站，没法定到某个区间
            }
        }
        val p = ctx.getSharedPreferences(STOPS, Context.MODE_PRIVATE)
        val edit = p.edit()
        // 记住这条线（含方向）的完整站序，用于「算法学习」按行车方向排序列出全部区间
        val seqKey = "$STOP_SEQ$lineKey"
        val prevSeq = (p.getString(seqKey, "") ?: "").split(RS).filter { it.isNotBlank() }
        val seq = StopOrder.merge(listOf(prevSeq, names))
        if (seq.size >= 2) edit.putString(seqKey, seq.joinToString(RS))
        pairs.forEach { (pair, secs) ->
            val k = "$STOP_PAIR$lineKey|$pair"
            edit.putString(k, (stopSamples(p, k) + secs).takeLast(STOP_KEEP_PAIR).joinToString(","))
            secs.forEach { journalNote("stop$JSEP$k$JSEP$it") }
        }
        if (avgs.isNotEmpty()) {
            val k = "$STOP_AVG$lineKey"
            edit.putString(k, (stopSamples(p, k) + avgs).takeLast(STOP_KEEP_AVG).joinToString(","))
            avgs.forEach { journalNote("stop$JSEP$k$JSEP$it") }
        }
        edit.apply()
    }

    /** 清空全部站间学习（线路、区间、站序），供重新学习 / 删除记录后整体重算 */
    fun clearStops(ctx: Context) {
        ctx.getSharedPreferences(STOPS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 这条线（含方向/终点站）学到的每站秒数；至少 1 个样本 */
    fun stopPerHopSec(ctx: Context, lineKey: String): Int? {
        if (lineKey.isEmpty()) return null
        return stopMedian(stopSamples(ctx.getSharedPreferences(STOPS, Context.MODE_PRIVATE), "$STOP_AVG$lineKey"))
    }

    fun stopPairSec(ctx: Context, lineKey: String, from: String, to: String): Int? {
        if (lineKey.isEmpty() || from.isEmpty() || to.isEmpty()) return null
        return stopMedian(stopSamples(ctx.getSharedPreferences(STOPS, Context.MODE_PRIVATE), "$STOP_PAIR$lineKey|$from|$to"))
    }

    /** 这条线的全部区间样本：进站/恢复时加载一次，避免逐站读 prefs；键 "from|to" → 中位数 */
    fun stopPairTable(ctx: Context, lineKey: String): Map<String, Int> {
        if (lineKey.isEmpty()) return emptyMap()
        val pre = "$STOP_PAIR$lineKey|"
        val p = ctx.getSharedPreferences(STOPS, Context.MODE_PRIVATE)
        return p.all.keys.filter { it.startsWith(pre) }.mapNotNull { k ->
            val v = stopMedian(stopSamples(p, k)) ?: return@mapNotNull null
            k.removePrefix(pre) to v
        }.toMap()
    }

    // ---------- 只读报表：给「算法学习」页面展示用，不参与预测，也不写任何数据 ----------
    data class Stat(val values: List<Int>, val need: Int) {
        val median: Int? get() = if (values.size >= need) values.sorted()[values.size / 2] else null
    }

    data class HabitStat(val days: Int, val typical: Int?, val needDays: Int)
    data class StopPair(val from: String, val to: String, val median: Int?, val count: Int)
    data class StopLineStat(val lineKey: String, val perHop: Stat, val pairs: List<StopPair>)
    data class MetroStat(val stop: String, val line: String, val dir: String, val peak: Boolean, val sec: Int)

    fun habitStat(ctx: Context, tripId: String, dow: Int): HabitStat {
        val all = entries(ctx, tripId)
        val day = all.filter { java.time.LocalDate.ofEpochDay(it.day).dayOfWeek.value == dow }
        return HabitStat(all.size, typicalBoard(ctx, tripId, dow), if (day.size >= 3) 3 else 5)
    }

    fun rideStat(ctx: Context, tripId: String, legIdx: Int): Stat {
        val values = (ctx.getSharedPreferences(RIDE, Context.MODE_PRIVATE).getString("$tripId#$legIdx", "") ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }
        return Stat(values, 1)
    }

    fun nodeStat(ctx: Context, m: Metric, scope: String): Stat =
        Stat(nodeEntries(ctx, m, scope).map { it.second }.filter { it in m.range }, m.minSamples)

    /** 站间样本按线路汇总；lineKey 自身含一个「|」，区间键从右往左拆 */
    fun stopStats(ctx: Context): List<StopLineStat> {
        val p = ctx.getSharedPreferences(STOPS, Context.MODE_PRIVATE)
        val avgs = LinkedHashMap<String, MutableList<Int>>()
        val pairs = LinkedHashMap<String, LinkedHashMap<Pair<String, String>, MutableList<Int>>>()
        val seqs = LinkedHashMap<String, MutableList<List<String>>>()
        p.all.keys.forEach { k ->
            if (k.startsWith(STOP_AVG)) {
                avgs.getOrPut(k.removePrefix(STOP_AVG)) { mutableListOf() }.addAll(stopSamples(p, k))
            } else if (k.startsWith(STOP_PAIR)) {
                val rest = k.removePrefix(STOP_PAIR)
                val to = rest.substringAfterLast('|', "")
                val before = rest.substringBeforeLast('|', "")
                val from = before.substringAfterLast('|', "")
                val line = before.substringBeforeLast('|', "")
                if (line.isEmpty() || from.isEmpty() || to.isEmpty()) return@forEach
                pairs.getOrPut(line) { LinkedHashMap() }
                    .getOrPut(from to to) { mutableListOf() }
                    .addAll(stopSamples(p, k))
            } else if (k.startsWith(STOP_SEQ)) {
                val line = k.removePrefix(STOP_SEQ)
                val seq = (p.getString(k, "") ?: "").split(RS).filter { it.isNotBlank() }
                if (line.isNotEmpty() && seq.size >= 2) seqs.getOrPut(line) { mutableListOf() }.add(seq)
            }
        }
        return (avgs.keys + pairs.keys + seqs.keys).distinct().sorted().map { line ->
            val pairMap = pairs[line].orEmpty()
            // 有过完整站序就用站序；只有区间样本时用区间边还原行车方向
            val order = StopOrder.merge(seqs[line].orEmpty() + pairMap.keys.map { listOf(it.first, it.second) })
            val ordered = if (order.size >= 2) order.zipWithNext() else pairMap.keys.toList()
            StopLineStat(
                lineKey = line,
                perHop = Stat(avgs[line].orEmpty(), 1),
                pairs = ordered.map { (a, b) ->
                    val vals = pairMap[a to b].orEmpty()
                    StopPair(a, b, stopMedian(vals), vals.size)
                },
            )
        }
    }

    /** 地铁班次偏差：键为「站名|线路|方向|p/o」，0 也保留（表示已学但没有偏差） */
    fun metroStats(ctx: Context): List<MetroStat> =
        ctx.getSharedPreferences(METRO, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
            val sec = (v as? Int) ?: return@mapNotNull null
            val parts = k.split('|')
            if (parts.size < 4) return@mapNotNull null
            MetroStat(parts[0], parts[1], parts[2], parts[3] == "p", sec)
        }.sortedWith(compareBy({ it.stop }, { it.line }, { it.dir }, { it.peak }))
}
