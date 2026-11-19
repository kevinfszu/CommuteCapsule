@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.busnotice.commute.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.busnotice.commute.Candidate
import com.busnotice.commute.HeadwayBand
import com.busnotice.commute.Transit
import com.busnotice.commute.NearStop
import com.busnotice.commute.LineDir
import com.busnotice.commute.LineDetail
import com.busnotice.commute.StopPoint
import com.busnotice.commute.SearchLine
import com.busnotice.commute.SearchStation
import com.busnotice.commute.SubwayDir
import com.busnotice.commute.Loc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ 公共小部件

/** 公交加「路」，地铁线（数据源里也能查到）标出「地铁」 */
fun lineText(name: String) = when {
    Candidate.looksLikeMetro(name) -> "$name · 地铁"
    name.endsWith("线") -> name
    else -> "${name}路"
}

@Composable
private fun PickerScaffold(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    GlassPage(title = title, onBack = onBack, content = content)
}

@Composable
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit) {
    GlassField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = hint,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, "清空") } },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun LazyListScope.header(text: String) = sectionHeader(text)

private fun LazyListScope.loading() = item {
    val dark = isSystemInDarkTheme()
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Fuse.accent(dark))
    }
}

private fun LazyListScope.hint(text: String) = item {
    val dark = isSystemInDarkTheme()
    Text(text, fontSize = 14.sp, color = Fuse.dim(dark), modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp))
}

/** 搜索结果、站点列表左边的定位小图标 */
@Composable
private fun PinIcon() {
    val dark = isSystemInDarkTheme()
    Icon(Icons.Default.LocationOn, null, tint = Fuse.dim(dark), modifier = Modifier.size(22.dp))
}

/** 线路牌：公交绿、地铁红 */
@Composable
private fun NameBadge(name: String) = LineBadge(shortLine(name), lineColor(null, name))

/** 一排小线路牌（附近站点经过的线路） */
@Composable
private fun BadgeStrip(names: List<String>, max: Int = 6) {
    val dark = isSystemInDarkTheme()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        names.take(max).forEach { NameBadge(it) }
        if (names.size > max) Text("+${names.size - max}", fontSize = 12.sp, color = Fuse.dim(dark))
    }
}

/** 站序号小圆 */
@Composable
private fun OrderDot(n: Int) {
    val dark = isSystemInDarkTheme()
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(Fuse.hairline(dark)),
        contentAlignment = Alignment.Center,
    ) { Text("$n", fontSize = 12.sp, color = Fuse.dim(dark), style = TnumStyle) }
}

private fun distText(m: Int) = if (m >= 1000) "%.1f 公里".format(m / 1000.0) else "$m 米"

private fun secText(sec: Int) = "%d 分 %02d 秒".format(sec / 60, sec % 60)

/** 筛选关键词：忽略空白与大小写，末尾的「路 / 号线 / 站」不影响匹配 */
private fun filterKey(text: String) =
    text.replace(Regex("\\s+"), "").lowercase().removeSuffix("号线").removeSuffix("路").removeSuffix("站")

private fun nameMatches(name: String, q: String): Boolean {
    val k = filterKey(q)
    return k.isEmpty() || filterKey(name).contains(k)
}

/** 线路名或任一端点站名命中即算匹配 */
private fun lineMatches(l: SearchLine, q: String): Boolean =
    nameMatches(l.name, q) || l.dirs.any { nameMatches(it.firstStop, q) || nameMatches(it.lastStop, q) }

private fun dirLabel(d: LineDir) = d.label.ifEmpty { "开往 ${d.lastStop}" }

/**
 * 可筛选的选项列表：顶部输入框 + 选项卡片。
 * 线路、站点、下车站共用；options 由调用方过滤好，这里只负责展示与 60 条上限。
 */
