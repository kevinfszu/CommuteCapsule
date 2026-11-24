@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.busnotice.commute.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Transit
import com.busnotice.commute.MetroRoute
import com.busnotice.commute.SubwayDir
import com.busnotice.commute.Learn
import com.busnotice.commute.MetroRoutes
import com.busnotice.commute.Geocode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import com.busnotice.commute.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.busnotice.commute.Candidate
import com.busnotice.commute.HeadwayBand
import com.busnotice.commute.Leg
import com.busnotice.commute.Scheduler
import com.busnotice.commute.StopPoint
import com.busnotice.commute.Trip
import com.busnotice.commute.TripStore

private val DAY_NAMES = arrayOf("一", "二", "三", "四", "五", "六", "日")

fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

@Composable
fun TripEditor(
    trip: Trip,
    dirty: Boolean,
    state: LazyListState = rememberLazyListState(),
    onChange: (Trip) -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onAddBus: (Int) -> Unit,
    onAddMetro: (Int) -> Unit,
    onEditShuttle: (Int, Int) -> Unit,
) {
    val ctx = LocalContext.current
    var pickTime by remember { mutableStateOf<Int?>(null) } // 0=开始 1=结束
    var pickFor by remember { mutableStateOf<Int?>(null) }  // 0=出发地 1=目的地

    fun updateLeg(i: Int, leg: Leg) = onChange(trip.copy(legs = trip.legs.toMutableList().also { it[i] = leg }))

    // 老数据只有坐标没有名称：进入页面时反查一次，仅用于展示（不改草稿，保存时才落库）
    var leaveAddr by remember { mutableStateOf("") }
    LaunchedEffect(trip.leaveLat, trip.leaveLng, trip.leaveName) {
        leaveAddr = if (!trip.leaveLat.isNaN() &&
            (trip.leaveName.isEmpty() || trip.leaveName == "家" || trip.leaveName == "公司")
        ) Geocode.nameOf(ctx, trip.leaveLat, trip.leaveLng).orEmpty() else ""
    }

    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    var confirmLeave by remember { mutableStateOf(false) }
    fun requestBack() { if (dirty) confirmLeave = true else onBack() }
    BackHandler { requestBack() }
    GlassPage(
        title = if (TripStore.get(ctx, trip.id) == null) "新建行程" else "编辑行程",
        onBack = ::requestBack,
        state = state,
        actions = {
            PillButton(onClick = {
                val legs = trip.legs.filter { it.candidates.isNotEmpty() }
                if (!trip.chinaWorkday && trip.dayMask == 0) {
                    Toast.makeText(ctx, "请至少选择一个生效日", Toast.LENGTH_SHORT).show()
                } else if (legs.isEmpty()) {
                    Toast.makeText(ctx, "请至少添加一条线路", Toast.LENGTH_SHORT).show()
                } else {
                    val t = trip.copy(legs = legs)
                    TripStore.upsert(ctx, t); Scheduler.schedule(ctx, t); onSaved()
                }
            }, modifier = Modifier.padding(start = 8.dp)) { Text("保存", fontWeight = FontWeight.Bold) }
        },
    ) {
            item {
                GlassField(
                    value = trip.name, onValueChange = { onChange(trip.copy(name = it)) },
                    label = "名称", singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Section("生效日期") {
                    // 两种重复方式互斥：默认值（法定工作日）排第一，再选具体日期
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassChip(selected = trip.chinaWorkday, onClick = {
                            onChange(trip.copy(chinaWorkday = true))
                        }, label = "法定工作日", modifier = Modifier.weight(1f), minTouch = false)
                        GlassChip(selected = !trip.chinaWorkday, onClick = {
                            onChange(trip.copy(chinaWorkday = false, dayMask = trip.dayMask.takeIf { it != 0 } ?: 0b0011111))
                        }, label = "每周", modifier = Modifier.weight(1f), minTouch = false)
                    }
                    Spacer(Modifier.height(10.dp))
                    if (!trip.chinaWorkday) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DAY_NAMES.forEachIndexed { i, n ->
                                val on = trip.dayMask and (1 shl i) != 0
                                GlassChip(selected = on, onClick = {
                                    onChange(trip.copy(dayMask = trip.dayMask xor (1 shl i)))
                                }, label = n, modifier = Modifier.weight(1f), minTouch = false)
                            }
                        }
                    } else {
                        Text("自动跳过法定节假日（含调休）", fontSize = 12.sp, color = dim)
                    }
                }
            }
            item {
                Section("胶囊唤起方式") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassChip(selected = trip.leaveRadiusM <= 0, onClick = { onChange(trip.copy(leaveRadiusM = 0)) },
                            label = "按时间唤起")
                        GlassChip(selected = trip.leaveRadiusM > 0, onClick = {
                            onChange(trip.copy(leaveRadiusM = trip.leaveRadiusM.takeIf { it > 0 } ?: 300))
                        }, label = "离开出发地后唤起")
                    }
                    Spacer(Modifier.height(8.dp))
                    if (trip.leaveRadiusM > 0) {
                        Text("时间范围内每 2 分钟进行一次粗略定位，离开设定范围后自动唤起。适用于下班时间不固定的情况，可将时间范围放宽，例如 17:30 至 22:00。",
                            fontSize = 12.sp, lineHeight = 17.sp, color = dim)
                        if (trip.leaveLat.isNaN()) {
                            Spacer(Modifier.height(4.dp))
                            Text("还未设置出发地：请在下方「出发地」选择地点，否则仍按时间唤起。",
                                fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.Amber)
                        }
                    } else {
                        Text("下班时间不固定时，请选择「离开出发地后唤起」并设置一次出发地；也可将「通勤胶囊」添加至快捷开关，出发时手动触发。",
                            fontSize = 12.sp, lineHeight = 17.sp, color = dim)
                    }
                }
            }
            item {
                Section("出发时间范围") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeBox(hhmm(trip.startMinute), dark) { pickTime = 0 }
                        Text("至", color = dim, modifier = Modifier.padding(horizontal = 12.dp))
                        TimeBox(hhmm(trip.endMinute), dark) { pickTime = 1 }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("定位预热提前量", style = Type.Body, color = dim)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 15, 20).forEach { m ->
                            GlassChip(selected = trip.warmupMin == m, onClick = { onChange(trip.copy(warmupMin = m)) },
                                label = "$m 分钟")
                        }
                    }
                }
            }
            item {
                Section("出发地") {
                    val main = trip.leaveName.ifEmpty { leaveAddr.ifEmpty { if (trip.leaveLat.isNaN()) "未设置" else "已设置" } }
                    val sub = if (trip.leaveLat.isNaN()) "选择地点后可用于「离开出发地后唤起」"
                        else "%.5f, %.5f".format(trip.leaveLat, trip.leaveLng)
                    Row(
                        Modifier.fillMaxWidth().clip(FieldShape)
                            .background(Color.White.copy(alpha = if (dark) 0.08f else 0.5f))
                            .border(1.dp, if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f), FieldShape)
                            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("出发地", fontSize = 12.sp, color = dim)
                            Spacer(Modifier.height(3.dp))
                            Text(main, style = Type.Section, color = ink)
                            Spacer(Modifier.height(2.dp))
                            Text(sub, fontSize = 12.sp, color = dim)
                        }
                        TextButton(onClick = { pickFor = 0 }) {
                            Text("选择地点", fontSize = 13.sp, color = ink)
                        }
                    }
                    if (trip.leaveRadiusM > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text("触发半径", style = Type.BodyDim, color = dim)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(100, 150, 200, 300).forEach { r ->
                                GlassChip(selected = trip.leaveRadiusM == r, onClick = { onChange(trip.copy(leaveRadiusM = r)) },
                                    label = "$r 米")
                            }
                        }
                    }
                }
            }
            val metroIdxs = trip.legs.indices.filter { i -> trip.legs[i].candidates.any { it.isMetro() } }
            val firstMetroIdx = metroIdxs.firstOrNull() ?: -1
            val lastMetroIdx = metroIdxs.lastOrNull() ?: -1
            trip.legs.forEachIndexed { i, leg ->
                item(key = "leg-$i") {
                    LegCard(
                        index = i, leg = leg, isLast = i == trip.legs.size - 1,
                        onChange = { updateLeg(i, it) },
                        onRemove = { onChange(trip.copy(legs = trip.legs.toMutableList().also { l -> l.removeAt(i) })) },
                        onAddBus = { onAddBus(i) }, onAddMetro = { onAddMetro(i) },
                        onAddShuttle = { onEditShuttle(i, -1) },
                        onEditShuttle = { ci -> onEditShuttle(i, ci) },
                        tripId = trip.id,
                        nextStop = trip.legs.getOrNull(i + 1)?.candidates?.firstOrNull(),
                        // 上车预估融入第一个地铁段，下车预估融入最后一个地铁段；没有地铁段则都不显示
                        boardEstimate = trip.boardEstimateSec.takeIf { i == firstMetroIdx },
                        alightEstimate = trip.alightEstimateSec.takeIf { i == lastMetroIdx },
                        onChangeBoardEstimate = { onChange(trip.copy(boardEstimateSec = it)) },
                        onChangeAlightEstimate = { onChange(trip.copy(alightEstimateSec = it)) },
                        onSplit = {
                            // 一段里上车站不同的几条线 → 按上车站拆成先后几段（地铁在前）
                            val groups = leg.candidates.groupBy { it.stopName }.values
                                .sortedBy { g -> if (g.any { it.type == Candidate.Type.METRO }) 0 else 1 }
                            val parts = groups.mapIndexed { k, g ->
                                Leg(g, rideMin = leg.rideMin, rideMinManual = leg.rideMinManual,
                                    transferWalkMin = if (k == 0) leg.transferWalkMin else 3)
                            }
                            onChange(trip.copy(legs = trip.legs.toMutableList().also { l -> l.removeAt(i); l.addAll(i, parts) }))
                        },
                    )
                }
            }
            item {
                PillButton(secondary = true, onClick = { onChange(trip.copy(legs = trip.legs + Leg(emptyList()))) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("添加换乘段")
                }
            }
            item {
                Section("目的地（可选）") {
                    Row(
                        Modifier.fillMaxWidth().clip(FieldShape)
                            .background(Color.White.copy(alpha = if (dark) 0.08f else 0.5f))
                            .border(1.dp, if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f), FieldShape)
                            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("目的地", fontSize = 12.sp, color = dim)
                            Spacer(Modifier.height(3.dp))
                            Text(trip.destName.ifEmpty { "未设置" }, style = Type.Section, color = ink)
                            if (trip.destMode()) {
                                Spacer(Modifier.height(2.dp))
                                Text("%.5f, %.5f".format(trip.destLat, trip.destLng), fontSize = 12.sp, color = dim)
                            }
                        }
                        TextButton(onClick = { pickFor = 1 }) { Text("选择地点", fontSize = 13.sp, color = ink) }
                        if (trip.destMode()) TextButton(onClick = {
                            onChange(trip.copy(destLat = Double.NaN, destLng = Double.NaN, destName = ""))
                        }) { Text("清除", fontSize = 13.sp, color = dim) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("设置后，到达目的地 150 米内驻留满 3 分钟、且地铁已出站，会自动结束行程。",
                        fontSize = 12.sp, lineHeight = 17.sp, color = dim)
                }
            }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
            title = { Text("放弃未保存的修改？") },
            text = { Text("离开后本次修改不会保留。") },
            confirmButton = { DialogButton("放弃修改", danger = true, onClick = { confirmLeave = false; onBack() }) },
            dismissButton = { DialogButton("继续编辑", onClick = { confirmLeave = false }) },
        )
    }

    pickTime?.let { which ->
        val init = if (which == 0) trip.startMinute else trip.endMinute
        TimeWheelSheet(
            title = if (which == 0) "开始时间" else "结束时间",
            initialMinute = init,
            onDismiss = { pickTime = null },
            onConfirm = { m ->
                var t = if (which == 0) trip.copy(startMinute = m) else trip.copy(endMinute = m)
                if (t.endMinute <= t.startMinute) t = t.copy(endMinute = (t.startMinute + 30).coerceAtMost(23 * 60 + 59))
                onChange(t); pickTime = null
            },
        )
    }

    pickFor?.let { f ->
        LocationPicker(
            title = if (f == 0) "选择出发地" else "选择目的地",
            initLat = if (f == 0) trip.leaveLat.takeIf { !it.isNaN() } else trip.destLat.takeIf { !it.isNaN() },
            initLng = if (f == 0) trip.leaveLng.takeIf { !it.isNaN() } else trip.destLng.takeIf { !it.isNaN() },
            onBack = { pickFor = null },
            onPick = { name, lat, lng ->
                if (f == 0) onChange(trip.copy(leaveLat = lat, leaveLng = lng, leaveName = name))
                else onChange(trip.copy(destLat = lat, destLng = lng, destName = name))
                pickFor = null
            },
        )
    }
}

