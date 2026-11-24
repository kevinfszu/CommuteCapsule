@file:OptIn(ExperimentalMaterial3Api::class)

package com.busnotice.commute.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Candidate
import com.busnotice.commute.CommuteService
import com.busnotice.commute.Learn
import com.busnotice.commute.LiveState
import com.busnotice.commute.NodeLog
import com.busnotice.commute.Reverse
import com.busnotice.commute.Scheduler
import com.busnotice.commute.Trip
import com.busnotice.commute.TripStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一项设置检查：granted = 已满足；optional = 可选项（不算「还差」） */
internal data class Perm(
    val title: String, val desc: String, val granted: Boolean, val optional: Boolean = false, val action: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(resumeTick: Int, onNew: () -> Unit, onEdit: (Trip) -> Unit, onOpenSettings: () -> Unit, onOpenRecords: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dimColor = Fuse.dim(dark)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var version by remember { mutableStateOf(0) }
    val trips = remember(resumeTick, version) { TripStore.all(ctx) }
    val live by LiveState.snapshot.collectAsState()
    val demo by com.busnotice.commute.DemoPlayer.snapshot.collectAsState()
    val demoCount by com.busnotice.commute.DemoPlayer.countdown.collectAsState()
    // 演示（含倒计时）期间：首页只放虚构的行程卡、不显示当前地点，录屏不露真实信息
    val demoOn = demo != null || demoCount != null
    val adviceFlow by com.busnotice.commute.DepartAdvisor.current.collectAsState()
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    // 出发建议卡片：每 20 秒刷新倒计时；过期 / 行程开始了就自动消失
    LaunchedEffect(resumeTick) {
        while (true) {
            nowTick = System.currentTimeMillis()
            com.busnotice.commute.DepartAdvisor.load(ctx, nowTick)
            delay(20_000L)
        }
    }
    val advice = adviceFlow?.takeIf { nowTick < it.busAt + 2 * 60_000L }
    var confirmDelete by remember { mutableStateOf<Trip?>(null) }
    var confirmSwitch by remember { mutableStateOf<Trip?>(null) }
    var confirmRestart by remember { mutableStateOf<Trip?>(null) }
    var reversing by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }
    val perms = remember(resumeTick, version) { permChecks(ctx) { p -> permLauncher.launch(p) } }

    // 进程重启后内存里的看板是空的：先用本地存的那份顶上；还没有的话显示「正在恢复…」，30 秒后服务还没起来就不再显示
    LaunchedEffect(resumeTick) { LiveState.ensureRestored(ctx) }
    // 要显示「正在恢复…」的行程：内存里没看板但 run_state 有没到期的行程，或者看板太旧（5 分钟没更新，多半是存下来的旧状态）
    val pendingTrip = remember(resumeTick, live) {
        val s = live
        when {
            s == null -> LiveState.activeRunTrip(ctx)
            System.currentTimeMillis() - s.updatedAt > 5 * 60_000L -> s.tripId
            else -> null
        }
    }
    var pendingExpired by remember(resumeTick) { mutableStateOf(false) }
    LaunchedEffect(resumeTick, pendingTrip) {
        if (pendingTrip != null) {
            delay(30_000)
            if (CommuteService.instance == null) pendingExpired = true
        }
    }

    val listState = rememberLazyListState()
    // 点「测试」或行程开始后，实时看板插到列表顶部：滚回顶部让看板完整露出来，不被挤到上面
    val liveId = live?.tripId
    LaunchedEffect(liveId) { if (liveId != null) listState.animateScrollToItem(0) }
    // 结束后的节点时间轴：回到前台、刚结束一趟、记录被修正时都刷新
    val recordRev by NodeLog.revision.collectAsState()
    var lastRun by remember { mutableStateOf<NodeLog.RunRecord?>(null) }
    LaunchedEffect(resumeTick, liveId, recordRev) {
        lastRun = NodeLog.lastRecord(ctx)?.takeIf { r ->
            val real = r.nodes.count { it.src != NodeLog.Src.ESTIMATED }
            val span = (r.nodes.maxOfOrNull { it.at } ?: 0L) - (r.nodes.minOfOrNull { it.at } ?: 0L)
            real >= 2 && span >= 2 * 60_000L
        }
    }

    Box(Modifier.fillMaxSize()) {
        GradientBackground(dark)
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = ink,
            snackbarHost = { SnackbarHost(snack) { Snackbar(it, shape = RoundedCornerShape(16.dp)) } },
        ) { pad ->
            // 没有顶栏：标题放进列表第一项；列表本身从状态栏下面开始（会裁切），内容不会画到状态栏文字下面
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
                state = listState,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "title") {
                  Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("通勤胶囊", style = Type.Display, color = ink, modifier = Modifier.weight(1f))
                        RoundButton(
                            onClick = onNew, dark = dark,
                            modifier = Modifier.semantics { contentDescription = "新建行程" },
                        ) {
                            Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ink)
                        }
                        Spacer(Modifier.width(8.dp))
                        RoundButton(
                            onClick = onOpenSettings, dark = dark,
                            modifier = Modifier.semantics { contentDescription = "设置" },
                        ) {
                            Icon(Icons.Default.Settings, null, tint = ink, modifier = Modifier.size(20.dp))
                        }
                    }
                  }
                }
                val demoBoard = demo
                demoCount?.let { n ->
                    item(key = "demo-count") { PlaceChip("演示将于 $n 秒后开始，可返回桌面等待", dark) }
                }
                if (demoBoard != null) {
                    item(key = "demo-badge") { PlaceChip(com.busnotice.commute.DemoPlayer.BADGE, dark) }
                    item(key = "demo") { LiveBoard(demoBoard, com.busnotice.commute.DemoPlayer.trip, dark) }
                }
                val board = if (demoBoard != null) null else live
                if (demoOn) Unit
                else if (board != null && pendingTrip == null) {
                    item(key = "live") {
                        LiveBoard(board, trips.firstOrNull { it.id == board.tripId }, dark,
                            onStopped = { scope.launch { snack.showSnackbar("行程已结束") } },
                            onCancelled = { scope.launch { snack.showSnackbar("行程已取消，未生成记录") } })
                    }
                } else if (pendingTrip != null && !pendingExpired) {
                    // 服务 30 秒内还没起来就不再显示占位
                    val name = board?.tripName ?: trips.firstOrNull { it.id == pendingTrip }?.name ?: "通勤"
                    item(key = "live") { RestoringBoard(name, dark) }
                }
                if (!demoOn && board == null && pendingTrip == null) {
                    lastRun?.let { r -> item(key = "last-run") { RecentRunCard(r.trip, r.nodes, dark, snack, r.id, onOpenAll = onOpenRecords) } }
                }
                // 缺必需设置时放上面显眼处，全齐了就放到列表最后
                val permUrgent = perms.any { !it.granted && !it.optional }
                if (permUrgent) item(key = "perm") { PermRow(perms, dark, onOpenSettings) }
                if (!demoOn && trips.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无行程", style = MaterialTheme.typography.titleMedium, color = ink)
                        Spacer(Modifier.height(4.dp))
                        Text("新建行程并设置上车站与出发时间即可", style = MaterialTheme.typography.bodyMedium, color = dimColor)
                        Spacer(Modifier.height(12.dp))
                        Text("点右上角 + 新建第一条行程", style = Type.BodyDim, color = dimColor)
                    }
                }
                if (demoOn) items(com.busnotice.commute.DemoPlayer.showTrips, key = { "demo-" + it.id }) { t ->
                    TripCard(
                        trip = t, running = demo?.tripId == t.id, dark = dark,
                        onToggle = {}, onTest = {}, onEdit = {}, onReverse = {}, onDelete = {}, onRestart = {}, onApplyLearned = {},
                    )
                } else items(trips, key = { it.id }) { t ->
                    TripCard(
                        trip = t,
                        running = live?.tripId == t.id,
                        dark = dark,
                        onToggle = { on ->
                            val nt = t.copy(enabled = on)
                            TripStore.upsert(ctx, nt); Scheduler.schedule(ctx, nt); version++
                        },
                        onTest = {
                            // 正在跑的那趟不许被重复点击清零；跑着别的趟要先确认再切换
                            val cur = CommuteService.runningTripId()
                            when {
                                cur == t.id -> scope.launch { snack.showSnackbar("该行程正在进行中；如需重新开始，请在「⋯」菜单中选择「重新开始」") }
                                cur != null -> confirmSwitch = t
                                else -> {
                                    CommuteService.start(ctx, t.id, manual = true)
                                    scope.launch { snack.showSnackbar("已手动开始，切换到其他应用可查看胶囊") }
                                }
                            }
                        },
                        onRestart = { confirmRestart = t },
                        onEdit = { onEdit(t) },
                        onReverse = {
                            if (!reversing) {
                                reversing = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { Reverse.build(t) } }
                                    reversing = false
                                    r.onSuccess { res ->
                                        if (res.warnings.isNotEmpty()) snack.showSnackbar(res.warnings.joinToString("；"))
                                        if (res.trip.legs.isNotEmpty()) onEdit(res.trip)
                                    }.onFailure { snack.showSnackbar("生成回程失败，请检查网络后重试") }
                                }
                            }
                        },
                        onDelete = { confirmDelete = t },
                        onApplyLearned = { sg ->
                            val nt = t.copy(startMinute = sg.start, endMinute = sg.end)
                            TripStore.upsert(ctx, nt); Scheduler.schedule(ctx, nt); version++
                            scope.launch { snack.showSnackbar("时间范围已调整为 ${hhmm(sg.start)}–${hhmm(sg.end)}") }
                        },
                    )
                }
                // 出发建议是辅助信息，放在行程列表之后，不占主位
                if (!demoOn && board == null && pendingTrip == null && advice != null) {
                    item(key = "depart") { DepartBoard(advice!!, nowTick, dark) }
                }
            }
        }
    }

    confirmSwitch?.let { t ->
        val curName = CommuteService.runningTripId()?.let { id -> trips.firstOrNull { it.id == id }?.name } ?: "其他行程"
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
            title = { Text("「$curName」正在进行") },
            text = { Text("将结束该行程并切换至「${t.name}」，当前进度将丢失。") },
            confirmButton = {
                DialogButton("切换", primary = true, onClick = {
                    CommuteService.start(ctx, t.id, manual = true, restart = true); confirmSwitch = null
                    scope.launch { snack.showSnackbar("已切换至「${t.name}」") }
                })
            },
            dismissButton = { DialogButton("取消", onClick = { confirmSwitch = null }) },
        )
    }

    confirmRestart?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmRestart = null },
            shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
            title = { Text("重新开始「${t.name}」？") },
            text = { Text("将根据当前位置重新判断所在阶段。") },
            confirmButton = {
                DialogButton("重新开始", primary = true, onClick = {
                    CommuteService.start(ctx, t.id, manual = true, restart = true); confirmRestart = null
                })
            },
            dismissButton = { DialogButton("取消", onClick = { confirmRestart = null }) },
        )
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
            title = { Text("删除「${t.name}」？") },
            text = { Text("该行程的定时提醒会一并取消，学习到的节点时间会保留。") },
            confirmButton = {
                DialogButton("删除", danger = true, onClick = {
                    Scheduler.cancel(ctx, t.id); TripStore.delete(ctx, t.id); confirmDelete = null; version++
                })
            },
            dismissButton = { DialogButton("取消", onClick = { confirmDelete = null }) },
        )
    }
}

