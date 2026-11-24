package com.busnotice.commute

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 高德「公交线路名称查询」结果 → 本应用的数据源 JSON。
 *
 * 不依赖 Android（org.json + java.time），方便在 JVM 单测里验证。
 * 高德返回的坐标是 GCJ-02，这里统一转成 WGS-84 再落盘。
 */
object AmapData {
    const val HOST = "https://restapi.amap.com/v3/bus/linename"

    /** 高德没有单站用时：地铁按 140 秒、公交按 90 秒给默认值，生成时用户可改 */
    const val DEFAULT_METRO_HOP = 140
    const val DEFAULT_BUS_HOP = 90

    /** 查询结果里的一条方向记录 */
    data class Hit(
        val amapId: String,
        val name: String,        // 清洗后的线路名，如「13号线」「11号线」
        val dirLabel: String,    // 环线的内环/外环；普通线路为空
        val fromStop: String,
        val toStop: String,
        val subway: Boolean,
        val loop: Boolean,
        val stopCount: Int,
        val firstMin: Int,
        val lastMin: Int,
        val colorHex: String,    // 高德 uicolor
        val reverseAmapId: String,
        val weekdayBands: List<HeadwayBand> = emptyList(),
        val weekendBands: List<HeadwayBand> = emptyList(),
    )

    data class Stop(val name: String, val lat: Double, val lng: Double)

    /** 生成数据源时统一使用的分时段发车间隔 */
    data class ServiceBands(val weekday: List<HeadwayBand>, val weekend: List<HeadwayBand>)

    fun searchUrl(key: String, city: String, keywords: String): String =
        "$HOST?key=${enc(key.trim())}&city=${enc(city.trim())}&keywords=${enc(keywords.trim())}" +
            "&extensions=all&output=json"