/** 一个小节 = 一张玻璃卡：小标题 + 内容 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    GlassCard {
        SectionLabel(title)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** 时间窗里的时间块：半透明圆角框 + 大号等宽数字，点开选时间 */
@Composable
private fun TimeBox(text: String, dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(FieldShape)
            .background(Color.White.copy(alpha = if (dark) 0.08f else 0.55f))
            .border(1.dp, if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f), FieldShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(text, style = Type.MetricLarge, color = Fuse.ink(dark))
    }
}

@Composable
private fun LegCard(
    onSplit: () -> Unit = {},
    nextStop: Candidate? = null,
    // 非空表示在本段内显示对应的地铁预估（首段显示上车、末段显示下车）
    boardEstimate: Int? = null,
    alightEstimate: Int? = null,
    onChangeBoardEstimate: (Int) -> Unit = {},
    onChangeAlightEstimate: (Int) -> Unit = {},
    index: Int, leg: Leg, isLast: Boolean,
    tripId: String,
    onChange: (Leg) -> Unit, onRemove: () -> Unit,
    onAddBus: () -> Unit, onAddMetro: () -> Unit, onAddShuttle: () -> Unit, onEditShuttle: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    editing?.let { ci ->
        leg.candidates.getOrNull(ci)?.let { c ->
            CandidateDialog(c, onDismiss = { editing = null }) { nc ->
                onChange(leg.copy(candidates = leg.candidates.toMutableList().also { it[ci] = nc })); editing = null
            }
        }
    }
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
            title = { Text("删除第 ${index + 1} 段？") },
            text = { Text("该段线路与学习到的时长会一并移除。") },
            confirmButton = { DialogButton("删除", danger = true, onClick = { confirmRemove = false; onRemove() }) },
            dismissButton = { DialogButton("取消", onClick = { confirmRemove = false }) },
        )
    }
    GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${index + 1} 段", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ink,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Default.Delete, "删除该段", tint = dim) }
            }
            if (index > 0) {
                Stepper("自上一段下车后步行", leg.transferWalkMin, 0, 30) { onChange(leg.copy(transferWalkMin = it)) }
            }
            if (leg.candidates.isEmpty()) {
                Text("同一上车站的多条线路可一并添加，以最先到达者为准；如需先乘 A 再换 B，请使用下方「添加换乘段」。",
                    fontSize = 12.sp, lineHeight = 17.sp, color = dim, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (leg.candidates.map { it.stopName }.distinct().size > 1) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(FieldShape)
                        .background(Fuse.Amber.copy(alpha = if (dark) 0.18f else 0.12f))
                        .border(1.dp, Fuse.Amber.copy(alpha = 0.35f), FieldShape)
                        .padding(12.dp),
                ) {
                    Text("当前段内的线路为「择一乘坐」，以最先到达者为准。这些线路上车站不同；如需先后乘坐，请拆分为两个段。",
                        fontSize = 13.sp, lineHeight = 18.sp, color = ink)
                    Spacer(Modifier.height(8.dp))
                    PillButton(onClick = onSplit) { Text("拆分为换乘段（按上车站）") }
                }
            }
            if (leg.candidates.isNotEmpty()) {
                // 已添加的线路：放在一块更浅的圆角底上，行间淡分隔线
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(FieldShape)
                        .background(Color.White.copy(alpha = if (dark) 0.05f else 0.4f)),
                ) {
                    leg.candidates.forEachIndexed { ci, c ->
                        if (ci > 0) HorizontalDivider(Modifier.padding(start = 12.dp), thickness = 1.dp, color = Fuse.hairline(dark))
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { if (c.type == Candidate.Type.SHUTTLE) onEditShuttle(ci) else editing = ci }
                                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LineBadge(shortLine(c.name.ifEmpty { c.label() }), lineColor(c))
                                Spacer(Modifier.width(8.dp))
                                Text(c.label(), style = Type.Section, color = ink)
                                Spacer(Modifier.width(6.dp))
                                ModeTag(
                                    when (c.type) {
                                        Candidate.Type.BUS -> "公交"
                                        Candidate.Type.SHUTTLE -> "班车"
                                        Candidate.Type.METRO -> "地铁"
                                    },
                                    dark,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = { if (c.type == Candidate.Type.SHUTTLE) onEditShuttle(ci) else editing = ci },
                                    modifier = Modifier.height(40.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text("编辑", fontSize = 13.sp, color = ink) }
                                IconButton(
                                    onClick = {
                                        onChange(leg.copy(candidates = leg.candidates.toMutableList().also { it.removeAt(ci) }))
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) { Icon(Icons.Default.Close, "移除", Modifier.size(18.dp), tint = dim) }
                            }
                            Spacer(Modifier.height(5.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.stopName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink)
                                if (c.alightName.isNotEmpty()) {
                                    Text(" → ", fontSize = 14.sp, color = dim)
                                    Text(c.alightName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink)
                                } else {
                                    Text("  上车", fontSize = 12.sp, color = dim)
                                }
                            }
                            val dir = when (c.type) {
                                Candidate.Type.BUS -> if (c.lastStop.isNotEmpty()) "开往 ${c.lastStop} 方向" else ""
                                Candidate.Type.METRO -> if (c.metroDir.isNotEmpty()) "往 ${c.metroDir}" else ""
                                Candidate.Type.SHUTTLE ->
                                    if (c.departures.isNotEmpty()) "发车 " + c.departures.sorted().joinToString(" ") { hhmm(it) }
                                    else ""
                            }
                            if (dir.isNotEmpty()) {
                                Spacer(Modifier.height(3.dp))
                                Text(dir, fontSize = 12.sp, color = dim)
                            }
                            if (c.type == Candidate.Type.METRO && (c.weekdayBands.isNotEmpty() || c.weekendBands.isNotEmpty())) {
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    HeadwayTag("工作日", c.bandsFor(false), dark)
                                    HeadwayTag("周末", c.bandsFor(true), dark)
                                }
                            }
                        }
                    }
                }
            }
            FlowRow(
                Modifier.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassChip(selected = false, onClick = onAddBus, label = "+ 公交")
                GlassChip(selected = false, onClick = onAddShuttle, label = "+ 通勤班车")
                GlassChip(selected = false, onClick = onAddMetro, label = "+ 地铁")
            }
            // 地铁段：站数估算只作为默认值，最终统一由 Learn.ride 给出唯一结果
            val metro = leg.candidates.firstOrNull { it.isMetro() }.takeIf { leg.isMetroOnly() }
            val ctx = LocalContext.current
            val target = metro?.let {
                if (!it.alightLat.isNaN()) Triple(it.alightName.ifEmpty { "下车站" }, it.alightLat, it.alightLng)
                else nextStop?.let { n -> Triple(it.alightName.ifEmpty { n.stopName }, n.lat, n.lng) }
            }
            var route by remember(metro, target) { mutableStateOf<MetroRoute?>(null) }
            LaunchedEffect(metro, target) {
                if (metro != null && target != null) route = withContext(Dispatchers.IO) {
                    MetroRoutes.get(ctx, metro, target.first, target.second, target.third)
                }
            }
            val ride = Learn.ride(ctx, tripId, index, leg, route?.durSec)
            var pendingRide by remember { mutableStateOf<Int?>(null) }
            var stepperNonce by remember { mutableStateOf(0) }
            if (ride.source == Learn.Ride.Source.LEARNED) {
                pendingRide?.let { v ->
                    AlertDialog(
                        onDismissRequest = { pendingRide = null; stepperNonce++ },
                        title = { Text("修改乘车时长") },
                        text = {
                            Text("修改将清空本段 ${Learn.rideSamples(ctx, tripId, index)} 次实测记录，之后重新统计实测值。")
                        },
                        confirmButton = {
                            DialogButton("确定修改", primary = true, onClick = {
                                Learn.resetRide(ctx, tripId, index)
                                pendingRide = null
                                onChange(leg.copy(rideMin = v, rideMinManual = true))
                            })
                        },
                        dismissButton = {
                            DialogButton("取消", onClick = { pendingRide = null; stepperNonce++ })
                        },
                    )
                }
            }
            key(ride.minutes, stepperNonce) {
                Stepper(if (isLast) "预计乘车时长" else "本段预计乘车时长",
                    ride.minutes, 1, 120) { v ->
                    if (ride.source == Learn.Ride.Source.LEARNED) pendingRide = v
                    else onChange(leg.copy(rideMin = v, rideMinManual = true))
                }
            }
            val noteColor = if (dark) Color(0xFF7FE0A6) else Color(0xFF167A40)
            val note = when (ride.source) {
                Learn.Ride.Source.LEARNED ->
                    "已按实测中位数 ${ride.minutes} 分钟（共 ${Learn.rideSamples(ctx, tripId, index)} 次）推算；修改需清空实测记录"
                Learn.Ride.Source.MANUAL -> "按你填写的 ${ride.minutes} 分钟推算"
                Learn.Ride.Source.ESTIMATE -> "按站数估算 ${ride.minutes} 分钟；有 1 次实测后改用实测"
                Learn.Ride.Source.DEFAULT ->
                    if (metro != null) "默认 ${ride.minutes} 分钟（选好下车站后可估算）" else "默认 ${ride.minutes} 分钟"
            }
            Text(note, fontSize = 12.sp, lineHeight = 17.sp, color = noteColor)
            if (boardEstimate != null) {
                InlineEstimate(
                    title = "上车预估（没打卡时用）",
                    summary = "过闸后 +$boardEstimate 秒上车",
                    label = "过闸后预计多久上车",
                    value = boardEstimate,
                    note = "「过闸后预计多久上车」用于没打卡时的上车推算，推算值也会计入学习样本。推算值带「≈」标记。",
                    onChange = onChangeBoardEstimate,
                )
            }
            if (alightEstimate != null) {
                InlineEstimate(
                    title = "下车预估（没打卡时用）",
                    summary = "下车比出站早 $alightEstimate 秒",
                    label = "终点下车比出站提前",
                    value = alightEstimate,
                    note = "出站时间永远以刷卡为准；「终点下车比出站提前」只在忘记下车打卡时，用出站时间倒推下车，学到实际差值后会优先用实际值。推算值带「≈」标记。",
                    onChange = onChangeAlightEstimate,
                )
            }
    }
}

