package com.busnotice.commute.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Candidate
import com.busnotice.commute.LearnRebuild
import com.busnotice.commute.NodeLog
import com.busnotice.commute.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/** 行程记录：历史列表 + 详情（节点来源、行程段、学习样本），可删除异常记录并回滚学习样本 */
@Composable
fun RecordsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    // 页内一次性结果统一用 Snackbar，列表页与详情页共用一个 host state
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(NodeLog.records(ctx)) }
    var detail by remember { mutableStateOf<NodeLog.RunRecord?>(null) }
    var askDelete by remember { mutableStateOf<NodeLog.RunRecord?>(null) }

    // 固定 / 取消固定：固定的记录不占 30 条名额，也不会在 30 天后过期
    val togglePin: (NodeLog.RunRecord) -> Unit = { r ->
        val next = !r.pinned
        if (NodeLog.setPinned(ctx, r.id, next)) {
            records = NodeLog.records(ctx)
            if (detail?.id == r.id) detail = records.firstOrNull { it.id == r.id }
            scope.launch { snack.showSnackbar(if (next) "已固定，不计入 30 条上限" else "已取消固定") }
        }
    }

    BackHandler(enabled = detail != null) { detail = null }

    val d = detail
    if (d == null) {
        GlassPage(title = "行程记录", onBack = onBack, snackbar = snack) {
            if (records.isEmpty()) {
                item {
                    GlassCard {
                        Text("还没有行程记录", style = Type.ItemTitle, color = Fuse.ink(dark))
                        Text(
                            "完成一次行程后会自动保存在这里，可查看详情或删除异常记录。",
                            fontSize = 13.sp, color = Fuse.dim(dark), modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            if (records.isNotEmpty()) {
                item { WeekCard(records, dark) }
            }
            glassGroup(records, key = "records") { r ->
                RunRow(
                    r,
                    onClick = { detail = r },
                    onDelete = { askDelete = r },
                    onPin = { togglePin(r) },
                )
            }
        }
    } else {
        RecordDetail(
            d, snack = snack, onBack = { detail = null },
            onDelete = { askDelete = d }, onTogglePin = { togglePin(d) },
        )
    }

    askDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            shape = DialogShape, containerColor = Fuse.surface(dark),
            titleContentColor = Fuse.ink(dark), textContentColor = Fuse.ink(dark),
            title = { Text("删除这条行程记录？") },
            text = {
                Text(
                    (if (r.pinned) "这是固定保留的记录。" else "") +
                        if (r.learned.isEmpty()) "删除后无法恢复，这条记录会从列表中移除。"
                        else "删除后无法恢复，这条记录写入的 ${r.learned.size} 条学习样本会一并回滚。"
                )
            },
            confirmButton = {
                DialogButton("删除", danger = true, onClick = {
                    askDelete = null
                    if (NodeLog.deleteRecord(ctx, r.id)) {
                        records = NodeLog.records(ctx)
                        if (detail?.id == r.id) detail = null
                        // 站间学习由剩余记录整体重算，删掉的行程不会再留在「线路」页
                        scope.launch(Dispatchers.IO) { LearnRebuild.rebuildStops(ctx) }
                        scope.launch { snack.showSnackbar("已删除行程记录") }
                    }
                })
            },
            dismissButton = { DialogButton("取消", onClick = { askDelete = null }) },
        )
    }
}

@Composable
private fun WeekCard(records: List<NodeLog.RunRecord>, dark: Boolean) {
    val now = System.currentTimeMillis()
    // 手动开始的行程也是真实通勤，只有「中途结束」不算完成
    val week = records.filter { it.savedAt >= now - 7 * 86_400_000L && !it.aborted }
    if (week.isEmpty()) return
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    val durations = week.mapNotNull { r ->
        val f = r.nodes.minOfOrNull { it.at }
        val l = r.nodes.maxOfOrNull { it.at }
        if (f != null && l != null) ((l - f) / 60_000L).toInt() else null
    }
    val avg = durations.takeIf { it.isNotEmpty() }?.average()?.toInt()
    val coverage = week.count { it.marks.isNotEmpty() } * 100 / week.size
    GlassCard {
        SectionLabel("最近 7 天")
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("完成", style = Type.Caption, color = dim)
                Text("${week.size} 次", style = Type.Metric, color = ink)
            }
            Column {
                Text("平均全程", style = Type.Caption, color = dim)
                Text(avg?.let { "$it 分钟" } ?: "—", style = Type.Metric, color = ink)
            }
            Column {
                Text("打卡覆盖", style = Type.Caption, color = dim)
                Text("$coverage%", style = Type.Metric, color = ink)
            }
        }
    }
}

