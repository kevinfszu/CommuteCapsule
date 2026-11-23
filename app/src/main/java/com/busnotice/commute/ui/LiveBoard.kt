package com.busnotice.commute.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Candidate
import com.busnotice.commute.CommuteService
import com.busnotice.commute.LiveSnapshot
import com.busnotice.commute.Transit
import com.busnotice.commute.NodeLog
import com.busnotice.commute.Trip
import kotlinx.coroutines.launch

/** 线路名缩短：纯数字线路（如「23路」「2号线」）只留数字，否则原样 */
fun shortLine(name: String): String {
    val m = Regex("^(地铁)?\\s*(\\d+)\\s*(路|号线|线)?$").find(name.trim())
    return m?.groupValues?.get(2) ?: name.trim().removeSuffix("路")
}

/** 打卡时刻 →「08:12」 */
private fun clockOf(at: Long): String =
    java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
        .toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

/** 公交线路牌的一组稳定配色：按线路名散列，让并排的 11 路 / 13 路一眼可分 */
private val BusPalette = listOf(
    Fuse.BusGreen,          // 绿
    Color(0xFF2F6FE4),      // 蓝
    Color(0xFF8A4FD1),      // 紫
    Color(0xFFB8631F),      // 棕橙
    Color(0xFF0E8A9C),      // 青
    Color(0xFFC2185B),      // 玫红
)

/** 地铁按线路号取色（与上海地铁官方线色一致），并排两条线不再同色 */
private val MetroPalette = listOf(
    Color(0xFFE4002B), Color(0xFF97D700), Color(0xFFFFD100), Color(0xFF5F259F),
    Color(0xFF63298F), Color(0xFFD9027D), Color(0xFFF57F29), Color(0xFF009EDB),
    Color(0xFF71C5E8), Color(0xFFA192B2), Color(0xFF76232F), Color(0xFF007B5F),
    Color(0xFFE999C0), Color(0xFF827A04), Color(0xFFB5A26D), Color(0xFF98D5DB),
    Color(0xFFBC796F), Color(0xFFD6A461),
)

private fun lineNo(name: String): Int? =
    Regex("(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()

/** 6 位十六进制线路色（高德 uicolor 或 JSON 的 color 字段），非法返回 null */
private fun hexColor(raw: String): Color? {
    val h = raw.trim().removePrefix("#")
    if (h.length != 6) return null
    val v = h.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or v)
}

fun lineColor(c: Candidate?, fallbackName: String = ""): Color {
    c?.let { hexColor(it.colorHex)?.let { col -> return col } }
    val name = (c?.name.orEmpty()).ifEmpty { c?.label().orEmpty() }.ifEmpty { fallbackName }
    // 旧行程没存颜色时按名字从数据源补查（高德 uicolor），查不到再退回本地调色板
    if (c?.type != Candidate.Type.SHUTTLE) hexColor(Transit.colorOf(name))?.let { return it }
    return when {
        c == null || c.type == Candidate.Type.BUS -> {
            val n = lineNo(name)
            if (Candidate.looksLikeMetro(name) && n != null) MetroPalette[(n - 1).coerceIn(0, MetroPalette.lastIndex)]
            else if (Candidate.looksLikeMetro(name)) Fuse.MetroRed
            else BusPalette[Math.floorMod(name.hashCode(), BusPalette.size)]
        }
        c.type == Candidate.Type.METRO -> {
            val n = lineNo(c.name.ifEmpty { c.label() })
            if (n != null) MetroPalette[(n - 1).coerceIn(0, MetroPalette.lastIndex)] else Fuse.MetroRed
        }
        else -> Fuse.Shuttle
    }
}

/** 彩色圆角线路牌 */
@Composable
fun LineBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp).widthIn(min = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (color.luminance() > 0.4f) Color(0xFF1B1B1F) else Color.White,
            fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
    }
}

private data class BigNum(val prefix: String, val num: String, val unit: String)