/** 地铁段内联的预估项：默认收起，点标题展开 */
@Composable
private fun InlineEstimate(
    title: String,
    summary: String,
    label: String,
    value: Int,
    note: String,
    onChange: (Int) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier.fillMaxWidth().clip(FieldShape)
            .background(Color.White.copy(alpha = if (dark) 0.05f else 0.4f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, modifier = Modifier.weight(1f))
            Text(if (open) "收起" else summary, fontSize = 12.sp, color = dim)
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            Stepper(label, value, 0, 900, unit = "秒", onChange = onChange)
            Text(note, fontSize = 12.sp, lineHeight = 17.sp, color = dim)
        }
    }
}

/** 11sp 细边框方式标签：地铁 / 公交 / 班车 */
@Composable
private fun ModeTag(text: String, dark: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    Text(text, fontSize = 11.sp, color = Fuse.dim(dark),
        modifier = Modifier.clip(shape).border(1.dp, Fuse.hairline(dark), shape).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** 11sp 灰底间隔胶囊：「工作日 6–11 分钟」 */
@Composable
private fun HeadwayTag(day: String, bands: List<HeadwayBand>, dark: Boolean) {
    if (bands.isEmpty()) return
    val hws = bands.map { it.headwaySec }
    val lo = hws.min(); val hi = hws.max()
    val text = if (lo == hi) "$day ${hwText(lo)} 分钟" else "$day ${hwText(lo)}–${hwText(hi)} 分钟"
    Text(text, fontSize = 11.sp, color = Fuse.dim(dark),
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = if (dark) 0.08f else 0.05f))
            .padding(horizontal = 7.dp, vertical = 3.dp))
}