@Composable
private fun RunRow(r: NodeLog.RunRecord, onClick: () -> Unit, onDelete: () -> Unit, onPin: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val first = r.nodes.minOfOrNull { it.at }
    val last = r.nodes.maxOfOrNull { it.at }
    val dur = if (first != null && last != null) ((last - first) / 60_000L).toInt().coerceAtLeast(1) else 0
    val time = if (first != null && last != null) "${hhmmText(first)}–${hhmmText(last)}" else "无节点记录"
    GlassRow(
        headline = "${dateText(first ?: r.savedAt)} $time",
        supporting = r.trip.name + (if (dur > 0) " · 全程 $dur 分钟" else "") +
            (if (r.aborted) " · 中途结束" else "") + (if (r.pinned) " · 已固定" else ""),
        onClick = onClick,
        trailing = {
            IconButton(onClick = onPin) {
                Icon(
                    Icons.Default.Star,
                    if (r.pinned) "取消固定" else "固定保留",
                    tint = if (r.pinned) Fuse.Yellow else Fuse.dim(dark),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "删除记录", tint = Fuse.MetroRed)
            }
        },
    )
}

@Composable
private fun RecordDetail(
    r: NodeLog.RunRecord,
    snack: SnackbarHostState,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    val first = r.nodes.minOfOrNull { it.at }
    val last = r.nodes.maxOfOrNull { it.at }
    val dur = if (first != null && last != null) ((last - first) / 60_000L).toInt() else 0
    val eff = remember(r.nodes) { NodeLog.effective(r.nodes) }
    val refs = remember(r.trip) { NodeLog.timeline(r.trip) }

    GlassPage(title = "行程详情", onBack = onBack, snackbar = snack) {
        item {
            GlassCard {
                Text(r.trip.name, style = Type.ItemTitle, color = ink)
                if (first != null && last != null) {
                    Text(
                        "${fullDate(first)} · ${hhmmText(first)}–${hhmmText(last)} · 全程 ${dur.coerceAtLeast(1)} 分钟",
                        style = Type.BodyDim, color = dim, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (r.aborted) Tag("中途结束", Fuse.Amber)
                    if (r.test) Tag("手动开始", dim)
                    Tag(if (r.learned.isEmpty()) "未写入学习样本" else "${r.learned.size} 条学习样本", dim)
                }
            }
        }
        item {
            GlassCard {
                SectionLabel("行程段")
                if (r.trip.legs.isEmpty()) {
                    Text("这条记录没有行程段信息", style = Type.BodyDim, color = dim, modifier = Modifier.padding(top = 6.dp))
                }
                r.trip.legs.forEachIndexed { i, leg ->
                    Column(Modifier.padding(top = if (i == 0) 8.dp else 12.dp)) {
                        val metros = leg.candidates.filter { it.type == Candidate.Type.METRO }.distinctBy { it.name }
                        if (leg.isMetroOnly() && metros.isNotEmpty()) {
                            // 纯地铁段：用线路特征色的线路牌，比一行文字更快认出是哪条线
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}. ", style = Type.Section, color = ink)
                                metros.forEach { c ->
                                    LineBadge(shortLine(c.name.ifEmpty { c.label() }), lineColor(c))
                                    Spacer(Modifier.width(4.dp))
                                }
                                val dir = metros.firstOrNull { it.metroDir.isNotEmpty() }?.metroDir.orEmpty()
                                Text(
                                    if (dir.isNotEmpty()) "往 $dir" else leg.title(),
                                    style = Type.Section, color = ink,
                                )
                            }
                        } else {
                            Text("${i + 1}. ${leg.title()}", style = Type.Section, color = ink)
                        }
                        Text(
                            leg.stopName().ifEmpty { "上车站未设置" } + " → " + alightOf(r.trip, i).ifEmpty { "下车站未设置" },
                            style = Type.BodyDim, color = dim, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionLabel("节点时间轴")
                // 时间颜色已表达来源，这里只留一行统一图例，不再逐行标注
                Text(
                    "绿色＝刷卡或手动 · 黄色＝补录 · 浅色＝推算 · ≈＝估算",
                    style = Type.Caption, color = dim,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
                refs.forEachIndexed { i, ref ->
                    val st = eff[ref]
                    val estimated = st?.src == NodeLog.Src.ESTIMATED
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            nodeLabel(ref, refs), style = Type.Body, color = ink,
                            modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            (if (estimated) "≈" else "") + (st?.let { hhmmText(it.at) } ?: "—"),
                            fontSize = 14.sp, fontWeight = FontWeight.Normal, style = TnumStyle,
                            color = if (st == null) dim.copy(alpha = 0.5f) else srcColor(st.src, dark),
                        )
                    }
                    if (i < refs.size - 1) HorizontalDivider(thickness = 1.dp, color = Fuse.hairline(dark))
                }
            }
        }
        val stationLegs = r.legStops.mapIndexedNotNull { i, names ->
            if (names.isEmpty()) null
            else Triple(i, r.trip.legs.getOrNull(i)?.title().orEmpty().ifEmpty { "第 ${i + 1} 段" }, names)
        }
        // 旧记录没有整段站点，只有打卡时留下的那几个站名
        val legacyNames = if (stationLegs.isEmpty()) r.markNames else emptyList()
        // 打卡次数按全行程所有段统计：有实际时间（含首末站补位）的站点都算
        val stopTotal = if (stationLegs.isEmpty()) r.marks.count { it.at > 0 }
        else stationLegs.sumOf { (i, _, names) ->
            val mine = r.marks.filter { m ->
                if (m.leg >= 0) m.leg == i else r.markLeg < 0 || r.markLeg == i
            }
            val boardAt = eff[NodeLog.NodeRef(NodeLog.Kind.BOARD, i)]?.at
            val alightAt = eff[NodeLog.NodeRef(NodeLog.Kind.ALIGHT, i)]?.at
            names.indices.count { idx ->
                mine.any { it.idx == idx && it.at > 0 } ||
                    (idx == 0 && boardAt != null) || (idx == names.lastIndex && alightAt != null)
            }
        }
        if (stationLegs.isNotEmpty() || legacyNames.isNotEmpty() || r.marks.isNotEmpty()) {
            item {
                GlassCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("到站打卡时间轴", Modifier.weight(1f))
                        if (stopTotal > 0) Text("$stopTotal 次", style = Type.BodyDim, color = dim)
                    }
                    if (stationLegs.isEmpty()) {
                        val names = legacyNames.ifEmpty { List(r.marks.maxOf { it.idx } + 1) { "" } }
                        names.forEachIndexed { idx, name ->
                            val at = r.marks.firstOrNull { it.idx == idx }?.at
                            StopRow(
                                name.ifEmpty { "站点未知" }, at,
                                dark = dark, divider = idx != names.lastIndex,
                            )
                        }
                    } else {
                        stationLegs.forEachIndexed { li, (i, title, names) ->
                            // 地铁段用线路特征色线路牌，和「行程段」保持一致的识别方式
                            val metros = r.trip.legs.getOrNull(i)?.candidates.orEmpty()
                                .filter { it.type == Candidate.Type.METRO }.distinctBy { it.name }
                            Row(
                                Modifier.fillMaxWidth().padding(top = if (li == 0) 8.dp else 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("${i + 1}.", style = Type.Section, color = ink)
                                if (metros.isNotEmpty()) {
                                    metros.forEach { c -> LineBadge(shortLine(c.name.ifEmpty { c.label() }), lineColor(c)) }
                                    val dir = metros.firstOrNull { it.metroDir.isNotEmpty() }?.metroDir.orEmpty()
                                    if (dir.isNotEmpty()) {
                                        Text("往$dir", style = Type.Section, color = ink)
                                    }
                                } else {
                                    Text(title, style = Type.Section, color = ink)
                                }
                            }
                            val mine = r.marks.filter { m ->
                                if (m.leg >= 0) m.leg == i else r.markLeg < 0 || r.markLeg == i
                            }
                            // 首尾两站没点「到站打卡」时，分别用「确认上车」「我已下车」的时刻补位
                            val boardAt = eff[NodeLog.NodeRef(NodeLog.Kind.BOARD, i)]?.at
                            val alightAt = eff[NodeLog.NodeRef(NodeLog.Kind.ALIGHT, i)]?.at
                            names.forEachIndexed { idx, name ->
                                val marked = mine.firstOrNull { it.idx == idx }?.at
                                val at = marked ?: when (idx) {
                                    0 -> boardAt
                                    names.lastIndex -> alightAt
                                    else -> null
                                }
                                StopRow(name, at, dark, divider = idx != names.lastIndex)
                            }
                        }
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionLabel("算法学习")
                Text(
                    (if (r.pinned) "已固定，不占 30 条名额、不会 30 天后过期。" else "") +
                        if (r.learned.isEmpty()) "本次没有写入学习样本，删除记录只移除这条历史。"
                        else "本次写入 ${r.learned.size} 条学习样本，删除这条记录时会一并回滚。",
                    style = Type.BodyDim, color = dim, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item {
            PillButton(onClick = onTogglePin, modifier = Modifier.fillMaxWidth(), secondary = true) {
                Icon(
                    Icons.Default.Star, null,
                    tint = if (r.pinned) Fuse.Yellow else ink,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (r.pinned) "取消固定" else "固定保留", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Button(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Fuse.MetroRed, contentColor = Color.White),
            ) {
                Text("删除这条记录", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    val dark = isSystemInDarkTheme()
    Text(
        text, fontSize = 11.sp, color = color,
        modifier = Modifier
            .background(color.copy(alpha = if (dark) 0.16f else 0.10f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 一行站点：有打卡就显示时间，没打卡就留空 */
@Composable
private fun StopRow(name: String, at: Long?, dark: Boolean, divider: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name, style = Type.Body, color = Fuse.ink(dark), modifier = Modifier.weight(1f),
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            at?.let { hhmmText(it) } ?: "—",
            fontSize = 14.sp, fontWeight = FontWeight.Normal, style = TnumStyle,
            color = if (at == null) Fuse.dim(dark).copy(alpha = 0.5f) else Fuse.ink(dark),
        )
    }
    if (divider) HorizontalDivider(thickness = 1.dp, color = Fuse.hairline(dark))
}

/** 时间颜色的唯一来源：与图例一致，刷卡/手动绿、补录黄、推算浅、自动黑 */
private fun srcColor(src: NodeLog.Src?, dark: Boolean): Color = when (src) {
    NodeLog.Src.TAP, NodeLog.Src.MANUAL -> Fuse.BusGreen
    NodeLog.Src.ESTIMATED -> Fuse.dim(dark)
    NodeLog.Src.BACKFILL -> Fuse.Yellow
    else -> Fuse.ink(dark)
}

private fun nodeLabel(ref: NodeLog.NodeRef, refs: List<NodeLog.NodeRef>): String {
    val dup = refs.count { it.kind == ref.kind } > 1
    val suffix = if (dup && ref.kind in listOf(
            NodeLog.Kind.ENTER, NodeLog.Kind.BOARD, NodeLog.Kind.ALIGHT, NodeLog.Kind.EXIT,
        )) " ${ref.leg + 1}" else ""
    val maxLeg = refs.filter { it.kind == ref.kind }.maxOfOrNull { it.leg } ?: ref.leg
    val base = when {
        ref.kind == NodeLog.Kind.BOARD && ref.leg > 0 -> "换乘上车"
        ref.kind == NodeLog.Kind.ALIGHT && ref.leg < maxLeg -> "换乘下车"
        ref.kind == NodeLog.Kind.ALIGHT && maxLeg > 0 -> "终点下车"
        else -> NodeLog.label(ref.kind)
    }
    return base + suffix
}

private fun alightOf(trip: Trip, i: Int): String {
    val leg = trip.legs.getOrNull(i) ?: return ""
    return leg.candidates.firstOrNull { it.alightName.isNotEmpty() }?.alightName
        ?: trip.legs.getOrNull(i + 1)?.stopName().orEmpty()
}

private fun dateText(at: Long): String {
    val days = ((System.currentTimeMillis() - at) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "今天"
        days == 1 -> "昨天"
        days == 2 -> "前天"
        else -> fullDate(at)
    }
}

private fun fullDate(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    return "${c.get(Calendar.YEAR)}年${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
}

private fun hhmmText(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}