/** 「5分后走」「约3分」「待12分」「4站」「500米」→ 数字 + 单位；其它（即将到站 / 下车 / 换乘…）返回 null */
private fun parseBig(headline: String): BigNum? {
    val m = Regex("^(约|待)?\\s*(\\d+)\\s*((分|站|米|秒).*)$").find(headline.trim()) ?: return null
    return BigNum(m.groupValues[1], m.groupValues[2], m.groupValues[3])
}

private val Tnum = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun LiveBoard(s: LiveSnapshot, trip: Trip?, dark: Boolean, onStopped: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var confirmEnd by remember { mutableStateOf(false) }
    val demo = s.tripId == com.busnotice.commute.DemoPlayer.TRIP_ID
    // 到站打卡：可打卡站点与已打卡记录由服务发布；演示回放用自己内存里的模拟数据
    val stopNames by (if (demo) com.busnotice.commute.DemoPlayer.stopChoices else CommuteService.stopChoices).collectAsState()
    val stopMarks by (if (demo) com.busnotice.commute.DemoPlayer.stopMarksUi else CommuteService.stopMarksUi).collectAsState()
    var pickStop by remember { mutableStateOf(false) }
    var showStopEtas by remember(s.tripId) { mutableStateOf(false) }
    val curNodeRef by NodeLog.current.collectAsState()
    val leg = trip?.legs?.getOrNull(s.legIndex)
    val cands = leg?.candidates.orEmpty()
    val shown = cands.filter { s.legTitle.contains(it.label()) }.take(3).ifEmpty { cands.take(1) }
    val main = shown.firstOrNull()
    // 本段下车站：填了就用，换乘段没填时用下一段上车站
    val alightName = leg?.candidates?.firstOrNull { it.alightName.isNotEmpty() }?.alightName
        ?: trip?.legs?.getOrNull(s.legIndex + 1)?.candidates?.firstOrNull()?.stopName
    val demoWalk = demo && (s.headline == "已到达" || s.detail.startsWith("出站后步行"))
    val headText = when {
        demoWalk && s.headline == "已到达" -> "广州国际金融城"
        demoWalk -> "科韵路"
        s.riding && !alightName.isNullOrEmpty() -> "至 $alightName"
        !main?.stopName.isNullOrEmpty() -> main!!.stopName
        else -> s.legTitle
    }

    Column(Modifier.fillMaxWidth().board(dark).padding(18.dp)) {
        // 顶部：线路牌 + 方向 | 行程名 + 分段条
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (demoWalk) LineBadge(if (s.headline == "已到达") "到达" else "步行", if (s.headline == "已到达") Fuse.BusGreen else Fuse.Yellow)
            else if (shown.isEmpty()) LineBadge(shortLine(s.legTitle), lineColor(null, s.legTitle))
            if (!demoWalk) {
                shown.forEachIndexed { i, c ->
                    if (i > 0) Spacer(Modifier.width(4.dp))
                    LineBadge(shortLine(c.name.ifEmpty { c.label() }), lineColor(c))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(headText, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(s.tripName, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(s.legCount.coerceIn(1, 6)) { i ->
                    val c = when {
                        i == s.legIndex -> Fuse.Yellow
                        i < s.legIndex -> Color.White.copy(alpha = 0.55f)
                        else -> Color.White.copy(alpha = 0.25f)
                    }
                    Box(Modifier.width(18.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c))
                }
            }
        }

        // 大号倒计时
        AnimatedContent(
            targetState = s.headline,
            transitionSpec = {
                (slideInVertically { it / 3 } + fadeIn()) togetherWith (slideOutVertically { -it / 3 } + fadeOut())
            },
            label = "headline",
        ) { h ->
            val big = parseBig(h)
            val glow = Shadow(Fuse.Yellow.copy(alpha = 0.45f), Offset.Zero, 24f)
            if (big != null) {
                val caption = when {
                    s.riding -> "乘车中"
                    demoWalk || s.detail.startsWith("步行") -> "步行到站"
                    else -> "候车"
                }
                Column {
                    Text(caption, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 10.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (big.prefix.isNotEmpty()) {
                            Text(big.prefix, color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp, end = 4.dp))
                        }
                        Text(big.num, color = Fuse.Yellow, fontSize = 80.sp, fontWeight = FontWeight.Bold,
                            style = Tnum.copy(shadow = glow, lineHeight = 80.sp))
                        Text(big.unit, color = Color.White, fontSize = 20.sp, maxLines = 1,
                            modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
                    }
                }
            } else {
                Text(h, color = Fuse.Yellow, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = glow, lineHeight = 52.sp), maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            }
        }

        // 节点时间轴：点当前节点记「现在」，长按任意节点补录；演示回放显示模拟节点，只读
        if (demo) {
            val demoNodes by com.busnotice.commute.DemoPlayer.nodes.collectAsState()
            val demoCur by com.busnotice.commute.DemoPlayer.currentNode.collectAsState()
            NodeTimeline(
                trip = trip, nodes = demoNodes, current = demoCur, editable = false, dark = true,
                onTap = {}, onLongPress = { _, _ -> },
            )
        } else {
            val nodes by NodeLog.flow.collectAsState()
            val curNode by NodeLog.current.collectAsState()
            NodeTimeline(
                trip = trip, nodes = nodes, current = curNode, editable = true, dark = true,
                onTap = { ref ->
                    if (ref == curNode) CommuteService.instance?.stampCurrentNode()
                    else CommuteService.instance?.undoNodeIfFresh(ref)
                },
                onLongPress = { ref, at -> CommuteService.instance?.setNodeAt(ref.kind, ref.leg, at) },
                eta = s.nodeEta,
            )
        }

        // 详情：「下一站 XX」里的站名标黄
        val station = Regex("下一站\\s*(?:就是\\s*)?(\\S+)").find(s.detail)?.groups?.get(1)
        Text(
            buildAnnotatedString {
                if (station == null) append(s.detail) else {
                    append(s.detail.substring(0, station.range.first))
                    withStyle(SpanStyle(color = Fuse.Yellow)) { append(station.value) }
                    append(s.detail.substring(station.range.last + 1))
                }
            },
            color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )

        val passed = s.stopsPassed
        val total = s.stopsTotal
        if (passed != null && total != null && total > 0) {
            StopDots(passed.coerceIn(0, total), total, s.fromStop, s.curStop, s.toStop)
        } else s.progress?.let { p ->
            Box(
                Modifier.padding(top = 16.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0, 100) / 100f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Fuse.Yellow))
            }
        }

        // 下一站预计到站：站点打卡后按学到的区间时长实时更新
        s.stopEta.firstOrNull()?.let { next ->
            Text(
                "下一站 ${next.name} 约 ${clockOf(next.at)}", color = Color.White.copy(alpha = 0.72f),
                fontSize = 13.sp, style = Tnum, modifier = Modifier.padding(top = 8.dp),
            )
        }

        // 逐级重算结果：本段 / 全程预计结束，以及后续各站到站时刻
        val legEnd = s.legEndAt
        val tripEnd = s.tripEndAt
        val parts = buildList {
            legEnd?.let { add("本段约 ${clockOf(it)} 到") }
            if (s.legCount > 1 && tripEnd != null) add("全程约 ${clockOf(tripEnd)} 结束")
        }
        if (parts.isNotEmpty()) {
            Text(
                parts.joinToString(" · "), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                style = Tnum, modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (s.stopEta.isNotEmpty()) {
            Row(
                Modifier.padding(top = 2.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { showStopEtas = !showStopEtas }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "后续各站到站时间", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (showStopEtas) "收起" else "展开 ${s.stopEta.size} 站",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                )
            }
            if (showStopEtas) {
                Column(Modifier.fillMaxWidth()) {
                    s.stopEta.forEach { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                e.name, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(
                                "约 " + clockOf(e.at), color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp, style = Tnum,
                            )
                        }
                    }
                }
            }
        }

        if (!demoWalk) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val undoAt by CommuteService.undoUntil.collectAsState()
            val canUndo = !demo && undoAt > System.currentTimeMillis()
            when {
                // 刚手点过「已上车」：2 分钟内这个按钮是撤销，防误点、防连点跳两段
                canUndo -> Pill("撤销上车", Color.White.copy(alpha = 0.12f), Fuse.Yellow, Fuse.Yellow.copy(alpha = 0.5f), Modifier.weight(1f)) { CommuteService.instance?.onBoarded() }
                // 最后一段地铁已下车：现在刷闸机出站；出站后再点一次就是「到达」
                !demo && curNodeRef?.kind == NodeLog.Kind.EXIT ->
                    Pill("现在出站", Fuse.Yellow, Color(0xFF111111), null, Modifier.weight(1f)) { CommuteService.instance?.stampCurrentNode() }
                !demo && curNodeRef?.kind == NodeLog.Kind.ARRIVE ->
                    Pill("现在到达", Fuse.Yellow, Color(0xFF111111), null, Modifier.weight(1f)) { CommuteService.instance?.stampCurrentNode() }
                !s.riding -> Pill("确认上车", Fuse.Yellow, Color(0xFF111111), null, Modifier.weight(1f)) {
                    if (demo) com.busnotice.commute.DemoPlayer.onBoarded() else CommuteService.instance?.onBoarded()
                }
                else -> {
                    if (stopNames.size > 1) Pill("到站打卡", Color.White.copy(alpha = 0.12f), Color.White, Color.White.copy(alpha = 0.2f), Modifier.weight(1f)) { pickStop = true }
                    // 只剩本段下车站：静音与记录下车分开，静音不写任何节点，避免污染学习数据
                    if (s.alightDue) {
                        Pill("我知道了", Color.White.copy(alpha = 0.12f), Color.White, Color.White.copy(alpha = 0.2f), Modifier.weight(1f)) {
                            if (demo) com.busnotice.commute.DemoPlayer.muteAlarm() else CommuteService.instance?.muteAlarm()
                        }
                        Pill("我已下车", Fuse.Yellow, Color(0xFF111111), null, Modifier.weight(1f)) {
                            if (demo) com.busnotice.commute.DemoPlayer.ackAlight() else CommuteService.instance?.ackAlight()
                        }
                    }
                }
            }
        }
        // 已打卡摘要：左侧点开选站弹窗，右侧「撤销」直接撤掉最近一次
        if (s.riding && stopMarks.isNotEmpty()) {
            val last = stopMarks.last()
            val lastLabel = stopNames.getOrNull(last.idx)
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已打卡 ${stopMarks.size} 站" + if (lastLabel != null) " · 最近 $lastLabel ${clockOf(last.at)}" else "",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .clickable { pickStop = true }.padding(vertical = 6.dp),
                )
                Text(
                    "撤销", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { if (demo) com.busnotice.commute.DemoPlayer.undoLastStopMark() else CommuteService.instance?.undoLastStopMark() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Box(
            Modifier.padding(top = 4.dp).fillMaxWidth().height(44.dp).clip(CircleShape)
                .clickable {
                    if (s.tripId == com.busnotice.commute.DemoPlayer.TRIP_ID) com.busnotice.commute.DemoPlayer.stop(ctx)
                    else if (trip != null) confirmEnd = true
                    else CommuteService.stop(ctx)
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("结束行程", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            shape = DialogShape, containerColor = Fuse.surface(dark),
            titleContentColor = Fuse.ink(dark), textContentColor = Fuse.ink(dark),
            title = { Text("结束本次行程？") },
            text = { Text("结束后本次不再提醒，可在最近行程中补录节点。") },
            confirmButton = {
                DialogButton("结束行程", danger = true, onClick = {
                    confirmEnd = false
                    CommuteService.stop(ctx)
                    onStopped?.invoke()
                })
            },
            dismissButton = { DialogButton("取消", onClick = { confirmEnd = false }) },
        )
    }

    // 到站打卡：以真实位置为中心列出 ±5 站（不含上车站与下车站）
    if (pickStop && stopNames.size > 2) {
        val maxIdx = stopNames.size - 2
        val markIdx = stopMarks.maxOfOrNull { it.idx }
        // 预测值在到站提醒后可能变 null；打卡记录是真实到达，取两者更靠后的作为当前位置
        val here = (listOfNotNull(s.stopsPassed, markIdx).maxOrNull() ?: 0).coerceIn(0, maxIdx)
        val from = (here - 5).coerceAtLeast(1)
        val to = (here + 5).coerceAtMost(maxIdx)
        val rows = (from..to).toList()
        val rowHeight = 46.dp
        val listHeight = rowHeight * rows.size.coerceAtMost(8)
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = (here - from - 2).coerceIn(0, (rows.size - 1).coerceAtLeast(0)),
        )
        AlertDialog(
            onDismissRequest = { pickStop = false },
            shape = DialogShape, containerColor = Fuse.surface(dark),
            title = { Text("现在到哪一站？", color = Fuse.ink(dark)) },
            text = {
                Column {
                    Text("记录当前时刻，用来校正后面的到站时间", color = Fuse.dim(dark), fontSize = 13.sp)
                    if (rows.size > 8) Text(
                        "共 ${rows.size} 站，可上下滑动",
                        color = Fuse.dim(dark).copy(alpha = 0.7f), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    LazyColumn(Modifier.padding(top = 8.dp).fillMaxWidth().height(listHeight), state = listState) {
                        items(rows, key = { it }) { i ->
                            val m = stopMarks.firstOrNull { it.idx == i }
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (m != null) Fuse.accent(dark).copy(alpha = 0.10f) else Color.Transparent)
                                    .clickable {
                                        if (demo) com.busnotice.commute.DemoPlayer.markStop(i) else CommuteService.instance?.markStop(i)
                                        pickStop = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stopNames[i], color = Fuse.ink(dark), fontSize = 15.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (m != null) {
                                    Text("✓ ${clockOf(m.at)}", color = Fuse.accent(dark), fontSize = 13.sp)
                                } else if (i == here) {
                                    Text("当前", color = Fuse.dim(dark), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    val lastMark = stopMarks.lastOrNull()
                    if (lastMark != null) {
                        Row(Modifier.padding(top = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("上次打卡：${stopNames.getOrNull(lastMark.idx) ?: "已过站"} ${clockOf(lastMark.at)}",
                                color = Fuse.dim(dark), fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("撤销", color = Fuse.accent(dark), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { if (demo) com.busnotice.commute.DemoPlayer.undoLastStopMark() else CommuteService.instance?.undoLastStopMark() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            },
            confirmButton = { DialogButton("关闭", onClick = { pickStop = false }) },
        )
    }
}

/** 进程重启后、服务还没刷新出新状态时的占位看板：行程名 +「正在恢复…」，可以直接结束 */
@Composable
fun RestoringBoard(tripName: String, dark: Boolean) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().board(dark).padding(18.dp)) {
        Text(tripName, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("正在恢复", color = Fuse.Yellow, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text("行程仍在进行，正在同步最新状态", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 14.dp)) {
            Pill("结束行程", Color.White.copy(alpha = 0.12f), Color.White, Color.White.copy(alpha = 0.2f), Modifier.weight(1f)) {
                CommuteService.stop(ctx)
            }
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, border: Color?, modifier: Modifier, onClick: () -> Unit) {
    var m = modifier.height(44.dp).clip(CircleShape).background(bg)
    if (border != null) m = m.border(1.dp, border, CircleShape)
    Box(m.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** 站点圆点：已过白色、当前黄色带光、未到暗色、下车站黄色圆环 */
@Composable
private fun StopDots(passed: Int, total: Int, from: String? = null, curName: String? = null, to: String? = null) {
    // 到达下车站（passed == total）时，进度点落在终点
    val cur = passed.coerceIn(0, total)
    val dense = total > 14   // 站太多就不画中间的小点，只画线
    Column(Modifier.padding(top = 16.dp)) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val pad = 8.dp.toPx()
            val y = size.height / 2
            val w = size.width - pad * 2
            fun x(i: Int) = pad + w * i / total
            val lineW = 3.dp.toPx()
            val dim = Color.White.copy(alpha = 0.2f)
            // 线
            if (cur > 0) drawLine(Color.White, Offset(x(0), y), Offset(x(cur), y), lineW, StrokeCap.Round)
            drawLine(dim, Offset(x(cur), y), Offset(x(total), y), lineW, StrokeCap.Round)
            if (cur < total) {
                drawLine(
                    Brush.horizontalGradient(listOf(Fuse.Yellow, Fuse.Yellow.copy(alpha = 0.2f)), x(cur), x(cur + 1)),
                    Offset(x(cur), y), Offset(x(cur + 1), y), lineW, StrokeCap.Round,
                )
            }
            // 点
            val r = 5.dp.toPx()
            for (i in 0 until total) {
                if (i == cur) continue
                if (dense && i != 0) continue
                drawCircle(if (i < cur) Color.White else Color.White.copy(alpha = 0.35f), r * (if (dense) 0.8f else 1f), Offset(x(i), y))
            }
            // 下车站：黄圈
            drawCircle(Fuse.Yellow, 6.dp.toPx(), Offset(x(total), y), style = Stroke(2.dp.toPx()))
            // 当前：黄点 + 光晕
            val c = Offset(x(cur), y)
            drawCircle(Brush.radialGradient(listOf(Fuse.Yellow.copy(alpha = 0.6f), Fuse.Yellow.copy(alpha = 0f)), c, 14.dp.toPx()), 14.dp.toPx(), c)
            drawCircle(Fuse.Yellow, 8.dp.toPx(), c)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val labelColor = Color.White.copy(alpha = 0.5f)
            Text(from ?: "上车站", color = labelColor, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.CenterStart).widthIn(max = 72.dp))
            Text(to ?: "下车站", color = labelColor, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = 72.dp))
            val f = cur.toFloat() / total
            if (f in 0.25f..0.75f) {
                // 当前站名居中到当前点下方（标签最宽约 64dp）
                val label = curName ?: "当前站"
                val half = (if (curName != null) 32 else 12).dp
                val cx = 8.dp + (maxWidth - 16.dp) * f - half
                Text(label, color = Fuse.Yellow, fontSize = 11.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = cx).width(half * 2))
            }
        }
    }
}

private val LegKinds = listOf(NodeLog.Kind.ENTER, NodeLog.Kind.BOARD, NodeLog.Kind.ALIGHT, NodeLog.Kind.EXIT)

/** 节点名：同一种节点出现多次时追加段号（如「下车 2」），避免换乘时看不出是哪一段 */
private fun nodeLabel(refs: List<NodeLog.NodeRef>, kind: NodeLog.Kind, leg: Int): String {
    val dup = refs.count { it.kind == kind } > 1
    val maxLeg = refs.filter { it.kind == kind }.maxOfOrNull { it.leg } ?: leg
    // 多段行程把「换乘下车 / 换乘上车 / 终点下车」说清楚，避免只看时刻不知道是哪个动作
    val base = when {
        kind == NodeLog.Kind.BOARD && leg > 0 -> "换乘上车"
        kind == NodeLog.Kind.ALIGHT && leg < maxLeg -> "换乘下车"
        kind == NodeLog.Kind.ALIGHT && maxLeg > 0 -> "终点下车"
        else -> NodeLog.label(kind)
    }
    return base + if (dup && kind in LegKinds) " ${leg + 1}" else ""
}

/** 一条节点时间轴：点当前节点记「现在」，长按任意节点打开时间滚轮补录；未记录的节点显示预测时刻 */
@Composable
fun NodeTimeline(
    trip: Trip?,
    nodes: List<NodeLog.Stamp>,
    current: NodeLog.NodeRef?,
    editable: Boolean,
    dark: Boolean,
    onTap: (NodeLog.NodeRef) -> Unit,
    onLongPress: (NodeLog.NodeRef, Long) -> Unit,
    onDelete: ((NodeLog.NodeRef) -> Unit)? = null,
    tapOpens: Boolean = false,
    eta: List<com.busnotice.commute.TimelineProjection.NodeEta> = emptyList(),
) {
    if (trip == null) return
    val eff = remember(nodes) { NodeLog.effective(nodes) }
    val refs = remember(trip) { NodeLog.timeline(trip) }
    var editing by remember { mutableStateOf<Pair<NodeLog.NodeRef, Int>?>(null) }
    var askClear by remember { mutableStateOf<NodeLog.NodeRef?>(null) }
    var askDelete by remember { mutableStateOf<NodeLog.NodeRef?>(null) }
    val ink = Fuse.ink(dark)
    val chipBg = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        refs.forEach { ref ->
            val st = eff[ref]
            // 还没记录的节点：用逐级重算出的预测时刻补位，避免整条轴都是「—」
            val etaAt = if (st == null) eta.firstOrNull { it.kind == ref.kind && it.leg == ref.leg }?.at else null
            val isCur = current == ref
            val label = nodeLabel(refs, ref.kind, ref.leg)
            val estimated = st?.src == NodeLog.Src.ESTIMATED || st?.src == NodeLog.Src.AUTO || etaAt != null
            val timeColor = when {
                isCur -> Fuse.Yellow
                st == null && etaAt != null -> ink.copy(alpha = 0.6f)
                st == null -> ink.copy(alpha = 0.35f)
                st.src == NodeLog.Src.TAP || st.src == NodeLog.Src.MANUAL -> Fuse.BusGreen
                st.src == NodeLog.Src.ESTIMATED -> ink.copy(alpha = 0.5f)
                st.src == NodeLog.Src.BACKFILL -> Fuse.Yellow
                st.src == NodeLog.Src.AUTO -> ink.copy(alpha = 0.75f)
                else -> ink
            }
            Column(
                Modifier
                    .minimumInteractiveComponentSize()
                    .widthIn(min = 54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isCur) Fuse.Yellow.copy(alpha = 0.16f) else chipBg)
                    .then(if (isCur) Modifier.border(1.dp, Fuse.Yellow, RoundedCornerShape(10.dp)) else Modifier)
                    .semantics {
                        role = Role.Button
                        contentDescription = label + " " + (st?.let { (if (estimated) "约" else "") + hhmm(it.at) }
                            ?: etaAt?.let { "预计 " + hhmm(it) } ?: "未记录") +
                            if (isCur) " 当前" else ""
                    }
                    .pointerInput(ref, st, editable, tapOpens, etaAt) {
                        detectTapGestures(
                            onTap = {
                                if (editable) {
                                    // 点按即编辑（最近行程用）；看板里点按仍是「记录现在」
                                    if (tapOpens) {
                                        if (onDelete != null && st != null) askClear = ref
                                        else editing = ref to minuteOf(st?.at ?: System.currentTimeMillis())
                                    } else onTap(ref)
                                }
                            },
                            onLongPress = {
                                if (editable) {
                                    if (onDelete != null && st != null) askClear = ref
                                    else editing = ref to minuteOf(st?.at ?: etaAt ?: System.currentTimeMillis())
                                }
                            },
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, color = ink.copy(alpha = if (isCur) 0.9f else 0.55f), fontSize = 11.sp, maxLines = 1)
                Text(
                    (if (estimated) "≈" else "") + (st?.let { hhmm(it.at) } ?: etaAt?.let { hhmm(it) } ?: "—"),
                    color = timeColor, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, style = Tnum,
                )
            }
        }
    }

    askClear?.let { ref ->
        val at = eff[ref]?.at ?: System.currentTimeMillis()
        AlertDialog(
            onDismissRequest = { askClear = null },
            shape = DialogShape, containerColor = Fuse.surface(dark),
            titleContentColor = ink, textContentColor = ink.copy(alpha = 0.78f),
            title = { Text(nodeLabel(refs, ref.kind, ref.leg) + " · " + hhmm(at)) },
            text = { Text("这条记录可以改时间；删掉后能重新补录。") },
            confirmButton = {
                DialogButton("改时间", primary = true, onDark = dark, onClick = {
                    askClear = null
                    editing = ref to minuteOf(at)
                })
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DialogButton("删除", danger = true, onDark = dark, onClick = { askClear = null; askDelete = ref })
                    DialogButton("取消", onDark = dark, onClick = { askClear = null })
                }
            },
        )
    }

    askDelete?.let { ref ->
        val name = nodeLabel(refs, ref.kind, ref.leg)
        AlertDialog(
            onDismissRequest = { askDelete = null },
            shape = DialogShape, containerColor = Fuse.surface(dark),
            titleContentColor = ink, textContentColor = ink.copy(alpha = 0.78f),
            title = { Text("删除「$name」？") },
            text = { Text("删掉后这一条会变回「—」，随时可以点它重新补录。") },
            confirmButton = {
                DialogButton("删除", danger = true, onDark = dark, onClick = {
                    askDelete = null
                    onDelete?.invoke(ref)
                })
            },
            dismissButton = { DialogButton("取消", onDark = dark, onClick = { askDelete = null }) },
        )
    }

    editing?.let { (ref, minute) ->
        TimeWheelSheet(
            title = "${nodeLabel(refs, ref.kind, ref.leg)} 时间",
            initialMinute = minute,
            onDismiss = { editing = null },
            onConfirm = { m ->
                editing = null
                onLongPress(ref, atToday(m))
            },
        )
    }
}

/** 结束后的「最近行程」：默认收起成一行摘要，展开后是完整时间轴，点按节点即可改/删 */
@Composable
fun RecentRunCard(
    trip: Trip, nodes: List<NodeLog.Stamp>, dark: Boolean,
    snack: SnackbarHostState? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(nodes) }
    androidx.compose.runtime.LaunchedEffect(nodes) { list = nodes }
    var expanded by remember { mutableStateOf(false) }
    val first = nodes.minOfOrNull { it.at }
    val last = nodes.maxOfOrNull { it.at }
    val durMin = if (first != null && last != null) ((last - first) / 60_000L).toInt() else 0
    Column(Modifier.fillMaxWidth().board(dark).padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 看板底色永远是深色，文字固定白色，不能跟随主题的 ink
            Text("最近行程", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(trip.name, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
        }
        if (first != null && last != null) {
            Text(
                dayLabel(first) + " · " + hhmm(first) + "–" + hhmm(last) + " · 全程 " + durMin.coerceAtLeast(1) + " 分钟",
                color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp), style = Tnum,
            )
        }
        if (!expanded) return@Column
        Text("点按节点可改/删", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp))
        NodeTimeline(
            trip = trip, nodes = list, current = null, editable = true, dark = true,
            onTap = {}, tapOpens = true,
            onLongPress = { ref, at ->
                val next = list + NodeLog.Stamp(ref.kind, ref.leg, at, NodeLog.Src.BACKFILL)
                NodeLog.updateLast(ctx, next)
                list = next
            },
            onDelete = { ref ->
                val prev = list
                val next = list.filterNot { it.kind == ref.kind && it.leg == ref.leg }
                NodeLog.updateLast(ctx, next)
                list = next
                val host = snack
                if (host != null) {
                    val name = nodeLabel(NodeLog.timeline(trip), ref.kind, ref.leg)
                    scope.launch {
                        val r = host.showSnackbar("已删除「$name」", actionLabel = "撤销", duration = SnackbarDuration.Short)
                        if (r == SnackbarResult.ActionPerformed) {
                            NodeLog.updateLast(ctx, prev)
                            list = prev
                        }
                    }
                }
            },
        )
        Text(
            "绿色＝手动记录 · 黄色＝补录 · 浅色＝推算 · ≈＝估算",
            color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** 7 天内的日期说「今天 / 昨天」，更早的写「M 月 d 日」 */
private fun dayLabel(at: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val today = java.util.Calendar.getInstance()
    val days = ((today.timeInMillis - at) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "今天"
        days == 1 -> "昨天"
        days == 2 -> "前天"
        else -> "${cal.get(java.util.Calendar.MONTH) + 1} 月 ${cal.get(java.util.Calendar.DAY_OF_MONTH)} 日"
    }
}

private fun hhmm(at: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
    return String.format(java.util.Locale.US, "%02d:%02d",
        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

private fun minuteOf(at: Long): Int {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
    return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
}

private fun atToday(minute: Int): Long {
    val c = java.util.Calendar.getInstance()
    c.set(java.util.Calendar.HOUR_OF_DAY, minute / 60)
    c.set(java.util.Calendar.MINUTE, minute % 60)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    var at = c.timeInMillis
    // 跨零点：选 23:50 但现在是 00:10，归到昨天
    if (at - System.currentTimeMillis() > 12 * 3600_000L) at -= 24 * 3600_000L
    return at
}