/** 标题下的小玻璃胶囊：在家 / 在公司 / 路上 / 学习中 */
@Composable
private fun PlaceChip(text: String, dark: Boolean) {
    Row(
        Modifier.padding(start = 8.dp, bottom = 2.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = if (dark) 0.10f else 0.55f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Fuse.BusGreen))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.Caption.copy(fontFeatureSettings = "tnum"), color = Fuse.ink(dark))
    }
}

/** 设置检查一行：「设置检查  N / M ›」，缺必需项时显示琥珀色提示 */
@Composable
private fun PermRow(perms: List<Perm>, dark: Boolean, onOpen: () -> Unit) {
    val ink = Fuse.ink(dark)
    val req = perms.filter { !it.optional }
    val opt = perms.filter { it.optional }
    val ok = req.count { it.granted }
    val missing = req.filter { !it.granted }
    Row(
        Modifier.fillMaxWidth().glass(dark).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("权限与设置", style = Type.Body, color = ink)
        Spacer(Modifier.width(12.dp))
        if (missing.isNotEmpty()) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Fuse.Amber))
            Spacer(Modifier.width(6.dp))
            Text("还差：${missing.first().title}", style = Type.Caption, color = Fuse.Amber,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            buildString {
                append("必需 $ok/${req.size}")
                if (opt.isNotEmpty()) append(" · 可选 ${opt.count { it.granted }}/${opt.size}")
            } + " ›",
            style = Type.Caption.copy(fontFeatureSettings = "tnum"), color = ink,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PermItem(p: Perm) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.bodyLarge)
            Text(p.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (p.granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, null, tint = Fuse.BusGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("已开启", style = MaterialTheme.typography.labelLarge, color = Fuse.BusGreen)
            }
        } else {
            PillButton(onClick = p.action) { Text("前往开启") }
        }
    }
}