@Composable
private fun <T> PickerOptions(
    title: String,
    placeholder: String,
    query: String,
    onQuery: (String) -> Unit,
    options: List<T>,
    emptyHint: String,
    onBack: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    PickerScaffold(title, onBack) {
        val lead = leading
        if (lead != null) item { lead() }
        item { SearchField(query, placeholder, onQuery) }
        if (options.isEmpty()) {
            hint(emptyHint)
        } else {
            glassGroup(options.take(60), key = "options") { row(it) }
            if (options.size > 60) hint("仅显示前 60 条，继续输入关键词缩小范围")
        }
    }
}

// ------------------------------------------------------------------ 公交

private sealed interface BusStage {
    data object Lines : BusStage
    data class Station(val title: String, val siteId: String, val sameNameId: String, val fallback: List<NearStop>) : BusStage
    data class Line(val name: String, val dirs: List<LineDir>, val dirIndex: Int, val query: String) : BusStage
    data class Alight(val detail: LineDetail, val board: StopPoint, val query: String) : BusStage
}

/** 选公交：先选线路 → 选上车站 → 选下车站（可跳过）；附近站点作为快捷入口 */
@Composable
fun BusPicker(onBack: () -> Unit, onPicked: (Candidate) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<BusStage>(BusStage.Lines) }
    var lineQuery by remember { mutableStateOf("") }
    var busLines by remember { mutableStateOf<List<SearchLine>?>(null) }
    var linesFailed by remember { mutableStateOf(false) }
    var nearby by remember { mutableStateOf<List<NearStop>?>(null) }
    var nearbyError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loc = Loc.current(ctx)
        if (loc == null) {
            nearbyError = "拿不到位置，可以直接筛选线路"
        } else {
            nearby = withContext(Dispatchers.IO) {
                runCatching { Transit.nearbyStops(loc.latitude, loc.longitude) }.getOrNull()
            }?.filter { !it.isSubway && it.lines.isNotEmpty() }
            if (nearby == null) nearbyError = "附近站点加载失败，可以直接筛选线路"
        }
        val all = withContext(Dispatchers.IO) { runCatching { Transit.lines() }.getOrNull() }
        busLines = all?.filter { !it.isSubway }
        linesFailed = all == null
    }

    /** 选定了上车站：拉线路详情拿坐标，进入「选下车站」 */
    fun board(lineId: String, order: Int, stopId: String) {
        if (busy) return
        busy = true
        scope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { Transit.lineDetail(lineId) }.getOrNull() }
            busy = false
            val st = d?.stations?.firstOrNull { it.order == order } ?: d?.stations?.firstOrNull { it.stopId == stopId }
            if (d == null || st == null) Toast.makeText(ctx, "线路详情加载失败", Toast.LENGTH_SHORT).show()
            else stage = BusStage.Alight(d, st, "")
        }
    }

    fun finish(d: LineDetail, b: StopPoint, alight: String): Unit {
        val a = d.stations.firstOrNull { it.name == alight && it.order > b.order }
        onPicked(Candidate.normalize(
            Candidate(
                type = Candidate.Type.BUS, name = d.name, stopName = b.name, lat = b.lat, lng = b.lng,
                lineId = d.lineId, stationId = b.stopId, lastStop = d.lastStop, stopIndex = b.order, alightName = alight,
                alightLat = a?.lat ?: Double.NaN, alightLng = a?.lng ?: Double.NaN,
            )
        ))
    }

    when (val s = stage) {
        BusStage.Lines -> {
            val all = busLines
            val shown = remember(all, lineQuery) { all.orEmpty().filter { lineMatches(it, lineQuery) } }
            PickerScaffold("添加公交", onBack) {
                item { SearchField(lineQuery, "筛选线路号或终点站", { lineQuery = it }) }
                if (busy) loading()
                if (lineQuery.isBlank()) {
                    header("附近站点")
                    when {
                        nearby != null && nearby!!.isEmpty() -> hint("附近没有公交站，可以从下面的线路进入")
                        nearby != null -> glassGroup(nearby!!.groupBy { it.name }.values.toList(), key = "nearby") { group ->
                            val st = group.first()
                            GlassRow(
                                headline = st.name,
                                supportingContent = { BadgeStrip(group.flatMap { it.lines }.map { it.name }.distinct().take(8)) },
                                trailing = { Text(distText(group.minOf { it.distanceM }), fontSize = 13.sp, color = Fuse.dim(isSystemInDarkTheme())) },
                                leading = { PinIcon() },
                                onClick = { stage = BusStage.Station(st.name, st.siteId, st.sameNameId, group) },
                            )
                        }
                        nearbyError != null -> hint(nearbyError!!)
                        else -> loading()
                    }
                }
                when {
                    all == null -> if (linesFailed) hint("线路列表加载失败，换个数据源试试") else loading()
                    shown.isEmpty() -> hint(
                        if (lineQuery.isBlank()) "当前数据里没有公交线路，可从附近站点进入或更换数据源"
                        else "没有匹配的线路"
                    )
                    else -> {
                        header(if (lineQuery.isBlank()) "全部公交线路" else "匹配的线路")
                        glassGroup(shown.take(60), key = "bus-lines") { l ->
                            GlassRow(
                                headline = lineText(l.name), bold = true,
                                supporting = l.dirs.joinToString(" / ") { d ->
                                    if (d.label.isEmpty()) "${d.firstStop} ⇄ ${d.lastStop}" else d.label
                                }.ifEmpty { null },
                                leading = { NameBadge(l.name) },
                                onClick = { stage = BusStage.Line(l.name, l.dirs, 0, "") },
                            )
                        }
                        if (shown.size > 60) hint("仅显示前 60 条，继续输入关键词缩小范围")
                    }
                }
            }
        }

        is BusStage.Station -> {
            var platforms by remember(s) { mutableStateOf<List<NearStop>?>(null) }
            var failed by remember(s) { mutableStateOf(false) }
            var query by remember(s) { mutableStateOf("") }
            LaunchedEffect(s) {
                val r = if (s.siteId.isNotEmpty()) withContext(Dispatchers.IO) {
                    runCatching { Transit.stationLines(s.siteId, s.sameNameId) }.getOrNull()
                } else null
                platforms = (r?.takeIf { it.isNotEmpty() } ?: s.fallback).filter { it.lines.isNotEmpty() }
                failed = platforms!!.isEmpty()
            }
            PickerScaffold(s.title, { stage = BusStage.Lines }) {
                if (busy) loading()
                item { SearchField(query, "筛选线路", { query = it }) }
                val ps = platforms
                when {
                    ps == null -> loading()
                    failed -> hint("这个站没查到线路")
                    else -> {
                        var shown = 0
                        ps.forEachIndexed { i, p ->
                            val hits = p.lines.filter { nameMatches(it.name, query) }
                            if (hits.isNotEmpty()) {
                                shown++
                                header(if (ps.size > 1) "站台 ${i + 1}" else "经过的线路")
                                glassGroup(hits, key = "platform$i") { l ->
                                    GlassRow(
                                        headline = lineText(l.name), bold = true,
                                        supporting = "开往 ${l.lastStop}",
                                        leading = { NameBadge(l.name) },
                                        onClick = { board(l.lineId, l.stopIndex, p.stopId) },
                                    )
                                }
                            }
                        }
                        if (shown == 0) hint("没有匹配的线路")
                    }
                }
            }
        }

        is BusStage.Line -> {
            val dir = s.dirs[s.dirIndex]
            var detail by remember(dir.lineId) { mutableStateOf<LineDetail?>(null) }
            var failed by remember(dir.lineId) { mutableStateOf(false) }
            LaunchedEffect(dir.lineId) {
                detail = withContext(Dispatchers.IO) { runCatching { Transit.lineDetail(dir.lineId) }.getOrNull() }
                failed = detail == null
            }
            val d = detail
            val stations = remember(d, s.query) {
                d?.stations.orEmpty().filter { nameMatches(it.name, s.query) }
            }
            when {
                failed -> PickerScaffold("${lineText(s.name)} · 选上车站", { stage = BusStage.Lines }) {
                    hint("线路详情加载失败")
                }
                d == null -> PickerScaffold("${lineText(s.name)} · 选上车站", { stage = BusStage.Lines }) {
                    loading()
                }
                else -> PickerOptions(
                    title = "${lineText(s.name)} · 选上车站",
                    placeholder = "筛选本站",
                    query = s.query,
                    onQuery = { stage = s.copy(query = it) },
                    options = stations,
                    emptyHint = "没有匹配的站点",
                    onBack = { stage = BusStage.Lines },
                    leading = {
                        if (s.dirs.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                s.dirs.forEachIndexed { i, item ->
                                    GlassChip(selected = i == s.dirIndex,
                                        onClick = { stage = s.copy(dirIndex = i, query = "") },
                                        label = dirLabel(item))
                                }
                            }
                        }
                    },
                ) { st ->
                    GlassRow(
                        headline = st.name,
                        leading = { OrderDot(st.order) },
                        onClick = { stage = BusStage.Alight(d, st, "") },
                    )
                }
            }
        }

        is BusStage.Alight -> {
            val alights = remember(s.detail, s.board, s.query) {
                s.detail.stations.filter { it.order > s.board.order && nameMatches(it.name, s.query) }
            }
            PickerOptions(
                title = "在哪下车？",
                placeholder = "筛选下车站",
                query = s.query,
                onQuery = { stage = s.copy(query = it) },
                options = alights,
                emptyHint = if (s.query.isBlank()) "这已经是终点站，可以直接跳过" else "没有匹配的站点",
                onBack = {
                    stage = BusStage.Line(
                        s.detail.name,
                        listOf(LineDir(s.detail.lineId, s.detail.firstStop, s.detail.lastStop)),
                        0, "",
                    )
                },
                leading = {
                    val dark = isSystemInDarkTheme()
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NameBadge(s.detail.name)
                            Spacer(Modifier.width(10.dp))
                            Text("${lineText(s.detail.name)} · ${s.board.name} 上车 · 开往 ${s.detail.lastStop}",
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Fuse.ink(dark))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("选了下车站就能一键生成回程，也可以跳过", fontSize = 12.sp, color = Fuse.dim(dark))
                        Spacer(Modifier.height(10.dp))
                        PillButton(secondary = true, onClick = { finish(s.detail, s.board, "") }) { Text("跳过") }
                    }
                },
            ) { st ->
                GlassRow(
                    headline = st.name,
                    supporting = "${st.order - s.board.order} 站",
                    leading = { OrderDot(st.order - s.board.order) },
                    onClick = { finish(s.detail, s.board, st.name) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 地铁

private sealed interface MetroStage {
    data object Lines : MetroStage
    data class Line(val line: SearchLine, val dirIndex: Int, val query: String) : MetroStage
    data class Station(val line: SearchLine, val dir: LineDir, val stop: StopPoint) : MetroStage
}

/** 地铁没有实时数据：先选线路 → 选站 → 确认方向、首末班与间隔，再填下车站 */
@Composable
fun MetroPicker(onBack: () -> Unit, onPicked: (Candidate) -> Unit) {
    var stage by remember { mutableStateOf<MetroStage>(MetroStage.Lines) }
    var lineQuery by remember { mutableStateOf("") }
    var metroLines by remember { mutableStateOf<List<SearchLine>?>(null) }
    var linesFailed by remember { mutableStateOf(false) }
    var headway by remember { mutableStateOf(300) }
    var offPeak by remember { mutableStateOf(540) }
    var alight by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val all = withContext(Dispatchers.IO) { runCatching { Transit.lines() }.getOrNull() }
        metroLines = all?.filter { it.isSubway }
        linesFailed = all == null
    }

    when (val s = stage) {
        MetroStage.Lines -> {
            val all = metroLines
            val shown = remember(all, lineQuery) { all.orEmpty().filter { lineMatches(it, lineQuery) } }
            PickerScaffold("添加地铁", onBack) {
                item { SearchField(lineQuery, "筛选地铁线路", { lineQuery = it }) }
                when {
                    all == null -> if (linesFailed) hint("线路列表加载失败，换个数据源试试") else loading()
                    shown.isEmpty() -> hint(
                        if (lineQuery.isBlank()) "当前数据里没有地铁线路，请先导入含地铁的数据"
                        else "没有匹配的线路"
                    )
                    else -> {
                        header(if (lineQuery.isBlank()) "全部地铁线路" else "匹配的线路")
                        glassGroup(shown.take(60), key = "metro-lines") { l ->
                            GlassRow(
                                headline = lineText(l.name), bold = true,
                                supporting = l.dirs.joinToString(" / ") { dirLabel(it) }.ifEmpty { null },
                                leading = { NameBadge(l.name) },
                                onClick = { stage = MetroStage.Line(l, 0, "") },
                            )
                        }
                        if (shown.size > 60) hint("仅显示前 60 条，继续输入关键词缩小范围")
                    }
                }
            }
        }

        is MetroStage.Line -> {
            val dir = s.line.dirs[s.dirIndex]
            var detail by remember(dir.lineId) { mutableStateOf<LineDetail?>(null) }
            var failed by remember(dir.lineId) { mutableStateOf(false) }
            LaunchedEffect(dir.lineId) {
                detail = withContext(Dispatchers.IO) { runCatching { Transit.lineDetail(dir.lineId) }.getOrNull() }
                failed = detail == null
            }
            val d = detail
            val stations = remember(d, s.query) {
                d?.stations.orEmpty().filter { nameMatches(it.name, s.query) }
            }
            when {
                failed -> PickerScaffold("${lineText(s.line.name)} · 选上车站", { stage = MetroStage.Lines }) {
                    hint("线路详情加载失败")
                }
                d == null -> PickerScaffold("${lineText(s.line.name)} · 选上车站", { stage = MetroStage.Lines }) {
                    loading()
                }
                else -> PickerOptions(
                    title = "${lineText(s.line.name)} · 选上车站",
                    placeholder = "筛选本站",
                    query = s.query,
                    onQuery = { stage = s.copy(query = it) },
                    options = stations,
                    emptyHint = "没有匹配的站点",
                    onBack = { stage = MetroStage.Lines },
                    leading = {
                        if (s.line.dirs.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                s.line.dirs.forEachIndexed { i, item ->
                                    GlassChip(selected = i == s.dirIndex,
                                        onClick = { stage = s.copy(dirIndex = i, query = "") },
                                        label = dirLabel(item))
                                }
                            }
                        }
                    },
                ) { st ->
                    GlassRow(
                        headline = st.name,
                        leading = { OrderDot(st.order) },
                        onClick = { stage = MetroStage.Station(s.line, s.line.dirs[s.dirIndex], st) },
                    )
                }
            }
        }

        is MetroStage.Station -> {
            var dirs by remember(s) { mutableStateOf<List<SubwayDir>?>(null) }
            var dirDest by remember(s) { mutableStateOf("") }
            LaunchedEffect(s) {
                dirs = withContext(Dispatchers.IO) {
                    runCatching { Transit.subwayDirs(s.stop.lat, s.stop.lng, s.stop.name) }.getOrNull()
                }.orEmpty()
            }
            val lineDirs = dirs.orEmpty().filter { Transit.sameLine(s.line.name, it) }
            val sel = lineDirs.firstOrNull { it.dest == dirDest }
                ?: lineDirs.firstOrNull { it.dest == s.dir.lastStop || it.dest == s.dir.label }
                ?: lineDirs.firstOrNull()
            val bands = sel?.weekdayBands.orEmpty()
            val weekendBands = sel?.weekendBands.orEmpty().ifEmpty { bands }
            val back = {
                val i = s.line.dirs.indexOfFirst { it.lineId == s.dir.lineId }.coerceAtLeast(0)
                stage = MetroStage.Line(s.line, i, "")
            }
            PickerScaffold("${s.stop.name} 上车", back) {
                item {
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val dark = isSystemInDarkTheme()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NameBadge(s.line.name)
                                Spacer(Modifier.width(10.dp))
                                Text("${lineText(s.line.name)} · ${s.stop.name} 上车", fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold, color = Fuse.ink(dark))
                            }
                            if (lineDirs.isNotEmpty()) {
                                SectionLabel("方向")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    lineDirs.forEach { item ->
                                        GlassChip(selected = sel?.dest == item.dest, onClick = { dirDest = item.dest },
                                            label = if (s.dir.label.isEmpty()) "开往 ${item.dest}" else item.dest)
                                    }
                                }
                                Text("本站首末班：" + lineDirs.joinToString("，") {
                                    "往${it.dest} ${hhmm(it.firstMin.coerceAtLeast(0))}–${hhmm(it.lastMin.coerceAtLeast(0))}"
                                }, fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark))
                                if (bands.isNotEmpty()) {
                                    SectionLabel("发车间隔")
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("工作日", fontSize = 12.sp, color = Fuse.dim(dark))
                                        bands.forEach { Text("${hhmm(it.from)}–${hhmm(it.to)}　每 ${secText(it.headwaySec)}",
                                            fontSize = 13.sp, color = Fuse.ink(dark)) }
                                        Text("周末", fontSize = 12.sp, color = Fuse.dim(dark),
                                            modifier = Modifier.padding(top = 4.dp))
                                        weekendBands.forEach { Text("${hhmm(it.from)}–${hhmm(it.to)}　每 ${secText(it.headwaySec)}",
                                            fontSize = 13.sp, color = Fuse.ink(dark)) }
                                    }
                                    Text("时段来自线路数据，添加后可在「编辑」里逐段调整间隔。",
                                        fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark))
                                } else {
                                    HeadwayStepper("高峰间隔", headway) { headway = it }
                                    HeadwayStepper("平峰间隔", offPeak) { offPeak = it }
                                }
                                Text("数据源没有地铁实时位置，按本站首班 + 间隔推算；间隔精确到秒。",
                                    fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark))
                            } else {
                                Text("线路数据没有 ${s.line.name} 在本站的首末班与间隔，请手工填写。",
                                    fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark))
                                HeadwayStepper("高峰间隔", headway) { headway = it }
                                HeadwayStepper("平峰间隔", offPeak) { offPeak = it }
                            }
                            GlassField(alight, { alight = it }, label = "下车站（可选，用于到站提醒和回程）", singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                            PillButton(onClick = {
                                    val peakSec = bands.minOfOrNull { it.headwaySec } ?: headway
                                    val offSec = bands.maxOfOrNull { it.headwaySec } ?: offPeak
                                    val manual = listOf(
                                        HeadwayBand(0, 7 * 60, offSec), HeadwayBand(7 * 60, 9 * 60 + 30, peakSec),
                                        HeadwayBand(9 * 60 + 30, 17 * 60, offSec), HeadwayBand(17 * 60, 19 * 60 + 30, peakSec),
                                        HeadwayBand(19 * 60 + 30, 24 * 60, offSec),
                                    )
                                    onPicked(Candidate(
                                        type = Candidate.Type.METRO, name = s.line.name,
                                        stopName = s.stop.name, lat = s.stop.lat, lng = s.stop.lng,
                                        headwayMin = (peakSec + 59) / 60, offPeakHeadwayMin = (offSec + 59) / 60,
                                        metroDir = sel?.dest ?: s.dir.label.ifEmpty { s.dir.lastStop },
                                        alightName = alight.trim(),
                                        weekdayBands = if (bands.isEmpty()) manual else bands,
                                        weekendBands = if (bands.isEmpty()) manual else weekendBands,
                                    ))
                                }, modifier = Modifier.fillMaxWidth()) { Text("添加", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 自定义通勤车

private val TIME_RE = Regex("(\\d{1,2})\\s*[:：.]\\s*(\\d{2})")

/** 自定义通勤车：名称、上车点、发车时刻 */
@Composable
fun ShuttleEditor(initial: Candidate?, onBack: () -> Unit, onDone: (Candidate) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "通勤车") }
    var stopName by remember { mutableStateOf(initial?.stopName ?: "") }
    var lat by remember { mutableStateOf(initial?.lat) }
    var lng by remember { mutableStateOf(initial?.lng) }
    var times by remember { mutableStateOf(initial?.departures?.sorted()?.joinToString(" ") { hhmm(it) } ?: "") }
    var locating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<List<SearchStation>?>(null) }
    var pickDep by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { result = null; return@LaunchedEffect }
        delay(350)
        result = withContext(Dispatchers.IO) { runCatching { Transit.search(q).stations }.getOrNull() }
    }
    val parsed = TIME_RE.findAll(times).mapNotNull { m ->
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }.distinct().sorted().toList()

    if (pickDep) {
        // 默认停在最后一班之后 20 分钟，没有就 07:00
        val init = parsed.lastOrNull()?.let { (it + 20).coerceAtMost(23 * 60 + 59) } ?: (7 * 60)
        TimeWheelSheet(
            title = "发车时刻", initialMinute = init,
            onDismiss = { pickDep = false },
            onConfirm = { m ->
                if (m !in parsed) times = (times.trimEnd() + " " + hhmm(m)).trim()
                pickDep = false
            },
        )
    }
    PickerScaffold(if (initial == null) "添加通勤车" else "编辑通勤车", onBack) {
        item {
            GlassCard {
              Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val dark = isSystemInDarkTheme()
                GlassField(name, { name = it }, label = "名称", singleLine = true, modifier = Modifier.fillMaxWidth())
                GlassField(
                    times, { times = it }, label = "发车时刻，如 07:20 07:40 08:00", singleLine = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    supportingText = { Text(if (parsed.isEmpty()) "还没识别到时刻" else "共 ${parsed.size} 班：" + parsed.joinToString(" ") { hhmm(it) }) },
                    modifier = Modifier.fillMaxWidth(),
                )
                // 也可以用滚轮选一个时刻，追加到上面
                PillButton(secondary = true, onClick = { pickDep = true }) { Text("+ 选一个时刻") }
              }
            }
        }
        item {
            GlassCard {
              Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val dark = isSystemInDarkTheme()
                SectionLabel("上车点")
                Text(
                    if (lat != null && stopName.isNotEmpty()) "已选：$stopName" else if (lat != null) "已用当前位置" else "还没选",
                    fontSize = 15.sp, color = Fuse.ink(dark),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(secondary = true, onClick = {
                        if (!locating) {
                            locating = true
                            scope.launch {
                                val l = Loc.current(ctx)
                                locating = false
                                if (l == null) Toast.makeText(ctx, "定位失败", Toast.LENGTH_SHORT).show()
                                else { lat = l.latitude; lng = l.longitude; if (stopName.isEmpty()) stopName = "通勤车上车点" }
                            }
                        }
                    }) { Text(if (locating) "定位中…" else "用我现在的位置") }
                }
                GlassField(stopName, { stopName = it }, label = "上车点名称", singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                SearchField(query, "搜一个离上车点近的站，借用它的位置") { query = it }
              }
            }
        }
        result?.let { r ->
            glassGroup(r.take(10), key = "shuttle-search") { st ->
                GlassRow(
                    headline = st.name,
                    supporting = if (st.isSubway) "地铁站" else "公交站",
                    leading = { if (st.isSubway) LineBadge("M", Fuse.MetroRed) else PinIcon() },
                    onClick = { lat = st.lat; lng = st.lng; stopName = st.name; query = "" },
                )
            }
        }
        item {
            PillButton(
                onClick = {
                    val la = lat; val ln = lng
                    when {
                        parsed.isEmpty() -> Toast.makeText(ctx, "请填发车时刻", Toast.LENGTH_SHORT).show()
                        la == null || ln == null -> Toast.makeText(ctx, "请选上车点", Toast.LENGTH_SHORT).show()
                        else -> onDone(Candidate(
                            type = Candidate.Type.SHUTTLE, name = name.trim().ifEmpty { "通勤车" },
                            stopName = stopName.trim().ifEmpty { "通勤车上车点" }, lat = la, lng = ln, departures = parsed,
                        ))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("保存", fontWeight = FontWeight.Bold) }
        }
    }
}
