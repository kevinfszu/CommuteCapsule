package com.busnotice.commute.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.LearnReport

/** 算法学习结果：按行程分页展示本机已学到的乘车、候车、步行与站间时间 */
@Composable
fun LearnScreen(onBack: () -> Unit, onOpenRecords: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val dim = Fuse.dim(dark)
    val report = remember { LearnReport.build(ctx) }
    // 0..trips.size-1 是行程，trips.size 是「线路与地点」；行程数量变化时兜底到末项
    var selected by rememberSaveable { mutableStateOf(0) }
    val index = selected.coerceIn(0, report.trips.size)
    // 切换行程时回到顶部，避免沿用上一页的滚动位置
    val listState = rememberLazyListState()
    LaunchedEffect(index) { listState.scrollToItem(0) }

    GlassPage(title = "算法学习", onBack = onBack, state = listState) {
        item(key = "tabs") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                report.trips.forEachIndexed { i, t ->
                    GlassChip(selected = index == i, onClick = { selected = i }, label = t.name)
                }
                GlassChip(
                    selected = index == report.trips.size,
                    onClick = { selected = report.trips.size },
                    label = "线路与地点",
                    enabled = report.trips.isNotEmpty(),
                )
            }
        }

        if (report.trips.isEmpty()) {
            item(key = "empty") {
                GlassCard {
                    Text("还没有行程，先在设置里导入线路并跑一趟", fontSize = 13.sp, color = dim)
                }
            }
        } else if (index < report.trips.size) {
            val t = report.trips[index]
            if (t.legs.isNotEmpty()) {
                item(key = "trip-legs") {
                    GlassCard {
                        SectionLabel("每段乘车（当前采用）")
                        t.legs.forEach { LegLineRow(it) }
                    }
                }
            }
            item(key = "trip-nodes") {
                GlassCard {
                    SectionLabel("节点时长")
                    NodeLines(t.nodes)
                }
            }
            item(key = "trip-habit") {
                GlassCard {
                    SectionLabel("上车习惯与步行")
                    MetricLine(t.habit)
                    t.walks.forEach { MetricLine(it) }
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
                    "以下数据跨行程共用，按线路 / 站点汇总。",
                    fontSize = 13.sp, color = dim, modifier = Modifier.padding(start = 6.dp),
                )
            }

            sectionHeader("站间行车时长（到站打卡）")
            if (report.stops.isEmpty()) {
                item(key = "stops-empty") {
                    Text(
                        "还没有站间打卡样本", fontSize = 13.sp, color = dim,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            } else {
                report.stops.forEachIndexed { si, s ->
                    item(key = "stop-$si") {
                        GlassCard {
                            Text(s.line, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fuse.ink(dark))
                            // 没有整条线的均值时就不显示，避免和下面具体的区间用时自相矛盾
                            s.perHop?.let {
                                Text(
                                    "平均每站 $it",
                                    fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (s.pairs.isNotEmpty()) {
                                Text(
                                    "区间用时", fontSize = 11.sp, color = dim,
                                    modifier = Modifier.padding(top = if (s.perHop != null) 8.dp else 4.dp),
                                )
                            }
                            s.pairs.forEach { (k, v) ->
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                    Text(k, fontSize = 13.sp, color = dim, modifier = Modifier.weight(1f))
                                    Text(v, fontSize = 13.sp, color = Fuse.ink(dark), style = TnumStyle)
                                }
                            }
                        }
                    }
                }
            }

            sectionHeader("地铁班次偏差")
            if (report.metros.isEmpty()) {
                item(key = "metros-empty") {
                    Text(
                        "还没有地铁手动上车样本", fontSize = 13.sp, color = dim,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            } else {
                item(key = "metros") {
                    GlassCard {
                        report.metros.forEachIndexed { mi, m ->
                            if (mi > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), thickness = 1.dp, color = Fuse.hairline(dark))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${m.stop} · ${m.line}", fontSize = 14.sp, color = Fuse.ink(dark),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "往${m.dir} · ${if (m.peak) "高峰" else "平峰"}",
                                        fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                Text(m.offset, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Fuse.ink(dark), style = TnumStyle)
                            }
                        }
                    }
                }
            }

            sectionHeader("常去地点")
            item(key = "places") {
                GlassCard {
                    report.places.forEachIndexed { pi, p ->
                        if (pi > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), thickness = 1.dp, color = Fuse.hairline(dark))
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(p.name, fontSize = 14.sp, color = Fuse.ink(dark), modifier = Modifier.weight(1f))
                            Text(
                                "${p.progress} · ${if (p.confirmed) "已确认" else "学习中"}",
                                fontSize = 13.sp, color = if (p.confirmed) Fuse.BusGreen else dim,
                            )
                        }
                    }
                }
            }
        }

        item(key = "footnote") {
            Text(
                "以下数值来自本机历史行程的统计，第一次实测就参与预测；样本越多越稳。",
                fontSize = 12.sp, color = dim, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp),
            )
        }
    }
}

/** 节点时长：只展示已就绪的项，未就绪统一收成一行提示，避免整卡都是「暂无样本」 */
@Composable
private fun NodeLines(nodes: List<LearnReport.Line>) {
    val dim = Fuse.dim(isSystemInDarkTheme())
    val ready = nodes.filter { it.ready }
    val pending = nodes.size - ready.size
    if (ready.isEmpty()) {
        Text(
            "还没有节点样本，跑一次这趟行程后开始记录",
            fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 6.dp),
        )
        return
    }
    ready.forEach { MetricLine(it) }
    if (pending > 0) {
        Text(
            "另有 $pending 项还没有样本，跑一次这趟行程后开始记录",
            fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun MetricLine(l: LearnReport.Line) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(l.label, fontSize = 14.sp, color = ink, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                l.value, fontSize = 14.sp, style = TnumStyle,
                fontWeight = if (l.ready) FontWeight.Bold else FontWeight.Normal,
                color = if (l.ready) ink else dim,
            )
            if (l.note.isNotEmpty()) {
                Text(l.note, fontSize = 11.sp, color = dim, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}

@Composable
private fun LegLineRow(l: LearnReport.LegLine) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${l.index + 1}. ${l.title}", fontSize = 14.sp, color = ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(l.samples, fontSize = 11.sp, color = dim, modifier = Modifier.padding(top = 1.dp))
        }
        Text(
            "${l.ride} · ${l.source}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink, style = TnumStyle,
        )
    }
}
