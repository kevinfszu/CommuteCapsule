package com.busnotice.commute.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.LearnReport

/** 算法学习结果：只读展示本机已学到的乘车、候车、步行与站间时间 */
@Composable
fun LearnScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val dim = Fuse.dim(dark)
    val report = remember { LearnReport.build(ctx) }

    GlassPage(title = "算法学习", onBack = onBack) {
        item {
            GlassCard {
                SectionLabel("说明")
                Text(
                    "以下数值来自本机历史行程的统计，样本不足时不会参与预测。",
                    fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "异常行程请在「行程记录」中删除，会一并回滚对应学习样本。",
                    fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (report.trips.isEmpty()) {
            item {
                GlassCard {
                    Text("还没有行程，学习结果会在完成真实行程后出现。", fontSize = 13.sp, color = dim)
                }
            }
        }
        report.trips.forEachIndexed { ti, t ->
            sectionHeader(t.name)
            item(key = "trip-habit-$ti") {
                GlassCard {
                    SectionLabel("习惯与步行")
                    MetricLine(t.habit)
                    t.walks.forEach { MetricLine(it) }
                }
            }
            if (t.legs.isNotEmpty()) {
                item(key = "trip-legs-$ti") {
                    GlassCard {
                        SectionLabel("每段乘车（当前采用）")
                        t.legs.forEach { LegLineRow(it) }
                    }
                }
            }
            if (t.nodes.isNotEmpty()) {
                item(key = "trip-nodes-$ti") {
                    GlassCard {
                        SectionLabel("节点时长")
                        t.nodes.forEach { MetricLine(it) }
                    }
                }
            }
        }

        sectionHeader("站间行车时长（到站打卡）")
        if (report.stops.isEmpty()) {
            item {
                GlassCard {
                    Text("还没有到站打卡样本，坐车时点「刚到某站」即可积累。", fontSize = 13.sp, color = dim)
                }
            }
        } else {
            report.stops.forEachIndexed { si, s ->
                item(key = "stop-$si") {
                    GlassCard {
                        Text(s.line, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fuse.ink(dark))
                        Text(
                            s.perHop?.let { "平均每站 $it" } ?: "平均每站：样本不足",
                            fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 2.dp),
                        )
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
            item {
                GlassCard {
                    Text("还没有地铁手动上车样本，坐地铁时点「已上车」即可积累。", fontSize = 13.sp, color = dim)
                }
            }
        } else {
            item {
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
        item {
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
