package com.busnotice.commute

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun raw13(): String {
        val stops = (1..15).map { "站$it" }
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
        val merged = AmapData.merge(doc11(), AmapData.buildLines(raw13(), AmapData.parseHits(raw13()), emptySet(), "gz", 140, emptyMap()))
        var doc = JsonTransit.parse(merged)
        assertEquals(setOf("11号线", "13号线"), doc.lines.map { it.name }.toSet())
        assertEquals(4, doc.lines.size)

        // 再生成一次 13 号线：同名被替换，不重复
        val again = AmapData.merge(merged, AmapData.buildLines(raw13(), AmapData.parseHits(raw13()), emptySet(), "gz", 140, emptyMap()))
        doc = JsonTransit.parse(again)
        assertEquals(4, doc.lines.size)
        assertEquals(setOf("11号线", "13号线"), doc.lines.map { it.name }.toSet())
    }

    private fun metersBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lng2 - lng1) * 111_320.0 * kotlin.math.cos(Math.toRadians(lat1))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