@Composable
private fun TripCard(
    trip: Trip, running: Boolean, dark: Boolean,
    onToggle: (Boolean) -> Unit, onTest: () -> Unit, onEdit: () -> Unit, onReverse: () -> Unit, onDelete: () -> Unit,
    onRestart: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onApplyLearned: (Learn.Suggestion) -> Unit,
) {
    val ctx = LocalContext.current
    val ink = Fuse.ink(dark)
    val dimColor = Fuse.dim(dark)
    val fade = if (trip.enabled) 1f else 0.55f
    var more by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().glass(dark).clickable(onClick = onEdit).padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(trip.name, style = Type.ItemTitle, color = ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(fade))
                val timeText =
                    if (trip.startMinute == trip.endMinute) "${hhmm(trip.startMinute)} 出发"
                    else "${hhmm(trip.startMinute)} 出发，最晚 ${hhmm(trip.endMinute)}"
                Text("${trip.daysText()} · $timeText", style = Type.BodyDim, color = dimColor,
                    modifier = Modifier.padding(top = 3.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(hhmm(trip.startMinute), style = Type.MetricLarge, color = ink,
                    modifier = Modifier.alpha(fade))
                Switch(
                    checked = trip.enabled, onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = "启用行程「${trip.name}」" },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = if (dark) Fuse.Yellow else Fuse.Night,
                        checkedThumbColor = if (dark) Fuse.Night else Color.White,
                        checkedBorderColor = Color.Transparent,
                        uncheckedTrackColor = Color(0x667F7F8C),
                        uncheckedThumbColor = Color.White,
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
        }
        RouteRow(trip, dark, Modifier.padding(top = 6.dp).alpha(fade))
        // 下次触发的说明可能较长：单独占一行（最多两行），不和「测试」「⋯」挤在一起被截断
        val nextText = if (!running && trip.enabled) Scheduler.nextTriggerText(trip, ctx) else null
        if (nextText != null) {
            Text(nextText, style = Type.BodyDim, color = dimColor, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp, end = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (nextText != null) 0.dp else 6.dp)) {
            Box(Modifier.weight(1f)) {
                when {
                    running -> Text(
                        "进行中", style = Type.Caption,
                        color = if (dark) Color(0xFF7FE0A6) else Color(0xFF167A40),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Fuse.BusGreen.copy(alpha = if (dark) 0.25f else 0.14f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    nextText != null -> {}
                    else -> Text("已停用", style = Type.BodyDim, color = dimColor)
                }
            }
            TextButton(onClick = onTest, modifier = Modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                if (!running) {
                    Icon(Icons.Default.PlayArrow, null, tint = ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    if (running) "行程进行中" else "开始",
                    style = Type.BodyDim, color = if (running) dimColor else ink,
                    fontWeight = if (running) FontWeight.Normal else FontWeight.Bold,
                )
            }
            Box {
                TextButton(
                    onClick = { more = true }, modifier = Modifier.height(48.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Text("⋯", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = dimColor,
                        modifier = Modifier.semantics { contentDescription = "更多" })
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }, shape = MenuShape, containerColor = Fuse.surface(dark)) {
                    if (running) DropdownMenuItem(
                        text = { Text("重新开始") },
                        leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp), tint = dimColor) },
                        onClick = { more = false; onRestart() },
                    )
                    DropdownMenuItem(
                        text = { Text("生成回程") },
                        leadingIcon = { Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(18.dp), tint = dimColor) },
                        onClick = { more = false; onReverse() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = Fuse.MetroRed) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = Fuse.MetroRed) },
                        onClick = { more = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** 线路小牌：步行 — 各段线路牌 — 步行，段内多个可选线路用「/」隔开 */
@Composable
private fun RouteRow(trip: Trip, dark: Boolean, modifier: Modifier = Modifier) {
    val ink = Fuse.ink(dark)
    val legs = trip.legs.filter { it.candidates.isNotEmpty() }
    if (legs.isEmpty()) {
        Text("尚未设置线路", style = Type.BodyDim, color = Fuse.dim(dark), modifier = modifier.padding(vertical = 4.dp))
        return
    }
    @Composable
    fun Dash() = Text("—", style = Type.BodyDim, color = ink.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = 6.dp))

    // 步行小牌：开头走到第一段上车点、结尾从最后下车走到目的地，两头对称
    @Composable
    fun Walk() = Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.07f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) { Text("步行", style = Type.Caption, color = ink) }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Walk()
        legs.take(4).forEachIndexed { li, leg ->
            if (li > 0 && leg.transferWalkMin > 0) {
                Dash(); Walk(); Dash()
            } else {
                Dash()
            }
            leg.candidates.take(3).forEachIndexed { i, c: Candidate ->
                if (i > 0) Text("/", style = Type.Caption, color = ink.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = 3.dp))
                LineBadge(shortLine(c.name.ifEmpty { c.label() }), lineColor(c))
            }
        }
        if (legs.size > 4) { Dash(); Text("…", style = Type.BodyDim, color = ink) }
        Dash()
        Walk()
    }
}

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** 全部设置检查（含已满足项），申请方式与引导流程相同 */
internal fun permChecks(ctx: Context, request: (Array<String>) -> Unit): List<Perm> {
    val out = mutableListOf<Perm>()
    val pkg = Uri.parse("package:${ctx.packageName}")
    fun open(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recover { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    out += Perm("精确定位", "用于估算步行至车站的时长", fine) {
        request(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    out += Perm("后台定位（始终允许）", "用于后台唤醒时获取位置", fine && granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
        // 系统要求先有前台定位才能申请后台定位
        if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION))
            request(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else request(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }
    out += Perm("身体活动（计步）", "用于识别下车与步行状态", granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)) {
        request(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
    }
    val notif = granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    out += Perm("通知", "用于显示实时胶囊", notif) { request(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
    if (Build.VERSION.SDK_INT >= 36) {
        val promoted = notif && ctx.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        out += Perm("实时通知（流体云）", "用于在状态栏显示胶囊", promoted) {
            if (!granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            else open(Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS").putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
    }
    out += Perm("精确闹钟", "用于准时预热定位", ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
        open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
    }
    out += Perm("电池优化限制", "请在「设置 → 电池」中允许后台活动与自启动",
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) {
        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
    }
    out += Perm("通知使用权", "读取交通卡刷卡与系统到站提醒，提升进出站判断精度；仅读取出行类通知",
        com.busnotice.commute.TransitNotif.enabled(ctx), optional = true) {
        open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    out += Perm("出行识别", "无障碍服务：行程中读取系统出行胶囊与交通卡弹窗文字，仅保存于本机",
        com.busnotice.commute.TransitA11y.enabled(ctx), optional = true) {
        open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    return out
}

/** 下班出发建议：「约 8 分钟后出发」+ 几点走、接哪班车；错过了会写「错过 18:47 那班」 */
@Composable
private fun DepartBoard(a: com.busnotice.commute.DepartAdvisor.Advice, now: Long, dark: Boolean) {
    val ink = Fuse.ink(dark)
    val dimColor = Fuse.dim(dark)
    val D = com.busnotice.commute.DepartAdvisor
    Column(Modifier.fillMaxWidth().glass(dark).padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (a.missedLeaveAt > 0) Fuse.Amber else Fuse.BusGreen))
            Spacer(Modifier.width(8.dp))
            Text("${a.tripName} · 出发建议", style = Type.BodyDim, color = dimColor)
            Spacer(Modifier.weight(1f))
            Text("${D.hm(a.leaveAt)} 出发", style = Type.BodyDim.copy(fontFeatureSettings = "tnum"), color = dimColor)
        }
        Spacer(Modifier.height(8.dp))
        Text(D.headline(a, now), style = Type.PageTitle, color = ink)
        Spacer(Modifier.height(4.dp))
        Text(D.detail(a), style = Type.BodyDim.copy(fontFeatureSettings = "tnum"), color = dimColor)
    }
}
