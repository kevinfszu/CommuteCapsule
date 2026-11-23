package com.busnotice.commute

import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行时导入的 JSON 线路数据：解析 + 查询。
 *
 * 不依赖 Android，方便在 JVM 单元测试里直接验证。数据统一使用 WGS-84 坐标。
 * 每个方向是一条独立的线路记录（共用 name，用 reverseId 互相指向）；环线用 loop + dirLabel 表示。
 */
object JsonTransit {
    data class Stop(
        val name: String,
        val lat: Double,
        val lng: Double,
        val firstMin: Int? = null,
        val lastMin: Int? = null,
    )

    data class Line(
        val id: String,
        val name: String,
        val subway: Boolean,
        val loop: Boolean,
        val dirLabel: String,
        val reverseId: String,
        val firstMin: Int,
        val lastMin: Int,
        val hopSec: Int,
        val stops: List<Stop>,
        val weekdayBands: List<HeadwayBand> = emptyList(),
        val weekendBands: List<HeadwayBand> = emptyList(),
        val color: String = "",   // 6 位十六进制，来自 color 或高德原始字段 uicolor
    )

    data class Meta(val source: String, val updated: String, val note: String)

    data class Doc(
        val region: String,
        val source: String,
        val updated: String,
        val note: String,
        val lines: List<Line>,
    ) {
        /** 线路条数按显示名去重（每条线路有两个方向记录）。 */
        val lineCount: Int get() = lines.map { it.name }.distinct().size
        val stationCount: Int get() = lines.flatMap { it.stops }.map { it.name }.distinct().size
    }

    private const val DEFAULT_FIRST = 6 * 60
    private const val DEFAULT_LAST = 23 * 60
    private val CLOCK = Regex("^(\\d{1,2})\\s*[:：]\\s*(\\d{2})$")

    fun parse(text: String): Doc {
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("JSON 格式无效：${it.message ?: "解析失败"}") }
        val arr = root.optJSONArray("lines") ?: throw IllegalArgumentException("缺少 lines 数组字段")
        if (arr.length() == 0) throw IllegalArgumentException("lines 数组不能为空")

        val ids = mutableSetOf<String>()
        val lines = (0 until arr.length()).map { idx ->
            val o = arr.optJSONObject(idx) ?: throw IllegalArgumentException("第 ${idx + 1} 条线路的数据不是对象")
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) throw IllegalArgumentException("第 ${idx + 1} 条线路缺少 id 字段")
            if (!ids.add(id)) throw IllegalArgumentException("线路 id 重复：$id")

            val name = o.optString("name", "").trim()
            if (name.isEmpty()) throw IllegalArgumentException("线路 $id 缺少 name 字段")
            val subway = o.optBoolean("subway", false)
            val loop = o.optBoolean("loop", false)

            val stopsArr = o.optJSONArray("stops") ?: throw IllegalArgumentException("线路 $name 缺少 stops 字段")
            if (stopsArr.length() < 2) throw IllegalArgumentException("线路 $name 至少需要 2 个站点")
            var stops = (0 until stopsArr.length()).map { si ->
                val s = stopsArr.optJSONObject(si)
                    ?: throw IllegalArgumentException("线路 $name 的第 ${si + 1} 个站点数据不是对象")
                val stopName = s.optString("name", "").trim()
                if (stopName.isEmpty()) throw IllegalArgumentException("线路 $name 的第 ${si + 1} 个站点缺少 name 字段")
                val lat = s.optDouble("lat", Double.NaN)
                val lng = s.optDouble("lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                    throw IllegalArgumentException("线路 $name 的站点 $stopName 坐标无效")
                }
                Stop(
                    name = stopName,
                    lat = lat,
                    lng = lng,
                    firstMin = parseClockOrNull(s.optString("first", "")),
                    lastMin = parseClockOrNull(s.optString("last", "")),
                )
            }

            // 环线闭合：首尾站相同，去掉末尾重复站
            if (loop && stops.size > 2 && stops.first().name == stops.last().name) {
                stops = stops.dropLast(1)
            }

            val firstMin = parseClock(o.optString("first", ""), DEFAULT_FIRST)
            val lastMin = parseClock(o.optString("last", ""), DEFAULT_LAST)
            val hopSec = o.optInt("hopSec", if (subway) 140 else 90).coerceIn(20, 900)
            val (weekdayBands, weekendBands) = parseService(o.optJSONObject("service"))

            var dirLabel = o.optString("dirLabel", "").trim()
            if (dirLabel.isEmpty()) {
                if (loop) throw IllegalArgumentException("环线 $name 必须提供 dirLabel（如 内环/外环）")
                dirLabel = stops.last().name
            }

