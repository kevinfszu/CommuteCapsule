package com.busnotice.commute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.LearnRebuild
import com.busnotice.commute.LearnReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页签与单位选择跨页面保留：从「管理行程记录」返回会重建页面，靠它恢复上次位置 */
private object LearnUi {
    var tab = 0
    var unitSec = false
}

/** 算法学习结果：按行程分页展示本机已学到的乘车、候车、步行与站间时间 */
@Composable
fun LearnScreen(onBack: () -> Unit, onOpenRecords: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val dim = Fuse.dim(dark)
    var report by remember { mutableStateOf(LearnReport.build(ctx)) }
    var confirmRebuild by remember { mutableStateOf(false) }
    var rebuilding by remember { mutableStateOf(false) }
    // 重学结果统一用 Snackbar 提示，不随列表滚动位置漂走
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 0..trips.size-1 是行程，trips.size 是「线路」；行程数量变化时兜底到末项
    var selected by rememberSaveable { mutableStateOf(LearnUi.tab.coerceIn(0, report.trips.size)) }
    // 线路页的时长单位：默认分钟，可切到秒；班次偏差是秒级偏差，不跟随
    var unitSec by rememberSaveable { mutableStateOf(LearnUi.unitSec) }
    val index = selected.coerceIn(0, report.trips.size)
    // 切换行程时回到顶部，避免沿用上一页的滚动位置；页签行滚动到当前项
    val listState = rememberLazyListState()
    val tabState = rememberLazyListState()
    LaunchedEffect(index) {
        listState.scrollToItem(0)
        runCatching { tabState.animateScrollToItem(index) }
    }

    GlassPage(title = "算法学习", onBack = onBack, state = listState, snackbar = snack, actions = {
        PillButton(onClick = { if (!rebuilding) confirmRebuild = true }, enabled = !rebuilding) {
            if (rebuilding) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Fuse.onAccent(dark), strokeWidth = 2.dp)
                Text("重新学习中…", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
            } else {
                Text("重新学习", fontWeight = FontWeight.Bold)
            }
        }
    }, subHeader = {
        // 页签行固定在顶部：右侧常驻「分钟 / 秒」开关，滚到任何卡片都能切换
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                state = tabState,
                modifier = Modifier.weight(1f)
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        // 行程多到需要横滑时，两侧加渐隐，提示还有更多页签
                        val fade = 20.dp.toPx()
                        if (tabState.canScrollBackward) {
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, fade),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                        if (tabState.canScrollForward) {
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - fade, size.width),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(report.trips.size) { i ->
                    GlassChip(
                        selected = index == i,
                        onClick = { selected = i; LearnUi.tab = i },
                        label = report.trips[i].name,
                        role = Role.Tab,
                        maxWidth = 200.dp,
                    )
                }
                item {
                    GlassChip(
                        selected = index == report.trips.size,
                        onClick = { selected = report.trips.size; LearnUi.tab = report.trips.size },
                        label = "线路",
                        // 有行程、站间样本或班次样本任一存在就允许查看线路汇总
                        enabled = report.trips.isNotEmpty() || report.stops.isNotEmpty() || report.metros.isNotEmpty(),
                        role = Role.Tab,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            UnitSwitch(unitSec, dark) { unitSec = it; LearnUi.unitSec = it }
        }
    }) {
        // 行程为空时 index == 0 == trips.size，直接落到「线路」页
        if (index < report.trips.size) {
            val t = report.trips[index]
            item(key = "trip-total") {
                GlassCard {
                    SectionLabel("时长概览", Modifier.padding(bottom = 6.dp))
                    // 三行各配一条细色条：乘车 / 站内 / 全程，扫一眼就能区分
                    val accents = listOf(Fuse.MetroRed, Fuse.Yellow, Fuse.BusGreen)
                    t.totals.forEachIndexed { ti, l -> MetricLine(l, accents.getOrNull(ti), unitSec) }
                }
            }
            item(key = "trip-nodes") {
                GlassCard {
                    SectionLabel("节间时长", Modifier.padding(bottom = 6.dp))
                    NodeLines(t.nodes, unitSec)
                }
            }
            item(key = "trip-extras") {
                GlassCard {
                    SectionLabel("其他学习项", Modifier.padding(bottom = 6.dp))
                    NodeLines(t.extras, unitSec)
                }
            }
            item(key = "trip-habit") {
                GlassCard {
                    SectionLabel("上车习惯与步行", Modifier.padding(bottom = 6.dp))
                    MetricLine(t.habit, unitSec = unitSec)
                    t.walks.forEach { MetricLine(it, unitSec = unitSec) }
                }
            }
            item(key = "trip-records") {
                GlassCard(padding = 0.dp) {
                    GlassRow(
                        headline = "管理行程记录",
                        supporting = "删除异常行程会一并回滚对应学习样本",
                        leading = { Icon(Icons.Default.List, null, tint = dim, modifier = Modifier.size(20.dp)) },
                        onClick = onOpenRecords,
                    )
                }
            }
        } else {
            item(key = "shared-hint") {
                Text(
                    "以下数据跨行程共用，按线路 / 站点汇总；空白表示还没有该区间样本。",
                    style = Type.BodyDim, color = dim, modifier = Modifier.padding(start = 6.dp),
                )
            }

            if (report.stops.isEmpty() && report.metros.isEmpty()) {
                // 站间与班次都没有样本时只给一张说明卡，不显示两个空小节
                item(key = "shared-empty") {
                    GlassCard {
                        Text(
                            "还没有线路相关的学习数据，跑一趟或补几次到站打卡后这里会自动出现",
                            style = Type.BodyDim, color = dim,
                        )
                    }
                }
            } else {
                item(key = "stops") {
                    GlassCard {
                        SectionLabel("站间行车时长", Modifier.padding(bottom = 6.dp))
                        if (report.stops.isEmpty()) {
                            Text("还没有站间打卡样本", style = Type.BodyDim, color = dim)
                        } else {
                            report.stops.forEachIndexed { si, s ->
                                if (si > 0) {
                                    HorizontalDivider(
                                        Modifier.padding(top = 12.dp),
                                        thickness = 1.dp, color = Fuse.hairline(dark),
                                    )
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(top = if (si == 0) 2.dp else 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        LineBadge(shortLine(s.lineName), lineColor(null, s.lineName))
                                        if (s.dir.isNotEmpty()) {
                                            Text(
                                                "往${s.dir}", style = Type.Caption, color = dim,
                                                modifier = Modifier.padding(start = 6.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        stopSummary(s, unitSec),
                                        style = Type.Value, color = Fuse.ink(dark),
                                        textAlign = TextAlign.End, maxLines = 1,
                                    )
                                }
                                // 区间之间加一条淡分隔线，避免多行糊在一起
                                s.pairs.forEachIndexed { pi, p ->
                                    if (pi > 0) {
                                        HorizontalDivider(
                                            Modifier.padding(top = 6.dp),
                                            thickness = 1.dp, color = Fuse.hairline(dark),
                                        )
                                    }
                                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                        Text(
                                            "${p.from} → ${p.to}", style = Type.Body, color = Fuse.ink(dark),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            p.sec?.let { durText(it, unitSec) + " · ${p.count} 次" } ?: "—",
                                            style = Type.Value,
                                            color = Fuse.ink(dark), textAlign = TextAlign.End,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "metros") {
                    GlassCard {
                        SectionLabel("地铁班次偏差", Modifier.padding(bottom = 6.dp))
                        if (report.metros.isEmpty()) {
                            Text("还没有地铁手动上车样本", style = Type.BodyDim, color = dim)
                        } else {
                            report.metros.forEachIndexed { mi, m ->
                                if (mi > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), thickness = 1.dp, color = Fuse.hairline(dark))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            LineBadge(shortLine(m.line), lineColor(null, m.line))
                                            Text(
                                                m.stop, style = Type.Body, color = Fuse.ink(dark),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(start = 6.dp),
                                            )
                                        }
                                        Text(
                                            "往${m.dir} · ${if (m.peak) "高峰" else "平峰"}",
                                            style = Type.Caption, color = dim, modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                    Text(
                                        m.offset, style = Type.Value,
                                        color = Fuse.ink(dark), textAlign = TextAlign.End,
                                    )
                                }
                            }
                        }
                    }
                }
            }

        }

        item(key = "footnote") {
            Text(
                "以下数值来自本机历史行程的统计，第一次实测就参与预测；样本越多越稳。",
                style = Type.Caption, color = dim, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp),
            )
        }
    }

    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            shape = DialogShape, containerColor = Fuse.surface(dark),
            titleContentColor = Fuse.ink(dark), textContentColor = Fuse.ink(dark),
            title = { Text("按历史记录重新学习？") },
            text = {
                Text(
                    "会清空并重建节间时长、上车时刻、每段乘车与上车习惯，使用的是最近 30 条记录。" +
                        "站间打卡、地铁基站、公交速度、步行与「提醒→确认下车」保持不变。" +
                        "早于记录范围的旧样本会被清除。"
                )
            },
            confirmButton = {
                DialogButton("开始重学", danger = true, onClick = {
                    confirmRebuild = false
                    rebuilding = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { LearnRebuild.rebuild(ctx) }
                        report = LearnReport.build(ctx)
                        rebuilding = false
                        snack.showSnackbar(
                            if (r.records == 0) "没有可用的历史记录"
                            else "已重新学习 ${r.records} 条记录，写入 ${r.samples} 个样本",
                        )
                    }
                })
            },
            dismissButton = { DialogButton("取消", onClick = { confirmRebuild = false }) },
        )
    }
}

/** 线路卡片右上角摘要：「共 35.4 分钟，3.3 分钟/站」，缺样本的部分省略 */
private fun stopSummary(s: LearnReport.StopBlock, asSeconds: Boolean): String {
    val parts = ArrayList<String>(2)
    s.totalSec?.let { parts += "共 " + (if (s.partial) "≥" else "") + durText(it, asSeconds) }
    s.perHopSec?.let { parts += durText(it, asSeconds) + "/站" }
    return parts.joinToString("，").ifEmpty { "—" }
}

/** 时长单位分段控件：紧凑胶囊，选中段落主色填充 */
@Composable
private fun UnitSwitch(sec: Boolean, dark: Boolean, onChange: (Boolean) -> Unit) {
    val border = if (dark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.10f)
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .border(1.dp, border, RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnitSegment("分钟", !sec, dark) { onChange(false) }
        UnitSegment("秒", sec, dark) { onChange(true) }
    }
}

@Composable
private fun RowScope.UnitSegment(label: String, selected: Boolean, dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxHeight().clip(RoundedCornerShape(20.dp))
            .background(if (selected) Fuse.accent(dark) else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = Type.Caption,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Fuse.onAccent(dark) else Fuse.dim(dark),
        )
    }
}

/** 秒数按当前单位显示：秒为单位时全用秒；分钟为单位时不足 1 分钟仍按秒，其余保留 1 位小数 */
private fun durText(sec: Int, asSeconds: Boolean): String {
    if (asSeconds) return "$sec 秒"
    if (sec < 60) return "$sec 秒"
    val m = kotlin.math.round(sec / 6.0) / 10.0
    return if (m % 1.0 == 0.0) "${m.toInt()} 分钟"
    else String.format(java.util.Locale.US, "%.1f 分钟", m)
}

/** 节间时长：全部列出；没有样本的项显示「样本不足」，方便看清还缺哪些 */
@Composable
private fun NodeLines(nodes: List<LearnReport.Line>, unitSec: Boolean) {
    val dim = Fuse.dim(isSystemInDarkTheme())
    if (nodes.isEmpty()) {
        Text(
            "还没有节间项目，跑一次这趟行程后开始记录",
            style = Type.Caption, color = dim, modifier = Modifier.padding(top = 6.dp),
        )
        return
    }
    nodes.forEach { MetricLine(it, unitSec = unitSec) }
}

@Composable
private fun MetricLine(l: LearnReport.Line, accent: Color? = null, unitSec: Boolean = false) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (accent != null) {
                Box(
                    Modifier.width(3.dp).height(14.dp)
                        .clip(RoundedCornerShape(2.dp)).background(accent),
                )
                Spacer(Modifier.width(8.dp))
            }
            l.badgeNames.forEachIndexed { bi, name ->
                if (bi > 0) Text(" / ", style = Type.Caption, color = dim)
                LineBadge(shortLine(name), lineColor(null, name))
            }
            Text(
                buildAnnotatedString {
                    append(l.label)
                    l.sub?.let {
                        withStyle(SpanStyle(fontSize = 12.sp, color = dim)) { append("（$it）") }
                    }
                },
                style = Type.Body, color = ink,
                modifier = Modifier.padding(start = if (l.badgeNames.isEmpty()) 0.dp else 6.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            // 数值与标签同级，靠右对齐、等宽与颜色深浅区分，不靠放大加粗
            Text(
                l.sec?.let { durText(it, unitSec) } ?: l.value,
                style = if (l.ready) Type.Value else Type.BodyDim,
                color = if (l.ready) ink else dim, textAlign = TextAlign.End,
            )
            if (l.note.isNotEmpty()) {
                Text(l.note, style = Type.Caption, color = dim, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}
