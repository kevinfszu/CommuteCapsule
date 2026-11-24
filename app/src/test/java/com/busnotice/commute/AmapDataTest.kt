package com.busnotice.commute

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class AmapDataTest {

    // ---------- 样本 ----------

    private fun line(
        id: String, name: String, type: String, from: String, to: String,
        start: String, end: String, color: String, loop: Boolean, direc: String,
        stops: List<String>, timedesc: String = "",
    ): JSONObject {
        val arr = JSONArray()
        stops.forEachIndexed { i, s ->
            arr.put(
                JSONObject().put("name", s).put("sequence", (i + 1).toString())
                    .put("location", "%.6f,%.6f".format(113.3 + i * 0.01, 23.1 + i * 0.01)),
            )
        }
        return JSONObject()
            .put("id", id).put("name", name).put("type", type)
            .put("start_stop", from).put("end_stop", to)
            .put("start_time", start).put("end_time", end)
            .put("uicolor", color).put("loop", if (loop) "1" else "0").put("direc", direc)
            .put("busstops", arr).put("timedesc", timedesc)
    }

    /** 高德 timedesc：URL 编码的 JSON；13 号线工作日 4 段、周末 3 段 */
    private fun timedesc13(): String {
        fun band(from: String, to: String, headway: String) =
            JSONObject().put("start_time", from).put("end_time", to).put("interval_time", headway)
        val obj = JSONObject().put(
            "rule_group", JSONArray()
                .put(
                    JSONObject().put("date", JSONObject().put("day_type", JSONArray().put("weekday")))
                        .put(
                            "time_group", JSONArray()
                                .put(band("06:00:00", "09:00:00", "00:06:00"))
                                .put(band("09:00:00", "17:00:00", "00:07:00"))
                                .put(band("17:00:00", "19:30:00", "00:09:00"))
                                .put(band("19:30:00", "22:30:00", "00:11:00")),
                        ),
                )
                .put(
                    JSONObject().put("date", JSONObject().put("day_type", JSONArray().put("weekend")))
                        .put(
                            "time_group", JSONArray()
                                .put(band("06:00:00", "09:00:00", "00:06:00"))
                                .put(band("09:00:00", "19:00:00", "00:08:00"))
                                .put(band("19:00:00", "22:30:00", "00:10:00")),
                        ),
                ),
        )
        return URLEncoder.encode(obj.toString(), "UTF-8")
    }

    /** 构造任意 day_type / 时段的 timedesc */
    private fun timedesc(days: List<String>, bands: List<Triple<String, String, String>>): String {
        val tg = JSONArray()
        bands.forEach { (from, to, headway) ->
            tg.put(JSONObject().put("start_time", from).put("end_time", to).put("interval_time", headway))
        }
        val obj = JSONObject().put(
            "rule_group", JSONArray().put(
                JSONObject()
                    .put("date", JSONObject().put("day_type", JSONArray(days)))
                    .put("time_group", tg),
            ),
        )
        return URLEncoder.encode(obj.toString(), "UTF-8")
    }

    private fun raw13(): String {        val stops = (1..15).map { "站$it" }
        val td = timedesc13()
        return JSONObject().put("status", "1").put(
            "buslines", JSONArray()
                .put(line("900000071579", "地铁13号线(新沙--天河公园)", "地铁", "新沙", "天河公园", "0600", "2230", "818530", false, "900000071578", stops, td))
                .put(line("900000071578", "地铁13号线(天河公园--新沙)", "地铁", "天河公园", "新沙", "0603", "2305", "818530", false, "900000071579", stops.reversed(), td)),
        ).toString()
    }

    private fun raw11Loop(): String {
        val stops = listOf("赤沙", "琶洲", "员村", "天河公园", "赤沙")
        return JSONObject().put("status", "1").put(
            "buslines", JSONArray()
                .put(line("900000094177", "地铁11号线内环(赤沙--赤沙)", "地铁", "赤沙", "赤沙", "0600", "2230", "F9BE58", true, "900000094176", stops))
                .put(line("900000094176", "地铁11号线外环(赤沙--赤沙)", "地铁", "赤沙", "赤沙", "0600", "2230", "F9BE58", true, "900000094177", stops.reversed())),
        ).toString()
    }

    private fun doc11(): String {
        val raw = raw11Loop()
        val hits = AmapData.parseHits(raw)
        return AmapData.buildLines(raw, hits, emptySet(), "gz", 140, emptyMap())
    }

    // ---------- 测试 ----------

    @Test
    fun `parse hits keeps name color and reverse`() {
        val hits = AmapData.parseHits(raw13())
        assertEquals(2, hits.size)
        val e = hits[0]
        assertEquals("13号线", e.name)
        assertEquals("", e.dirLabel)
        assertTrue(e.subway)
        assertEquals("818530", e.colorHex)
        assertEquals(6 * 60, e.firstMin)
        assertEquals(22 * 60 + 30, e.lastMin)
        assertEquals(15, e.stopCount)
        assertEquals("900000071578", e.reverseAmapId)
        assertEquals("新沙", e.fromStop)
        assertEquals("天河公园", e.toStop)
    }

    @Test
    fun `clean name strips parentheses and loop label`() {
        assertEquals("13号线" to "", AmapData.cleanName("地铁13号线(新沙--天河公园)"))
        assertEquals("11号线" to "内环", AmapData.cleanName("地铁11号线内环(赤沙--赤沙)"))
        assertEquals("11号线" to "外环", AmapData.cleanName("地铁11号线外环（赤沙--赤沙）"))
    }

    @Test
    fun `api error maps to readable message`() {
        val bad = JSONObject().put("status", "0").put("info", "INVALID_USER_KEY").put("infocode", "10001").toString()
        val e = runCatching { AmapData.parseHits(bad) }.exceptionOrNull()
        assertEquals("高德 Key 无效，请检查是否填错", e?.message)
    }

    @Test
    fun `build lines produces parsable doc with reverse and service`() {
        val raw = raw13()
        val hits = AmapData.parseHits(raw)
        val text = AmapData.buildLines(raw, hits, emptySet(), "gz", 150, emptyMap())
        val doc = JsonTransit.parse(text)
        assertEquals("gz", doc.region)
        assertEquals(2, doc.lines.size)
        assertEquals("13号线", doc.lines[0].name)
        assertEquals("13号线", doc.lines[1].name)
        assertEquals("amap-900000071578", doc.lines[0].reverseId)
        assertEquals("amap-900000071579", doc.lines[1].reverseId)
        assertEquals("818530", doc.lines[0].color)
        assertEquals(150, doc.lines[0].hopSec)
        assertEquals(15, doc.lines[0].stops.size)
        assertEquals(listOf(360, 420, 540, 660), doc.lines[0].weekdayBands.map { it.headwaySec })
        assertEquals(listOf(360, 480, 600), doc.lines[0].weekendBands.map { it.headwaySec })
    }

    @Test
    fun `day type all fills both weekday and weekend and derives clocks`() {
        val td = timedesc(
            listOf("all"),
            listOf(
                Triple("06:00:00", "10:00:00", "00:05:00"),
                Triple("10:00:00", "22:00:00", "00:08:00"),
            ),
        )
        val raw = JSONObject().put("status", "1").put(
            "buslines", JSONArray().put(
                line("900000000001", "1路(甲--乙)", "", "甲", "乙", "", "", "000000", false, "900000000002", listOf("甲", "乙", "丙"), td),
            ),
        ).toString()
        val hit = AmapData.parseHits(raw).single()
        assertEquals(2, hit.weekdayBands.size)
        assertEquals(2, hit.weekendBands.size)
        assertEquals(6 * 60, hit.firstMin)
        assertEquals(22 * 60, hit.lastMin)
    }

    @Test
    fun `day type with weekday and weekend in one group fills both`() {
        val td = timedesc(listOf("weekend", "weekday"), listOf(Triple("06:00:00", "22:30:00", "00:07:00")))
        val raw = JSONObject().put("status", "1").put(
            "buslines", JSONArray().put(
                line("900000000003", "2路(甲--乙)", "", "甲", "乙", "", "", "000000", false, "900000000004", listOf("甲", "乙"), td),
            ),
        ).toString()
        val hit = AmapData.parseHits(raw).single()
        assertEquals(1, hit.weekdayBands.size)
        assertEquals(1, hit.weekendBands.size)
    }

    @Test
    fun `sixteen timedesc bands survive build and parse`() {
        val bands = (6..21).map { h ->
            Triple("%02d:00:00".format(h), "%02d:00:00".format(h + 1), "00:05:00")
        }
        val td = timedesc(listOf("weekday"), bands)
        val raw = JSONObject().put("status", "1").put(
            "buslines", JSONArray().put(
                line("900000000005", "3路(甲--乙)", "", "甲", "乙", "0600", "2200", "000000", false, "900000000006", listOf("甲", "乙"), td),
            ),
        ).toString()
        val hits = AmapData.parseHits(raw)
        assertEquals(16, hits.single().weekdayBands.size)
        val doc = JsonTransit.parse(AmapData.buildLines(raw, hits, emptySet(), "gz", 120, emptyMap()))
        assertEquals(16, doc.lines.single().weekdayBands.size)
    }

    @Test
    fun `explicit service overrides timedesc for all directions`() {
        val raw = raw13()
        val hits = AmapData.parseHits(raw)
        val service = AmapData.ServiceBands(listOf(HeadwayBand(6 * 60, 8 * 60, 300)), emptyList())
        val doc = JsonTransit.parse(AmapData.buildLines(raw, hits, emptySet(), "gz", 120, emptyMap(), service))
        assertTrue(
            doc.lines.all {
                it.weekdayBands.map { b -> b.headwaySec } == listOf(300) && it.weekendBands.isEmpty()
            },
        )
    }

    @Test
    fun `invalid timedesc bands are dropped`() {
        val td = timedesc(
            listOf("weekday"),
            listOf(
                Triple("09:00:00", "06:00:00", "00:05:00"),
                Triple("06:00:00", "09:00:00", "00:00:10"),
            ),
        )
        val raw = JSONObject().put("status", "1").put(
            "buslines", JSONArray().put(
                line("900000000007", "4路(甲--乙)", "", "甲", "乙", "0600", "2200", "000000", false, "900000000008", listOf("甲", "乙"), td),
            ),
        ).toString()
        val hit = AmapData.parseHits(raw).single()
        assertTrue(hit.weekdayBands.isEmpty())
    }

    @Test
    fun `manual clocks override one direction only`() {
        val raw = raw13()
        val hits = AmapData.parseHits(raw)
        val override = mapOf(hits[1].amapId to (5 * 60 + 45 to 23 * 60 + 20))
        val doc = JsonTransit.parse(AmapData.buildLines(raw, hits, emptySet(), "gz", 120, override))
        assertEquals(6 * 60, doc.lines[0].firstMin)     // 新沙：保持高德 06:00
        assertEquals(22 * 60 + 30, doc.lines[0].lastMin)
        assertEquals(5 * 60 + 45, doc.lines[1].firstMin) // 天河公园：人工改的
        assertEquals(23 * 60 + 20, doc.lines[1].lastMin)
    }

    @Test
    fun `kept stops filter both directions and loop tail is trimmed`() {
        val raw = raw11Loop()
        val hits = AmapData.parseHits(raw)
        val keep = setOf("赤沙", "琶洲", "员村")
        val doc = JsonTransit.parse(AmapData.buildLines(raw, hits, keep, "gz", 140, emptyMap()))
        assertEquals(2, doc.lines.size)
        assertTrue(doc.lines[0].loop)
        assertEquals("内环", doc.lines[0].dirLabel)
        assertEquals(listOf("赤沙", "琶洲", "员村"), doc.lines[0].stops.map { it.name })
        assertEquals(3, doc.lines[1].stops.size)
    }

    @Test
    fun `loop closing stop is dropped when all stops kept`() {
        val raw = raw11Loop()
        val hits = AmapData.parseHits(raw)
        val doc = JsonTransit.parse(AmapData.buildLines(raw, hits, emptySet(), "gz", 140, emptyMap()))
        assertEquals(listOf("赤沙", "琶洲", "员村", "天河公园"), doc.lines[0].stops.map { it.name })
    }

    @Test
    fun `gcj to wgs round trip stays within 3 meters`() {
        val lat = 23.1291
        val lng = 113.2644
        val (gLat, gLng) = AmapData.wgsToGcj(lat, lng)
        assertNotEquals(lat, gLat, 0.0)
        val (wLat, wLng) = AmapData.gcjToWgs(gLat, gLng)
        val meters = metersBetween(lat, lng, wLat, wLng)
        assertTrue("误差 $meters 米", meters < 3.0)
    }

    @Test
    fun `merge appends and replaces same line name`() {
        val merged = JsonTransit.merge(doc11(), AmapData.buildLines(raw13(), AmapData.parseHits(raw13()), emptySet(), "gz", 140, emptyMap()))
        var doc = JsonTransit.parse(merged)
        assertEquals(setOf("11号线", "13号线"), doc.lines.map { it.name }.toSet())
        assertEquals(4, doc.lines.size)

        // 再生成一次 13 号线：同名被替换，不重复
        val again = JsonTransit.merge(merged, AmapData.buildLines(raw13(), AmapData.parseHits(raw13()), emptySet(), "gz", 140, emptyMap()))
        doc = JsonTransit.parse(again)
        assertEquals(4, doc.lines.size)
        assertEquals(setOf("11号线", "13号线"), doc.lines.map { it.name }.toSet())
    }

    private fun metersBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lng2 - lng1) * 111_320.0 * kotlin.math.cos(Math.toRadians(lat1))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // ---------- 选点 ----------

    @Test
    fun `input tips url encodes keyword and gcj location`() {
        val url = AmapData.inputTipsUrl(" k ", "广州", "华师", 23.1, 113.3)
        assertTrue(url.contains("assistant/inputtips"))
        assertTrue(url.contains("key=k"))
        assertTrue(url.contains("keywords=${URLEncoder.encode("华师", "UTF-8")}"))
        val (gLat, gLng) = AmapData.wgsToGcj(23.1, 113.3)
        assertTrue(url.contains("location=%.6f,%.6f".format(gLng, gLat)))
        assertFalse(AmapData.inputTipsUrl("k", "广州", "华师", null, null).contains("location="))
    }

    @Test
    fun `static map url has markers at gcj point`() {
        val url = AmapData.staticMapUrl("k", 23.1, 113.3)
        assertTrue(url.contains("staticmap"))
        assertTrue(url.contains("markers="))
        assertTrue(url.contains("0xE4572E"))
    }

    @Test
    fun `parse tips skips entries without location`() {
        val json = JSONObject().put("status", "1").put(
            "tips", JSONArray()
                .put(
                    JSONObject().put("name", "华师")
                        .put("address", JSONArray().put("广东省广州市天河区").put("中山大道西"))
                        .put("location", "113.345678,23.123456"),
                )
                .put(JSONObject().put("name", "地铁3号线").put("address", "线路")),
        ).toString()
        val tips = AmapData.parseTips(json)
        assertEquals(1, tips.size)
        val p = tips.single()
        assertEquals("华师", p.name)
        assertEquals("广东省广州市天河区中山大道西", p.address)
        val (wLat, wLng) = AmapData.gcjToWgs(23.123456, 113.345678)
        assertEquals(wLat, p.lat, 1e-9)
        assertEquals(wLng, p.lng, 1e-9)
        assertNull(p.distanceM)
        assertTrue(AmapData.parseTips("{\"status\":\"0\"}").isEmpty())
    }

    @Test
    fun `parse around keeps distance and string address`() {
        val json = JSONObject().put("status", "1").put(
            "pois", JSONArray().put(
                JSONObject().put("name", "石牌桥").put("address", "天河路")
                    .put("location", "113.34,23.13").put("distance", "356"),
            ),
        ).toString()
        val p = AmapData.parseAround(json).single()
        assertEquals("石牌桥", p.name)
        assertEquals("天河路", p.address)
        assertEquals(356, p.distanceM)
    }

    @Test
    fun `parse regeo uses formatted address and request point`() {
        val json = JSONObject().put("status", "1").put(
            "regeocode", JSONObject().put("formatted_address", "广东省广州市天河区中山大道西55号"),
        ).toString()
        val p = AmapData.parseRegeo(json, 23.1, 113.3)!!
        assertEquals("广东省广州市天河区中山大道西55号", p.name)
        assertEquals(23.1, p.lat, 0.0)
        assertEquals(113.3, p.lng, 0.0)
        assertNull(AmapData.parseRegeo("不是 JSON"))
        assertNull(AmapData.parseRegeo("{\"status\":\"0\"}"))
    }
}