private fun hwText(sec: Int) = "${((sec + 30) / 60).coerceAtLeast(1)}"

/** 点已添加的公交/地铁弹出来改：地铁可改方向和逐段间隔（分 + 秒），两者都能改下车站 */
@Composable
fun CandidateDialog(c: Candidate, onDismiss: () -> Unit, onSave: (Candidate) -> Unit) {
    val ctx = LocalContext.current
    var alight by remember { mutableStateOf(c.alightName) }
    var alightLat by remember(c) { mutableStateOf(c.alightLat) }
    var alightLng by remember(c) { mutableStateOf(c.alightLng) }
    var picking by remember { mutableStateOf(false) }
    var dir by remember { mutableStateOf(c.metroDir) }
    var dirs by remember { mutableStateOf<List<SubwayDir>>(emptyList()) }
    var wd by remember(c) { mutableStateOf(c.weekdayBands) }
    var we by remember(c) { mutableStateOf(c.weekendBands.ifEmpty { c.weekdayBands }) }
    var peakSec by remember(c) { mutableStateOf(c.peakHwSec()) }
    var offSec by remember(c) { mutableStateOf(c.offPeakHwSec()) }
    var lastDir by remember(c) { mutableStateOf(c.metroDir) }
    if (c.isMetro()) LaunchedEffect(c) {
        dirs = withContext(Dispatchers.IO) {
            runCatching { Transit.subwayDirs(c.lat, c.lng, c.stopName, c.regionId) }.getOrNull()
        }.orEmpty().filter { Transit.sameLine(c.name, it) }
    }
    LaunchedEffect(dir, dirs) {
        if (dir != lastDir) {
            lastDir = dir
            val d = dirs.firstOrNull { it.dest == dir }
            if (d != null && d.weekdayBands.isNotEmpty()) {
                wd = d.weekdayBands
                we = d.weekendBands.ifEmpty { d.weekdayBands }
                peakSec = d.weekdayBands.minOf { it.headwaySec }
                offSec = d.weekdayBands.maxOf { it.headwaySec }
            }
        }
    }
    val hasBands = wd.isNotEmpty() || we.isNotEmpty()
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
        title = { Text("${c.label()} · ${c.stopName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (c.isMetro()) {
                    if (dirs.isNotEmpty()) {
                        SectionLabel("方向")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            dirs.forEach { d ->
                                GlassChip(selected = dir == d.dest, onClick = { dir = d.dest }, label = "开往 ${d.dest}")
                            }
                        }
                    }
                    if (hasBands) {
                        SectionLabel("发车间隔")
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        ) {
                            if (wd.isNotEmpty()) {
                                Text("工作日", fontSize = 12.sp, color = Fuse.dim(dark))
                                wd.forEachIndexed { i, b ->
                                    BandRow(b) { sec ->
                                        wd = wd.toMutableList().also { it[i] = b.copy(headwaySec = sec) }
                                    }
                                }
                            }
                            if (we.isNotEmpty()) {
                                Text("周末", fontSize = 12.sp, color = Fuse.dim(dark), modifier = Modifier.padding(top = 6.dp))
                                we.forEachIndexed { i, b ->
                                    BandRow(b) { sec ->
                                        we = we.toMutableList().also { it[i] = b.copy(headwaySec = sec) }
                                    }
                                }
                            }
                        }
                        Text("时段来自线路数据，仅可调整各时段发车间隔（分、秒）。", fontSize = 12.sp,
                            lineHeight = 17.sp, color = Fuse.dim(dark))
                    } else {
                        HeadwayStepper("高峰间隔", peakSec) { peakSec = it }
                        HeadwayStepper("平峰间隔", offSec) { offSec = it }
                    }
                }
                // 地铁、公交的下车站从线路站序里选；通勤车没有线路数据，仍手填
                if (c.isMetro() || c.type == Candidate.Type.BUS) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("下车站", fontSize = 12.sp, color = Fuse.dim(dark))
                            Text(
                                alight.ifEmpty { "未选择" }, fontSize = 15.sp,
                                color = if (alight.isEmpty()) Fuse.dim(dark) else ink,
                            )
                        }
                        PillButton(secondary = true, onClick = { picking = true }) {
                            Text(if (alight.isEmpty()) "选择" else "重选")
                        }
                    }
                } else {
                    GlassField(alight, { alight = it }, label = "下车站", singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                if (c.isMetro()) Text("地铁段手动确认「已上车」后，将以真实上车时间修正推算，使用次数越多越准确。",
                    fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark))
            }
        },
        confirmButton = {
            DialogButton("保存", primary = true, onClick = {
                val nwd: List<HeadwayBand>
                val nwe: List<HeadwayBand>
                if (c.isMetro() && hasBands) {
                    nwd = wd
                    nwe = we.ifEmpty { wd }
                } else {
                    nwd = c.copy(
                        headwayMin = (peakSec + 59) / 60, offPeakHeadwayMin = (offSec + 59) / 60,
                        weekdayBands = emptyList(), weekendBands = emptyList(),
                    ).bandsFor(false)
                    nwe = nwd
                }
                val changedSchedule = c.isMetro() &&
                    (nwd != c.bandsFor(false) || nwe != c.bandsFor(true) || dir != c.metroDir)
                if (changedSchedule) Learn.resetMetro(ctx, c)
                val peakMin = ((nwd.minOfOrNull { it.headwaySec } ?: 360) + 59) / 60
                val offMin = ((nwd.maxOfOrNull { it.headwaySec } ?: 540) + 59) / 60
                onSave(c.copy(
                    alightName = alight.trim(),
                    alightLat = if (alight.trim().isEmpty()) Double.NaN else alightLat,
                    alightLng = if (alight.trim().isEmpty()) Double.NaN else alightLng,
                    headwayMin = if (c.isMetro()) peakMin else c.headwayMin,
                    offPeakHeadwayMin = if (c.isMetro()) offMin else c.offPeakHeadwayMin,
                    metroDir = dir,
                    weekdayBands = if (c.isMetro()) nwd else c.weekdayBands,
                    weekendBands = if (c.isMetro()) nwe else c.weekendBands,
                ))
            })
        },
        dismissButton = { DialogButton("取消", onClick = onDismiss) },
    )
    if (picking) AlightPickerSheet(
        c = c, dir = dir, dirs = dirs,
        onPick = { name, la, ln -> alight = name; alightLat = la; alightLng = ln },
        onDismiss = { picking = false },
    )
}

