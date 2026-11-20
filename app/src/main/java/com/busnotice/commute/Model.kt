package com.busnotice.commute

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** 一段发车间隔：from/to 为一天中的分钟，to 允许 1440（24:00） */
data class HeadwayBand(val from: Int, val to: Int, val headwaySec: Int)

/** 一个可以上车的选项：实时公交、自定义通勤车（时刻表）、地铁（按间隔估算） */
data class Candidate(
    val type: Type,
    val name: String,            // 公交线路名 / 通勤车名称 / 地铁线路名
    val stopName: String,        // 上车点
    val lat: Double,             // WGS-84
    val lng: Double,
    // 公交
    val regionId: String = "",
    val lineId: String = "",
    val stationId: String = "",
    val lastStop: String = "",
    val stopIndex: Int = -1,
    val alightName: String = "", // 下车站（可选，用于生成回程）
    // 通勤车：一天中的发车分钟数
    val departures: List<Int> = emptyList(),
    // 地铁：高峰发车间隔（分钟）、平峰间隔（0 = 按高峰 ×1.5）、方向（终点站名，空 = 不分方向）
    val headwayMin: Int = 0,
    val offPeakHeadwayMin: Int = 0,
    val metroDir: String = "",
    // 下车站坐标（WGS-84，用于到站提醒；NaN 表示运行时再查）
    val alightLat: Double = Double.NaN,
    val alightLng: Double = Double.NaN,
    // 地铁：工作日 / 周末分时段发车间隔；周末为空 = 复制工作日
    val weekdayBands: List<HeadwayBand> = emptyList(),
    val weekendBands: List<HeadwayBand> = emptyList(),
) {
    enum class Type { BUS, SHUTTLE, METRO }

    fun isMetro() = type == Type.METRO
    fun peakHw() = (peakHwSec() + 59) / 60
    fun offPeakHw() = (offPeakHwSec() + 59) / 60

    /** 当天（工作日 / 周末）的时段表：周末为空复制工作日；都为空按旧分钟字段合成 */
    fun bandsFor(weekend: Boolean): List<HeadwayBand> = when {
        weekend && weekendBands.isNotEmpty() -> weekendBands
        !weekend && weekdayBands.isNotEmpty() -> weekdayBands
        weekdayBands.isNotEmpty() -> weekdayBands
        else -> synthesizedBands()
    }

    /** 某时刻的发车间隔（秒） */
    fun headwaySec(minOfDay: Int, weekend: Boolean = false): Int {
        val bands = bandsFor(weekend)
        if (bands.isEmpty()) return 360
        bands.firstOrNull { minOfDay >= it.from && minOfDay < it.to }?.let { return it.headwaySec }
        return if (minOfDay < bands.first().from) bands.first().headwaySec else bands.last().headwaySec
    }

    fun peakHwSec(weekend: Boolean = false): Int =
        bandsFor(weekend).minOfOrNull { it.headwaySec } ?: 360

    fun offPeakHwSec(weekend: Boolean = false): Int =
        bandsFor(weekend).maxOfOrNull { it.headwaySec } ?: 540

    /** 列表摘要：「工作日 6–11 分钟 · 周末 6–8 分钟」；没有分时段数据时「约 6 分钟一班」 */
    fun scheduleText(): String {
        if (weekdayBands.isEmpty() && weekendBands.isEmpty()) return "约 ${peakHw()} 分钟一班"
        fun part(weekend: Boolean): String {
            val hws = bandsFor(weekend).map { it.headwaySec }
            val lo = hws.minOrNull() ?: 360
            val hi = hws.maxOrNull() ?: lo
            return if (lo == hi) "每 ${minText(lo)} 分钟" else "${minText(lo)}–${minText(hi)} 分钟"
        }
        return "工作日 ${part(false)} · 周末 ${part(true)}"
    }

    private fun minText(sec: Int) =
        if (sec % 60 == 0) "${sec / 60}" else "%.1f".format(sec / 60.0)

    private fun synthesizedBands(): List<HeadwayBand> {
        val metro = isMetro()
        val peak = headwayMin.takeIf { it > 0 }?.times(60) ?: if (metro) 360 else 600
        val off = offPeakHeadwayMin.takeIf { it > 0 }?.times(60) ?: if (metro) peak * 3 / 2 else 900
        return listOf(
            HeadwayBand(0, 420, off), HeadwayBand(420, 570, peak),
            HeadwayBand(570, 1020, off), HeadwayBand(1020, 1170, peak),
            HeadwayBand(1170, 1440, off),
        )
    }

    fun label() = when (type) {
        Type.BUS -> if (looksLikeMetro(name) || name.endsWith("线")) name else "${name}路"
        Type.SHUTTLE -> name
        Type.METRO -> name.ifEmpty { "地铁" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name).put("name", name).put("stopName", stopName)
        .put("lat", lat).put("lng", lng).put("regionId", regionId).put("lineId", lineId)
        .put("stationId", stationId).put("lastStop", lastStop).put("stopIndex", stopIndex)
        .put("alightName", alightName).put("departures", JSONArray(departures)).put("headwayMin", headwayMin)
        .put("offPeakHeadwayMin", offPeakHeadwayMin).put("metroDir", metroDir)
        .put("weekdayBands", bandsText(weekdayBands)).put("weekendBands", bandsText(weekendBands))
        .apply { if (!alightLat.isNaN() && !alightLng.isNaN()) put("alightLat", alightLat).put("alightLng", alightLng) }

    companion object {
        private val METRO_RE = Regex("^(地铁)?\\s*\\d+\\s*号线|地铁|机场线|城际")

        /** 有的数据源把地铁也当线路返回，名字像「4号线」 */
        fun looksLikeMetro(name: String) = METRO_RE.containsMatchIn(name)

        fun fromJson(o: JSONObject): Candidate = normalize(parse(o))

        /** 数据源按公交返回的地铁线，按名称归类为地铁 */
        fun normalize(c: Candidate): Candidate =
            if (c.type == Type.BUS && looksLikeMetro(c.name)) c.copy(type = Type.METRO, headwayMin = c.headwayMin.takeIf { it > 0 } ?: 7)
            else c

        private fun parse(o: JSONObject): Candidate {
            val deps = o.optJSONArray("departures")
            return Candidate(
                type = runCatching { Type.valueOf(o.getString("type")) }.getOrDefault(Type.BUS),
                name = o.optString("name"), stopName = o.optString("stopName"),
                lat = o.optDouble("lat"), lng = o.optDouble("lng"),
                regionId = o.optString("regionId"), lineId = o.optString("lineId"),
                stationId = o.optString("stationId"), lastStop = o.optString("lastStop"),
                stopIndex = o.optInt("stopIndex", -1), alightName = o.optString("alightName"),
                departures = if (deps == null) emptyList() else (0 until deps.length()).map { deps.getInt(it) },
                headwayMin = o.optInt("headwayMin"),
                offPeakHeadwayMin = o.optInt("offPeakHeadwayMin"), metroDir = o.optString("metroDir"),
                alightLat = o.optDouble("alightLat"), alightLng = o.optDouble("alightLng"),
                weekdayBands = parseBands(o.optString("weekdayBands")),
                weekendBands = parseBands(o.optString("weekendBands")),
            )
        }

        /** "360-540:360,540-1020:420"；空列表写 "" */
        internal fun bandsText(bands: List<HeadwayBand>): String =
            bands.joinToString(",") { "${it.from}-${it.to}:${it.headwaySec}" }

        private fun parseBands(raw: String): List<HeadwayBand> =
            raw.split(',').mapNotNull { item ->
                val parts = item.trim().split(':')
                if (parts.size != 2) return@mapNotNull null
                val range = parts[0].split('-')
                if (range.size != 2) return@mapNotNull null
                val from = range[0].toIntOrNull() ?: return@mapNotNull null
                val to = range[1].toIntOrNull() ?: return@mapNotNull null
                val sec = parts[1].toIntOrNull() ?: return@mapNotNull null
                if (from < 0 || to <= from || to > 1665 || sec !in 30..1800) return@mapNotNull null
                HeadwayBand(from, to, sec)
            }.sortedBy { it.from }
    }
}

/** 行程中的一段：在同一处换乘点可选的若干交通方式，哪个先来坐哪个 */
data class Leg(
    val candidates: List<Candidate>,
    val rideMin: Int = 10,          // 这段大约坐多久（默认值由地铁按站数估算得出，可手动调整）
    val transferWalkMin: Int = 3,   // 从上一段下车走到这里要多久（第一段忽略）
    val rideMinManual: Boolean = false, // rideMin 是否由用户手填；false 时地铁段优先用按站数估算
) {
    fun title(): String = candidates.joinToString(" / ") { it.label() }.ifEmpty { "未设置" }
    fun stopName(): String = candidates.map { it.stopName }.distinct().joinToString(" / ")
    fun isMetroOnly() = candidates.isNotEmpty() && candidates.all { it.type == Candidate.Type.METRO }

    fun toJson(): JSONObject = JSONObject()
        .put("candidates", JSONArray().apply { candidates.forEach { put(it.toJson()) } })
        .put("rideMin", rideMin).put("transferWalkMin", transferWalkMin)
        .put("rideMinManual", rideMinManual)

    companion object {
        fun fromJson(o: JSONObject): Leg {
            val arr = o.optJSONArray("candidates") ?: JSONArray()
            val ride = o.optInt("rideMin", 10)
            return Leg(
                candidates = (0 until arr.length()).map { Candidate.fromJson(arr.getJSONObject(it)) },
                rideMin = ride, transferWalkMin = o.optInt("transferWalkMin", 3),
                // 旧数据没有这个字段：和默认 10 分钟不同就认为用户调过
                rideMinManual = o.optBoolean("rideMinManual", ride != 10),
            )
        }
    }
}

/** 一条通勤行程：哪些天、什么时间窗、依次坐哪几段 */
data class Trip(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "上班",
    val legs: List<Leg> = emptyList(),
    val dayMask: Int = 0b0011111,   // bit0=周一 ... bit6=周日
    val chinaWorkday: Boolean = false, // true = 按中国法定工作日（忽略 dayMask）
    val startMinute: Int = 8 * 60,  // 出发时间窗
    val endMinute: Int = 8 * 60 + 30,
    val warmupMin: Int = 10,
    val enabled: Boolean = true,
    // 「离开这里才弹」：时间窗内每几分钟粗定位一次，走出这个范围才启动胶囊；半径 0 = 不用
    val leaveLat: Double = Double.NaN,
    val leaveLng: Double = Double.NaN,
    val leaveRadiusM: Int = 0,
    // 没人工打卡时的预估：地铁上车 = 进站 + n 秒；终点下车 = 出站 - n 秒
    val boardEstimateSec: Int = 180,
    val alightEstimateSec: Int = 120,
) {
    fun leaveMode() = leaveRadiusM > 0 && !leaveLat.isNaN() && !leaveLng.isNaN()

    /** 这一天这趟行程要不要跑 */
    fun runsOn(date: LocalDate, isStatutoryWorkday: (LocalDate) -> Boolean): Boolean =
        if (chinaWorkday) isStatutoryWorkday(date)
        else dayMask and (1 shl (date.dayOfWeek.value - 1)) != 0

    fun runsOn(date: LocalDate): Boolean = runsOn(date, ChinaWorkdays::isWorkday)

    /** 有没有任何可运行的日子（替代原来的 dayMask == 0 判断） */
    fun hasDays(): Boolean = chinaWorkday || dayMask != 0

    fun timeText() = "%02d:%02d–%02d:%02d".format(startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60)
    fun daysText(): String {
        if (chinaWorkday) return "法定工作日"
        if (dayMask == 0b0011111) return "周一至周五"
        if (dayMask == 0b1111111) return "每天"
        if (dayMask == 0b1100000) return "周末"
        val n = arrayOf("一", "二", "三", "四", "五", "六", "日")
        return "周" + (0..6).filter { dayMask and (1 shl it) != 0 }.joinToString("") { n[it] }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("legs", JSONArray().apply { legs.forEach { put(it.toJson()) } })
        .put("dayMask", dayMask).put("chinaWorkday", chinaWorkday)
        .put("startMinute", startMinute).put("endMinute", endMinute)
        .put("warmupMin", warmupMin).put("enabled", enabled)
        .put("leaveRadiusM", leaveRadiusM)
        .put("boardEstimateSec", boardEstimateSec).put("alightEstimateSec", alightEstimateSec)
        .apply { if (!leaveLat.isNaN() && !leaveLng.isNaN()) put("leaveLat", leaveLat).put("leaveLng", leaveLng) }

    companion object {
        fun fromJson(o: JSONObject): Trip {
            val arr = o.optJSONArray("legs") ?: JSONArray()
            return Trip(
                id = o.getString("id"), name = o.optString("name", "通勤"),
                legs = (0 until arr.length()).map { Leg.fromJson(arr.getJSONObject(it)) },
                dayMask = o.optInt("dayMask", 0b0011111),
                chinaWorkday = o.optBoolean("chinaWorkday", false),
                startMinute = o.optInt("startMinute", 480), endMinute = o.optInt("endMinute", 510),
                warmupMin = o.optInt("warmupMin", 10), enabled = o.optBoolean("enabled", true),
                leaveLat = o.optDouble("leaveLat"), leaveLng = o.optDouble("leaveLng"),
                leaveRadiusM = o.optInt("leaveRadiusM", 0),
                boardEstimateSec = o.optInt("boardEstimateSec", 180).coerceIn(0, 1800),
                alightEstimateSec = o.optInt("alightEstimateSec", 120).coerceIn(0, 1800),
            )
        }
    }
}

object TripStore {
    private const val PREFS = "trips"
    private const val KEY = "list"

    fun all(ctx: Context): List<Trip> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { runCatching { Trip.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, trips: List<Trip>) {
        val arr = JSONArray()
        trips.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(ctx: Context, t: Trip) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) list[i] = t else list += t
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) = save(ctx, all(ctx).filter { it.id != id })
}
