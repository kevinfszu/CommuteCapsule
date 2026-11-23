package com.busnotice.commute.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.NodeLog
import com.busnotice.commute.Trip
import java.util.Calendar
import java.util.Locale

/** 行程记录：历史列表 + 详情（节点来源、行程段、学习样本），可删除异常记录并回滚学习样本 */
@Composable
fun RecordsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    var records by remember { mutableStateOf(NodeLog.records(ctx)) }
    var detail by remember { mutableStateOf<NodeLog.RunRecord?>(null) }
    var askDelete by remember { mutableStateOf<NodeLog.RunRecord?>(null) }

    BackHandler(enabled = detail != null) { detail = null }

    val d = detail
    if (d == null) {
        GlassPage(title = "行程记录", onBack = onBack) {
            if (records.isEmpty()) {
                item {
                    GlassCard {
                        Text("还没有行程记录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Fuse.ink(dark))
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
                RunRow(r, onClick = { detail = r }, onDelete = { askDelete = r })
            }
        }
    } else {
        RecordDetail(d, onBack = { detail = null }, onDelete = { askDelete = d })
    }

    askDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            shape = DialogShape, containerColor = Fuse.surface(dark),
            titleContentColor = Fuse.ink(dark), textContentColor = Fuse.ink(dark),
            title = { Text("删除这条行程记录？") },
            text = {
                Text(
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
                        Toast.makeText(ctx, "已删除行程记录", Toast.LENGTH_SHORT).show()
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
    val week = records.filter { it.savedAt >= now - 7 * 86_400_000L && !it.aborted && !it.test }
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
                Text("完成", fontSize = 11.sp, color = dim)
                Text("${week.size} 次", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink, style = TnumStyle)
            }
            Column {
                Text("平均全程", fontSize = 11.sp, color = dim)
                Text(avg?.let { "$it 分钟" } ?: "—", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink, style = TnumStyle)
            }
            Column {
                Text("打卡覆盖", fontSize = 11.sp, color = dim)
                Text("$coverage%", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink, style = TnumStyle)
            }
        }
    }
}

@Composable
private fun RunRow(r: NodeLog.RunRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    val first = r.nodes.minOfOrNull { it.at }
    val last = r.nodes.maxOfOrNull { it.at }
    val dur = if (first != null && last != null) ((last - first) / 60_000L).toInt().coerceAtLeast(1) else 0
    val time = if (first != null && last != null) "${hhmmText(first)}–${hhmmText(last)}" else "无节点记录"
    GlassRow(
        headline = "${dateText(first ?: r.savedAt)} $time",
        supporting = r.trip.name + (if (dur > 0) " · 全程 $dur 分钟" else "") +
            (if (r.aborted) " · 中途结束" else ""),
        onClick = onClick,
        trailing = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "删除记录", tint = Fuse.MetroRed)
            }
        },
    )
}

@Composable
private fun RecordDetail(r: NodeLog.RunRecord, onBack: () -> Unit, onDelete: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    val first = r.nodes.minOfOrNull { it.at }
    val last = r.nodes.maxOfOrNull { it.at }
    val dur = if (first != null && last != null) ((last - first) / 60_000L).toInt() else 0
    val eff = remember(r.nodes) { NodeLog.effective(r.nodes) }
    val refs = remember(r.trip) { NodeLog.timeline(r.trip) }

    GlassPage(title = "行程详情", onBack = onBack) {
        item {
            GlassCard {
                Text(r.trip.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ink)
                if (first != null && last != null) {
                    Text(
                        "${fullDate(first)} · ${hhmmText(first)}–${hhmmText(last)} · 全程 ${dur.coerceAtLeast(1)} 分钟",
                        fontSize = 13.sp, color = dim, style = TnumStyle, modifier = Modifier.padding(top = 4.dp),
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
                    Text("这条记录没有行程段信息", fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 6.dp))
                }
                r.trip.legs.forEachIndexed { i, leg ->
                    Column(Modifier.padding(top = if (i == 0) 8.dp else 12.dp)) {
                        Text("${i + 1}. ${leg.title()}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink)
                        Text(
                            leg.stopName().ifEmpty { "上车站未设置" } + " → " + alightOf(r.trip, i).ifEmpty { "下车站未设置" },
                            fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionLabel("节点时间轴")
                refs.forEachIndexed { i, ref ->
                    val st = eff[ref]
                    val estimated = st?.src == NodeLog.Src.ESTIMATED
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(nodeLabel(ref, refs), fontSize = 14.sp, color = ink, modifier = Modifier.width(80.dp))
                        Text(
                            (if (estimated) "≈" else "") + (st?.let { hhmmText(it.at) } ?: "—"),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, style = TnumStyle,
                            color = when {
                                st == null -> dim.copy(alpha = 0.5f)
                                st.src == NodeLog.Src.TAP -> Fuse.BusGreen
                                st.src == NodeLog.Src.ESTIMATED -> dim
                                st.src == NodeLog.Src.BACKFILL -> Fuse.Yellow
                                else -> ink
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        Text(srcText(st?.src), fontSize = 11.sp, color = dim)
                    }
                    if (i < refs.size - 1) HorizontalDivider(thickness = 1.dp, color = Fuse.hairline(dark))
                }
            }
        }
        if (r.marks.isNotEmpty()) {
            item {
                GlassCard {
                    SectionLabel("到站打卡")
                    Text("共 ${r.marks.size} 次", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink,
                        modifier = Modifier.padding(top = 6.dp))
                    r.marks.sortedBy { it.idx }.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                r.markNames.getOrNull(m.idx) ?: "第 ${m.idx + 1} 站",
                                fontSize = 13.sp, color = ink, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(hhmmText(m.at), fontSize = 13.sp, color = dim, style = TnumStyle)
                        }
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionLabel("算法学习")
                Text(
                    if (r.learned.isEmpty()) "本次没有写入学习样本，删除记录只移除这条历史。"
                    else "本次写入 ${r.learned.size} 条学习样本，删除这条记录时会一并回滚。",
                    fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 6.dp),
                )
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

private fun srcText(src: NodeLog.Src?): String = when (src) {
    NodeLog.Src.AUTO -> "自动"
    NodeLog.Src.TAP -> "刷卡"
    NodeLog.Src.MANUAL -> "手动"
    NodeLog.Src.ESTIMATED -> "推算"
    NodeLog.Src.BACKFILL -> "补录"
    null -> "—"
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