/** 下车站候选：地铁按当前方向、公交按上车站之后的站序；可搜索 */
@Composable
private fun AlightPickerSheet(
    c: Candidate,
    dir: String,
    dirs: List<SubwayDir>,
    onPick: (String, Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    var query by remember { mutableStateOf("") }
    var stops by remember(c, dir) { mutableStateOf<List<StopPoint>?>(null) }
    LaunchedEffect(c, dir) {
        stops = withContext(Dispatchers.IO) {
            runCatching {
                if (c.isMetro()) {
                    val d = dirs.firstOrNull { it.dest == dir } ?: dirs.firstOrNull()
                        ?: return@runCatching emptyList<StopPoint>()
                    if (d.dirId.isEmpty()) emptyList<StopPoint>()
                    else Transit.lineDetail(d.dirId, c.regionId).stations.filter { it.order > d.order }
                } else {
                    Transit.lineDetail(c.lineId, c.regionId).stations.filter { it.order > c.stopIndex }
                }
            }.getOrNull()
        }
    }
    val shown = remember(stops, query) { stops.orEmpty().filter { nameMatches(it.name, query) } }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 72.dp)
                .clip(RoundedCornerShape(24.dp)).background(Fuse.surface(dark)).padding(16.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text("${c.label()} · 选择下车站", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.height(10.dp))
                GlassField(query, { query = it }, placeholder = "筛选站点", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                val list = stops
                when {
                    list == null -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Fuse.accent(dark))
                    }
                    shown.isEmpty() -> Text(
                        if (query.isBlank()) "没有可选的下车站，可留空" else "无匹配站点",
                        fontSize = 14.sp, color = Fuse.dim(dark), modifier = Modifier.padding(vertical = 20.dp),
                    )
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(shown, key = { it.stopId.ifEmpty { it.name } }) { st ->
                            GlassRow(
                                headline = st.name,
                                supporting = "${st.order} 站",
                                onClick = { onPick(st.name, st.lat, st.lng); onDismiss() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BandRow(b: HeadwayBand, onChange: (Int) -> Unit) {
    HeadwayStepper("${hhmm(b.from)}–${hhmm(b.to)}", b.headwaySec, onChange)
}

/** 公交蓝色大巴、地铁绿色 M、通勤车橙色：列表里一眼分开 */
@Composable
fun TransitIcon(c: Candidate) {
    val (res, color) = when (c.type) {
        Candidate.Type.METRO -> R.drawable.ic_metro to Color(0xFF1AAE6F)
        Candidate.Type.SHUTTLE -> R.drawable.ic_bus to Color(0xFFFF8A3D)
        Candidate.Type.BUS -> R.drawable.ic_bus to Color(0xFF3D7BFF)
    }
    Icon(painterResource(res), null, tint = color, modifier = Modifier.size(22.dp))
}

@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, unit: String = "分钟", onChange: (Int) -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(value.toString()) }

    fun commit() {
        val parsed = text.toIntOrNull()?.coerceIn(min, max) ?: value
        if (parsed != value) onChange(parsed)
        text = parsed.toString()
    }

    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 14.sp, color = ink, modifier = Modifier.weight(1f))
        RoundButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }, dark = dark, size = 34.dp) {
            Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = if (dark) 0.08f else 0.55f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter(Char::isDigit).take(3)
                    text = digits
                    digits.toIntOrNull()?.takeIf { it in min..max }?.let(onChange)
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
                modifier = Modifier.width(36.dp).onFocusChanged { if (!it.isFocused) commit() },
            )
            Text(" $unit", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink, style = TnumStyle)
        }
        RoundButton(onClick = { onChange((value + 1).coerceAtMost(max)) }, dark = dark, size = 34.dp) {
            Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
        }
    }
}

/** 秒级发车间隔：上行改分、下行改秒，总秒数限制 30–1800 */
@Composable
fun HeadwayStepper(label: String, sec: Int, onChange: (Int) -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    fun apply(total: Int) = onChange(total.coerceIn(30, 1800))
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = ink, modifier = Modifier.weight(1f))
            RoundButton(onClick = { apply(sec - 60) }, dark = dark, size = 30.dp) {
                Text("−", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink)
            }
            Text("${sec / 60} 分", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink, style = TnumStyle,
                modifier = Modifier.padding(horizontal = 8.dp))
            RoundButton(onClick = { apply(sec + 60) }, dark = dark, size = 30.dp) {
                Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            RoundButton(onClick = { apply(sec - 1) }, dark = dark, size = 30.dp) {
                Text("−", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = dim)
            }
            Text("%02d 秒".format(sec % 60), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink,
                style = TnumStyle, modifier = Modifier.padding(horizontal = 8.dp))
            RoundButton(onClick = { apply(sec + 1) }, dark = dark, size = 30.dp) {
                Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = dim)
            }
        }
    }
}