            Line(
                id = id,
                name = name,
                subway = subway,
                loop = loop,
                dirLabel = dirLabel,
                reverseId = o.optString("reverseId", ""),
                firstMin = firstMin,
                lastMin = lastMin,
                hopSec = hopSec,
                stops = stops,
                weekdayBands = weekdayBands,
                weekendBands = weekendBands,
                color = parseColor(o),
            )
        }

        val meta = root.optJSONObject("meta")
        return Doc(
            region = root.optString("region", ""),
            source = meta?.optString("source", "").orEmpty(),
            updated = meta?.optString("updated", "").orEmpty(),
            note = meta?.optString("note", "").orEmpty(),
            lines = lines,
        )
    }

    /** 合并：incoming 里出现的同名线路，先从 existing 移除，再追加 incoming；existing 为空/不可解析直接返回 incoming */
    fun merge(existing: String?, incoming: String): String {
        if (existing.isNullOrBlank()) return incoming
        val old = runCatching { JSONObject(existing) }.getOrNull() ?: return incoming
        val newRoot = JSONObject(incoming)
        val newLines = newRoot.optJSONArray("lines") ?: return incoming
        val names = (0 until newLines.length())
            .mapNotNull { newLines.optJSONObject(it)?.optString("name") }
            .toSet()
        val oldLines = old.optJSONArray("lines") ?: return incoming
        val out = JSONArray()
        for (i in 0 until oldLines.length()) {
            val o = oldLines.optJSONObject(i) ?: continue
            if (o.optString("name") !in names) out.put(o)
        }
        for (i in 0 until newLines.length()) out.put(newLines.optJSONObject(i))
        val root = JSONObject()
            .put("region", newRoot.optString("region", old.optString("region", "")))
            .put("meta", newRoot.optJSONObject("meta") ?: JSONObject())
            .put("lines", out)
        val text = root.toString(2)
        return if (runCatching { parse(text) }.isSuccess) text else incoming
    }

    /** 删除指定显示名的全部方向记录；删完没有线路返回 null；结果必须能通过 parse 校验 */
    fun deleteLine(text: String, name: String): String? {
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("JSON 格式无效：${it.message ?: "解析失败"}") }
        val oldLines = root.optJSONArray("lines") ?: throw IllegalArgumentException("缺少 lines 数组字段")
        val out = JSONArray()
        for (i in 0 until oldLines.length()) {
            val o = oldLines.optJSONObject(i) ?: continue
            if (o.optString("name") != name) out.put(o)
        }
        if (out.length() == 0) return null
        root.put("lines", out)
        val updated = root.toString(2)
        parse(updated)
        return updated
    }

    /**
     * 修改同名全部方向记录的 hopSec；并按方向 id 覆盖始发站 first/last；返回新 JSON 文本。
     * @param service 非空时替换同名全部方向的 service；两张表都为空则移除 service 字段
     */
    fun updateLineParams(
        text: String,
        name: String,
        hopSec: Int,
        clocks: Map<String, Pair<Int, Int>>,
        service: Pair<List<HeadwayBand>, List<HeadwayBand>>? = null,
    ): String {
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("JSON 格式无效：${it.message ?: "解析失败"}") }
        val arr = root.optJSONArray("lines") ?: throw IllegalArgumentException("缺少 lines 数组字段")
        val hop = hopSec.coerceIn(20, 900)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") != name) continue
            o.put("hopSec", hop)
            clocks[o.optString("id")]?.let { (first, last) ->
                o.put("first", minToClock(first))
                o.put("last", minToClock(last))
            }
            if (service != null) {
                val sj = serviceJson(service.first, service.second)
                if (sj == null) o.remove("service") else o.put("service", sj)
            }
        }
        val updated = root.toString(2)
        parse(updated)
        return updated
    }

    private fun minToClock(min: Int): String {
        val m = ((min % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

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

    /** 两张表都为空时返回 null，表示移除 service 字段；分时段边界允许 24:00 */
    private fun serviceJson(weekday: List<HeadwayBand>, weekend: List<HeadwayBand>): JSONObject? {
        if (weekday.isEmpty() && weekend.isEmpty()) return null
        return JSONObject().put("weekday", bandsJson(weekday)).put("weekend", bandsJson(weekend))
    }

    private fun bandClock(min: Int): String {
        val m = if (min == 1440) 1440 else ((min % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** 全部线路按名称归并双向；环线带上方向名，供浏览界面直接展示 */
    fun lines(doc: Doc, region: String): List<SearchLine> =
        doc.lines.groupBy { it.name }.map { (name, group) ->
            SearchLine(
                name = name,
                isSubway = group.first().subway,
                dirs = group.map {
                    LineDir(
                        it.id, it.stops.first().name, it.stops.last().name,
                        if (it.loop) it.dirLabel else "",
                    )
                },
            )
        }

    fun search(doc: Doc, key: String, region: String): SearchResult {
        val raw = key.trim()
        val k = norm(raw)
        if (k.isEmpty()) return SearchResult(emptyList(), emptyList())

        val ls = lines(doc, region).filter { norm(it.name).contains(k) }

        val st = doc.lines
            .flatMap { line -> line.stops.mapIndexed { i, stop -> Triple(line, i, stop) } }
            .filter { (_, _, stop) -> stop.name.contains(raw) || norm(stop.name).contains(k) }
            .distinctBy { (line, _, stop) -> stop.name to line.subway }
            .map { (line, i, stop) ->
                SearchStation(
                    stopId = "${line.id}-${i + 1}",
                    name = stop.name,
                    lat = stop.lat,
                    lng = stop.lng,
                    isSubway = line.subway,
                    siteId = "p-${stop.name}",
                    sameNameId = "p-${stop.name}",
                )
            }

        return SearchResult(ls, st)
    }

    fun lineDetail(doc: Doc, lineId: String, region: String): LineDetail {
        val line = doc.lines.firstOrNull { it.id == lineId }
            ?: throw IllegalArgumentException("未找到线路 $lineId")
        return LineDetail(
            lineId = line.id,
            name = line.name,
            firstStop = line.stops.first().name,
            lastStop = line.stops.last().name,
            stations = line.stops.mapIndexed { i, stop ->
                StopPoint("${line.id}-${i + 1}", stop.name, i + 1, stop.lat, stop.lng)
            },
            reverseLineId = line.reverseId,
            hopSec = line.hopSec,
            colorHex = line.color,
        )
    }

    fun nearbyStops(doc: Doc, lat: Double, lng: Double, region: String): List<NearStop> =
        doc.lines
            .flatMap { line -> line.stops.map { line to it } }
            .map { (line, stop) -> Triple(line, stop, Geo.distanceM(lat, lng, stop.lat, stop.lng)) }
            .filter { it.third < 1500.0 }
            .distinctBy { (line, stop, _) -> stop.name to line.subway }
            .sortedBy { it.third }
            .mapNotNull { (_, stop, distance) -> stopAt(doc, stop.name, distance.toInt()) }

    fun stationLines(doc: Doc, siteId: String, sameNameId: String, region: String): List<NearStop> {
        val name = siteId.removePrefix("p-").ifEmpty { sameNameId.removePrefix("p-") }
        if (name.isEmpty()) return emptyList()
        return listOfNotNull(stopAt(doc, name, 0))
    }

    fun subwayDirs(
        doc: Doc,
        lat: Double,
        lng: Double,
        stationName: String,
        region: String,
    ): List<SubwayDir> {
        val exact = doc.lines.any { line -> line.stops.any { it.name == stationName } }
        val name = if (exact) {
            stationName
        } else {
            val nearest = doc.lines
                .flatMap { it.stops }
                .map { it to Geo.distanceM(lat, lng, it.lat, it.lng) }
                .minByOrNull { it.second }
                ?: return emptyList()
            if (nearest.second > 800.0) return emptyList()
            nearest.first.name
        }

        return doc.lines.mapNotNull { line ->
            val index = line.stops.indexOfFirst { it.name == name }
            if (index < 0) return@mapNotNull null
            // 终点就是本站的方向没有继续乘坐的意义（环线没有真正的终点，保留双向）
            if (!line.loop && line.stops.last().name == name) return@mapNotNull null
            val stop = line.stops[index]
            val offsetMin = index * line.hopSec / 60
            SubwayDir(
                line = line.name,
                shortName = line.name,
                dest = if (line.loop) line.dirLabel else line.stops.last().name,
                firstMin = stop.firstMin ?: (line.firstMin + offsetMin),
                lastMin = stop.lastMin ?: (line.lastMin + offsetMin),
                dirId = line.id,
                order = index + 1,
                weekdayBands = line.weekdayBands,
                weekendBands = line.weekendBands,
                colorHex = line.color,
            )
        }.distinctBy { it.line to it.dest }
    }

    fun metroStops(
        doc: Doc,
        board: List<SubwayDir>,
        toLat: Double,
        toLng: Double,
        region: String,
    ): Int? {
        var best: Int? = null
        for (dir in board) {
            val line = doc.lines.firstOrNull { it.id == dir.dirId } ?: continue
            val (toIndex, distance) = nearest(line.stops, toLat, toLng)
            if (distance > 3000.0) continue
            val fromIndex = (dir.order - 1).coerceIn(0, line.stops.size - 1)
            if (fromIndex == toIndex) continue
            val hops = if (line.loop) {
                (toIndex - fromIndex + line.stops.size) % line.stops.size
            } else {
                toIndex - fromIndex
            }
            val current = best
            if (hops > 0 && (current == null || hops < current)) best = hops
        }
        return best
    }

    fun metroRoute(doc: Doc, q: MetroQuery): MetroRoute? {
        val key = norm(q.lineName)
        if (key.isEmpty()) return null

        var candidates = doc.lines.filter { norm(it.name) == key }
        if (candidates.isEmpty()) {
            candidates = doc.lines.filter { norm(it.name).contains(key) || key.contains(norm(it.name)) }
        }
        if (candidates.isEmpty()) return null

        if (q.dir.isNotEmpty()) {
            val byDir = candidates.filter {
                it.dirLabel == q.dir || (!it.loop && it.stops.last().name == q.dir)
            }
            if (byDir.isNotEmpty()) candidates = byDir
        }

        var best: MetroRoute? = null
        for (line in candidates) {
            val from = stationIndex(line.stops, q.fromName)
                ?: nearest(line.stops, q.fromLat, q.fromLng)
                    .let { if (it.second <= 1500.0) it.first else null }
                ?: continue
            val to = stationIndex(line.stops, q.toName)
                ?: nearest(line.stops, q.toLat, q.toLng)
                    .let { if (it.second <= 3000.0) it.first else null }
                ?: continue
            if (from == to) continue

            val indices: List<Int> = if (line.loop) {
                val hops = (to - from + line.stops.size) % line.stops.size
                if (hops == 0) continue
                (0..hops).map { (from + it) % line.stops.size }
            } else {
                if (to <= from) continue
                (from..to).toList()
            }

            val route = MetroRoute(
                stations = indices.map {
                    MetroStation(line.stops[it].name, line.stops[it].lat, line.stops[it].lng)
                },
                durSec = (indices.size - 1) * line.hopSec,
            )
            val current = best
            if (current == null || route.hops < current.hops) best = route
        }
        return best
    }

    private fun stopAt(doc: Doc, name: String, distanceM: Int): NearStop? {
        val hits = doc.lines.filter { line -> line.stops.any { it.name == name } }
        if (hits.isEmpty()) return null
        return NearStop(
            stopId = "p-$name",
            name = name,
            distanceM = distanceM,
            isSubway = hits.all { it.subway },
            siteId = "p-$name",
            sameNameId = "p-$name",
            lines = hits.filter { !it.subway }.map { line ->
                NearLine(
                    lineId = line.id,
                    name = line.name,
                    lastStop = line.stops.last().name,
                    stopIndex = line.stops.indexOfFirst { it.name == name } + 1,
                )
            },
        )
    }

    private fun stationIndex(stops: List<Stop>, name: String): Int? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val exact = stops.indexOfFirst { it.name == n }
        if (exact >= 0) return exact
        val short = n.removeSuffix("站")
        val withoutSuffix = stops.indexOfFirst { it.name.removeSuffix("站") == short }
        return if (withoutSuffix >= 0) withoutSuffix else null
    }

    private fun nearest(stops: List<Stop>, lat: Double, lng: Double): Pair<Int, Double> {
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        stops.forEachIndexed { index, stop ->
            val distance = Geo.distanceM(lat, lng, stop.lat, stop.lng)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex to bestDistance
    }

    private fun norm(s: String) = s.replace("地铁", "").replace(" ", "").trim()

    /** 线路色：优先 color，其次高德原始字段 uicolor；非 6 位十六进制视为没有 */
    private fun parseColor(o: JSONObject): String {
        val raw = o.optString("color", "").ifBlank { o.optString("uicolor", "") }
        val hex = raw.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        return if (hex.length == 6 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) hex.uppercase() else ""
    }

    /** service.weekday / service.weekend 的 band 数组，最多 24 段；非法段跳过 */
    private fun parseService(o: JSONObject?): Pair<List<HeadwayBand>, List<HeadwayBand>> {
        if (o == null) return emptyList<HeadwayBand>() to emptyList()
        fun bands(name: String): List<HeadwayBand> {
            val arr = o.optJSONArray(name) ?: return emptyList()
            return (0 until minOf(arr.length(), 24)).mapNotNull { i ->
                val b = arr.optJSONObject(i) ?: return@mapNotNull null
                val from = parseClockOrNull(b.optString("from")) ?: return@mapNotNull null
                val to = parseClockOrNull(b.optString("to")) ?: return@mapNotNull null
                val sec = b.optInt("headwaySec", 0)
                if (to <= from || sec !in 30..1800) null else HeadwayBand(from, to, sec)
            }.sortedBy { it.from }
        }
        return bands("weekday") to bands("weekend")
    }

    private fun parseClock(raw: String, fallback: Int): Int =
        parseClockOrNull(raw) ?: fallback

    private fun parseClockOrNull(raw: String): Int? {
        val match = CLOCK.matchEntire(raw.trim()) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour !in 0..27 || minute !in 0..59) return null
        return hour * 60 + minute
    }
}