    /** status != 1 时抛出带中文说明的异常；没有 buslines 返回空表 */
    fun parseHits(json: String): List<Hit> {
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw IllegalStateException("高德返回的数据无法解析") }
        if (root.optString("status") != "1") {
            throw IllegalStateException(errText(root.optString("info"), root.optString("infocode")))
        }
        val arr = root.optJSONArray("buslines") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            if (id.isEmpty()) return@mapNotNull null
            val (name, dir) = cleanName(o.optString("name"))
            if (name.isEmpty()) return@mapNotNull null
            val stops = o.optJSONArray("busstops")
            val (weekdayBands, weekendBands) = parseTimedesc(o.optString("timedesc"))
            val allBands = weekdayBands + weekendBands
            Hit(
                amapId = id,
                name = name,
                dirLabel = dir,
                fromStop = o.optString("start_stop").ifBlank { stops?.optJSONObject(0)?.optString("name").orEmpty() },
                toStop = o.optString("end_stop").ifBlank {
                    stops?.optJSONObject((stops.length() - 1).coerceAtLeast(0))?.optString("name").orEmpty()
                },
                subway = o.optString("type").contains("地铁"),
                loop = o.optString("loop") == "1",
                stopCount = stops?.length() ?: 0,
                firstMin = hhmmToMin(o.optString("start_time")) ?: allBands.minOfOrNull { it.from } ?: 6 * 60,
                lastMin = hhmmToMin(o.optString("end_time"))?.let { if (it == 0) 1440 else it }
                    ?: allBands.maxOfOrNull { it.to } ?: 23 * 60,
                colorHex = hexOf(o.optString("uicolor")),
                reverseAmapId = o.optString("direc").trim(),
                weekdayBands = weekdayBands,
                weekendBands = weekendBands,
            )
        }
    }

    /** 站名清洗：去掉末尾「(…‑…)」，剥离内环/外环，去掉「地铁」前缀 */
    fun cleanName(raw: String): Pair<String, String> {
        var s = raw.trim()
        s = s.replace(Regex("[（(][^)）]*[)）]\\s*$"), "").trim()
        var dir = ""
        for (d in listOf("内环", "外环")) {
            if (s.endsWith(d)) { dir = d; s = s.removeSuffix(d).trim(); break }
        }
        if (s.startsWith("地铁")) s = s.removePrefix("地铁").trim()
        return s to dir
    }

    /** 某方向的站点（WGS-84，按 sequence 升序；环线首尾同名去掉末尾重复） */
    fun stopsOf(rawJson: String, hit: Hit): List<Stop> {
        val o = busline(rawJson, hit.amapId) ?: return emptyList()
        val arr = o.optJSONArray("busstops") ?: return emptyList()
        val list = (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = s.optString("name").trim()
            if (name.isEmpty()) return@mapNotNull null
            val loc = s.optString("location").split(',')
            if (loc.size != 2) return@mapNotNull null
            val lng = loc[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lat = loc[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            Triple(s.optInt("sequence", i), name, gcjToWgs(lat, lng))
        }.sortedBy { it.first }.map { Stop(it.second, it.third.first, it.third.second) }
        return if (hit.loop && list.size > 2 && list.first().name == list.last().name) list.dropLast(1) else list
    }

    /**
     * 生成数据源 JSON。
     * @param hits 选中的方向（1–2 条，通常一条及其反向）
     * @param keepStops 保留的站名；为空表示全保留
     * @param hopSec 单站用时（秒），20–900
     * @param clocks 每个方向的首末班（分钟），缺省用高德值
     * @param service 用户确认/修改后的分时段发车间隔；为空时用各方向高德 timedesc 的原值
     */
    fun buildLines(
        rawJson: String,
        hits: List<Hit>,
        keepStops: Set<String>,
        region: String,
        hopSec: Int,
        clocks: Map<String, Pair<Int, Int>>,
        service: ServiceBands? = null,
    ): String {
        require(hits.isNotEmpty()) { "请先选择一条线路" }
        val hop = hopSec.coerceIn(20, 900)
        val lines = JSONArray()
        for (h in hits) {
            val stops = stopsOf(rawJson, h).filter { keepStops.isEmpty() || it.name in keepStops }
            require(stops.size >= 2) { "「${h.name}」勾选后不足 2 站" }
            val rev = reverseOf(h, hits)
            val clock = clocks[h.amapId] ?: (h.firstMin to h.lastMin)
            val o = JSONObject()
                .put("id", "amap-${h.amapId}")
                .put("name", h.name)
                .put("subway", h.subway)
                .put("first", minToHhmm(clock.first))
                .put("last", minToHhmm(clock.second))
                .put("hopSec", hop)
            if (h.loop) o.put("loop", true).put("dirLabel", h.dirLabel)
            if (rev != null) o.put("reverseId", "amap-${rev.amapId}")
            if (h.colorHex.isNotEmpty()) o.put("color", h.colorHex)
            o.put("stops", JSONArray().apply {
                stops.forEach { put(JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng)) }
            })
            val weekday = service?.weekday ?: h.weekdayBands
            val weekend = service?.weekend ?: h.weekendBands
            if (weekday.isNotEmpty() || weekend.isNotEmpty()) o.put("service", serviceJson(weekday, weekend))
            lines.put(o)
        }
        return JSONObject()
            .put("region", region)
            .put(
                "meta", JSONObject()
                    .put("source", "高德开放平台（App 内生成）")
                    .put("updated", LocalDate.now().toString())
                    .put("note", "站点首末班按「始发站首末班 + 站序 × 单站用时」推算"),
            )
            .put("lines", lines)
            .toString(2)
    }

    // ---------- 选点（输入提示 / 周边 / 逆地理 / 静态地图） ----------

    /** 高德选点结果：坐标已转成 WGS-84 */
    data class PlacePoint(
        val name: String,
        val address: String,
        val lat: Double,
        val lng: Double,
        val distanceM: Int? = null,
    )

    private const val AMAP = "https://restapi.amap.com/v3"

    fun inputTipsUrl(key: String, city: String, keywords: String, lat: Double?, lng: Double?): String {
        val base = "$AMAP/assistant/inputtips?key=${enc(key.trim())}&keywords=${enc(keywords.trim())}" +
            "&city=${enc(city.trim())}&citylimit=false&output=json"
        if (lat == null || lng == null) return base
        val (glat, glng) = wgsToGcj(lat, lng)
        return "$base&location=${"%.6f".format(glng)},${"%.6f".format(glat)}"
    }

    fun aroundUrl(key: String, lat: Double, lng: Double, radiusM: Int = 1200): String {
        val (glat, glng) = wgsToGcj(lat, lng)
        return "$AMAP/place/around?key=${enc(key.trim())}&location=${"%.6f".format(glng)},${"%.6f".format(glat)}" +
            "&radius=$radiusM&types=${enc("商务住宅|公司企业|地名地址信息|交通设施服务")}" +
            "&offset=20&page=1&extensions=base&output=json"
    }

    fun regeoUrl(key: String, lat: Double, lng: Double): String {
        val (glat, glng) = wgsToGcj(lat, lng)
        return "$AMAP/geocode/regeo?key=${enc(key.trim())}&location=${"%.6f".format(glng)},${"%.6f".format(glat)}" +
            "&extensions=base&output=json"
    }

    /** 静态地图：直接返回图片字节，不能拖动，只做预览 */
    fun staticMapUrl(key: String, lat: Double, lng: Double, w: Int = 640, h: Int = 320, zoom: Int = 16): String {
        val (glat, glng) = wgsToGcj(lat, lng)
        val p = "${"%.6f".format(glng)},${"%.6f".format(glat)}"
        return "$AMAP/staticmap?key=${enc(key.trim())}&location=$p&zoom=$zoom&size=${w}*${h}" +
            "&markers=${enc("mid,0xE4572E,A:$p")}"
    }

    /** 输入提示：没有坐标的条目（公交线路、纯文本提示）跳过；错误返回空表 */
    fun parseTips(json: String): List<PlacePoint> = parsePois(json, "tips")

    /** 周边地点：错误返回空表 */
    fun parseAround(json: String): List<PlacePoint> = parsePois(json, "pois")

    private fun parsePois(json: String, field: String): List<PlacePoint> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        if (root.optString("status") != "1") return emptyList()
        val arr = root.optJSONArray(field) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val loc = o.optString("location").trim().split(',')
            if (loc.size != 2) return@mapNotNull null
            val lng = loc[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lat = loc[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            val (wLat, wLng) = gcjToWgs(lat, lng)
            val name = o.optString("name").trim().ifEmpty { o.optString("district").trim() }
            PlacePoint(
                name = name,
                address = textOf(o.opt("address")).trim(),
                lat = wLat,
                lng = wLng,
                distanceM = o.optString("distance").trim().toIntOrNull(),
            )
        }
    }

    /** 逆地理：取 formatted_address 当名称；坐标用请求坐标（已是 WGS-84） */
    fun parseRegeo(json: String, lat: Double = Double.NaN, lng: Double = Double.NaN): PlacePoint? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optString("status") != "1") return null
        val re = root.optJSONObject("regeocode") ?: return null
        val addr = textOf(re.opt("formatted_address")).trim()
        if (addr.isEmpty()) return null
        return PlacePoint(name = addr, address = "", lat = lat, lng = lng)
    }

    /** 字符串或字符串数组 → 文本（高德的 address 两种都可能） */
    private fun textOf(v: Any?): String = when (v) {
        is JSONArray -> (0 until v.length()).joinToString("") { v.optString(it) }
        is String -> v
        else -> ""
    }

    // ---------- 坐标 ----------

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun wgsToGcj(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lng + dLng)
    }

    fun gcjToWgs(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var wLat = lat
        var wLng = lng
        repeat(3) {
            val (gLat, gLng) = wgsToGcj(wLat, wLng)
            wLat -= gLat - lat
            wLng -= gLng - lng
        }
        return wLat to wLng
    }

    private fun outOfChina(lat: Double, lng: Double) =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    // ---------- 内部 ----------

    fun reverseOf(h: Hit, hits: List<Hit>): Hit? =
        hits.firstOrNull { it.amapId != h.amapId && (it.amapId == h.reverseAmapId || h.amapId == it.reverseAmapId) }
            ?: hits.firstOrNull {
                it.amapId != h.amapId && it.name == h.name && it.dirLabel != h.dirLabel && (it.loop || h.loop)
            }

    private fun busline(rawJson: String, amapId: String): JSONObject? {
        val arr = runCatching { JSONObject(rawJson).optJSONArray("buslines") }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == amapId) return o
        }
        return null
    }

    /**
     * 高德 timedesc → (工作日表, 周末表)。
     *
     * 实测结构：rule_group[].date.day_type 可能是 ["weekday"] / ["weekend"] /
     * ["weekend","weekday"] / ["all"]；time_group[].start_time / end_time / interval_time
     * 都是 HH:mm:ss。peak_time_group / low_time_group / flat_time_group 实测始终为空且
     * 官方没有说明，暂不使用。解析失败或没有可用时段时返回两张空表。
     */
    fun parseTimedesc(timedesc: String): Pair<List<HeadwayBand>, List<HeadwayBand>> {
        val empty = emptyList<HeadwayBand>()
        if (timedesc.isBlank()) return empty to empty
        val decoded = runCatching { URLDecoder.decode(timedesc, "UTF-8") }.getOrNull()
        if (decoded.isNullOrBlank() || decoded == "\"\"") return empty to empty
        val obj = runCatching { JSONObject(decoded) }.getOrNull() ?: return empty to empty
        val groups = obj.optJSONArray("rule_group") ?: return empty to empty
        val weekday = mutableListOf<HeadwayBand>()
        val weekend = mutableListOf<HeadwayBand>()
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            val days = g.optJSONObject("date")?.optJSONArray("day_type") ?: continue
            val types = (0 until days.length()).map { days.optString(it) }
            val toWeekday = types.contains("weekday") || types.contains("all")
            val toWeekend = types.contains("weekend") || types.contains("all")
            if (!toWeekday && !toWeekend) continue
            val tgs = g.optJSONArray("time_group") ?: continue
            for (j in 0 until tgs.length()) {
                val tg = tgs.optJSONObject(j) ?: continue
                val from = clockSec(tg.optString("start_time")) ?: continue
                val to = clockSec(tg.optString("end_time")) ?: continue
                val headway = clockSec(tg.optString("interval_time")) ?: continue
                if (to <= from || headway !in 30..1800) continue
                val band = HeadwayBand(from / 60, to / 60, headway)
                if (toWeekday) weekday += band
                if (toWeekend) weekend += band
            }
        }
        return cleanBands(weekday) to cleanBands(weekend)
    }

    /** 去重、按开始时间排序，并限制最多 24 段 */
    private fun cleanBands(bands: List<HeadwayBand>): List<HeadwayBand> =
        bands.distinctBy { Triple(it.from, it.to, it.headwaySec) }.sortedBy { it.from }.take(24)

    private fun bandsJson(bands: List<HeadwayBand>): JSONArray = JSONArray().apply {
        bands.forEach { b ->
            put(
                JSONObject()
                    .put("from", bandClock(b.from))
                    .put("to", bandClock(b.to))
                    .put("headwaySec", b.headwaySec),
            )
        }
    }

    private fun serviceJson(weekday: List<HeadwayBand>, weekend: List<HeadwayBand>): JSONObject =
        JSONObject().put("weekday", bandsJson(weekday)).put("weekend", bandsJson(weekend))

    /** 分时段边界允许 24:00（1440 分钟），其余按当天时间格式化 */
    private fun bandClock(min: Int): String {
        val m = if (min == 1440) 1440 else ((min % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    private fun clockSec(raw: String): Int? {
        val p = raw.trim().split(':')
        if (p.size < 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val s = p.getOrNull(2)?.toIntOrNull() ?: 0
        if (h !in 0..24 || m !in 0..59 || s !in 0..59) return null
        return h * 3600 + m * 60 + s
    }

    private fun hhmmToMin(raw: String): Int? {
        val t = raw.trim()
        if (t.length != 4 || !t.all { it.isDigit() }) return null
        val h = t.substring(0, 2).toIntOrNull() ?: return null
        val m = t.substring(2, 4).toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun minToHhmm(min: Int): String {
        val m = ((min % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    private fun hexOf(raw: String): String {
        val h = raw.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        return if (h.length == 6 && h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) h.uppercase() else ""
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun errText(info: String, code: String): String = when (info) {
        "INVALID_USER_KEY" -> "高德 Key 无效，请检查是否填错"
        "INVALID_USER_SCODE" -> "这个 Key 开启了数字签名校验，请在控制台关闭签名或换一个未开启签名的 Key"
        "SERVICE_NOT_AVAILABLE" -> "这个 Key 没有开通「公交信息查询 API」服务，请在控制台开通"
        "DAILY_QUERY_OVER_LIMIT" -> "这个 Key 今天的查询次数已用完"
        "INVALID_USER_IP" -> "这个 Key 绑定了 IP 白名单，请在控制台取消限制"
        else -> "高德返回错误：${info.ifBlank { "未知错误" }}（${code.ifBlank { "无错误码" }}）"
    }
}
