package com.busnotice.commute.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.AmapClient
import com.busnotice.commute.AmapData
import com.busnotice.commute.ImportSummary
import com.busnotice.commute.ImportedJsonSource
import com.busnotice.commute.Transit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CLOCK = Regex("^(\\d{1,2})\\s*[:：]\\s*(\\d{2})$")

private fun hm(min: Int): String = "%02d:%02d".format((min % 1440 + 1440) % 1440 / 60, min % 60)

private fun parseClock(raw: String): Int? {
    val m = CLOCK.matchEntire(raw.trim()) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val mm = m.groupValues[2].toIntOrNull() ?: return null
    if (h !in 0..24 || mm !in 0..59) return null
    return h * 60 + mm
}

/**
 * 用高德 Web API 查询线路并生成数据源。
 * 每次生成一条线路（含反向），同名线路覆盖已有导入数据。
 */
@Composable
fun AmapImportDialog(dark: Boolean, onDismiss: () -> Unit, onImported: (ImportSummary) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)

    var key by remember { mutableStateOf(Transit.amapKey()) }
    var city by remember { mutableStateOf(Transit.amapCity()) }
    var keywords by remember { mutableStateOf("") }

    var searching by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var raw by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<AmapData.Hit>>(emptyList()) }

    var selectedId by remember { mutableStateOf<String?>(null) }
    var keepStops by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hopMin by remember { mutableStateOf("2") }
    var hopSec by remember { mutableStateOf("20") }
    var clocks by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }

    val sel = hits.firstOrNull { it.amapId == selectedId }
    val rev = sel?.let { AmapData.reverseOf(it, hits) }
    val stops = remember(selectedId, raw) { sel?.let { s -> raw?.let { AmapData.stopsOf(it, s) } }.orEmpty() }
    val contentScroll = rememberScrollState()

    fun fail(msg: String) {
        error = msg
        scope.launch { contentScroll.animateScrollTo(0) }
    }

    fun select(h: AmapData.Hit) {
        selectedId = h.amapId
        keepStops = (raw?.let { AmapData.stopsOf(it, h) } ?: emptyList()).map { it.name }.toSet()
        val hop = if (h.subway) AmapData.DEFAULT_METRO_HOP else AmapData.DEFAULT_BUS_HOP
        hopMin = (hop / 60).toString()
        hopSec = (hop % 60).toString().padStart(2, '0')
        clocks = listOfNotNull(h, AmapData.reverseOf(h, hits)).associate { d ->
            d.amapId to (hm(d.firstMin) to hm(d.lastMin))
        }
        error = null
    }

    fun search() {
        error = null
        searching = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AmapClient.search(key, city, keywords) } }
            searching = false
            r.onSuccess { text ->
                raw = text
                selectedId = null
                keepStops = emptySet()
                runCatching { AmapData.parseHits(text) }
                    .onSuccess {
                        hits = it
                        if (it.isEmpty()) fail("没有找到匹配的线路，换个关键字或城市试试")
                    }
                    .onFailure { fail(it.message ?: "查询失败") }
            }.onFailure { fail(it.message ?: "查询失败") }
        }
    }

    fun generate() {
        val h = sel ?: return
        error = null
        val total = (hopMin.toIntOrNull() ?: -1) * 60 + (hopSec.toIntOrNull() ?: -1)
        if (total !in 20..900) { fail("单站用时请填 20–900 秒"); return }
        val clockMin = HashMap<String, Pair<Int, Int>>()
        for ((id, v) in clocks) {
            val from = parseClock(v.first)
            val to = parseClock(v.second)
            if (from == null || to == null) { fail("首末班时间格式应为 HH:mm"); return }
            clockMin[id] = from to to
        }
        if (keepStops.size < 2) { fail("至少保留 2 个站点"); return }
        val text = raw ?: run { fail("请先查询线路"); return }
        generating = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val gen = AmapData.buildLines(
                        text, listOfNotNull(h, rev), keepStops, Transit.region, total, clockMin,
                    )
                    val merged = AmapData.merge(ImportedJsonSource.rawText(ctx), gen)
                    Transit.importJson(ctx, merged)
                }
            }
            generating = false
            result.onSuccess {
                Transit.setAmapKey(ctx, key)
                Transit.setAmapCity(ctx, city)
                Toast.makeText(ctx, "已生成，当前共 ${it.lineCount} 条线路、${it.stationCount} 个站点", Toast.LENGTH_LONG).show()
                onImported(it)
            }.onFailure { fail(it.message ?: "生成失败") }
        }
    }

    val canGenerate = sel != null && keepStops.size >= 2 && !generating && !searching

    AlertDialog(
        onDismissRequest = { if (!generating) onDismiss() },
        shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
        title = { Text("用高德生成数据源") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(contentScroll),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "使用高德「公交信息查询 API」：需先注册为高德开发者，并在控制台创建「Web 服务」类型的 Key。",
                    fontSize = 12.sp, lineHeight = 17.sp, color = dim,
                )
                error?.let { msg ->
                    Text(
                        msg,
                        fontSize = 13.sp, lineHeight = 18.sp, color = Fuse.MetroRed,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Fuse.MetroRed.copy(alpha = 0.10f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                GlassField(
                    key, { key = it }, label = "高德 Key",
                    supportingText = { Text("高德开放平台 → 应用管理 → Web 服务 Key（只存本机）") },
                    singleLine = true,
                )
                GlassField(
                    city, { city = it }, label = "城市",
                    supportingText = { Text("城市名或 adcode，如 广州 / 440100") },
                    singleLine = true,
                )
                GlassField(
                    keywords, { keywords = it }, label = "线路关键字",
                    placeholder = "如 13号线",
                    singleLine = true,
                )
                PillButton(
                    onClick = { search() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !searching && !generating && key.isNotBlank() && city.isNotBlank() && keywords.isNotBlank(),
                ) { Text(if (searching) "查询中…" else "查询") }

                if (hits.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("选择方向（会自动带上反方向）", Modifier.weight(1f))
                        if (sel != null) DialogButton("换一条", onClick = {
                            selectedId = null
                            keepStops = emptySet()
                            error = null
                        })
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (if (sel != null) listOf(sel) else hits).forEach { h ->
                            val on = h.amapId == selectedId
                            val bg = if (on) Fuse.accent(dark).copy(alpha = 0.16f) else Fuse.ink(dark).copy(alpha = 0.05f)
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg)
                                    .clickable(enabled = !generating) { select(h) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = on, onClick = null,
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = Fuse.accent(dark), unselectedColor = dim,
                                        ),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            h.name + (if (h.dirLabel.isNotEmpty()) " ${h.dirLabel}" else " · 开往 ${h.toStop}"),
                                            fontSize = 15.sp,
                                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                            color = ink,
                                        )
                                        Text(
                                            "${h.stopCount} 站 · ${hm(h.firstMin)}–${hm(h.lastMin)}",
                                            fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (sel != null) {
                    Spacer(Modifier.height(2.dp))
                    SectionLabel("站点（未开通的车站请取消勾选）")
                    Text(
                        "已选 ${keepStops.size}/${stops.size} 站" +
                            if (keepStops.size < 2) " · 至少保留 2 站" else "",
                        fontSize = 12.sp, color = if (keepStops.size < 2) Fuse.MetroRed else dim,
                    )
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(rememberScrollState()),
                    ) {
                        stops.forEach { s ->
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !generating) {
                                    keepStops = if (s.name in keepStops) keepStops - s.name else keepStops + s.name
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = s.name in keepStops,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Fuse.accent(dark), uncheckedColor = dim,
                                        checkmarkColor = Fuse.onAccent(dark),
                                    ),
                                )
                                Text(s.name, fontSize = 14.sp, color = ink)
                            }
                        }
                    }

                    SectionLabel("单站用时（高德没有，按整条线填一个值）")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        GlassField(
                            hopMin, { hopMin = it.filter { c -> c.isDigit() }.take(2) },
                            label = "分", modifier = Modifier.weight(1f),
                        )
                        GlassField(
                            hopSec, { hopSec = it.filter { c -> c.isDigit() }.take(2) },
                            label = "秒", modifier = Modifier.weight(1f),
                        )
                    }
                    Text("影响站点首末班与到站推算，20–900 秒", fontSize = 12.sp, color = dim)

                    Spacer(Modifier.height(2.dp))
                    SectionLabel("始发站首末班")
                    listOfNotNull(sel, rev).forEach { d ->
                        val v = clocks[d.amapId] ?: (hm(d.firstMin) to hm(d.lastMin))
                        Text(
                            "开往 ${d.toStop}" + (if (d.dirLabel.isNotEmpty()) "（${d.dirLabel}）" else ""),
                            fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            GlassField(
                                v.first, { t -> clocks = clocks + (d.amapId to (t to v.second)) },
                                label = "首班", modifier = Modifier.weight(1f), singleLine = true,
                            )
                            GlassField(
                                v.second, { t -> clocks = clocks + (d.amapId to (v.first to t)) },
                                label = "末班", modifier = Modifier.weight(1f), singleLine = true,
                            )
                        }
                    }
                }

                Text(
                    "生成后会合并到已导入的数据里，同名线路（两个方向）被覆盖。",
                    fontSize = 12.sp, lineHeight = 17.sp, color = dim,
                )
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            DialogButton(
                if (generating) "生成中…" else "生成并导入",
                primary = true, enabled = canGenerate, onClick = { generate() },
            )
        },
        dismissButton = {
            DialogButton("取消", onClick = onDismiss, enabled = !generating)
        },
    )
}
