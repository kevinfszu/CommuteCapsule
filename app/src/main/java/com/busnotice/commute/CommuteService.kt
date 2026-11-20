package com.busnotice.commute

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** App 界面读取的实时状态 */
data class LiveSnapshot(
    val tripId: String,
    val tripName: String,
    val legIndex: Int,
    val legCount: Int,
    val legTitle: String,
    val headline: String,
    val detail: String,
    val mode: Decision.Mode?,
    val progress: Int?,
    val countdownTo: Long?,
    val riding: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    // 仅供界面显示：车上已过几站 / 上车到下车共几站（没有站点数据时为 null）
    val stopsPassed: Int? = null,
    val stopsTotal: Int? = null,
    // 仅供界面显示：当前（刚过）站、上车站、下车站名（没有站点数据时为 null）
    val curStop: String? = null,
    val fromStop: String? = null,
    val toStop: String? = null,
)

/**
 * 界面读的实时状态。内存里一份（StateFlow），另外存一份到本地（SharedPreferences）：
 * 进程被杀后重开 App、服务还没跑第一轮刷新时，首页用存下的这份先把看板显示出来。
 * 只是显示用的缓存，不参与任何判断。
 */
object LiveState {
    val snapshot = MutableStateFlow<LiveSnapshot?>(null)

    private const val PREFS = "live_snapshot"
    private const val SAVE_EVERY_MS = 15_000L
    private var lastSaved: LiveSnapshot? = null
    private var lastSavedAt = 0L

    /** 服务每次刷新时调用：更新内存，按需存盘（内容变了才写；内容没变最多 15 秒写一次） */
    fun publish(ctx: Context, s: LiveSnapshot, deadline: Long) {
        snapshot.value = s
        val now = System.currentTimeMillis()
        val same = lastSaved?.copy(updatedAt = 0L) == s.copy(updatedAt = 0L)
        if (same && now - lastSavedAt < SAVE_EVERY_MS) return
        lastSaved = s; lastSavedAt = now
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("snap", toJson(s).toString()).putLong("deadline", deadline).apply()
        }
    }

    /** 行程结束：删掉存下的那份 */
    fun clearPersisted(ctx: Context) {
        lastSaved = null; lastSavedAt = 0L
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
    }

    /** 中断恢复记录（run_state）里还没到期的行程：tripId；没有返回 null */
    fun activeRunTrip(ctx: Context): String? {
        val p = ctx.getSharedPreferences("run_state", Context.MODE_PRIVATE)
        val id = p.getString("trip", null) ?: return null
        return id.takeIf { p.getLong("deadline", 0) > System.currentTimeMillis() }
    }

    /** 读存下的那份：run_state 里同一趟行程还没到期才算数（tripId 不对就返回 null） */
    fun loadPersisted(ctx: Context, tripId: String? = activeRunTrip(ctx)): LiveSnapshot? {
        if (tripId == null) return null
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getLong("deadline", 0) <= System.currentTimeMillis()) return null
        val s = runCatching { fromJson(org.json.JSONObject(p.getString("snap", null) ?: return null)) }.getOrNull()
        return s?.takeIf { it.tripId == tripId }
    }

    /** 内存里是空的（进程刚重启）：用存下的那份补上；已经有就不动 */
    fun ensureRestored(ctx: Context, tripId: String? = activeRunTrip(ctx)) {
        if (snapshot.value != null) return
        val s = loadPersisted(ctx, tripId) ?: return
        snapshot.compareAndSet(null, s)
    }

    private fun toJson(s: LiveSnapshot) = org.json.JSONObject()
        .put("tripId", s.tripId).put("tripName", s.tripName).put("legIndex", s.legIndex).put("legCount", s.legCount)
        .put("legTitle", s.legTitle).put("headline", s.headline).put("detail", s.detail)
        .put("mode", s.mode?.name ?: "").put("progress", s.progress ?: -1).put("countdownTo", s.countdownTo ?: -1L)
        .put("riding", s.riding).put("updatedAt", s.updatedAt)
        .put("stopsPassed", s.stopsPassed ?: -1).put("stopsTotal", s.stopsTotal ?: -1)
        .put("curStop", s.curStop ?: "").put("fromStop", s.fromStop ?: "").put("toStop", s.toStop ?: "")

    private fun fromJson(o: org.json.JSONObject) = LiveSnapshot(
        tripId = o.getString("tripId"), tripName = o.optString("tripName"),
        legIndex = o.optInt("legIndex"), legCount = o.optInt("legCount", 1), legTitle = o.optString("legTitle"),
        headline = o.optString("headline"), detail = o.optString("detail"),
        mode = o.optString("mode").takeIf { it.isNotEmpty() }?.let { m -> runCatching { Decision.Mode.valueOf(m) }.getOrNull() },
        progress = o.optInt("progress", -1).takeIf { it >= 0 },
        countdownTo = o.optLong("countdownTo", -1L).takeIf { it >= 0 },
        riding = o.optBoolean("riding"), updatedAt = o.optLong("updatedAt", 0L),
        stopsPassed = o.optInt("stopsPassed", -1).takeIf { it >= 0 },
        stopsTotal = o.optInt("stopsTotal", -1).takeIf { it >= 0 },
        curStop = o.optString("curStop").ifEmpty { null },
        fromStop = o.optString("fromStop").ifEmpty { null },
        toStop = o.optString("toStop").ifEmpty { null },
    )
}

/**
 * 只在通勤时间窗内运行的定位前台服务。
 * 一条行程可以有多段：当前段没上车 → 按步行时间算出发；上车后 → 预估到换乘点能赶上下一段哪班车。
 * 省电：离站远/还早时低频定位、慢轮询；快出发或在站台时才开高频 GPS。
 */
class CommuteService : Service() {

    companion object {
        private const val TAG = "CommuteService"
        /** 预计这么久内到下一站就先显示「即将到」（基站采样约 30 秒 + 停站） */
        private const val METRO_SOON_MS = 40_000L
        private const val EXTRA_TRIP_ID = "tripId"
        private const val EXTRA_MANUAL = "manual"
        private const val EXTRA_RESTART = "restart"
        private const val MANUAL_RUN_MS = 30 * 60_000L
        private const val UNDO_MS = 2 * 60_000L
        /** 手点「已上车」后 2 分钟内：按钮变「撤销」，再点是撤回不是跳下一段 */
        val undoUntil = MutableStateFlow(0L)
        fun undoActive() = undoUntil.value > System.currentTimeMillis()
        private const val NEAR_STOP_M = 120.0
        @Volatile var instance: CommuteService? = null

        /**
         * restart = 用户明确要「重新开始 / 切换到这一趟」：才允许清掉正在跑的进度。
         * 不带 restart 时：同一趟正在跑 → 不动；别的趟正在跑 → 自动启动直接忽略，手动启动交给界面先确认。
         */
        fun start(ctx: Context, tripId: String, manual: Boolean, fresh: Boolean = false, restart: Boolean = false) {
            val i = Intent(ctx, CommuteService::class.java)
                .putExtra(EXTRA_TRIP_ID, tripId)
                .putExtra(EXTRA_MANUAL, manual)
                .putExtra("fresh", fresh)
                .putExtra(EXTRA_RESTART, restart)
            ctx.startForegroundService(i)
            Scheduler.markRan(ctx, tripId)
        }

        /** 正在跑的行程 id（服务活着且行程没结束）；没有返回 null */
        fun runningTripId(): String? = instance?.runningId()

        /** 有没结束的行程但服务不在（被系统杀了）：重新拉起，接着原进度跑 */
        fun resumeIfNeeded(ctx: Context) {
            if (instance != null) return
            val p = ctx.getSharedPreferences("run_state", Context.MODE_PRIVATE)
            val id = p.getString("trip", null) ?: return
            if (p.getLong("deadline", 0) <= System.currentTimeMillis()) return
            runCatching { start(ctx, id, p.getBoolean("manual", false)) }
                .onSuccess { Diag.mark(ctx, "胶囊不见了，已重新拉起") }
        }

        /** 用户主动结束：清掉进度，不再恢复 */
        fun stop(ctx: Context) {
            val svc = instance
            if (svc != null) { svc.userFinish(); return }
            ctx.getSharedPreferences("run_state", Context.MODE_PRIVATE).edit().clear().apply()
            LiveState.clearPersisted(ctx); LiveState.snapshot.value = null
            LiveNotifier.clear(ctx)
            ctx.stopService(Intent(ctx, CommuteService::class.java))
        }
    }

    private enum class Power { OFF, LOW, HIGH }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var lm: LocationManager? = null
    private var power: Power? = null
    @Volatile private var best: Location? = null

    private lateinit var trip: Trip
    private var legIdx = 0              // 正在坐或正在等的这一段
    private var boardedAt = 0L          // 当前段上车时间
    private var nearSince = 0L          // 第一次走到当前段上车点附近的时间
    private var fastFixes = 0
    private var busFastLogged = 0L
    private var alertedKey: String? = null
    private val maxStops = HashMap<String, Int>()
    private val maxEta = HashMap<String, Int>()
    private var manualRun = false
    private var riding = false          // 已上当前段车，正在去本段下车站
    private var arriveAlertedAt = 0L
    private var arriveAgainAt = 0L      // 到站前的二次确认
    private var overshootAlerted = false
    private var alightLeg = -1          // 已经下车的那一段
    @Volatile private var alight: Pair<Double, Double>? = null
    private val legAlight = java.util.concurrent.ConcurrentHashMap<Int, Pair<Double, Double>>() // 每段下车站坐标
    private val metroDirs = java.util.concurrent.ConcurrentHashMap<String, List<SubwayDir>>()
    private val routes = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Double, Double>>>() // lineId → 沿线站点
    private val speeds = ArrayDeque<Float>()
    private val routeHits = ArrayDeque<Boolean>()
    @Volatile private var lastGpsFix: Location? = null
    // 最后一段坐公交：这条线上车站..下车站的站点（内存缓存，恢复后重新取）和沿线进度
    @Volatile private var busRoute: List<StopPoint>? = null
    @Volatile private var busRouteLine = ""
    private var busRouteTries = 0
    private var busRouteTriedAt = 0L
    private var busSeg = -1            // 当前投到的段
    private var busMaxSeg = -1         // 到过的最远段：不许往回退超过 1 站
    private var busSnap: BusRide.Snap? = null
    private var busSnapAt = 0L
    private var busEtaAt = 0L          // 按沿线距离估的到站时刻

    private val listener = LocationListener { loc -> onLocation(loc) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent 为空 = 被系统杀掉后自动拉起：接着跑中断前那趟
        val id = intent?.getStringExtra(EXTRA_TRIP_ID) ?: savedRunTrip()
        val t = id?.let { TripStore.get(this, it) }
        LiveNotifier.ensureChannels(this)
        // 是什么拉起的这一趟（闹钟 / 离开出发地 / 刷卡 / 围栏…）：取启动前最后一条诊断，写进行程记录，方便查误启动
        val why = Diag.last(this)
        val manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false
        // 已经有一趟在跑：不许被重复点击 / 自动启动悄悄覆盖掉进度
        val curId = runningId()
        if (intent != null && curId != null && !intent.getBooleanExtra(EXTRA_RESTART, false)) {
            val same = curId == id
            TraceLog.event("start_ignored", "same" to same, "manual" to manual, "fresh" to intent.getBooleanExtra("fresh", false))
            Diag.mark(this, if (same) "行程已在进行中，忽略重复启动" else "另一趟行程进行中，忽略这次启动")
            if (same) LiveNotifier.unhide(this)
            return START_STICKY
        }
        try {
            startForeground(LiveNotifier.LIVE_ID, LiveNotifier.warming(this, t?.name ?: "通勤"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // 没有后台定位权限时，系统不允许从后台启动定位前台服务
            Log.e(TAG, "startForeground 失败", e)
            Diag.mark(this, "定位服务被拦截（后台定位未设为始终允许？）")
            t?.let { LiveNotifier.fallback(this, it) }
            stopSelf(); return START_NOT_STICKY
        }
        if (t == null || t.legs.isEmpty()) { finish(); return START_NOT_STICKY }

        instance = this
        LiveNotifier.hidden = false
        if (intent == null) Diag.mark(this, "服务被系统杀掉后自动重启")
        else if (!manual) Diag.mark(this, "已自动启动")
        val restart = intent?.getBooleanExtra(EXTRA_RESTART, false) == true
        trip = t
        finishing = false
        restored = false
        startedAt = System.currentTimeMillis()
        // 用户手动「重新开始 / 切换」时，往往已在途中（可能位于地下、无定位信号），
        // 允许用最近 10 分钟内的定位来判断现在在第几段，而不是一律从第一段的上车站算起
        trustFixSince = startedAt - if (restart) 10 * 60_000L else 30_000L
        runCatching { TraceLog.start(this, t.id, t.name) }
        TraceLog.event("run", "manual" to manual, "restart" to (intent == null), "userRestart" to restart, "why" to why)
        Health.logSystem(this); Health.logExits(this)
        if (!screenRegistered) runCatching {
            registerReceiver(screenRx, Health.screenFilter(), RECEIVER_NOT_EXPORTED); screenRegistered = true
        }
        TraceLog.event("dev", "what" to (if (Health.screenOn(this)) "screen_on" else "screen_off"), "plugged" to Health.plugged(this))
        TraceLog.onCell = { snap -> scope.launch(Dispatchers.Main) { runCatching { onCell(snap) }.onFailure { Log.w(TAG, "基站处理失败", it) } } }
        walkFixes.clear(); firstWalkDone = false; userRestarted = restart
        metroGate.reset(); cellExitAt = 0L; lastUgAt = 0L; surfaceSince = 0L; goodGpsAt = 0L; lastLiveKey = ""; if (metroRideOn) fpRec.finish(); metroRideOn = false; busMovingOn = false; tacLearner = null
        stationAt = 0L; metroRideSec.clear(); metroRoutes.clear(); legAlight.clear(); alightAt = 0L; fenceFixes.clear(); stillSince = 0L; lastFastAt = 0L; rideLearned = false; stopAlerted = ""
        stationByTap = false; arriveAnchorAt = 0L; metroAnchorIdx = -1; metroAnchorAt = 0L
        deferredTapAt = 0L; deferredTapKind = null
        boardSnap = null; undoUntil.value = 0L; pendingLearnLeg = -1
        metroExitSeen = false; metroExitAt = 0L
        joinLeg = -1; joinSkip = 0; joinScale = 1.0; joinOrigStop = ""; joinStop = ""; joinCandIdx = -1; joinCandAt = 0L; joinCandFixT = 0L; pendingJoin = null
        placeStart = null; arrived = false; lastPlace = null; placeSince = 0L; startLabel = null; busHitTimes.clear(); tail = false; tailFixes.clear(); startVoted = false
        legIdx = 0; boardedAt = 0L; nearSince = 0L; fastFixes = 0; alertedKey = null; planDone = false; planAlerted = ""
        maxStops.clear(); maxEta.clear()
        manualRun = manual; pendingBoardAt = 0L; pendingBoardLoc = null; boardCommitted = false; riding = false
        arriveAlertedAt = 0L; arriveAgainAt = 0L; overshootAlerted = false; alightLeg = -1
        alightAlertAt.clear()
        resetAlightGuard()
        speeds.clear(); routeHits.clear(); lastGpsFix = null
        resetBusRide(); busRoute = null; busRouteLine = ""
        power = null; highSince = 0L; coolUntil = 0L
        setPower(Power.HIGH) // 预热：先拿到一个准的位置
        holdCpu(true)
        scope.launch(Dispatchers.IO) { prefetchMetroStops(t); prefetch(t); prefetchLastMetroByName(t) }
        loop?.cancel()
        // 围栏拉起的是一趟全新的行程：不接中断前的进度
        val fresh = intent?.getBooleanExtra("fresh", false) == true || restart
        geoStart = intent?.getBooleanExtra("fresh", false) == true && !restart && !manual
        if (fresh) clearRun()
        val resumedDl = if (fresh) null else restoreRun(t)
        // 节点时间轴：新行程开一份；接着中断前那趟跑就沿用原来的记录
        if (resumedDl == null) {
            nodeStartedAt = System.currentTimeMillis()
            NodeLog.begin(this, t.id, nodeStartedAt)
            stampNode(NodeLog.Kind.DEPART, 0, startedAt, if (manual) NodeLog.Src.MANUAL else NodeLog.Src.AUTO)
        } else {
            nodeStartedAt = runPrefs().getLong("nodeStart", 0L)
            NodeLog.loadActive(this)
        }
        syncNodeCurrent()
        // 看板缓存：接着中断前那趟跑就先用存下的看板顶上（第一轮刷新前首页不空着）；新的一趟就删掉旧缓存
        if (resumedDl != null) LiveState.ensureRestored(this, t.id) else LiveState.clearPersisted(this)
        loop = scope.launch { runLoop(resumedDl ?: if (fresh) freshDeadline(t) else deadline(t, manual)) }
        // 由刷卡弹窗拉起的新行程：把那次刷卡（3 分钟内）补交给行程，直接按进站 / 上车处理
        if (resumedDl == null) TransitSignal.recent(this, 3 * 60_000L)?.let { (k, txt, at) ->
            if (k == TransitSignal.Kind.ENTER || k == TransitSignal.Kind.TAP) onTransitSignal(k, txt, at)
        }
        return START_STICKY
    }

    /** 预取的站数估算只对当前行程有效（恢复其他行程时不串用） */
    private fun metroSecOf(t: Trip, i: Int): Int? = if (t.id == trip.id) metroRideSec[i] else null

    private fun freshDeadline(t: Trip): Long =
        System.currentTimeMillis() +
            (t.legs.indices.sumOf { Learn.ride(this, t, it, metroSecOf(t, it)).minutes + transferWalkMin(t, it) } + 50) * 60_000L

    private fun deadline(t: Trip, manual: Boolean): Long {
        val now = System.currentTimeMillis()
        fun rideAndWalk(t: Trip, i: Int) = Learn.ride(this, t, i, metroSecOf(t, i)).minutes + transferWalkMin(t, i)
        val rideTotal = t.legs.indices.sumOf { rideAndWalk(t, it) } + 20
        if (manual) return now + maxOf(MANUAL_RUN_MS, rideTotal * 60_000L)
        // 「离开某地才弹」：启动时刻不固定，从现在起留足整段路程
        if (t.leaveMode()) return now + (rideTotal + 30) * 60_000L
        val end = LocalDate.now().atStartOfDay().plusMinutes(t.endMinute.toLong() + 5)
        // 多段行程：给后面几段留出乘车时间
        val extra = (0 until (t.legs.size - 1)).sumOf { Learn.ride(this, t, it, metroSecOf(t, it)).minutes + 5 }
        return end.plusMinutes(extra.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** 这段大概坐多久：实测中位数 > 手填 > 地铁按站数估算，统一走 Learn.ride */
    private fun rideMs(i: Int): Long {
        val scale = if (i == joinLeg) joinScale else 1.0
        return Learn.ride(this, trip, i, metroRideSec[i], scale).minutes * 60_000L
    }

    /** 地铁段按站数估的乘车秒数（每站约 2 分 20 秒，含停站） */
    private val metroRideSec = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val SEC_PER_STOP = 140

    /** 每段地铁途经的站（站名 + 坐标），拿来显示「下一站」 */
    private val metroRoutes = java.util.concurrent.ConcurrentHashMap<Int, MetroRoute>()

    /**
     * 最后一段是地铁、下车站只填了名字没有坐标时（编辑页改站名会清掉坐标）：
     * prefetch() 已按站名查到 alight 坐标，用它补查途经站，显示「还有 N 站」、按站数估到站。
     */
    private fun prefetchLastMetroByName(t: Trip) {
        val i = t.legs.size - 1
        val leg = t.legs.getOrNull(i) ?: return
        if (!leg.isMetroOnly() || metroRoutes[i] != null) return
        val c = leg.candidates.firstOrNull { it.isMetro() } ?: return
        val (la, ln) = alight ?: return
        val name = c.alightName.ifEmpty { "下车站" }
        MetroRoutes.get(this, c, name, la, ln)?.let { r ->
            metroRoutes[i] = r
            metroRideSec[i] = r.durSec
            TraceLog.event("metro_route", "leg" to i, "hops" to r.hops, "by" to "name")
        }
    }

    private fun prefetchMetroStops(t: Trip) {
        t.legs.forEachIndexed { i, leg ->
            if (!leg.isMetroOnly()) return@forEachIndexed
            val c = leg.candidates.firstOrNull { it.isMetro() } ?: return@forEachIndexed
            MetroRoutes.target(t, i)?.let { (name, la, ln) ->
                MetroRoutes.get(this, c, name, la, ln)?.let { r ->
                    metroRoutes[i] = r
                    metroRideSec[i] = r.durSec
                    Log.i(TAG, "第 ${i + 1} 段地铁 ${r.hops} 站，约 ${r.durSec / 60} 分钟")
                }
            }
            if (i >= t.legs.size - 1) return@forEachIndexed
            val to = t.legs[i + 1].candidates.firstOrNull() ?: return@forEachIndexed
            runCatching {
                val dirs = Transit.subwayDirs(c.lat, c.lng, c.stopName, c.regionId).filter { Transit.sameLine(c.name, it) }
                metroDirs.putIfAbsent("${c.stopName}|${c.name}", dirs)
                val use = dirs.filter { c.metroDir.isEmpty() || it.dest == c.metroDir }
                Transit.metroStops(use, to.lat, to.lng, c.regionId)
            }.getOrNull()?.let { n ->
                if (metroRideSec.putIfAbsent(i, n * SEC_PER_STOP + 60) == null) Log.i(TAG, "第 ${i + 1} 段地铁约 $n 站")
            }
        }
    }

    /** 进站时刻之后（留 1 分钟走到站台），第一班车几点到；拿不到班次返回 null */
    private fun nextTrainAfter(leg: Leg, atMillis: Long): Long? {
        val c = leg.candidates.firstOrNull { it.isMetro() } ?: return null
        val dirs = metroDirs[dirKey(c)]?.filter { c.metroDir.isEmpty() || it.dest == c.metroDir } ?: return null
        val at = java.time.Instant.ofEpochMilli(atMillis + 60_000).atZone(ZoneId.systemDefault())
        val sec = at.toLocalTime().toSecondOfDay()
        val weekday = at.dayOfWeek.value <= 5
        val bands = c.bandsFor(!weekday)
        val peak = Decision.isPeakAt(sec / 60, weekday, bands)
        val off = if (c.metroDir.isNotEmpty()) Learn.metroOffsetSec(this, Learn.metroKey(c, peak)) else 0
        val eta = dirs.mapNotNull { d ->
            Decision.metroSchedule(sec - off, d.firstMin, d.lastMin, weekday, bands, 1).firstOrNull()
        }.minOrNull() ?: return null
        return atMillis + 60_000 + eta * 1000L
    }

    private var startedAt = 0L
    private var stationAt = 0L     // 推断进站的时刻
    private var alightAt = 0L      // 推断下了上一段地铁的时刻
    // ---------- 节点时间轴 / 防坐过站 ----------
    private var nodeStartedAt = 0L          // NodeLog 的行程键（不受 startedAt 恢复逻辑影响）
    private var alightAckedAt = 0L          // 用户点「我已下车」
    private var alightStage = AlightGuard.Stage.NONE
    private var alightStageAt = 0L
    private var alightAlarmCount = 0
    private var alightAlarmNextAt = 0L
    private var announceAtTarget = false    // 播报/文字已确认到达目标站
    private val alightAlertAt = HashMap<Int, Long>()   // 每段首次响铃/强提醒的时刻（算反应时长）

    private fun stampNode(kind: NodeLog.Kind, leg: Int, at: Long, src: NodeLog.Src) {
        if (!::trip.isInitialized || nodeStartedAt <= 0L || at <= 0L) return
        NodeLog.stamp(this, trip.id, nodeStartedAt, kind, leg, at, src)
    }

    /** 看板节点条当前建议打点的节点 */
    private fun syncNodeCurrent() {
        if (!::trip.isInitialized || finishing) { NodeLog.current.value = null; return }
        NodeLog.current.value = when {
            tail -> NodeLog.NodeRef(NodeLog.Kind.ARRIVE, 0)
            riding -> NodeLog.NodeRef(NodeLog.Kind.ALIGHT, legIdx)
            legIdx !in trip.legs.indices -> NodeLog.NodeRef(NodeLog.Kind.DEPART, 0)
            trip.legs[legIdx].isMetroOnly() && stationAt == 0L -> NodeLog.NodeRef(NodeLog.Kind.ENTER, legIdx)
            else -> NodeLog.NodeRef(NodeLog.Kind.BOARD, legIdx)
        }
    }

    /** 换乘步行秒数：学到的优先，样本不足用手填分钟 */
    private fun transferWalkSecOf(i: Int): Int {
        val leg = trip.legs.getOrNull(i) ?: return 180
        return Learn.transferWalkSec(this, trip.id, i) ?: (leg.transferWalkMin * 60)
    }

    /** 别的行程（出发建议对比用）没有自己的样本，只读本行程学到的 */
    private fun transferWalkMin(t: Trip, i: Int): Int =
        (if (t.id == trip.id) Learn.transferWalkSec(this, t.id, i)?.let { (it + 59) / 60 } else null)
            ?: t.legs[i].transferWalkMin

    /**
     * 过闸之后多久上车：有相位按排班推算，没有相位用 w + h/2；
     * 实测等待由 [BoardEst] 按逆方差加权修正。两者都没有时退回用户手填值。
     */
    private fun boardOffsetSec(i: Int, enter: Long): Int {
        val leg = trip.legs.getOrNull(i) ?: return trip.boardEstimateSec
        val c = leg.candidates.firstOrNull { it.isMetro() } ?: return trip.boardEstimateSec
        val at = java.time.Instant.ofEpochMilli(enter).atZone(java.time.ZoneId.systemDefault())
        val sec = at.toLocalTime().toSecondOfDay()
        val weekday = at.dayOfWeek.value <= 5
        val bands = c.bandsFor(!weekday)
        val peak = Decision.isPeakAt(sec / 60, weekday, bands)
        val samples = Learn.waitSamples(this, trip.id, i)
        val base = BoardEst.Input(
            enter = enter,
            headwaySec = Decision.headwayAt(sec / 60, weekday, bands),
            offsetSec = Learn.metroOffsetSec(this, Learn.metroKey(c, peak)),
            waitSamples = samples,
            weekday = weekday,
            bands = bands,
            walkSec = BoardEst.walkFromWait(samples, Decision.headwayAt(sec / 60, weekday, bands))
                ?: BoardEst.WALK_SEC,
        )
        // 同一段有多条线时，取能赶上的最早一班（与 nextTrainAfter 一致）
        val dirs = metroDirs[dirKey(c)]?.filter { c.metroDir.isEmpty() || it.dest == c.metroDir }.orEmpty()
        val r = dirs.mapNotNull { d ->
            BoardEst.estimate(base.copy(firstMin = d.firstMin, lastMin = d.lastMin)).takeIf { it.phased }
        }.minByOrNull { it.at } ?: BoardEst.estimate(base)
        if (!r.phased && samples.isEmpty()) return trip.boardEstimateSec
        return BoardEst.offsetSec(r, enter)
    }

    /** 首页/通知动作：给当前节点打一个「现在」 */
    fun stampCurrentNode() {
        scope.launch(Dispatchers.Main) {
            if (!::trip.isInitialized || finishing) return@launch
            val now = System.currentTimeMillis()
            when {
                tail -> { stampNode(NodeLog.Kind.ARRIVE, 0, now, NodeLog.Src.MANUAL); userFinish() }
                riding -> ackAlight()
                trip.legs[legIdx].isMetroOnly() && stationAt == 0L -> {
                    stationAt = now; stationByTap = false
                    stampNode(NodeLog.Kind.ENTER, legIdx, now, NodeLog.Src.MANUAL)
                    saveRun(deadlineAt); syncNodeCurrent(); kick()
                }
                else -> onBoarded(manual = true, at = now)
            }
            saveRun(deadlineAt)
            syncNodeCurrent()
        }
    }

    /** 长按节点条补录 / 修正：只改数据，不推进状态机 */
    fun setNodeAt(kind: NodeLog.Kind, leg: Int, at: Long) {
        scope.launch(Dispatchers.Main) {
            if (!::trip.isInitialized) return@launch
            stampNode(kind, leg, at, NodeLog.Src.BACKFILL)
            saveRun(deadlineAt)
        }
    }

    /** 5 秒内再点一次已记录节点：撤销这次人工打点（自动值会重新露出来） */
    fun undoNodeIfFresh(ref: NodeLog.NodeRef) {
        scope.launch(Dispatchers.Main) {
            if (!::trip.isInitialized) return@launch
            val cur = NodeLog.effective(NodeLog.raw(this@CommuteService, trip.id, nodeStartedAt))[ref]
            if (cur != null && (cur.src == NodeLog.Src.MANUAL || cur.src == NodeLog.Src.BACKFILL) &&
                System.currentTimeMillis() - cur.at <= 5_000L) {
                NodeLog.removeLast(this@CommuteService, trip.id, nodeStartedAt, ref.kind, ref.leg)
                saveRun(deadlineAt)
                syncNodeCurrent()
            }
        }
    }

    /** 「我已下车」：停止响铃，记下车节点；还在车上就按本段到站继续 */
    fun ackAlight() {
        scope.launch(Dispatchers.Main) {
            if (!::trip.isInitialized || alightAckedAt > 0L) return@launch
            val now = System.currentTimeMillis()
            alightAckedAt = now
            alightStage = AlightGuard.Stage.NONE
            alightStageAt = 0L; alightAlarmCount = 0; alightAlarmNextAt = 0L
            AlarmBuzzer.stop()
            if (riding) {
                if (legIdx >= trip.legs.size - 1) {
                    markAlight(now, "user")
                    // 地铁：下车不等于出站，还在站内，等刷卡/基站出站后才结束
                    if (trip.legs[legIdx].isMetroOnly()) {
                        Diag.mark(this@CommuteService, "已确认下车，还在站内，等待出站")
                    } else {
                        arrived = true
                        endTrip("ack")
                    }
                } else {
                    markAlight(now, "user")
                    setPower(Power.HIGH)
                    advanceLeg(now)
                }
            } else {
                stampNode(NodeLog.Kind.ALIGHT, legIdx, now, NodeLog.Src.MANUAL)
            }
            saveRun(deadlineAt)
            syncNodeCurrent()
        }
    }

    /** 最后一段是地铁、且还没出站：下车/到站/到家都不能结束行程 */
    private fun metroStillInside(): Boolean {
        if (!::trip.isInitialized) return false
        val last = trip.legs.lastIndex
        if (trip.legs.getOrNull(last)?.isMetroOnly() != true) return false
        return NodeLog.flow.value.none { it.kind == NodeLog.Kind.EXIT && it.leg == last }
    }

    private fun resetAlightGuard() {
        placeExitWaitLogged = false
        alightAckedAt = 0L
        alightStage = AlightGuard.Stage.NONE
        alightStageAt = 0L
        alightAlarmCount = 0
        alightAlarmNextAt = 0L
        announceAtTarget = false
        AlarmBuzzer.stop()
    }

    /** 提前多久进入「准备下车」：按学到的个人反应时长，夹在 60–240 秒 */
    private fun alertLeadMs(leg: Int): Long = Learn.alertLeadMs(this, trip.id, leg)

    /**
     * 到站阶段提醒：换乘站与终点站同一套。
     * T-2 只更新胶囊；T-1 提示音；到站响铃；过站长响。重复节奏由 AlightGuard 决定。
     */
    private fun fireAlight(stage: AlightGuard.Stage, leg: Int, at: Long, force: Boolean = false) {
        if (!::trip.isInitialized || stage == AlightGuard.Stage.NONE) return
        val now = System.currentTimeMillis()
        if (alightStage == stage) {
            if (stage == AlightGuard.Stage.T2) return
            if (!force && now < alightAlarmNextAt) return
        } else {
            alightStage = stage
            alightStageAt = at
            alightAlarmCount = 0
        }
        if (stage != AlightGuard.Stage.T2 && !alightAlertAt.containsKey(leg)) alightAlertAt[leg] = at
        val name = legAlightName(leg)
        val metro = trip.legs.getOrNull(leg)?.isMetroOnly() ?: true
        val title = AlightGuard.title(stage, name)
        val text = AlightGuard.text(stage)
        when (stage) {
            AlightGuard.Stage.T2 -> LiveNotifier.alert(this, title, text, metro = metro, id = LiveNotifier.ALIGHT_ID)
            AlightGuard.Stage.T1 -> {
                LiveNotifier.alert(this, title, text, metro = metro, id = LiveNotifier.ALIGHT_ID)
                AlarmBuzzer.beep(this)
            }
            AlightGuard.Stage.AT, AlightGuard.Stage.OVERSHOOT -> {
                LiveNotifier.alert(this, title, text, metro = metro, id = if (stage == AlightGuard.Stage.AT) LiveNotifier.ALIGHT_ID else LiveNotifier.OVERSHOOT_ID, ack = true)
                val cap = AlightGuard.maxRings(stage)
                if (alightAlarmCount < cap) {
                    AlarmBuzzer.ring(this, long = stage == AlightGuard.Stage.OVERSHOOT)
                    alightAlarmCount++
                }
            }
            AlightGuard.Stage.NONE -> Unit
        }
        TraceLog.event("alight_stage", "stage" to stage.name, "leg" to leg, "rings" to alightAlarmCount)
        alightAlarmNextAt = if (alightAlarmCount >= AlightGuard.maxRings(stage) && AlightGuard.shouldRing(stage))
            now + 10 * 60_000L else now + AlightGuard.intervalMs(stage)
    }

    /** 结束结算：补算缺失节点，写入「最近行程」，清理进行中记录 */
    private fun settleNodes(aborted: Boolean) {
        if (!::trip.isInitialized || nodeStartedAt <= 0L) return
        runCatching {
            val raw = NodeLog.raw(this, trip.id, nodeStartedAt)
            val eff = NodeLog.effective(raw)
            val anchors = NodeLog.Anchors(
                startedAt = if (startedAt > 0L) startedAt else nodeStartedAt,
                now = System.currentTimeMillis(),
                boardOffsetSec = trip.legs.indices.map { i ->
                    eff[NodeLog.NodeRef(NodeLog.Kind.ENTER, i)]?.at?.let { boardOffsetSec(i, it) }
                        ?: trip.boardEstimateSec
                },
                // 下车比出站早多少：优先用实测中位数，样本不足用编辑页预设
                alightEstimateSec = Learn.exitWalkSec(this, trip.id, trip.legs.lastIndex)
                    ?: trip.alightEstimateSec,
                transferWalkSec = trip.legs.indices.map { transferWalkSecOf(it).coerceAtLeast(60) },
                rideSec = trip.legs.indices.map { (rideMs(it) / 1000).toInt() },
                lastWalkSec = Learn.lastWalkSec(this, trip.id)
                    ?: WalkLearn.durationMedian(this, trip.id, "last") ?: 480,
            )
            val nodes = NodeLog.resolve(trip, raw, anchors, aborted)
            learnNodes(nodes, aborted)
            NodeLog.saveLastRun(this, trip, nodes)
            TraceLog.event("nodes_settled", "n" to nodes.size, "aborted" to aborted)
        }
        NodeLog.clearActive(this)
    }

    /**
     * 从结算后的时间轴学节点时长。补录不参与。
     * 推算值只用在「进站→上车」这种固定差上：进站刷卡实测、上车按预设推算，差值就是候车。
     * 出站从不推算，所以「下车→出站」只统计实测差值，不再用于补算。
     * 乘车、换乘步行、末段步行、反应时长只认实测，避免推算自我强化。
     * 测试行程、中途上车、恢复的行程都不学。
     */
    private fun learnNodes(nodes: List<NodeLog.Stamp>, aborted: Boolean) {
        if (aborted || restored || manualRun) return
        val eff = NodeLog.effective(nodes)
        fun at(kind: NodeLog.Kind, leg: Int, estimated: Boolean = false): Long? =
            eff[NodeLog.NodeRef(kind, leg)]
                ?.takeIf { it.src != NodeLog.Src.BACKFILL && (estimated || it.src != NodeLog.Src.ESTIMATED) }?.at
        fun sec(from: Long, to: Long) = ((to - from) / 1000).toInt()

        trip.legs.indices.forEach { i ->
            val enter = at(NodeLog.Kind.ENTER, i)
            val boardReal = at(NodeLog.Kind.BOARD, i)
            val board = at(NodeLog.Kind.BOARD, i, estimated = true)
            val alight = at(NodeLog.Kind.ALIGHT, i)
            val alightOrEst = at(NodeLog.Kind.ALIGHT, i, estimated = true)
            val exit = at(NodeLog.Kind.EXIT, i)
            if (i != joinLeg) {
                if (enter != null && board != null && board > enter) Learn.recordNode(this, Learn.Metric.WAIT, "${trip.id}#$i", sec(enter, board))
                if (alightOrEst != null && exit != null && exit > alightOrEst) Learn.recordNode(this, Learn.Metric.EXIT_WALK, "${trip.id}#$i", sec(alightOrEst, exit))
                alightAlertAt[i]?.let { a -> if (alight != null && alight > a) Learn.recordNode(this, Learn.Metric.REACT, "${trip.id}#$i", sec(a, alight)) }
            }
            if (i > 0) {
                val prev = at(NodeLog.Kind.ALIGHT, i - 1)
                if (prev != null && boardReal != null && boardReal > prev) Learn.recordNode(this, Learn.Metric.TRANSFER, "${trip.id}#$i", sec(prev, boardReal))
            }
        }
        val last = trip.legs.lastIndex
        val arrive = at(NodeLog.Kind.ARRIVE, 0)
        val anchor = if (trip.legs.getOrNull(last)?.isMetroOnly() == true)
            at(NodeLog.Kind.EXIT, last) else at(NodeLog.Kind.ALIGHT, last)
        if (arrive != null && anchor != null && arrive > anchor) Learn.recordNode(this, Learn.Metric.LAST_WALK, trip.id, sec(anchor, arrive))
        alightAlertAt.clear()
    }
    private val fenceFixes = ArrayDeque<android.location.Location>()
    private var pendingBoardAt = 0L
    private var pendingBoardLoc: Location? = null
    private var boardCommitted = false

    /**
     * 真坐车 vs 测试：上车后离上车点 1.5 公里以上、或已经到了下一段（换乘/下地铁），
     * 且距上车已过 5 分钟，才算一次真实乘车写进习惯。原地反复点测试永远够不上。
     */
    private fun commitBoard(now: Long) {
        if (pendingBoardAt == 0L || boardCommitted || manualRun) return
        if (now - pendingBoardAt < 5 * 60_000L) return
        val from = pendingBoardLoc
        val moved = from != null && best?.let { b ->
            b.time > pendingBoardAt && b.accuracy < 300 &&
                Geo.distanceM(from.latitude, from.longitude, b.latitude, b.longitude) > 1_500
        } == true
        val progressed = legIdx > 0 && (alightAt > 0 || riding || legIdx > 1)
        if (!moved && !progressed) return
        boardCommitted = true
        if (joinLeg >= 0) return   // 半路上车：上车时刻不代表平时什么时候出发，不学
        Learn.record(this, trip.id, pendingBoardAt)
        // 时间窗只由用户自己改：学到的习惯只决定窗口内什么时候弹胶囊，绝不改用户设置（硬规则）
        Log.i(TAG, "记下一次真实乘车：${if (moved) "位移" else "到了下一段"}")
    }
    private var stillSince = 0L
    private var lastFastAt = 0L

    private var rideLearned = false
    private var stopAlerted = ""   // 已在站牌边提醒过的那班车
    private var steps = 0
    private var stepOn = false
    private val stepListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) { steps++ }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    /** 计步（低功耗协处理器）：只在快到换乘站前后几分钟开，用来判断下车开始走路 */
    private fun stepSensor(on: Boolean) {
        if (on == stepOn) return
        val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
        if (on) {
            if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR) ?: return
            steps = 0
            stepOn = sm.registerListener(stepListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            sm.unregisterListener(stepListener); stepOn = false
        }
    }
    private var lastCand: Candidate? = null    // 最近一次建议坐的线
    private var ridingCand: Candidate? = null  // 最后一段实际坐的线

    /** 地铁段手动点「已上车」= 真实列车时刻，拿来修正推算 */
    private fun learnMetro(leg: Leg, nowSec: Int = LocalTime.now().toSecondOfDay()) {
        val c = leg.candidates.firstOrNull { it.isMetro() && it.metroDir.isNotEmpty() } ?: return
        val d = metroDirs["${c.stopName}|${c.name}"]?.firstOrNull { it.dest == c.metroDir } ?: return
        val weekday = LocalDate.now().dayOfWeek.value <= 5
        val bands = c.bandsFor(!weekday)
        val r = Decision.metroResidual(nowSec - 30, d.firstMin, d.lastMin, weekday, bands) ?: return
        val peak = Decision.isPeakAt(nowSec / 60, weekday, bands)
        Learn.recordMetro(this, Learn.metroKey(c, peak), r)
    }

    /** 手点「已上车」前的进度，撤销时整个放回去 */
    private data class BoardSnap(
        val legIdx: Int, val boardedAt: Long, val riding: Boolean, val stationAt: Long, val stationByTap: Boolean,
        val alightAt: Long, val rideLearned: Boolean, val arriveAnchorAt: Long, val arriveAlertedAt: Long,
        val pendingBoardAt: Long, val pendingBoardLoc: Location?, val ridingCand: Candidate?,
        val metroAnchorIdx: Int, val metroAnchorAt: Long, val nearSince: Long, val alightLeg: Int,
        val arriveAgainAt: Long, val overshootAlerted: Boolean, val deadlineAt: Long,
    )
    private var boardSnap: BoardSnap? = null
    // 地铁段手点的时刻：撤销窗口过了才拿去学，误点撤回的不学
    private var pendingLearnLeg = -1
    private var pendingLearnSec = 0

    private fun undoBoard() {
        val b = boardSnap ?: return
        boardSnap = null; undoUntil.value = 0L; pendingLearnLeg = -1
        legIdx = b.legIdx; boardedAt = b.boardedAt; riding = b.riding; stationAt = b.stationAt; stationByTap = b.stationByTap
        alightAt = b.alightAt; rideLearned = b.rideLearned; arriveAnchorAt = b.arriveAnchorAt; arriveAlertedAt = b.arriveAlertedAt
        pendingBoardAt = b.pendingBoardAt; pendingBoardLoc = b.pendingBoardLoc; ridingCand = b.ridingCand
        metroAnchorIdx = b.metroAnchorIdx; metroAnchorAt = b.metroAnchorAt; nearSince = b.nearSince; alightLeg = b.alightLeg
        arriveAgainAt = b.arriveAgainAt; overshootAlerted = b.overshootAlerted
        alertedKey = null; speeds.clear(); routeHits.clear()
        NodeLog.removeLast(this, trip.id, nodeStartedAt, NodeLog.Kind.BOARD, legIdx)
        TraceLog.event("board_undo", "leg" to legIdx)
        Diag.mark(this, "撤销了手点的「已上车」，回到第 ${legIdx + 1} 段")
        loop?.cancel()
        deadlineAt = b.deadlineAt
        loop = scope.launch { runLoop(b.deadlineAt) }
        saveRun(b.deadlineAt)
        resetAlightGuard()
        syncNodeCurrent()
    }

    /** 撤销窗口过了：手点的地铁上车时刻才算数 */
    private fun flushPendingLearn(now: Long) {
        if (undoUntil.value > now) return
        boardSnap = null
        val i = pendingLearnLeg
        if (i < 0) return
        pendingLearnLeg = -1
        runCatching { learnMetro(trip.legs[i], pendingLearnSec) }
    }

    /** 「已上车」按钮或速度判断：上当前这一段的车，进入「去本段下车站」的状态 */
    fun onBoarded(manual: Boolean = true, at: Long = System.currentTimeMillis()) {
        if (!::trip.isInitialized) { finish(); return }
        if (tail) return
        // 手点后 2 分钟内再点 = 撤销（防误点 / 连点直接跳两段）
        if (manual && boardSnap != null && undoActive()) { undoBoard(); return }
        val now = at
        TraceLog.event("boarded", "leg" to legIdx, "manual" to manual, "at" to at, "riding" to riding)
        if (riding) return                // 已经在这一段的车上
        stampNode(NodeLog.Kind.BOARD, legIdx, at,
            if (manual) NodeLog.Src.MANUAL else if (boardingByTap) NodeLog.Src.TAP else NodeLog.Src.AUTO)
        resetAlightGuard()
        if (manual) {
            boardSnap = BoardSnap(legIdx, boardedAt, riding, stationAt, stationByTap, alightAt, rideLearned, arriveAnchorAt, arriveAlertedAt,
                pendingBoardAt, pendingBoardLoc, ridingCand, metroAnchorIdx, metroAnchorAt, nearSince, alightLeg, arriveAgainAt, overshootAlerted, deadlineAt)
            undoUntil.value = System.currentTimeMillis() + UNDO_MS
        } else if (boardSnap != null) {
            // 自动判断接着推进了：之前那次手点就不再能撤
            boardSnap = null; undoUntil.value = 0L
        }
        stationAt = 0L
        if (manual && trip.legs[legIdx].isMetroOnly()) { pendingLearnLeg = legIdx; pendingLearnSec = LocalTime.now().toSecondOfDay() }
        // 只学自动启动的行程：手动点开测试的不算习惯；先挂起，确认真的坐了一段才记（commitBoard）
        if (legIdx == 0 && boardedAt == 0L && !manualRun && !boardCommitted) {
            pendingBoardAt = now; pendingBoardLoc = best?.let { Location(it) }
        }
        // 整趟最后一段连下车站都没填才结束；中间段没填时用下一段上车站兜底
        if (legIdx == trip.legs.size - 1 && legAlight[legIdx] == null &&
            trip.legs[legIdx].candidates.none { it.alightName.isNotEmpty() || !it.alightLat.isNaN() }) { finish(); return }
        riding = true; boardedAt = now
        alightAt = 0L; alightLeg = -1
        arriveAlertedAt = 0L; arriveAgainAt = 0L; overshootAlerted = false
        resetBusRide(); metroAnchorIdx = -1; metroAnchorAt = 0L
        val l = trip.legs[legIdx]
        busBoardGuessed = !manual && !boardingByTap && !l.isMetroOnly()
        ridingCand = l.candidates.firstOrNull { it.isMetro() }.takeIf { l.isMetroOnly() } ?: lastCand ?: l.candidates.firstOrNull()
        speeds.clear(); routeHits.clear()
        loop?.cancel()
        val dl = if (legIdx == trip.legs.size - 1)
            maxOf(at, System.currentTimeMillis()) + rideMs(legIdx) + 20 * 60_000L
        else deadline(trip, false).coerceAtLeast(now + 20 * 60_000L)
        deadlineAt = dl   // 先同步更新，紧接着的 kick()/saveRun() 才不会用旧截止时间
        loop = scope.launch { runLoop(dl) }
        saveRun(dl)
        syncNodeCurrent()
    }

    /** 本段到站确认：转入下一段的等车状态（最后一段由 finish 处理，不走这里） */
    private fun advanceLeg(now: Long) {
        if (legIdx >= trip.legs.size - 1) return
        val ended = trip.legs[legIdx]
        if (ended.isMetroOnly() && metroRideOn) runCatching { endMetroRide() }
        // 上一段的基站推算不能带到下一段
        if (ended.isMetroOnly()) { fpEtaAt = 0L; fpSeen.clear(); fpEta = emptyMap() }
        legIdx++
        riding = false; ridingCand = null
        nearSince = 0L; fastFixes = 0; alertedKey = null; stopAlerted = ""
        alightLeg = legIdx - 1        // 保留上一段的下车时刻，用来算换乘步行
        rideLearned = false; stepSensor(false)
        stationAt = 0L; stationByTap = false; arriveAnchorAt = 0L; arriveAlertedAt = 0L
        arriveAgainAt = 0L; overshootAlerted = false
        deferredTapAt = 0L; deferredTapKind = null
        metroExitSeen = false; metroExitAt = 0L
        // 地铁换地铁：人已经在下车站的站厅里，不用再等「进站」判断
        if (ended.isMetroOnly() && trip.legs[legIdx].isMetroOnly()) stationAt = now
        fenceFixes.clear(); stillSince = 0L; lastFastAt = 0L
        maxStops.clear(); maxEta.clear(); speeds.clear(); routeHits.clear()
        resetBusRide(); metroAnchorIdx = -1; metroAnchorAt = 0L
        loop?.cancel()
        val dl = deadline(trip, false).coerceAtLeast(now + 20 * 60_000L)
        deadlineAt = dl
        loop = scope.launch { runLoop(dl) }
        saveRun(dl)
        resetAlightGuard()
        syncNodeCurrent()
        TraceLog.event("leg_advance", "leg" to legIdx)
    }

    /** 后台预取：沿线站点（判断是否在车上）和每一段的下车站坐标（每段到站提醒） */
    private fun prefetch(t: Trip) {
        t.legs.flatMap { it.candidates }.filter { it.lineId.isNotEmpty() }.distinctBy { it.lineId }.forEach { c ->
            runCatching { Transit.lineDetail(c.lineId, c.regionId) }.getOrNull()?.let { d ->
                routes[c.lineId] = d.stations.map { it.lat to it.lng }
            }
        }
        t.legs.forEachIndexed { i, leg -> resolveAlight(leg, t.legs.getOrNull(i + 1))?.let { legAlight[i] = it } }
        alight = legAlight[t.legs.size - 1]
    }

    /** 本段下车站坐标：填好的坐标 > 按线路站点名查 > 下一段上车站兜底 */
    private fun resolveAlight(leg: Leg, next: Leg?): Pair<Double, Double>? =
        leg.candidates.firstNotNullOfOrNull { c ->
            when {
                !c.alightLat.isNaN() && !c.alightLng.isNaN() -> c.alightLat to c.alightLng
                c.alightName.isEmpty() -> null
                c.lineId.isNotEmpty() -> runCatching { Transit.lineDetail(c.lineId, c.regionId) }.getOrNull()
                    ?.stations?.firstOrNull { it.name == c.alightName }?.let { it.lat to it.lng }
                else -> null
            }
        } ?: next?.candidates?.firstOrNull { !it.lat.isNaN() }?.let { it.lat to it.lng }
            ?: leg.candidates.firstOrNull { it.alightName.isNotEmpty() }?.let { c ->
                runCatching { Transit.search(c.alightName, c.regionId).stations }.getOrNull()
                    ?.firstOrNull { it.name.startsWith(c.alightName) && (it.isSubway == c.isMetro()) }
                    ?.let { it.lat to it.lng }
            }

    /** 本段下车站名：没填时用下一段上车站名 */
    private fun legAlightName(i: Int): String {
        val leg = trip.legs.getOrNull(i) ?: return "下车站"
        return leg.candidates.firstOrNull { it.alightName.isNotEmpty() }?.alightName
            ?: trip.legs.getOrNull(i + 1)?.candidates?.firstOrNull()?.stopName
            ?: "下车站"
    }

    /**
     * 市区公交走走停停，连续 3 次 ≥6 m/s 太苛刻：最近 5 次 GPS 里 ≥3 次 ≥4.5 m/s、至少 1 次 ≥6 m/s，
     * 且 ≥4 次贴线（最新一次必须贴线）就算在车上。
     */
    private fun busRideLikely(): Boolean {
        if (speeds.size < 4 || routeHits.size < 4) return false
        val sp = speeds.toList(); val r = routeHits.toList()
        return sp.count { it >= 4.5f } >= 3 && sp.maxOrNull()!! >= 6f && r.count { it } >= 4 && r.last()
    }

    /** 到这段公交线路（相邻站连成折线）的最近距离，米；没线路数据返回 null */
    private fun lineDistM(loc: Location, leg: Leg): Double? {
        val lines = leg.candidates.filter { !it.isMetro() }.mapNotNull { routes[it.lineId] }.filter { it.size >= 2 }
        if (lines.isEmpty()) return null
        val k = Math.cos(Math.toRadians(loc.latitude)) * 111_320.0
        fun xy(p: Pair<Double, Double>) = (p.second - loc.longitude) * k to (p.first - loc.latitude) * 110_540.0
        var best = Double.MAX_VALUE
        for (pts in lines) for (i in 0 until pts.size - 1) {
            val (ax, ay) = xy(pts[i]); val (bx, by) = xy(pts[i + 1])
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            best = minOf(best, Math.sqrt(px * px + py * py))
        }
        return best
    }

    private val busHitTimes = ArrayDeque<Long>()

    /**
     * H1：当前还是地铁段（没进站 / 没上车），人却在地面以车速贴着下一段公交线路走（GPS 连续 3 次、跨 60 秒以上，离线路 ≤ 80 米）
     * → 没坐地铁，直接改坐了这路公交（或从别的站上的车）：跳过地铁段，按第一次贴线的时刻算公交上车。
     */
    private fun busJumpCheck(loc: Location, spd: Float): Boolean {
        val nk = legIdx + 1
        if (!trip.legs[legIdx].isMetroOnly() || inMetroRide() || nk >= trip.legs.size || trip.legs[nk].isMetroOnly()) { busHitTimes.clear(); return false }
        val d = lineDistM(loc, trip.legs[nk])
        if (spd >= 4f && d != null && d <= 80.0) busHitTimes.addLast(loc.time) else { busHitTimes.clear(); return false }
        if (busHitTimes.size < 3 || busHitTimes.last() - busHitTimes.first() < 60_000L) return false
        val at = busHitTimes.first(); busHitTimes.clear()
        val now = System.currentTimeMillis()
        TraceLog.event("bus_jump", "from" to legIdx, "to" to nk, "at" to at, "distM" to (d ?: 0.0).toInt())
        Diag.mark(this, "在地面上以车速沿第 ${nk + 1} 段公交走，跳过地铁，判断已上公交")
        // 跳过地铁段，直接等第 nk 段公交上车
        while (legIdx < nk) advanceLeg(at)
        joinLeg = nk                                   // 半路上的车：不学这段乘车时长
        onBoarded(manual = false, at = at)             // 公交段：上车
        saveRun(deadlineAt); kick()
        return true
    }

    private fun onRoute(loc: Location, leg: Leg): Boolean {
        val pts = leg.candidates.mapNotNull { routes[it.lineId] }.flatten()
        if (pts.isEmpty()) return false
        return pts.any { Geo.distanceM(loc.latitude, loc.longitude, it.first, it.second) <= 400 }
    }

    private var deadlineAt = 0L

    private suspend fun runLoop(deadline: Long) {
        deadlineAt = deadline
        while (scope.isActive && System.currentTimeMillis() < deadlineAt) {
            val wait = try { tick() } catch (e: Exception) {
                Log.w(TAG, "刷新失败", e); 20_000L
            }
            val t0 = System.currentTimeMillis()
            delay(wait)
            val over = System.currentTimeMillis() - t0 - wait
            // 该醒没醒：进程被系统冻住了多久（熄屏时常见），给保活排查用
            if (over > 60_000L) TraceLog.event("freeze", "sec" to over / 1000, "plannedS" to wait / 1000,
                "screen" to Health.screenOn(this), "plugged" to Health.plugged(this))
        }
        finish()
    }

    /** 刷新一次，返回下次刷新的间隔 */
    private suspend fun tick(): Long {
        if (tail) return tailTick(System.currentTimeMillis())
        commitBoard(System.currentTimeMillis())
        flushPendingLearn(System.currentTimeMillis())
        retryDeferredTap(System.currentTimeMillis())
        runCatching { checkFirstWalk() }
        val leg = trip.legs[legIdx]
        val now = System.currentTimeMillis()
        val fresh = best?.takeIf { it.accuracy < 200 && now - it.time < 120_000 }
        // 地铁里网络定位误差大（几百米到一公里），坐地铁时放宽到 800 米也拿来用
        if (riding) return rideTick(leg, fresh ?: best?.takeIf { leg.isMetroOnly() && it.accuracy < 800 && now - it.time < 90_000 }, now)
        // 第一段还没上车（在家/公司里不动）：没有新定位时试系统缓存位置，再不行且手机一直没动就沿用上一次的好定位
        val loc = fresh ?: if (legIdx == 0 && boardedAt == 0L) walkPhaseLoc(now) else null

        pendingJoin?.let { (i, j) -> if (metroRoutes[i] != null) { pendingJoin = null; applyJoin(i, j) } }
        // 半路上车：人在这段地铁沿线后面某站附近（不是配置的上车站）→ 从那站算起
        if (fresh != null) tryJoin(fresh, legIdx, now, byTap = false)
        else if (stationAt > 0 && !stationByTap) {
            // 基站已认出在地下、还没上车：没有准定位时退一步用 500 米内的网络定位
            best?.takeIf { it.accuracy <= 500 && now - it.time < 90_000 }?.let { tryJoin(it, legIdx, now, byTap = false, coarse = true) }
        }
        // 按位置校正进度：已位于后续某段的上车站附近且远离当前段车站，说明前面各段已完成（上车未被识别）
        jumpByLocation(now)?.let { return it }

        val dists = leg.candidates.map { c -> loc?.let { Geo.distanceM(it.latitude, it.longitude, c.lat, c.lng) } }
        val nearest = dists.filterNotNull().minOrNull()
        if (nearest != null && nearest <= NEAR_STOP_M && nearSince == 0L) nearSince = now

        // 推断进站：在站口附近待了两分钟，或最后一次卫星定位在站口 500 米内、之后 90 秒没信号。
        // 进站 ≠ 上车：按进站后第一班赶得上的车算上车时刻，那班车开走了才切到下一段。
        if (leg.isMetroOnly() && stationAt == 0L) {
            val g = lastGpsFix
            stationAt = when {
                nearSince > 0 && now - nearSince > 120_000 -> nearSince
                // 最后一次 GPS 要贴着站口（200 米），且不是还在出发地（公司 / 家离车站近，出门 GPS 一停就会误判进站）
                g != null && g.time >= startedAt - 30_000 && now - g.time > 90_000 &&
                    leg.candidates.any { Geo.distanceM(g.latitude, g.longitude, it.lat, it.lng) <= 200 } &&
                    walkFixes.firstOrNull().let { o -> o == null || Geo.distanceM(g.latitude, g.longitude, o.lat, o.lng) > 150 } -> g.time
                else -> 0L
            }
        }
        if (leg.isMetroOnly() && stationAt > 0) {
            val train = nextTrainAfter(leg, stationAt) ?: (stationAt + 4 * 60_000L)
            // 所有地铁段（上班最后一段、下班第一段）都一样：进站后先「等车」，有证据真的坐上了才切过去
            metroBoardAt(leg, train, now)?.let { onBoarded(manual = false, at = it); return 1_000L }
        }

        val secOfDay = LocalTime.now().toSecondOfDay()
        val options = withContext(Dispatchers.IO) {
            leg.candidates.mapIndexed { i, c ->
                async {
                    val walk = dists[i]?.let { Decision.walkSeconds(it) }
                        // 站里没定位：按换乘步行时间减去下车后已走的时间
                        ?: if (alightAt > 0) (transferWalkSecOf(legIdx) - ((now - alightAt) / 1000).toInt()).coerceAtLeast(0) else 0
                    when (c.type) {
                        Candidate.Type.BUS -> {
                            val rt = runCatching {
                                Transit.lineRealtime(c, loc?.latitude ?: c.lat, loc?.longitude ?: c.lng)
                            }.getOrNull()
                            rt?.let { BusSampler.observe(applicationContext, c, it, "trip") }
                            Decision.Option(c, walk,
                                rt?.buses?.map { Decision.Arrival(it.travelSec, it.stopsAway, it.busId) } ?: emptyList(),
                                rt?.tip ?: "查询失败")
                        }
                        Candidate.Type.SHUTTLE -> Decision.Option(c, walk, Decision.shuttleArrivals(c, secOfDay))
                        Candidate.Type.METRO -> {
                            // 数据源没有地铁列车实时位置：按本站该方向首末班 + 高峰/平峰间隔推算
                            val dirs = metroDirs.getOrPut("${c.stopName}|${c.name}") {
                                runCatching { Transit.subwayDirs(c.lat, c.lng, c.stopName, c.regionId) }.getOrNull()
                                    ?.filter { Transit.sameLine(c.name, it) } ?: return@async Decision.Option(c, walk, emptyList())
                            }.filter { c.metroDir.isEmpty() || it.dest == c.metroDir }
                            val weekday = LocalDate.now().dayOfWeek.value <= 5
                            val bands = c.bandsFor(!weekday)
                            val peakNow = Decision.isPeakAt(secOfDay / 60, weekday, bands)
                            val off = if (c.metroDir.isNotEmpty()) Learn.metroOffsetSec(this@CommuteService, Learn.metroKey(c, peakNow)) else 0
                            val arr = dirs.flatMap { d ->
                                Decision.metroSchedule(secOfDay - off, d.firstMin, d.lastMin, weekday, bands, 3, Decision.DWELL_SEC)
                                    .map { Decision.Arrival(it, -1, if (dirs.size > 1) "往${d.dest}" else "", estimated = true) }
                            }
                            Decision.Option(c, walk, arr,
                                if (dirs.isNotEmpty() && arr.isEmpty()) Decision.END_OF_SERVICE else "",
                                located = dists[i] != null)
                        }
                    }
                }
            }.awaitAll()
        }
        val advice = Decision.decide(options)
        // 实时说「无车」：按历史到站时刻给「下一班约 HH:MM」，倒计时交给系统走（进程被冻住也照样对）。只显示，不提醒
        val tt = if (advice.mode == Decision.Mode.NO_BUS && (advice.arrival == null || advice.arrival.stopsAway < 0))
            advice.option?.candidate?.takeIf { it.type == Candidate.Type.BUS }
                ?.let { BusTimetable.predict(this, BusSampler.key(it), now) }?.takeIf { it.atMs >= now - 60_000L }
        else null
        if (tt != null && ttLogged != tt.atMs) { ttLogged = tt.atMs; TraceLog.event("timetable", "at" to tt.atMs, "lo" to tt.loMin, "hi" to tt.hiMin, "days" to tt.days) }
        val a = if (tt != null) advice.copy(headline = "约" + BusTimetable.hhmm(tt.mid()),
            detail = "实时暂无车 · 近 ${tt.days} 天这班一般 ${BusTimetable.hhmm(tt.loMin)}–${BusTimetable.hhmm(tt.hiMin)} 到" +
                (advice.detail.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")) else advice
        // 关键时刻：等车、或车 15 分钟内到 → 每分钟用闹钟叫醒一次（熄屏冻结时只有闹钟叫得醒）
        val waitingBus = !leg.isMetroOnly() && (legIdx == 0 || alightAt > 0)
        val soonBus = (a.arrival?.etaSec?.let { it in 0..900 } == true) || (tt != null && tt.atMs - now <= 15 * 60_000L) ||
            alightAt > 0
        if (waitingBus && soonBus) WakeAlarm.arm(this, now + 60_000L) else WakeAlarm.cancel(this)
        a.option?.candidate?.let { lastCand = it }
        // 先坐地铁再换公交、还没出发：倒推「最晚几点走，到换乘站刚好赶上公交」
        val plan = if (!planDone && legIdx == 0 && boardedAt == 0L && nearSince == 0L && leg.isMetroOnly())
            runCatching { planTransfer(leg, options, secOfDay, loc) }.getOrNull() else null
        if (plan != null) return planTick(plan, leg, a, now)
        val v = visual(a, leg).let { v0 ->
            if (tt != null && (v0.countdownTo == null || v0.countdownTo <= now)) v0.copy(countdownTo = tt.atMs) else v0
        }
        LiveNotifier.update(this, LiveNotifier.build(this, v)); logLive(v)
        LiveState.publish(this, LiveSnapshot(
            trip.id, trip.name, legIdx, trip.legs.size, leg.title(), v.short, v.text, a.mode, v.progress, v.countdownTo,
        ), deadlineAt)

        // 该走了 / 换乘紧张：横幅 + 震动，每班车只提醒一次
        val key = "${a.mode}|${a.option?.candidate?.label()}|${a.arrival?.busId}"
        if ((a.mode == Decision.Mode.LEAVE_NOW || a.mode == Decision.Mode.HURRY || a.mode == Decision.Mode.CONNECT_MISS) &&
            alertedKey != key) {
            alertedKey = key
            LiveNotifier.alert(this, "${a.headline} · ${a.option?.candidate?.label() ?: trip.name}", a.detail,
                metro = a.option?.candidate?.isMetro() == true)
        }
        // 已在站牌边：车 2 分钟内到，再提醒一次（每班车一次）
        val arrSec = a.arrival?.etaSec ?: -1
        val busKey = "${a.option?.candidate?.label()}|${a.arrival?.busId}"
        if (nearSince > 0 && arrSec in 0..120 && stopAlerted != busKey && a.option?.candidate?.isMetro() != true) {
            stopAlerted = busKey
            LiveNotifier.alert(this, "${a.option?.candidate?.label()} 即将到站", a.detail, id = 1006)
        }

        // 省电：快出发、在站台附近才开高频 GPS
        val hot = when (a.mode) {
            Decision.Mode.LEAVE_NOW, Decision.Mode.HURRY, Decision.Mode.AT_STOP -> true
            Decision.Mode.LEAVE_LATER -> a.leaveInSec < 300
            Decision.Mode.METRO -> nearest != null && nearest < 200
            else -> nearest != null && nearest < 300
        } || loc == null
        setPower(if (hot) Power.HIGH else Power.LOW)

        val soonest = a.arrival?.etaSec ?: Int.MAX_VALUE
        return when {
            a.mode == Decision.Mode.METRO -> 30_000L
            hot || soonest < 600 -> 20_000L
            else -> 40_000L
        }
    }

    /**
     * 按位置纠正进度，两种情况跳到后面那一段：
     * 1. 新鲜定位在后面某段上车站 250 米内，且离当前段的站都在 600 米外；
     * 2. 地下没信号：最后一次定位在后面某段地铁站 500 米内，之后 90 秒以上没有新定位 → 进站了。
     */
    private fun jumpByLocation(now: Long): Long? {
        // 只认这趟行程开始之后拿到的定位：系统缓存的旧位置（比如上次进地铁时）会让刚启动就误跳段
        val last = best?.takeIf { it.accuracy < 300 && it.time >= trustFixSince } ?: return null
        fun near(l: Leg) = l.candidates.filter { !it.lat.isNaN() }
            .minOfOrNull { Geo.distanceM(last.latitude, last.longitude, it.lat, it.lng) } ?: Double.MAX_VALUE
        val age = now - last.time
        val fresh = age < 120_000 && last.accuracy < 150
        val later = legIdx + 1 until trip.legs.size
        val k = when {
            fresh && near(trip.legs[legIdx]) >= 600 -> later.firstOrNull { near(trip.legs[it]) <= 250 }
            !fresh && age > 90_000 && near(trip.legs[legIdx]) >= 300 ->
                later.firstOrNull { trip.legs[it].isMetroOnly() && near(trip.legs[it]) <= 500 }
            else -> null
        } ?: return null
        Diag.mark(this, if (fresh) "定位显示已到第 ${k + 1} 段上车站附近，跳过前面的段" else "在第 ${k + 1} 段地铁站附近没了信号，判断已进站")
        // 跳过前面各段：直接等第 k 段的车；是否上车由进站/定位判断，不再伪造乘车
        while (legIdx < k) advanceLeg(now)
        if (trip.legs[legIdx].isMetroOnly()) { stationAt = if (fresh) 0L else last.time; if (fresh) nearSince = now }
        return 1_000L
    }

    /**
     * 最后一段地铁：进站后什么时候算上车。
     * 1. 定位（地下网络定位也行，精度 1 公里内）已经离开上车站 500 米以上（扣掉误差）= 车开了；
     * 2. 兜底：赶得上的那班车过去 6 分钟、且 1 分钟内没在走路；再晚到 12 分钟无论如何切。
     * 上车时刻 = max(那班车, 最后一段持续走路结束)，不早于人还在走的时候。
     */
    /**
     * 地铁段（不论第几段）：进站后先「等车」，有证据真的坐上了才切过去，上车时刻按列车真正开动来记。
     * 证据（按可靠程度）：
     *  1. 定位离开上车站 500 米以上（精度 ≤ 1000 米）；
     *  2. 加速度显示「最后一次走动（跨进车门）之后连续 90 秒在晃」+ 那之后服务基站换了 → 列车开了；
     *  3. 兜底：预计班次过去 6 分钟且 1 分钟内没在走路；最晚 12 分钟一定切。
     * 上车时刻优先用 2 里「开始连续晃动」那一刻；没有就用 max(预计班次, 最后一次走动)。
     */
    private fun metroBoardAt(leg: Leg, train: Long, now: Long): Long? {
        val ws = TraceLog.recentWindows()
        val walkEnd = AccMotion.lastWalkEnd(ws, stationAt, now) ?: stationAt
        val dep = AccMotion.departureSince(ws, walkEnd, now)
        val fallbackAt = dep ?: maxOf(train, walkEnd)
        val st = leg.candidates.firstOrNull { it.isMetro() }
        val b = best
        if (st != null && b != null && b.time > stationAt && b.accuracy <= 1_000 &&
            Geo.distanceM(b.latitude, b.longitude, st.lat, st.lng) - b.accuracy > 500) {
            // 可能是从沿线后面某站进的：先等最多 90 秒确认半路上车，别急着按「离开上车站」判上车
            if (joinLeg < 0 && joinCandIdx >= 0 && now - joinCandAt < 90_000L) return null
            val at = minOf(fallbackAt, b.time)
            TraceLog.event("metro_board", "by" to "moved", "at" to at, "dep" to dep, "walkEnd" to walkEnd)
            return at
        }
        if (dep != null) {
            val cc = TraceLog.cellChangeAfter(maxOf(dep + 30_000L, walkEnd + 20_000L))
            if (cc != null && cc <= now) {
                TraceLog.event("metro_board", "by" to "cell", "at" to dep, "cell" to cc, "walkEnd" to walkEnd)
                return dep
            }
        }
        // H2：不用刷卡也能判——磁场持续大幅抖动 = 列车在跑（只看停下走路之后的；要已经持续 1 分钟以上）
        TraceLog.magTrainAt(maxOf(walkEnd, stationAt))?.takeIf { now - it >= 60_000L }?.let { at ->
            TraceLog.event("metro_board", "by" to "mag", "at" to at, "walkEnd" to walkEnd)
            return at
        }
        if ((now >= train + 6 * 60_000L && now - walkEnd > 60_000L) || now >= train + 12 * 60_000L) {
            TraceLog.event("metro_board", "by" to "time", "at" to fallbackAt, "dep" to dep, "walkEnd" to walkEnd)
            return fallbackAt
        }
        return null
    }

    private var planAlerted = ""
    private var planDone = false   // 提醒过「该走了」就不再倒推，改回正常的地铁进站显示

    /** 第一段地铁 + 第二段有公交时，算出发方案；条件不满足返回 null（照常显示地铁） */
    private suspend fun planTransfer(leg: Leg, options: List<Decision.Option>, secOfDay: Int, loc: Location?): List<Decision.Plan>? {
        val next = trip.legs.getOrNull(1) ?: return null
        val buses = next.candidates.filter { it.type == Candidate.Type.BUS }
        if (buses.isEmpty()) return null
        val c = leg.candidates.first()
        val dirs = metroDirs[dirKey(c)]?.filter { c.metroDir.isEmpty() || it.dest == c.metroDir } ?: return null
        if (dirs.size != 1) return null // 方向不确定就不推，免得算错
        val d = dirs.first()
        val weekday = LocalDate.now().dayOfWeek.value <= 5
        val bands = c.bandsFor(!weekday)
        val peakNow = Decision.isPeakAt(secOfDay / 60, weekday, bands)
        val off = Learn.metroOffsetSec(this, Learn.metroKey(c, peakNow))
        val trains = Decision.metroSchedule(secOfDay - off, d.firstMin, d.lastMin, weekday, bands, 30)
        val busArr = withContext(Dispatchers.IO) {
            buses.map { b ->
                async {
                    val rt = runCatching { Transit.lineRealtime(b, b.lat, b.lng) }.getOrNull()
                    rt?.let { BusSampler.observe(applicationContext, b, it, "plan") }
                    Decision.extendBuses(b.label(), rt?.buses?.map { it.travelSec } ?: emptyList(), b.headwayMin * 60)
                }
            }.awaitAll().flatten()
        }
        if (busArr.isEmpty()) return null
        val walk = options.minOfOrNull { it.walkSec } ?: 0
        val plans = Decision.planDeparture(walk, trains, (rideMs(0) / 1000).toInt(), transferWalkSecOf(1), busArr)
        return plans.ifEmpty { null }
    }

    private fun clock(nowMs: Long, sec: Int): String =
        java.time.Instant.ofEpochMilli(nowMs + sec * 1000L).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .let { "%02d:%02d".format(it.hour, it.minute) }

    /** 显示「还能待多久」：倒计时到出发时间，10 分钟前和该走时各提醒一次 */
    private fun planTick(plans: List<Decision.Plan>, leg: Leg, a: Decision.Advice, now: Long): Long {
        val p = plans.first()
        val c = leg.candidates.first()
        val stay = Decision.mins(p.leaveSec)
        val est = if (p.busEstimated) "约" else ""
        val text = "${clock(now, p.leaveSec)} 出发 → ${c.label()} ${clock(now, p.trainSec)} → " +
            "${p.busLabel} $est${clock(now, p.busSec)}" + (if (Decision.mins(p.waitSec) == 0) "（换乘几乎无需等待）" else "（换乘等待约 ${Decision.mins(p.waitSec)} 分）") +
            (plans.getOrNull(1)?.let { " · 晚一班：${clock(now, it.leaveSec)} 出发，可乘 ${it.busLabel}" } ?: "")
        val short = if (p.leaveSec <= 60) "现在出发" else "${stay} 分后出发"
        val title = if (p.leaveSec <= 60) "现在出发 · ${c.stopName}" else "$stay 分钟后出发"
        val v = LiveNotifier.Visual(title, text, short, trip.name,
            countdownTo = now + p.leaveSec * 1000L, metro = true, trackerRes = R.drawable.ic_metro)
        LiveNotifier.update(this, LiveNotifier.build(this, v)); logLive(v)
        LiveState.publish(this, LiveSnapshot(
            trip.id, trip.name, legIdx, trip.legs.size, leg.title(), short, text, a.mode, null, v.countdownTo,
        ), deadlineAt)
        val key = "${p.busLabel}@${(now / 1000 + p.trainSec) / 300}"
        if (p.leaveSec in 0..660 && planAlerted != "$key|10" && planAlerted != "$key|0" && p.leaveSec > 120) {
            planAlerted = "$key|10"
            LiveNotifier.alert(this, "${Decision.mins(p.leaveSec)} 分钟后出发", text, metro = true)
        } else if (p.leaveSec <= 120 && planAlerted != "$key|0") {
            planAlerted = "$key|0"; planDone = true
            LiveNotifier.alert(this, "现在出发 · 前往${c.stopName}", text, metro = true)
        }
        // 在公司多待一会：胶囊别在出发前就到期收起
        val need = now + p.leaveSec * 1000L +
            trip.legs.indices.sumOf { Learn.ride(this, trip, it, metroRideSec[it]).minutes + transferWalkMin(trip, it) + 10 } * 60_000L
        if (need > deadlineAt) deadlineAt = need
        setPower(if (p.leaveSec < 300) Power.HIGH else Power.LOW)
        return when {
            p.leaveSec < 600 -> 20_000L
            p.leaveSec < 1800 -> 45_000L
            else -> 90_000L     // 离出发还早：少查几次，省电
        }
    }

    // ---------- 静止时沿用定位（只给第一段上车前的步行估算用） ----------
    private val still = AccMotion.StillTracker()
    private var reuseLoggedFix = 0L
    private val REUSE_MAX_MS = 15 * 60_000L

    /**
     * 当前没有新鲜定位时（不动就没有定位回调）：
     * 1. 取 FUSED/NETWORK/GPS 的系统缓存位置，精度 <200 米里最新的一个，比现有的新就换上；
     * 2. 还是不新鲜：加速度显示从那次定位起手机一直没动（没有持续走路/坐车），15 分钟内照样当作当前位置。
     * 不改 best 以外的任何状态，也不用于车上和下车判断。
     */
    @SuppressLint("MissingPermission")
    private fun walkPhaseLoc(now: Long): Location? {
        val m = lm ?: getSystemService(LocationManager::class.java)?.also { lm = it }
        if (m != null) {
            val cached = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { pr -> runCatching { m.getLastKnownLocation(pr) }.getOrNull() }
                .filter { it.accuracy < 200 && it.time <= now + 5_000 }
                .maxByOrNull { it.time }
            val cur = best
            if (cached != null && (cur == null || cached.time > cur.time)) { best = cached; TraceLog.loc(cached) }
        }
        val b = best?.takeIf { it.accuracy < 200 } ?: return null
        val age = now - b.time
        if (age < 120_000) return b
        if (age > REUSE_MAX_MS) return null
        still.feed(TraceLog.recentWindows())
        if (!still.stillSince(b.time, now)) return null
        if (reuseLoggedFix != b.time) {
            reuseLoggedFix = b.time
            TraceLog.event("loc_reuse", "age" to age / 1000, "acc" to b.accuracy.toInt())
        }
        return b
    }

    // 最后一段地铁：靠得住的定位（地下网络定位精度 300 米内）贴着某一站时，记下「这时在第几站」，之后按每站时间往后推
    private var metroAnchorIdx = -1
    private var metroAnchorAt = 0L

    /** 地铁已过几站（0..hops-1）和预计到下车站的时刻；没有途经站数据返回 null */
    /**
     * 列车开出上车站那一刻的剩余时间。模型里最大的值是上车站基站，含站台候车（实测最大值比真实开车后的剩余时间多出约 5 分钟）。取第一个离开上车站（比最大值少 150 秒以上）的基站 + 30 秒。
     */
    private fun depRem(model: Map<Long, Long>): Long? {
        val mx = model.values.maxOrNull() ?: return null
        val moving = model.values.filter { mx - it >= 150_000L }.maxOrNull() ?: return mx
        return minOf(mx, moving + 30_000L)
    }

    /** E1：按基站学到的剩余时间 + 真实站间距离，算已过几站；没有 E1 返回 null */
    private fun fpPassed(mr: MetroRoute, now: Long): Int? {
        if (fpEtaAt <= 0 || fpEtaAt <= boardedAt) return null
        val full = depRem(fpEta)?.takeIf { it > 0 } ?: return null
        val st = mr.stations
        if (st.size != mr.hops + 1 || st.size < 2) return null
        val rem = (fpEtaAt - now).coerceAtLeast(0L)
        val cum = DoubleArray(st.size)
        for (i in 1 until st.size) cum[i] = cum[i - 1] + Geo.distanceM(st[i - 1].lat, st[i - 1].lng, st[i].lat, st[i].lng)
        val pos = (1.0 - rem.toDouble() / full).coerceIn(0.0, 1.0) * cum.last()
        return cum.indexOfLast { it <= pos + 150.0 }
    }

    private fun metroProgress(mr: MetroRoute, total: Long, now: Long): Pair<Int, Long> {
        val hops = mr.hops.coerceAtLeast(1)
        val planned = (total / hops).coerceAtLeast(60_000L)
        // 已经按定位对上过第 2 站以后：用实际走过的速度（上车到锚点平均每站多久）；只往慢里调，免得提前说快到了
        val observed = if (metroAnchorIdx >= 2 && metroAnchorAt > boardedAt) (metroAnchorAt - boardedAt) / metroAnchorIdx else 0L
        val perHop = maxOf(planned, observed.coerceAtMost(240_000L))
        // E1：有按基站学到的到站时刻就用它，已过几站按剩余时间倒推（区间长短不一，可能差一站）
        if (fpEtaAt > 0 && fpEtaAt > boardedAt) {
            val rem = (fpEtaAt - now).coerceAtLeast(0L)
            val full = depRem(fpEta)?.takeIf { it > 0 }
            val st = mr.stations
            // 按真实站间距离分配剩余时间（区间长短不一，均分会慢一站）；实测：已到第 2 站还显示第 1 站
            val fpPassed = if (full != null && st.size == hops + 1) {
                val cum = DoubleArray(st.size)
                for (i in 1 until st.size) cum[i] = cum[i - 1] + Geo.distanceM(st[i - 1].lat, st[i - 1].lng, st[i].lat, st[i].lng)
                val pos = (1.0 - rem.toDouble() / full).coerceIn(0.0, 1.0) * cum.last()
                cum.indexOfLast { it <= pos + 150.0 }
            } else hops - ((rem + planned - 1) / planned).toInt()
            // 定位匹配过的车站（metro_stop）同样计入，两者取较大值，避免被 E1 估计值低估
            val anchorPassed = if (metroAnchorIdx >= 0 && metroAnchorAt > 0) metroAnchorIdx + ((now - metroAnchorAt) / perHop).toInt() else -1
            return maxOf(fpPassed, anchorPassed).coerceIn(0, hops - 1) to fpEtaAt
        }
        return if (metroAnchorIdx >= 0 && metroAnchorAt > 0) {
            val passed = (metroAnchorIdx + ((now - metroAnchorAt) / perHop).toInt()).coerceIn(0, hops - 1)
            passed to (metroAnchorAt + (hops - metroAnchorIdx) * perHop)
        } else {
            val passed = ((now - boardedAt).toDouble() / total * hops).toInt().coerceIn(0, hops - 1)
            passed to (boardedAt + total)
        }
    }

    private fun resetBusRide() {
        busSeg = -1; busMaxSeg = -1; busSnap = null; busSnapAt = 0L; busEtaAt = 0L
        busRouteTries = 0; busRouteTriedAt = 0L
    }

    /** 坐公交：后台取一次这条线的站点，截出上车站..下车站；失败隔一分钟再试，最多 3 次 */
    private fun ensureBusRoute(rc: Candidate?, now: Long) {
        if (rc == null || rc.isMetro() || rc.lineId.isEmpty()) return
        if (busRouteLine == rc.lineId && busRoute != null) return
        if (busRouteLine != rc.lineId) { busRoute = null; busRouteLine = rc.lineId; busRouteTries = 0; busRouteTriedAt = 0L }
        if (busRouteTries >= 3 || now - busRouteTriedAt < 60_000L) return
        busRouteTries++; busRouteTriedAt = now
        val dest = legAlight[legIdx]
        scope.launch(Dispatchers.IO) {
            val st = runCatching { Transit.lineDetail(rc.lineId, rc.regionId) }.getOrNull()?.stations ?: return@launch
            val r = BusRide.slice(st, rc.stopIndex, rc.stopName, rc.lat, rc.lng, rc.alightName,
                if (!rc.alightLat.isNaN()) rc.alightLat else dest?.first ?: Double.NaN,
                if (!rc.alightLng.isNaN()) rc.alightLng else dest?.second ?: Double.NaN)
            if (r != null && busRouteLine == rc.lineId) {
                busRoute = r
                TraceLog.event("bus_route", "stops" to r.size - 1)
            }
        }
    }

    /** 车上（任意一段）：快到本段下车站时提醒一次，到了就结束或转下一段 */
    private fun rideTick(leg: Leg, loc: Location?, now: Long): Long {
        val rc = ridingCand
        val metro = rc?.isMetro() ?: leg.isMetroOnly()
        if (!metro) ensureBusRoute(rc, now)
        val br = if (metro) null else busRoute?.takeIf { rc != null && busRouteLine == rc.lineId }
        val dest = legAlight[legIdx] ?: br?.last()?.let { it.lat to it.lng }
        val name = legAlightName(legIdx)
        val lineTitle = rc?.label() ?: leg.title()
        // 地铁里网络定位常常漂几百米到几公里：只认精度 300 米内的，其余当没定位（按站数和时间估）
        val mrRide = if (metro) metroRoutes[legIdx] else null
        val locUse = if (metro) loc?.takeIf { it.accuracy <= 300 } else loc
        if (mrRide != null && locUse != null && now - locUse.time < 180_000 && locUse.time > boardedAt) {
            val k = mrRide.stations.indices.minByOrNull { Geo.distanceM(locUse.latitude, locUse.longitude, mrRide.stations[it].lat, mrRide.stations[it].lng) }
            if (k != null && k > metroAnchorIdx && k < mrRide.stations.size - 1 &&
                Geo.distanceM(locUse.latitude, locUse.longitude, mrRide.stations[k].lat, mrRide.stations[k].lng) <= 400) {
                metroAnchorIdx = k; metroAnchorAt = locUse.time
                TraceLog.event("metro_stop", "idx" to k, "left" to (mrRide.hops - k))
                saveRun(deadlineAt)
            }
        }
        val d = if (dest == null) null else locUse?.let { Geo.distanceM(it.latitude, it.longitude, dest.first, dest.second) }
        // 新鲜可靠的定位（2 分钟内、200 米精度内）说离下车站还远：人肯定还在车上
        val freshFar = loc != null && now - loc.time < 120_000 && loc.accuracy < 200 && d != null && d > 1_500

        // 公交沿线进度：投到最近一段，不许往回退超过 1 站
        val speed = Learn.busSpeed(this, trip.id, legIdx) ?: BusRide.DEFAULT_M_PER_MIN
        if (br != null && loc != null && loc.time != busSnapAt) {
            BusRide.snap(br, loc.latitude, loc.longitude, busMaxSeg - 1)?.takeIf { it.offM <= 1_500 }?.let { sn ->
                if (sn.seg != busSeg) {
                    TraceLog.event("bus_stop", "idx" to sn.seg, "left" to sn.stopsLeft)
                    busSeg = sn.seg
                    if (sn.seg > busMaxSeg) busMaxSeg = sn.seg
                    saveRun(deadlineAt)
                }
                busSnap = sn; busSnapAt = loc.time
                busEtaAt = now + (sn.remainM / speed * 60_000L).toLong()
            }
        }
        // 太久没更新的投影不用（3 分钟）
        val bs = busSnap?.takeIf { br != null && now - busSnapAt < 180_000 }
        // 只给流体云/首页显示用：熄屏时 GPS 4–5 分钟才来一次，投影过期后按学到的车速（打 85 折）往前推，
        // 最多推 15 分钟、最多推到倒数第二站，不往回跳；提醒仍只认上面的 bs
        val bsShow = bs ?: busSnap?.takeIf { br != null && now - busSnapAt < 15 * 60_000L }?.let { old ->
            BusRide.advance(br!!, old, speed * 0.85 * (now - busSnapAt) / 60_000.0)
        }

        val timeEta = anchoredArrive(legIdx)
            ?: mrRide?.let { metroProgress(it, rideMs(legIdx).coerceAtLeast(60_000L), now).second }
            ?: (boardedAt + rideMs(legIdx))
        // 公交按沿线距离估过到站时间：取两者晚的，免得时间估短了提前结束
        val eta = if (!metro && busEtaAt > 0) maxOf(timeEta, busEtaAt) else timeEta
        // 公交站距常只有 300–500 米，700 米会提前两三站（实测）：公交收到 350 米；地铁仍 700 米
        val near = d != null && (loc?.accuracy ?: 9999f) < 400 &&
            (if (metro) d <= 700 else d <= 350 + minOf(loc?.accuracy ?: 0f, 50f))
        // 公交有新鲜沿线投影：过了倒数第二站（只剩 1 站）且离站 ≤ 600 米才算快到
        val busLast = !metro && bs != null && bs.stopsLeft <= 1 && d != null && d <= 600
        // 按时间提醒「快到了」：有定位且离下车站 1.5 公里以上时不提醒（地铁里网络定位误差大，放宽精度）
        // 公交有新鲜定位时只信位置，不按倒计时（倒计时只在没定位时兜底）
        val etaKnown = !metro ||
            Learn.ride(this, trip, legIdx, metroRideSec[legIdx]).source != Learn.Ride.Source.DEFAULT
        // 到站阶段：换乘站与终点站共用。已确认「我已下车」后不再提醒
        val passed = when {
            metro && mrRide != null -> metroProgress(mrRide, rideMs(legIdx).coerceAtLeast(60_000L), now).first
            br != null -> busMaxSeg
            else -> 0
        }
        val remaining = when {
            metro && mrRide != null -> (mrRide.hops - passed).coerceAtLeast(0)
            br != null -> (br.size - 1 - busMaxSeg).coerceAtLeast(0)
            else -> -1
        }
        val atTarget = near || busLast || announceAtTarget ||
            (etaKnown && now >= eta - 30_000L && !freshFar)
        val overshoot = !overshootAlerted && (arriveAlertedAt > 0L || alightStage != AlightGuard.Stage.NONE) && (
            if (metro) now > eta + 5 * 60_000L && !(d != null && d <= 800)
            else now > eta + 3 * 60_000L && !(d != null && d <= 500))
        val st = AlightGuard.stage(AlightGuard.Input(
            remaining = remaining,
            atStation = atTarget,
            etaKnown = etaKnown,
            now = now, eta = eta,
            leadMs = alertLeadMs(legIdx),
            acked = alightAckedAt > 0L,
            overshoot = overshoot,
            far = freshFar,
        ))
        if (st != AlightGuard.Stage.NONE) {
            if (arriveAlertedAt == 0L) {
                arriveAlertedAt = now
                saveRun(deadlineAt)   // 立刻存：实测被杀重启 5 次，每次都重弹「快到了」
            }
            if (st != alightStage || now >= alightAlarmNextAt) {
                TraceLog.event("arrive_alert", "stage" to st.name, "left" to remaining, "d" to d?.toInt(),
                    "near" to near, "busLast" to busLast)
                fireAlight(st, legIdx, now)
            }
        }
        // 过站：提醒过了、早过了预计到站时间，位置也不在下车站附近 → 已经坐过站
        if (overshoot && !overshootAlerted) {
            overshootAlerted = true
            saveRun(deadlineAt)
            TraceLog.event("overshoot", "leg" to legIdx, "d" to d?.toInt(), "eta" to eta)
            fireAlight(AlightGuard.Stage.OVERSHOOT, legIdx, now, force = true)
        }
        // 结束：到了下车站 150 米内；或提醒后过了 5 分钟且超过预计到站时间（地铁 3 分钟、公交 10 分钟）。
        // 新鲜定位显示离下车站 1.5 公里以上时绝不按时间结束（人还在车上，只是车慢）
        // 地铁：出站后拿到较准的定位（≤100 米）离下车站 500 米内，且已过预计到站 → 直接算到了，不等 150 米
        val metroOut = metro && loc != null && now - loc.time < 120_000 && loc.accuracy <= 100 && d != null && d <= 500 && now >= eta - 60_000L
        val byLoc = (arriveAlertedAt > 0 && d != null && d <= 150) || metroOut
        val byTime = arriveAlertedAt > 0 && !freshFar && now - arriveAlertedAt > 5 * 60_000L &&
            now > eta + (if (metro) 3 else 10) * 60_000L && (!overshootAlerted || now > eta + 12 * 60_000L)
        if (byLoc || byTime) {
            if (byLoc && br != null && !restored && boardedAt > 0) {
                val min = (now - boardedAt) / 60_000.0
                if (min >= 3) Learn.recordBusSpeed(this, trip.id, legIdx, BusRide.lengthM(br) / min)
            }
            TraceLog.event("ride_end", "leg" to legIdx, "by" to (if (byLoc) "loc" else "time"))
            // 地铁按位置到站：记下这次实际坐了多久（用于后续按站推算，避免过早提示即将到站）
            if (byLoc && !metroOut && metro && boardedAt > 0 && alightAt == 0L) {
                val min = ((now - boardedAt) / 60_000L).toInt()
                val plan = (rideMs(legIdx) / 60_000L).toInt()
                if (min >= 3 && min in plan / 2..plan * 2 + 5) learnRide(legIdx, min)
            }
            if (legIdx >= trip.legs.size - 1) {
                if (metro) {
                    // 地铁：到站/定位到站只说明离开车厢，结束要等刷卡或基站出站
                    if (alightAt == 0L) markAlight(now, if (byLoc) "loc" else "time")
                    return 30_000L
                }
                stampNode(NodeLog.Kind.ALIGHT, legIdx, now, NodeLog.Src.AUTO)
                arrived = true
                endTrip("ride_" + (if (byLoc) "loc" else "time")); return 30_000L
            }
            // 中间段到站：和终点站同一套到站流程，只是到站后转下一段而不是结束
            markAlight(now, if (byLoc) "loc" else "time")
            setPower(Power.HIGH)
            advanceLeg(now)
            return 1_000L
        }
        // 车比预计慢、人还在车上：截止时间往后顺延，别到点自己收起（最多顺延到乘车时间的 2 倍 + 40 分钟）
        if (freshFar && deadlineAt - now < 10 * 60_000L) {
            val cap = boardedAt + rideMs(legIdx) * 2 + 40 * 60_000L
            val want = minOf(now + 10 * 60_000L, cap)
            if (want > deadlineAt) { deadlineAt = want; saveRun(deadlineAt) }
        }
        val total = rideMs(legIdx).coerceAtLeast(60_000L)
        // 有定位时以距离为准（时间估短了也不会提前显示「快到了」），没定位才按时间
        val span = if (dest == null) null else rc?.let { Geo.distanceM(it.lat, it.lng, dest.first, dest.second) }?.takeIf { it > 300 }
        val hops = (br?.size ?: 0) - 1
        val p = when {
            bsShow != null && hops > 0 -> (bsShow.seg * 100 / hops).coerceIn(0, 97)
            mrRide != null && arriveAlertedAt == 0L -> (metroProgress(mrRide, total, now).first * 100 / mrRide.hops.coerceAtLeast(1)).coerceIn(0, 97)
            d != null && span != null -> ((1 - d / span) * 100).toInt().coerceIn(0, 97)
            else -> ((now - boardedAt) * 100 / total).toInt().coerceIn(0, 100)
        }
        val left = if (metro) ((eta - now) / 60_000L).toInt().let { if (freshFar) it.coerceAtLeast(1) else it }
            else if (d != null && d > 1_500) (d / 350.0).toInt().coerceAtLeast(1)
            else ((eta - now) / 60_000L).toInt()
        val short = when {
            // 公交有定位时以距离为准；地铁/没定位才按时间
            arriveAlertedAt > 0 && (d != null && d <= 700 || ((metro || d == null) && now >= eta - 60_000)) -> "请下车"
            bsShow != null -> {
                val m = bsShow.remainM / speed
                when {
                    bs != null && (bs.remainM < 300 || m < 1) -> "即将到站"
                    bsShow.stopsLeft >= 3 -> "${bsShow.stopsLeft} 站"
                    else -> "约 ${kotlin.math.ceil(m).toInt()} 分"
                }
            }
            arriveAlertedAt > 0 -> "即将到达"
            d != null && d < 1_500 -> "${d.toInt()} 米"
            left > 0 -> "约 ${left} 分"
            else -> "即将到达"
        }
        val mr = if (metro) metroRoutes[legIdx] else null
        // 基站 30 秒才采一次、再加停站：站名常在关门后才切。预计 40 秒内到下一站就先显示「即将到」
        val metroSoon = mr != null && arriveAlertedAt == 0L &&
            metroProgress(mr, total, now + METRO_SOON_MS).first > metroProgress(mr, total, now).first
        val text = when {
            mr != null && arriveAlertedAt == 0L -> {
                val passed = metroProgress(mr, total, now).first
                val next = mr.stations[passed + 1].name
                val n = mr.hops.coerceAtLeast(1) - passed
                if (metroSoon) "即将到达 $next · 距 $name 还有 $n 站" else "当前 ${mr.stations[passed].name} · 下一站 $next · 距 $name 还有 $n 站"
            }
            bsShow != null && br != null ->
                if (bsShow.stopsLeft <= 1) "下一站为 $name" else "下一站 ${br[bsShow.seg + 1].name} · 距 $name 还有 ${bsShow.stopsLeft} 站"
            else -> "前往 $name" + (d?.let { " · 还有约 ${"%.1f".format(it / 1000)} 公里" } ?: "")
        }
        // 流体云标题：地铁有站点数据时直接显示「当前站 → 下一站」
        val metroCur = if (mr != null && arriveAlertedAt == 0L) metroProgress(mr, total, now).first else -1
        val title = when {
            metroCur >= 0 && metroSoon -> "$lineTitle · 即将到达 ${mr!!.stations[metroCur + 1].name}"
            metroCur >= 0 -> "$lineTitle · ${mr!!.stations[metroCur].name} → ${mr.stations[metroCur + 1].name}"
            else -> "$lineTitle · 前往 $name"
        }
        val v = LiveNotifier.Visual(title, text, short,
            if (trip.legs.size > 1) "${trip.name} · 第 ${legIdx + 1}/${trip.legs.size} 段" else trip.name,
            progress = p, stops = when { bsShow != null -> hops.coerceIn(0, 12); mr != null && arriveAlertedAt == 0L -> mr.hops.coerceIn(0, 12); else -> 0 },
            metro = metro, trackerRes = if (metro) R.drawable.ic_metro else R.drawable.ic_bus, riding = true)
        LiveNotifier.update(this, LiveNotifier.build(this, v)); logLive(v)
        // 界面用的站点进度：公交按沿线投影，地铁按已坐时间推算（与上面文字同一算法，只读）
        val stopUi: Pair<Int, Int>? = when {
            mr != null && arriveAlertedAt == 0L -> {
                val hopsM = mr.hops.coerceAtLeast(1)
                metroProgress(mr, total, now).first to hopsM
            }
            bsShow != null && hops > 0 -> bsShow.seg.coerceIn(0, hops) to hops
            else -> null
        }
        val names: Triple<String?, String?, String?> = when {
            mr != null && arriveAlertedAt == 0L -> Triple(mr.stations[metroCur.coerceAtLeast(0)].name, mr.stations.first().name, mr.stations.last().name)
            bsShow != null && br != null && hops > 0 -> Triple(br[bsShow.seg.coerceIn(0, hops)].name, br.first().name, br.last().name)
            else -> Triple(null, null, null)
        }
        LiveState.publish(this, LiveSnapshot(trip.id, trip.name, legIdx, trip.legs.size, lineTitle, short, text, null, p, null, riding = true,
            stopsPassed = stopUi?.first, stopsTotal = stopUi?.second,
            curStop = names.first, fromStop = names.second, toStop = names.third), deadlineAt)
        if (metro && boardedAt + total - now > 5 * 60_000L) { setPower(Power.LOW); return 30_000L }
        setPower(if (d != null && d < 1_500) Power.HIGH else Power.LOW)
        // 坐公交离下车站还远：网络定位 + 一分钟刷一次就够
        return if (d != null && d > 3_000) 60_000L else 20_000L
    }

    private fun visual(a: Decision.Advice, leg: Leg, now: Long = System.currentTimeMillis()): LiveNotifier.Visual {
        val c = a.option?.candidate
        val title = when {
            c == null -> trip.name
            c.type == Candidate.Type.METRO -> "${c.label()} · ${c.stopName}"
            else -> "${c.label()} · ${c.stopName}"
        }
        val sub = if (trip.legs.size > 1) "${trip.name} · 第 ${legIdx + 1}/${trip.legs.size} 段" else trip.name
        val arr = a.arrival
        val metro = c?.isMetro() == true || leg.isMetroOnly()
        val icon = if (metro) R.drawable.ic_metro else R.drawable.ic_bus

        if (a.mode == Decision.Mode.METRO) {
            return LiveNotifier.Visual(title, a.detail, a.headline, sub, progress = null,
                trackerRes = R.drawable.ic_walk, metro = true)
        }

        val countdown = when (a.mode) {
            Decision.Mode.LEAVE_LATER -> now + a.leaveInSec * 1000L
            else -> arr?.takeIf { it.etaSec >= 0 }?.let { now + it.etaSec * 1000L }
        }
        var progress: Int? = null
        var stops = 0
        if (arr != null) {
            val key = (c?.label() ?: "") + arr.busId
            if (arr.stopsAway >= 0) {
                val m = maxOf(maxStops[key] ?: 0, arr.stopsAway, 3).coerceAtMost(10)
                maxStops[key] = m
                stops = m
                progress = (m - arr.stopsAway.coerceAtMost(m)) * 100 / m
            } else if (arr.etaSec >= 0) {
                val m = maxOf(maxEta[key] ?: 0, arr.etaSec, 300)
                maxEta[key] = m
                progress = (m - arr.etaSec) * 100 / m
            }
        }
        return LiveNotifier.Visual(title, a.detail, a.headline, sub, countdown, progress, stops,
            urgent = a.mode == Decision.Mode.HURRY, metro = metro,
            trackerRes = icon)
    }

    private var lastLiveKey = ""
    /** 流体云内容变了就记一行（只记录），方便核对胶囊上到底显示了什么 */
    private fun logLive(v: LiveNotifier.Visual) {
        val key = "${v.title}|${v.short}|${v.stops}"
        if (key == lastLiveKey) return
        lastLiveKey = key
        TraceLog.event("live", "title" to v.title, "short" to v.short, "stops" to v.stops, "p" to v.progress)
    }


    private var highSince = 0L
    private var coolUntil = 0L

    /**
     * 省电：室内搜不到卫星时 GPS 会一直全速搜星，是发热大户。
     * 高精度开了 90 秒还拿不到 50 米内的定位 → 退回网络定位，冷却 5 分钟再试。
     */
    private fun setPower(want: Power) {
        val now = System.currentTimeMillis()
        var p = want
        if (p == Power.HIGH) {
            if (now < coolUntil) p = Power.LOW
            else if (power == Power.HIGH && highSince > 0 && now - highSince > 90_000 &&
                (best?.let { it.accuracy > 50 || now - it.time > 30_000 } != false)) {
                coolUntil = now + 5 * 60_000L; p = Power.LOW
                Log.i(TAG, "室内搜星无果，降到网络定位")
            }
        }
        if (p == Power.HIGH && power != Power.HIGH) highSince = now
        applyPower(p)
    }

    @SuppressLint("MissingPermission")
    private fun applyPower(p: Power) {
        if (p == power) return
        power = p
        val m = lm ?: getSystemService(LocationManager::class.java).also { lm = it }
        try { m.removeUpdates(listener) } catch (_: Exception) { }
        if (p == Power.OFF) { Log.i(TAG, "定位已暂停"); return }
        val providers = m.allProviders.filter {
            when (p) {
                // GPS + 网络两路就够，不再叠加 fused（它内部也会开 GPS，等于重复）
                Power.HIGH -> it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
                Power.LOW -> it == LocationManager.NETWORK_PROVIDER || it == LocationManager.FUSED_PROVIDER
                Power.OFF -> false
            }
        }.ifEmpty { listOf(LocationManager.GPS_PROVIDER) }
        val interval = if (p == Power.HIGH) 5_000L else 30_000L
        // 不设最小位移：人不动时也照样按间隔回调，否则在家里一直「定位中」
        val minDist = 0f
        providers.forEach { pr ->
            try {
                if (best == null) m.getLastKnownLocation(pr)?.let { onLocation(it) }
                if (m.isProviderEnabled(pr)) m.requestLocationUpdates(pr, interval, minDist, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.w(TAG, "定位源 $pr 不可用", e)
            }
        }
        Log.i(TAG, "定位档位 $p: $providers")
    }

    private var lastFixForSpeed: Location? = null

    /** 网络/融合定位常常不带速度：用前后两次定位自己算 */
    private var lastSpeedFixT = 0L
    /** 最后一段公交是靠速度/位置自动判的上车（不是刷卡、不是手点）：之后几分钟内来了公交刷卡，以刷卡为准 */
    private var busBoardGuessed = false
    private var boardingByTap = false
    /** 基站确认是在这段的配置上车站进的站（不是半路上车） */
    private var originCellLeg = -1

    private fun speedOf(loc: Location): Float? {
        if (loc.hasSpeed()) return loc.speed
        val p = lastFixForSpeed
        if (loc.accuracy < 100) lastFixForSpeed = Location(loc)
        if (p == null || loc.accuracy >= 100) return null
        val dt = (loc.time - p.time) / 1000.0
        if (dt < 5 || dt > 120) return null
        return (Geo.distanceM(p.latitude, p.longitude, loc.latitude, loc.longitude) / dt).toFloat()
    }

    // ---------- 常去地点（只显示/记录，不参与判断） ----------
    private var placeStart: Location? = null   // 这趟第一段上车前的第一个好定位（起点）
    private var arrived = false                // 按到站结束（到下车站 / 刷卡出站）
    private var lastPlace: Places.Label? = null
    private var placeSince = 0L
    private var startLabel: Places.Label? = null
    private var placeExitWaitLogged = false

    private fun placeFix(loc: Location) {
        runCatching {
            Places.passive(this, loc.latitude, loc.longitude, loc.accuracy, loc.time)
            if (!::trip.isInitialized || loc.accuracy >= 200) return
            if (placeStart == null && legIdx == 0 && boardedAt == 0L && loc.time >= startedAt - 30_000) placeStart = Location(loc)
            val l = Places.classify(this, loc.latitude, loc.longitude, loc.accuracy)
            if (l != lastPlace) { lastPlace = l; placeSince = loc.time; TraceLog.event("place", "label" to l.name) }
            if (startLabel == null && !restored && loc.time - startedAt < 120_000L) startLabel = l
            val neverBoarded = legIdx == 0 && boardedAt == 0L && !riding
            // 由上车站地理围栏触发，但首个高精度定位位于家或公司：判定为误触发。
            // 静默结束，并且不算「今天已经走过」，等真离开时还能自动开始
            if (geoStart && neverBoarded && !restored && (l == Places.Label.HOME || l == Places.Label.WORK) &&
                loc.time - placeSince >= 3 * 60_000L && !finishing && !tail) {
                TraceLog.event("false_start", "label" to l.name)
                Diag.mark(this, "还在${if (l == Places.Label.HOME) "家" else "公司"}，没出发，先收起")
                Scheduler.unmarkRan(this, trip.id); GeoWatch.unmarkToday(this, trip.id)
                finish(); return
            }
            // H3 兜底：进了家 / 公司（不是出发的那个地方）待满 3 分钟、行程还没结束 → 直接算到了
            // 一次车都没坐过就不按「到家/到公司」结束：分不清是到了还是根本没走（实测：首个定位晚到，在公司里被判成「到公司」）
            if ((l == Places.Label.HOME || l == Places.Label.WORK) && l != startLabel && !neverBoarded && !arrived && !tail && !finishing &&
                loc.time - startedAt > 5 * 60_000L && loc.time - placeSince >= 3 * 60_000L) {
                // 地铁最后一段还没出站：人可能刚出闸/还在通道里，不擅自结束
                if (metroStillInside()) {
                    if (!placeExitWaitLogged) {
                        placeExitWaitLogged = true
                        TraceLog.event("place_arrive_wait_exit", "label" to l.name)
                        Diag.mark(this, "已经${if (l == Places.Label.HOME) "到家" else "到公司"}附近，还在等地铁出站")
                    }
                    return
                }
                TraceLog.event("place_arrive", "label" to l.name, "leg" to legIdx, "riding" to riding)
                Diag.mark(this, "已经${if (l == Places.Label.HOME) "到家" else "到公司"}，行程结束")
                arrived = true
                if (metroRideOn) runCatching { endMetroRide() }
                stampNode(NodeLog.Kind.ARRIVE, 0, loc.time, NodeLog.Src.AUTO)
                placeVotes(); finish()   // 已在目的地，无需记录末段步行
            }
        }
    }

    /**
     * 结束时投起点票：真的坐过车才投（起点 = 出发前在家/公司里的第一个好定位）。
     * 终点不按下车站计票（下车后仍需步行，下车站并非家或公司），而由到站后的驻留位置计票，见 tailTick。
     */
    private var startVoted = false
    private fun placeVotes() {
        if (!::trip.isInitialized || startVoted) return
        startVoted = true
        runCatching {
            val boarded = boardedAt > 0 || riding || legIdx > 0
            placeStart?.takeIf { boarded }?.let { Places.voteTrip(this, true, startedAt, it.latitude, it.longitude, it.accuracy, it.time) }
        }
    }

    // ---------- 到站后的最后一段步行：只记录 + 学常去地点，不提醒、不上流体云 ----------
    private var tail = false
    private var tailStart = 0L
    private val tailFixes = ArrayDeque<Location>()
    private val TAIL_MS = 25 * 60_000L      // 最多记 25 分钟
    private val DWELL_MS = 5 * 60_000L      // 在同一处（60 米内）待满 5 分钟才算到了目的地
    private val DWELL_R = 60.0

    /** 到站结束：行程算完（不再恢复），但再低功耗记一会儿下车后的步行，人停下来或满 25 分钟就停 */
    private fun endTrip(by: String) {
        if (tail || finishing) return
        if (metroRideOn) runCatching { endMetroRide() }
        if (!arrived) { finish(); return }
        placeVotes()
        val now = System.currentTimeMillis()
        tail = true; tailStart = now; tailFixes.clear()
        clearRun()
        LiveState.clearPersisted(this)
        if (instance === this || instance == null) LiveState.snapshot.value = null
        LiveNotifier.replace(this, LiveNotifier.quiet(this, "${trip.name} 已到站", "正在记录前往目的地的最后一段路程（用于学习常去地点）"))
        TraceLog.event("tail_start", "by" to by)
        holdCpu(false)
        stepSensor(false)
        deadlineAt = now + TAIL_MS
        setPower(Power.LOW)
    }

    /**
     * 驻留判断：进入记录满 5 分钟后，最近 5 分钟的好定位（精度 ≤ 100 米）至少 3 个、跨度 ≥ 3 分钟、都在中心 60 米内，
     * 且最近 3 分钟走路不到 30 秒 → 人停下来了，结束记录（只留轨迹给分析用，不投票）。
     */
    private fun tailTick(now: Long): Long {
        val b = best
        if (b != null && b.time > tailStart && b.accuracy <= 100 && tailFixes.lastOrNull()?.time != b.time) {
            tailFixes.addLast(Location(b)); while (tailFixes.size > 60) tailFixes.removeFirst()
        }
        if (now - tailStart < DWELL_MS) return 30_000L
        val recent = tailFixes.filter { now - it.time <= DWELL_MS }
        if (recent.size < 3 || recent.last().time - recent.first().time < 3 * 60_000L) return 30_000L
        val wsum = recent.sumOf { 1.0 / it.accuracy.coerceAtLeast(10f) }
        val lat = recent.sumOf { it.latitude / it.accuracy.coerceAtLeast(10f) } / wsum
        val lng = recent.sumOf { it.longitude / it.accuracy.coerceAtLeast(10f) } / wsum
        val spread = recent.maxOf { Geo.distanceM(lat, lng, it.latitude, it.longitude) }
        if (spread > DWELL_R) return 30_000L
        val walking = TraceLog.recentWindows().count { it.t > now - 180_000L && AccMotion.classify(it) == AccMotion.Kind.WALK }
        if (walking > 15) return 30_000L
        val acc = recent.map { it.accuracy }.sorted()[recent.size / 2]
        // 停止移动仅用于结束记录，不参与终点计票；家和公司的位置由全天随机采样学习（PlaceSampler）
        TraceLog.event("tail_dwell", "after" to (now - tailStart) / 1000, "spread" to spread.toInt(), "acc" to acc.toInt())
        runCatching { learnLastWalk(lat, lng) }
        finish()
        return 60_000L
    }

    private var lastLocAt = 0L

    private fun onLocation(loc: Location) {
        val nowL = System.currentTimeMillis()
        // 定位整段断掉（系统冻结后台）：记下断了多久，给保活排查用
        if (lastLocAt > 0 && nowL - lastLocAt > 3 * 60_000L) TraceLog.event("loc_gap", "sec" to (nowL - lastLocAt) / 1000)
        lastLocAt = nowL
        TraceLog.loc(loc)
        placeFix(loc)
        val spd = speedOf(loc)
        val cur = best
        val fresher = cur == null || loc.time - cur.time > 15_000
        if (fresher || loc.accuracy <= cur!!.accuracy) best = loc
        if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsFix = loc
        if (loc.provider == LocationManager.GPS_PROVIDER && loc.accuracy <= 30f) goodGpsAt = maxOf(goodGpsAt, loc.time)
        // 第一段步行阶段（还没到上车点）：留下定位轨迹，给学步行用
        if (::trip.isInitialized && !tail && !firstWalkDone && legIdx == 0 && boardedAt == 0L) {
            walkFixes.addLast(WalkLearn.Fix(loc.time, loc.latitude, loc.longitude, loc.accuracy))
            while (walkFixes.size > 300) walkFixes.removeFirst()
        }
        if (!::trip.isInitialized || riding) return

        // 判上车用的速度只信 GPS（精度 ≤30 米、每个定位时刻只算一次）：网络定位常报假速度，出地铁口时尤其多
        val trusted = loc.provider == LocationManager.GPS_PROVIDER && loc.accuracy <= 30f && loc.time != lastSpeedFixT
        if (trusted) lastSpeedFixT = loc.time
        // 中途才打开：还没到过上车点、但速度像坐车且贴着这条线走 → 说明已经在车上了
        if (spd != null && trusted) {
            speeds.addLast(spd); if (speeds.size > 5) speeds.removeFirst()
            routeHits.addLast(onRoute(loc, trip.legs[legIdx])); if (routeHits.size > 5) routeHits.removeFirst()
            val onLine = routeHits.size >= 3 && routeHits.toList().takeLast(3).all { it }
            // 不限于首段或未到达上车点的情况：换乘后（boardedAt 已有值）且未在站旁获得定位的公交段同样适用
            if (!trip.legs[legIdx].isMetroOnly() &&
                (Motion.classify(speeds.toList(), onLine) == Motion.State.RIDE || busRideLikely())) {
                TraceLog.event("bus_board", "by" to "gps", "near" to (nearSince > 0), "spd" to speeds.toList().toString(), "onLine" to routeHits.count { it })
                speeds.clear(); routeHits.clear()
                onBoarded(manual = false); return
            }
        }

        if (spd != null && trusted && busJumpCheck(loc, spd)) return

        // 到过上车点、现在速度很快 → 大概率上车了
        // 地铁段不看车速：站口附近跑两步、打车到站口都会被当成「车开了」；地铁上车只认刷卡 / 地下基站 / 列车晃动
        if (nearSince > 0 && spd != null && trusted && spd > 6f && !trip.legs[legIdx].isMetroOnly()) {
            fastFixes++
            if (fastFixes >= 3) onBoarded(manual = false)
        } else if (spd != null && trusted && spd < 1f && fastFixes > 0) {
            fastFixes--   // 红灯/进站停一下不清零，真停下来才慢慢减
        }
        // 公交段：GPS 已经是车速却没判上车，记下卡在哪个条件上（每分钟最多一条）
        if (spd != null && trusted && spd > 6f && !trip.legs[legIdx].isMetroOnly() && !riding && loc.time - busFastLogged > 60_000L) {
            busFastLogged = loc.time
            TraceLog.event("bus_fast_wait", "spd" to spd, "near" to (nearSince > 0), "boardedAt" to boardedAt, "onLine" to routeHits.toList().takeLast(3).count { it },
                "route" to trip.legs[legIdx].candidates.count { routes[it.lineId] != null }, "fast" to fastFixes)
        }
    }

    // ---------- 中断恢复：把进行到哪一段存下来，App 被杀/重开后接着跑 ----------
    private fun runPrefs() = getSharedPreferences("run_state", MODE_PRIVATE)

    private fun saveRun(deadline: Long) {
        runPrefs().edit()
            .putString("trip", trip.id).putInt("leg", legIdx).putLong("boardedAt", boardedAt)
            .putBoolean("riding", riding).putLong("alightAt", alightAt).putLong("deadline", deadline).putLong("anchorAt", arriveAnchorAt)
            .putString("ridingCand", ridingCand?.let { "${it.name}|${it.stopName}" } ?: "")
            .putBoolean("manual", manualRun)
            .putInt("busSeg", busMaxSeg).putInt("joinLeg", joinLeg).putInt("joinSkip", joinSkip)
            .putInt("mAnchorIdx", metroAnchorIdx).putLong("mAnchorAt", metroAnchorAt)
            .putLong("arrAlert", arriveAlertedAt).putLong("arrAgain", arriveAgainAt)
            .putBoolean("overshoot", overshootAlerted).putInt("alightLeg", alightLeg).putLong("fpEtaAt", fpEtaAt)
            .putInt("sv", 2)
            .putLong("pendBoard", pendingBoardAt).putBoolean("boardDone", boardCommitted)
            .putString("pendLoc", pendingBoardLoc?.let { "${it.latitude},${it.longitude}" } ?: "")
            .putLong("nodeStart", nodeStartedAt)
            .apply()
    }

    private fun clearRun() = runPrefs().edit().clear().apply()

    private fun savedRunTrip(): String? = runPrefs().let { p ->
        p.getString("trip", null)?.takeIf { p.getLong("deadline", 0) > System.currentTimeMillis() }
    }

    /** 有同一趟行程的未结束进度就恢复，返回原来的截止时间；没有返回 null */
    // 行程中保持 CPU 不睡：实测熄屏后进程被冻结 4–5 分钟（传感器、基站、主循环全停），地铁里一点更新都没有
    private var wl: PowerManager.WakeLock? = null
    private fun holdCpu(on: Boolean) {
        runCatching {
            if (on) {
                if (wl == null) wl = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "busnotice:trip").apply { setReferenceCounted(false) }
                wl?.acquire(100 * 60_000L)   // 最多 100 分钟，防忘记释放
            } else wl?.takeIf { it.isHeld }?.release()
        }
    }

    private fun restoreRun(t: Trip): Long? {
        val p = runPrefs()
        val dl = p.getLong("deadline", 0)
        if (p.getString("trip", null) != t.id || dl <= System.currentTimeMillis() || p.getInt("sv", 0) != 2) { clearRun(); return null }
        val leg = p.getInt("leg", 0)
        if (leg !in t.legs.indices || p.getLong("boardedAt", 0) <= 0) { clearRun(); return null }
        legIdx = leg
        boardedAt = p.getLong("boardedAt", 0)
        riding = p.getBoolean("riding", false)
        alightAt = p.getLong("alightAt", 0)
        arriveAnchorAt = p.getLong("anchorAt", 0)
        manualRun = p.getBoolean("manual", manualRun)
        pendingBoardAt = p.getLong("pendBoard", 0L); boardCommitted = p.getBoolean("boardDone", false)
        pendingBoardLoc = (p.getString("pendLoc", "") ?: "").split(',').takeIf { it.size == 2 }?.let { (a, b) ->
            val la = a.toDoubleOrNull(); val lo = b.toDoubleOrNull()
            if (la == null || lo == null) null else Location("saved").apply { latitude = la; longitude = lo }
        }
        rideLearned = true // 中断过的这趟不再学乘车时长，免得样本不准
        p.getInt("joinLeg", -1).takeIf { it in t.legs.indices }?.let { pendingJoin = it to p.getInt("joinSkip", 0) }
        metroAnchorIdx = p.getInt("mAnchorIdx", -1); metroAnchorAt = p.getLong("mAnchorAt", 0L)
        // 已发出的提醒不重复发出（进程被系统终止后重启时，可能再次触发「准备下车」）
        arriveAlertedAt = p.getLong("arrAlert", 0L); arriveAgainAt = p.getLong("arrAgain", 0L)
        overshootAlerted = p.getBoolean("overshoot", false); alightLeg = p.getInt("alightLeg", -1)
        fpEtaAt = p.getLong("fpEtaAt", 0L)
        busMaxSeg = p.getInt("busSeg", -1); busSeg = busMaxSeg   // 站点列表不存，恢复后重新取
        nodeStartedAt = p.getLong("nodeStart", 0L)
        resetAlightGuard()
        restored = true
        val rc = p.getString("ridingCand", "") ?: ""
        ridingCand = t.legs[leg].candidates.firstOrNull { "${it.name}|${it.stopName}" == rc }
            ?: if (riding) t.legs[leg].candidates.firstOrNull() else null
        Diag.mark(this, "已恢复中断前的行程：第 ${leg + 1} 段${if (riding) "（车上）" else ""}")
        syncNodeCurrent()
        return dl
    }

    private var finishing = false
    private var trustFixSince = 0L

    /** 本服务正在跑的行程：主循环还活着才算 */
    fun runningId(): String? = if (::trip.isInitialized && !finishing && !tail && loop?.isActive == true) trip.id else null

    /** 打开 App：马上定一次位，按最新位置重算一遍（纠正段落、刷新胶囊） */
    @SuppressLint("MissingPermission")
    fun relocate() {
        val m = lm ?: getSystemService(LocationManager::class.java).also { lm = it } ?: return
        var kicked = false
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).forEach { pr ->
            runCatching {
                if (!m.isProviderEnabled(pr)) return@runCatching
                m.getCurrentLocation(pr, null, mainExecutor) { l ->
                    if (l == null || !::trip.isInitialized) return@getCurrentLocation
                    onLocation(l)
                    // 循环还没真正开始（刚点了运行）时别重启，否则截止时间为 0 会直接结束
                    if (deadlineAt > System.currentTimeMillis() && (!kicked || pr == LocationManager.GPS_PROVIDER)) {
                        kicked = true
                        loop?.cancel(); loop = scope.launch { runLoop(deadlineAt) }
                    }
                }
            }
        }
    }

    private var restored = false
    // 刷卡时位置对不上（比如刷了卡但定位离这段站点很远）：先记下来，之后定位对上了再按刷卡时刻补上
    private var deferredTapAt = 0L
    // 坐完地铁后有没有见过出站刷卡：地铁出站扣费也弹「消费成功」，第一次刷卡一律算出站
    private var metroExitSeen = false
    private var metroExitAt = 0L
    private var deferredTapKind: TransitSignal.Kind? = null
    private var stationByTap = false   // 进站时刻来自刷卡（比定位推断准）
    private var arriveAnchorAt = 0L    // 收到「准备下车」：列车刚离开下车站的前一站

    /** 最后一站间大概要跑多久：按两站距离估，查不到按 2 分钟 */
    private fun lastHopMs(i: Int): Long {
        val st = metroRoutes[i]?.stations
        if (st == null || st.size < 2) return 120_000L
        val a = st[st.size - 2]; val b = st.last()
        val d = Geo.distanceM(a.lat, a.lng, b.lat, b.lng)
        return (60_000L + (d / 16.7 * 1000).toLong()).coerceIn(90_000L, 300_000L)
    }

    /** 有「准备下车」锚点时，预计开门时刻；没有返回 null */
    private fun anchoredArrive(i: Int): Long? = arriveAnchorAt.takeIf { it > 0 }?.let { it + lastHopMs(i) }

    /** 别的 App 的出行通知（NFC 刷卡进/出站、系统到站提醒）：比定位和时间推算都准，直接校准进度 */
    fun onTransitSignal(kind: TransitSignal.Kind, text: String, at: Long) {
        scope.launch(Dispatchers.Main) { runCatching { applyTransit(kind, text, at) }.onFailure { Log.w(TAG, "出行通知处理失败", it) } }
    }

    // ---------- 半路上车：从地铁段沿线后面某一站进站 ----------
    private var joinLeg = -1          // 改过上车站的那段；-1 = 按配置的上车站
    private var joinSkip = 0          // 跳过了前面几站
    private var joinScale = 1.0       // 剩下的站数 / 全程站数
    private var joinOrigStop = ""     // 原来配置的上车站（查首末班用）
    private var joinStop = ""
    private var joinCandIdx = -1      // 定位第一次贴近的沿线站（要停留确认）
    private var joinCandAt = 0L
    private var joinCandFixT = 0L
    private var pendingJoin: Pair<Int, Int>? = null   // 被杀重启后，等途经站查到再恢复

    private fun dirKey(c: Candidate) =
        "${if (joinOrigStop.isNotEmpty() && c.stopName == joinStop) joinOrigStop else c.stopName}|${c.name}"

    private fun learnRide(i: Int, minutes: Int) {
        if (i == joinLeg) return   // 只坐了一部分，不学这段乘车时长
        Learn.recordRide(this, trip.id, i, minutes)
    }

    /**
     * 第 i 段地铁还没上车时，人离配置的上车站 600 米以上、却在沿线后面某站 300 米内：
     * 定位要隔 45 秒两次都贴着同一站才认（路过不算）；刷卡进站时位置对上直接认。
     */
    private fun tryJoin(loc: Location, i: Int, now: Long, byTap: Boolean, coarse: Boolean = false): Boolean {
        if (joinLeg >= 0 || i !in trip.legs.indices || i < legIdx) return false
        // 基站已确认在配置的上车站进站：之后人在沿线别的站附近只是车开过去了，不是半路上车
        if (originCellLeg == i && !byTap) return false
        val leg = trip.legs[i]
        if (!leg.isMetroOnly() || (i == legIdx && (riding || stationByTap))) return false
        if (i == legIdx && i > 0 && boardedAt > 0 && alightAt == 0L) return false   // 还在上一段车上
        val r = metroRoutes[i] ?: return false
        if (r.stations.size < 3) return false
        val c = leg.candidates.firstOrNull { it.isMetro() } ?: return false
        val slack = loc.accuracy.coerceAtMost(if (coarse) 500f else 150f)
        val fromOrigin = Geo.distanceM(loc.latitude, loc.longitude, c.lat, c.lng)
        if (fromOrigin < 600 || (coarse && fromOrigin - loc.accuracy < 500)) return false
        val j = JoinPick.pick(r.stations.map { it.lat to it.lng }, loc.latitude, loc.longitude, 300.0 + slack) ?: run { joinCandIdx = -1; return false }
        if (!byTap) {
            // 粗定位：两次不同时刻的定位（隔 20 秒以上）指向同一站；准定位：贴着同一站 45 秒
            if (joinCandIdx != j) { joinCandIdx = j; joinCandAt = now; joinCandFixT = loc.time; return false }
            if (coarse) { if (loc.time - joinCandFixT < 20_000L) return false }
            else if (now - joinCandAt < 45_000L) return false
        }
        applyJoin(i, j)
        return true
    }

    private fun applyJoin(i: Int, j: Int) {
        val r = metroRoutes[i] ?: return
        if (j < 1 || j >= r.stations.size - 1) return
        val st = r.stations[j]
        val leg = trip.legs[i]
        joinScale = (r.hops - j).toDouble() / r.hops
        joinOrigStop = leg.candidates.firstOrNull { it.isMetro() }?.stopName ?: ""
        joinStop = st.name
        joinLeg = i; joinSkip = j
        val legs = trip.legs.toMutableList()
        legs[i] = leg.copy(
            candidates = leg.candidates.map { if (it.isMetro()) it.copy(stopName = st.name, lat = st.lat, lng = st.lng) else it },
            rideMin = (leg.rideMin * joinScale).toInt().coerceAtLeast(1),
        )
        trip = trip.copy(legs = legs)
        val sub = MetroRoute(r.stations.drop(j), (r.durSec * joinScale).toInt())
        metroRoutes[i] = sub; metroRideSec[i] = sub.durSec
        if (i == 0) firstWalkDone = true   // 不是从平常的地方出发，首段步行不学
        nearSince = 0L
        TraceLog.event("join", "leg" to i, "skip" to j, "left" to sub.hops)
        Diag.mark(this, "从沿线 ${st.name} 上车，按剩下 ${sub.hops} 站算")
        saveRun(deadlineAt)
        kick()
    }

    /** 当前公交段有车正在进站 / 停在站上（刷卡时刻前后 90 秒） */
    private fun busAtStopNow(at: Long): Boolean =
        trip.legs[legIdx].candidates.any { BusSampler.busAtStop(it, at) }

    /**
     * 通知栏里「N 分钟前」的刷卡（当时没读到）：坐完地铁、还没认出出站、也还没上公交时，
     * 那一下就是出站；当时公交站正好有车的话按上公交算。其余情况不补，免得重复。
     */
    fun onStaleTap(at: Long) {
        scope.launch(Dispatchers.Main) {
            runCatching {
                if (!::trip.isInitialized || tail || riding) return@runCatching
                // 公交段还没上车：刷卡弹窗当时没读到、1–2 分钟后才在通知栏看到 → 按刷卡时刻补认上车（照样要位置对得上）
                val cur = trip.legs[legIdx]
                if (!cur.isMetroOnly() && (legIdx == 0 || !trip.legs[legIdx - 1].isMetroOnly()) && System.currentTimeMillis() - at <= 3 * 60_000L) {
                    TraceLog.event("tap_backfill", "as" to "BUS_BOARD", "leg" to legIdx, "at" to at)
                    applyTransit(TransitSignal.Kind.TAP, "补读刷卡", at); return@runCatching
                }
                if (metroExitSeen || legIdx == 0) return@runCatching
                if (!trip.legs[legIdx - 1].isMetroOnly() || trip.legs[legIdx].isMetroOnly()) return@runCatching
                if (boardedAt <= 0 || at - boardedAt < 3 * 60_000L) return@runCatching
                val bus = busAtStopNow(at) && outsideBy(if (alightAt > 0) alightAt else boardedAt, at)
                TraceLog.event("tap_backfill", "as" to if (bus) "BUS_BOARD" else "EXIT", "leg" to legIdx, "at" to at)
                applyTransit(if (bus) TransitSignal.Kind.TAP else TransitSignal.Kind.EXIT, "补读刷卡", at)
            }.onFailure { Log.w(TAG, "补读刷卡失败", it) }
        }
    }

    private var ttLogged = 0L
    private var geoStart = false
    private var screenRegistered = false
    private val screenRx = Health.screenReceiver()

    /** 关键时刻的闹钟到了：马上刷新一次（进程刚被叫醒） */
    fun onWakeAlarm() { scope.launch(Dispatchers.Main) { kick() } }

    private fun kick() {
        if (finishing || deadlineAt <= System.currentTimeMillis()) return
        loop?.cancel(); loop = scope.launch { runLoop(deadlineAt) }
    }

    /** 刷卡时刻前后 2 分钟内、精度 200 米内的定位；没有就返回 null（地下、刚启动），这时退回按段顺序判断 */
    private fun tapFix(at: Long): Location? =
        best?.takeIf { it.accuracy < 200 && kotlin.math.abs(at - it.time) < 120_000 && System.currentTimeMillis() - it.time < 180_000 }

    /** 定位是否在第 i 段公交的上车站附近（300 米）或贴着这条线（150 米） */
    private fun nearBusLeg(loc: Location, i: Int): Boolean {
        val leg = trip.legs[i]
        val slack = loc.accuracy.coerceAtMost(100f)
        if (leg.candidates.any { !it.lat.isNaN() && Geo.distanceM(loc.latitude, loc.longitude, it.lat, it.lng) <= 300 + slack }) return true
        val pts = leg.candidates.filter { !it.isMetro() }.mapNotNull { routes[it.lineId] }.flatten()
        return pts.any { Geo.distanceM(loc.latitude, loc.longitude, it.first, it.second) <= 150 + slack }
    }

    /** 定位是否在第 i 段地铁的进站点 500 米内 */
    private fun nearMetroLeg(loc: Location, i: Int): Boolean {
        val slack = loc.accuracy.coerceAtMost(150f)
        return trip.legs[i].candidates.any { !it.lat.isNaN() && Geo.distanceM(loc.latitude, loc.longitude, it.lat, it.lng) <= 500 + slack }
    }

    private fun deferTap(kind: TransitSignal.Kind, at: Long, why: String) {
        deferredTapAt = at; deferredTapKind = kind
        TraceLog.event("tap_deferred", "kind" to kind.name, "leg" to legIdx, "why" to why)
        Diag.mark(this, "刷卡但位置对不上（$why），先不切换，等定位确认")
    }

    /** 每轮刷新：之前位置对不上的刷卡，5 分钟内定位对上了就按刷卡时刻补上；超时作废 */
    private fun retryDeferredTap(now: Long) {
        val k = deferredTapKind ?: return
        if (now - deferredTapAt > 5 * 60_000L || riding) { deferredTapKind = null; deferredTapAt = 0L; return }
        val cur = trip.legs[legIdx]
        val afterMetro = legIdx > 0 && trip.legs[legIdx - 1].isMetroOnly() && !cur.isMetroOnly()
        val loc = best?.takeIf { it.accuracy < 200 && now - it.time < 60_000 &&
            (!afterMetro || (it.provider == LocationManager.GPS_PROVIDER && it.accuracy <= 50f)) } ?: return
        val metroTarget = if (cur.isMetroOnly()) legIdx else (legIdx + 1 until trip.legs.size).firstOrNull { trip.legs[it].isMetroOnly() }
        val ok = when {
            k == TransitSignal.Kind.ENTER -> metroTarget != null && nearMetroLeg(loc, metroTarget)
            cur.isMetroOnly() -> nearMetroLeg(loc, legIdx)
            else -> nearBusLeg(loc, legIdx)
        }
        if (!ok) return
        val at = deferredTapAt
        deferredTapKind = null; deferredTapAt = 0L
        TraceLog.event("tap_confirmed", "kind" to k.name, "leg" to legIdx)
        applyTransit(k, "补认刷卡", at, geoChecked = true)
    }

    private fun applyTransit(kind: TransitSignal.Kind, text: String, at: Long, geoChecked: Boolean = false) {
        if (!::trip.isInitialized || tail) return
        val last = trip.legs.size - 1
        when (kind) {
            TransitSignal.Kind.ENTER -> {
                if (riding) return
                // 已经按刷卡进过站：同一次进站的重复通知不算；之前只是定位推断的，用刷卡时刻覆盖
                if (trip.legs[legIdx].isMetroOnly() && stationByTap && stationAt > 0 && at - stationAt < 10 * 60_000L) return
                // 地理围栏：刷卡时有可靠定位、且离要切到的那段地铁站很远 → 先不认，等定位确认
                val target = if (trip.legs[legIdx].isMetroOnly()) legIdx else (legIdx + 1..last).firstOrNull { trip.legs[it].isMetroOnly() } ?: return
                val fix = if (geoChecked) null else tapFix(at)
                if (fix != null && !nearMetroLeg(fix, target) && !tryJoin(fix, target, at, byTap = true)) { deferTap(TransitSignal.Kind.ENTER, at, "离地铁站远"); return }
                if (!trip.legs[legIdx].isMetroOnly()) {
                    // 人已经在后面那段地铁的站里：前面各段视为已完成，直接等车
                    while (legIdx < target) advanceLeg(at)
                }
                stationAt = at; stationByTap = true   // 刷卡时刻就是进站时刻，按它之后第一班车算上车
                stampNode(NodeLog.Kind.ENTER, legIdx, at, NodeLog.Src.TAP)
                resetAlightGuard()
                syncNodeCurrent()
                Diag.mark(this, "刷卡进站，切到地铁段")
                kick()
            }
            TransitSignal.Kind.EXIT -> {
                val cur = trip.legs[legIdx]
                val prevMetro = legIdx > 0 && trip.legs[legIdx - 1].isMetroOnly()
                if (prevMetro || cur.isMetroOnly()) { metroExitSeen = true; metroExitAt = at }
                when {
                    // 本段地铁出站 = 到站：最后一段结束，中间段转下一段（和终点站同一套流程）
                    cur.isMetroOnly() && (riding || stationAt > 0) -> {
                        stampNode(NodeLog.Kind.EXIT, legIdx, at, NodeLog.Src.TAP)
                        if (riding) markAlight(at)
                        if (legIdx == last) {
                            Diag.mark(this, "刷卡出站，行程结束")
                            arrived = true
                            endTrip("tap_exit")
                        } else {
                            Diag.mark(this, "刷卡出站，换乘下一段")
                            advanceLeg(if (riding) at else System.currentTimeMillis())
                        }
                        return
                    }
                    prevMetro && alightAt == 0L -> {
                        stampNode(NodeLog.Kind.EXIT, legIdx - 1, at, NodeLog.Src.TAP)
                        markAlight(at)
                    }
                }
                kick()
            }
            TransitSignal.Kind.APPROACH -> Unit
            TransitSignal.Kind.TAP -> {
                // 余额通知只说明「刚刷了卡」：按现在在哪一段来解释成进站 / 出站 / 上公交
                val cur = trip.legs[legIdx]
                val prevMetro = legIdx > 0 && trip.legs[legIdx - 1].isMetroOnly()
                val sinceExit = at - metroExitAt
                val meaning = when {
                    // 正在坐地铁：一次刷卡多半是出站扣费
                    // 例外：已经有人在地面的证据（出站那下没读到，这次是上公交）
                    riding && cur.isMetroOnly() && !metroExitSeen && at - boardedAt > 3 * 60_000L &&
                        !outsideBy(if (alightAt > 0) alightAt else boardedAt, at) -> TransitSignal.Kind.EXIT
                    // 还在地铁上（没下车）且已坐了 3 分钟以上：出站
                    riding && cur.isMetroOnly() && at - boardedAt > 3 * 60_000L -> TransitSignal.Kind.EXIT
                    // 当前段是地铁：以为还在站台等车，但进站已经 3 分钟以上又刷卡 → 只能是出站
                    // 只有刷卡进过站才算：进站只是推断出来的（定位 / 时间），这次刷卡多半就是过闸进站
                    cur.isMetroOnly() && !riding && stationAt > 0 && stationByTap && at - stationAt > 3 * 60_000L -> TransitSignal.Kind.EXIT
                    // 刚出站 1 分钟内又来一次：同一次出站的重复，不算上公交
                    metroExitSeen && sinceExit < 60_000L -> return
                    // 当前段是地铁、没在车上：进站（之前的进站刷卡 10 分钟内的重复不算）
                    cur.isMetroOnly() && !riding && !(stationByTap && stationAt > 0 && at - stationAt < 10 * 60_000L) -> TransitSignal.Kind.ENTER
                    // 当前段为公交且尚未上车：刷卡即视为上车（交通卡仅在乘车时使用，无需先到达站牌附近）
                    !cur.isMetroOnly() && !riding -> null
                    // 自动判定上车后 5 分钟内出现刷卡：说明判定偏早，上车时刻以刷卡时刻为准
                    !cur.isMetroOnly() && riding && busBoardGuessed && at >= boardedAt && at - boardedAt < 5 * 60_000L -> {
                        TraceLog.event("tap_reanchor", "leg" to legIdx, "was" to boardedAt, "at" to at)
                        Diag.mark(this, "刷卡确认上公交，上车时刻按刷卡校正")
                        busBoardGuessed = false; boardedAt = at; resetBusRide(); kick(); return
                    }
                    else -> return
                }
                val bal = Regex("余额为?\\s*([0-9]+(?:\\.[0-9]+)?)").find(text)?.groupValues?.get(1)
                TraceLog.event("tap", "as" to (meaning?.name ?: "BUS_BOARD"), "leg" to legIdx, "bal" to bal,
                    "ug" to (lastUgAt > 0 && surfaceSince == 0L), "gpsAgo" to (if (goodGpsAt > 0) (at - goodGpsAt) / 1000 else null))
                if (meaning != null) { applyTransit(meaning, text, at, geoChecked); return }
                // 当成上公交之前看位置：在这段公交站/沿线附近才算；若位于后续某段的地铁站口 → 按进站处理；均不匹配时先记录，待后续确认
                // 刚出地铁：网络定位（±100 米）在换乘站常常「贴着」公交站，只认 GPS
                val fix = if (geoChecked) null else tapFix(at)?.takeIf { !prevMetro || (it.provider == LocationManager.GPS_PROVIDER && it.accuracy <= 50f) }
                // 刚出地铁：换乘站离公交站往往很近，只认公交站 150 米内（不认沿线）
                val busOk = { l: Location -> if (prevMetro) trip.legs[legIdx].candidates.any { !it.lat.isNaN() &&
                    Geo.distanceM(l.latitude, l.longitude, it.lat, it.lng) <= 150 + l.accuracy.coerceAtMost(50f) } else nearBusLeg(l, legIdx) }
                if (fix != null && !busOk(fix)) {
                    val m = (legIdx + 1..last).firstOrNull { trip.legs[it].isMetroOnly() && nearMetroLeg(fix, it) }
                    if (m != null) { applyTransit(TransitSignal.Kind.ENTER, text, at, geoChecked = true); return }
                    deferTap(TransitSignal.Kind.TAP, at, "离公交站远"); return
                }
                Diag.mark(this, "刷卡上公交")
                boardingByTap = true
                try { onBoarded(manual = false, at = at) } finally { boardingByTap = false }
                kick()
            }
            TransitSignal.Kind.ARRIVE -> {
                // 「准备下车」= 列车刚离开前一站、快到下车站了；只当锚点，不当下车
                if (arriveAnchorAt > 0 && at - arriveAnchorAt < 10 * 60_000L) return
                fun alightName(i: Int) = legAlightName(i).takeIf { it != "下车站" }
                fun matches(i: Int) = alightName(i)?.let { text.contains(it.removeSuffix("站")) } == true
                val cur = trip.legs[legIdx]
                // 当前状态仍为站台候车：说明实际已上车，按进站后的首班车切换
                if (cur.isMetroOnly() && !riding && stationAt > 0 && matches(legIdx)) {
                    onBoarded(manual = false, at = nextTrainAfter(cur, stationAt) ?: stationAt)
                }
                val metroIdx = when {
                    riding && trip.legs[legIdx].isMetroOnly() && matches(legIdx) -> legIdx
                    !riding && legIdx > 0 && trip.legs[legIdx - 1].isMetroOnly() && alightAt == 0L && matches(legIdx - 1) -> legIdx - 1
                    else -> return
                }
                arriveAnchorAt = at
                TraceLog.event("arrive_anchor", "leg" to metroIdx, "expect" to (at + lastHopMs(metroIdx)))
                Diag.mark(this, "快到${alightName(metroIdx)}，按播报校准")
                val atTarget = Regex("到了|已到站|到站了|即将到达|即将到站").containsMatchIn(text)
                if (metroIdx == legIdx) {
                    if (atTarget) {
                        announceAtTarget = true
                        fireAlight(AlightGuard.Stage.AT, metroIdx, at, force = true)
                    } else if (alightStage == AlightGuard.Stage.NONE) {
                        fireAlight(AlightGuard.Stage.T1, metroIdx, at, force = true)
                    }
                }
                saveRun(deadlineAt)
                kick()
            }
        }
    }

    // ---------- 学步行（只学不用）：出发地 → 第一段上车点、最后下车 → 目的地 ----------
    private val walkFixes = ArrayDeque<WalkLearn.Fix>()
    private var firstWalkDone = false
    private var userRestarted = false

    /** 第一段：走到上车站附近（公交）或进了站（地铁）的那一刻，结算这段步行 */
    private fun checkFirstWalk() {
        if (firstWalkDone || restored || legIdx != 0) return
        if (nearSince == 0L && stationAt == 0L) return
        firstWalkDone = true
        val end = if (stationAt > 0) stationAt else nearSince
        val wins = TraceLog.recentWindows()
        val start = WalkLearn.walkStart(wins, startedAt - 30_000L, end) ?: return
        // 启动时已在步行（离开出发地后启动或手动重新开始）：时长仅为下限，步速仍可用
        val partial = start - startedAt < 30_000L || userRestarted
        val s = WalkLearn.build(LocalDate.now().toEpochDay(), "first", start, end, walkFixes.toList(), wins, partial) ?: return
        WalkLearn.record(this, trip.id, s)
        TraceLog.event("walk_learn", "kind" to "first", "dur" to s.durSec, "path" to s.pathM, "walk" to s.walkSec, "spd" to s.speed, "partial" to partial)
    }

    /** 最后一段：从下车 / 出站（尾段记录开始）到在终点停下来的那一刻 */
    private fun learnLastWalk(lat: Double, lng: Double): Long? {
        if (restored) return null
        val fixes = tailFixes.map { WalkLearn.Fix(it.time, it.latitude, it.longitude, it.accuracy) }
        val arrive = WalkLearn.arriveAt(fixes, lat, lng) ?: return null
        stampNode(NodeLog.Kind.ARRIVE, 0, arrive, NodeLog.Src.AUTO)
        val wins = TraceLog.recentWindows()
        val s = WalkLearn.build(LocalDate.now().toEpochDay(), "last", tailStart, arrive, fixes, wins, partial = false) ?: return arrive
        WalkLearn.record(this, trip.id, s)
        TraceLog.event("walk_learn", "kind" to "last", "dur" to s.durSec, "path" to s.pathM, "walk" to s.walkSec, "spd" to s.speed)
        return arrive
    }

    // ---------- 地铁基站：学地下专网区号判进出站，顺便记沿途基站序列（只存本机） ----------
    private var tacLearner: MetroTacLearner? = null
    private val metroGate = MetroGate { tac -> tacLearner?.isMetroTac(tac) == true }
    private val fpRec = MetroFpRecorder()
    private var fpEta: Map<Long, Long> = emptyMap()
    private val fpSeen = HashSet<Long>()
    /** 各段地铁的 E1 模型（后台读一次；读历史记录要一两秒，不放主线程） */
    private val etaCache = java.util.concurrent.ConcurrentHashMap<String, Map<Long, Long>>()
    private val etaLoading = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun etaFor(legI: Int): Map<Long, Long>? {
        val key = trip.id.take(8) + ":" + legI
        etaCache[key]?.let { return it }
        if (etaLoading.add(key)) {
            val single = trip.legs.count { it.isMetroOnly() } == 1
            val learner = tacLearner
            scope.launch(Dispatchers.IO) {
                val m = MetroCells.eta(applicationContext, key, if (single && learner != null) ({ t: Int? -> learner.isMetroTac(t) }) else null)
                etaCache[key] = m
                withContext(Dispatchers.Main) { TraceLog.event("fp_eta_load", "key" to key, "cells" to m.size) }
            }
        }
        return null
    }

    /**
     * G5：没在车上（中途才打开 / 重启 / 判漏了上车），却在地下见到一个学过的、不是上车站的基站
     * → 其实已经在车上了：直接切到这段地铁「车上」，上车时刻和到站时刻都按学到的剩余时间倒推。
     */
    private fun joinByCell(s: CellSnap, underground: Boolean): Boolean {
        if (!underground || riding && trip.legs[legIdx].isMetroOnly()) return false
        val e = s.enb?.toLong() ?: return false
        val m = listOf(legIdx, legIdx + 1).firstOrNull { it < trip.legs.size && trip.legs[it].isMetroOnly() } ?: return false
        val model = etaFor(m) ?: return false
        val rem = model[e] ?: return false
        val maxRem = model.values.maxOrNull() ?: return false
        if (maxRem - rem < 150_000L) return false   // 还在上车站附近：照常等刷卡 / 列车开动
        val boardAt = s.t - ((depRem(model) ?: maxRem) - rem).coerceAtLeast(0L)   // 开车时刻，不含站台候车
        TraceLog.event("metro_join_cell", "leg" to m, "enb" to e, "remS" to rem / 1000, "boardAt" to boardAt)
        Diag.mark(this, "认出地铁沿途的基站，判断已在车上")
        var n = 0
        while (legIdx < m && n++ < 4) advanceLeg(boardAt)
        if (legIdx == m && !riding) onBoarded(manual = false, at = boardAt)
        if (!inMetroRide()) return false
        boardedAt = boardAt
        fpEta = model; fpEtaAt = s.t + rem
        saveRun(deadlineAt); kick()
        return true
    }
    /** E1：按基站学到的预计到下车站时刻（0 = 没有） */
    private var fpEtaAt = 0L
    private var metroRideOn = false
    private var metroRideKey = ""
    private var metroRideStart = 0L
    private var busMovingOn = false
    private var cellExitAt = 0L          // 基站判断出站的时刻（离开地下区号的第一次采样）
    private var lastUgAt = 0L            // 最后一次连着地下区号的采样时刻
    private var surfaceSince = 0L        // 离开地下区号后第一次地面采样（0 = 现在还在地下 / 没数据）
    private var goodGpsAt = 0L           // 最后一次精度 30 米内的 GPS（地下拿不到，有它说明人在地面）

    /**
     * 坐完地铁后，到 at 为止有没有「人已经在地面」的证据：ref 之后有精度 30 米内的 GPS，
     * 或者离开地下区号已经 1 分钟以上。只按时间猜不算（实测：判下车后在站内等了 12 分钟才刷卡出站）。
     */
    private fun outsideBy(ref: Long, at: Long): Boolean {
        if (goodGpsAt > ref && goodGpsAt <= at) return true
        if (surfaceSince > 0 && surfaceSince > lastUgAt && lastUgAt > 0 && at - surfaceSince >= 60_000L) return true
        if (cellExitAt > 0 && at - cellExitAt >= 60_000L) return true
        return false
    }

    /** 正在坐地铁：当前这一段已上车且是地铁 */
    private fun inMetroRide(): Boolean = riding && trip.legs[legIdx].isMetroOnly()

    /** 坐着公交、车在路上跑（GPS 速度 ≥ 4 m/s）：这时见到的区号肯定是地面的 */
    private fun busMoving(now: Long): Boolean {
        val onBus = riding && !trip.legs[legIdx].isMetroOnly()
        val fast = best?.let { now - it.time < 30_000 && it.hasSpeed() && it.speed >= 4f } == true
        return onBus && fast
    }

    private fun onCell(s: CellSnap) {
        if (!::trip.isInitialized || tail || finishing) return
        val l = tacLearner ?: MetroCells.load(this).also { tacLearner = it }
        val underground = l.isMetroTac(s.nrTac)
        // 只看有 5G 区号的采样：地下掉到只有 4G 时没有区号，不能当成到了地面
        if (s.nrTac != null) { if (underground) { lastUgAt = s.t; surfaceSince = 0L } else if (surfaceSince == 0L) surfaceSince = s.t }
        if (!inMetroRide()) joinByCell(s, underground)
        val inRide = inMetroRide()
        if (inRide) {
            val legM = legIdx
            if (!metroRideOn) {
                metroRideOn = true; metroRideStart = s.t
                metroRideKey = trip.id.take(8) + ":" + legM
                fpSeen.clear()
            }
            if (fpEta.isEmpty()) etaFor(legM)?.let { fpEta = it }
            l.sampleRide(s); fpRec.sample(s, underground)
            // E1：地下见到一个新基站、学过它离下车站还有多久 → 用它校准到站时刻
            val e = s.enb?.toLong()
            val maxRem = fpEta.values.maxOrNull() ?: 0L
            // 上车站的基站（含站台等车）等多久不定，不拿来校准；中途重启时第一个基站已在路上，照样用
            if (underground && e != null && fpSeen.add(e)) fpEta[e]?.takeIf { maxRem - it >= 120_000L }?.let { rem ->
                fpEtaAt = s.t + rem
                TraceLog.event("fp_eta", "enb" to e, "remS" to rem / 1000, "eta" to fpEtaAt)
            }
        } else if (metroRideOn) endMetroRide()
        if (busMoving(s.t)) { busMovingOn = true; l.sampleSurface(s) }
        else if (busMovingOn) { busMovingOn = false; l.endSurface(); MetroCells.save(this, l) }

        val edge = metroGate.feed(s) ?: return
        val last = trip.legs.size - 1
        val cur = trip.legs[legIdx]
        when (edge.first) {
            MetroGate.Edge.ENTER -> {
                // 当前该进地铁站、还没判进站：连上地下区号 = 进站（刷卡来了会用刷卡时刻覆盖）
                if (!cur.isMetroOnly() || riding || stationAt > 0) return
                // 有可靠定位且离这段地铁站很远（比如坐公交路过别的地铁站）：不算
                val fix = best?.takeIf { it.accuracy < 200 && s.t - it.time < 60_000 }
                if (fix != null && !nearMetroLeg(fix, legIdx)) { TraceLog.event("cell_enter_skip", "leg" to legIdx); return }
                stationAt = edge.second; stationByTap = false
                val origin = cur.candidates.firstOrNull { it.isMetro() }
                if (fix != null && origin != null && Geo.distanceM(fix.latitude, fix.longitude, origin.lat, origin.lng) < 600 + fix.accuracy.coerceAtMost(200f))
                    originCellLeg = legIdx
                TraceLog.event("cell_enter", "leg" to legIdx, "origin" to (originCellLeg == legIdx))
                Diag.mark(this, "连上地铁里的基站，判断已进站")
                saveRun(deadlineAt); kick()
            }
            MetroGate.Edge.EXIT -> {
                if (!inRide) return
                cellExitAt = edge.second
                TraceLog.event("cell_exit", "leg" to legIdx, "at" to edge.second)
                when {
                    riding && cur.isMetroOnly() && legIdx == last -> {
                        endMetroRide()
                        // 基站离开地下 = 出站；下车时间按同一时刻补记
                        stampNode(NodeLog.Kind.EXIT, legIdx, edge.second, NodeLog.Src.AUTO)
                        markAlight(edge.second, by = "cell")
                        Diag.mark(this, "离开地铁里的基站，判断已出站，行程结束")
                        arrived = true
                        endTrip("cell_exit")
                        return
                    }
                    riding && cur.isMetroOnly() -> {
                        // 中间段出站 = 本段到站，转下一段
                        Diag.mark(this, "离开地铁里的基站，判断已到换乘站")
                        markAlight(edge.second, by = "cell")
                        advanceLeg(System.currentTimeMillis())
                        return
                    }
                    !riding && legIdx > 0 && trip.legs[legIdx - 1].isMetroOnly() && alightAt == 0L -> {
                        endMetroRide()
                        stampNode(NodeLog.Kind.EXIT, legIdx - 1, edge.second, NodeLog.Src.AUTO)
                        markAlight(edge.second, by = "cell")
                    }
                }
                kick()
            }
        }
    }

    /** 一趟地铁结束：结算区号学习，存下沿途基站序列 */
    private fun endMetroRide() {
        metroRideOn = false
        val l = tacLearner ?: return
        val tac = l.endRide()
        MetroCells.save(this, l)
        fpEtaAt = 0L; fpSeen.clear(); fpEta = emptyMap()
        // 半路上车的也存：E1 只看到终点还剩多久，从哪站上都一样
        fpRec.finish()?.let { seq ->
            MetroCells.appendRide(this, metroRideKey, org.json.JSONObject()
                .put("start", metroRideStart).put("end", System.currentTimeMillis())
                .put("board", boardedAt).put("tacOk", tac != null).put("mt", true).put("join", joinLeg >= 0).put("seq", seq))
        }
        TraceLog.event("metro_cells", "tacOk" to (tac != null), "learned" to l.hasLearned(), "rides" to MetroCells.rideCount(this))
    }

    /** 换乘段：出站时刻就是下地铁时刻，顺便学这段地铁实际坐了多久 */
    private fun markAlight(at: Long, by: String = "tap") {
        if (alightLeg == legIdx) return
        alightLeg = legIdx
        alightAt = at; stepSensor(false)
        stampNode(NodeLog.Kind.ALIGHT, legIdx, at,
            if (by == "tap") NodeLog.Src.TAP else if (by == "user") NodeLog.Src.MANUAL else NodeLog.Src.AUTO)
        resetAlightGuard()
        syncNodeCurrent()
        TraceLog.event("alight_detect", "leg" to legIdx, "by" to by, "at" to at)
        if (!rideLearned && !restored && boardedAt > 0) {
            rideLearned = true
            learnRide(legIdx, ((at - boardedAt) / 60_000L).toInt())
        }
        Diag.mark(this, if (by == "tap") "刷卡出站，开始换乘" else "已到换乘点，开始换乘")
        saveRun(deadlineAt)
    }

    /** 用户点「结束」：这是真实到站/放弃的时刻，记录下来作为对照 */
    fun userFinish() {
        TraceLog.event("user_end", "leg" to legIdx, "riding" to riding)
        scope.launch(Dispatchers.Main) { loop?.cancel(); finish() }
    }

    private fun finish() {
        if (finishing) return
        finishing = true
        holdCpu(false)
        WakeAlarm.cancel(this)
        AlarmBuzzer.stop()
        if (metroRideOn) runCatching { endMetroRide() }
        if (busMovingOn) runCatching { busMovingOn = false; tacLearner?.let { it.endSurface(); MetroCells.save(this, it) } }
        TraceLog.onCell = null
        if (!tail) placeVotes()
        if (tail) TraceLog.event("tail_end", "after" to (System.currentTimeMillis() - tailStart) / 1000)
        settleNodes(aborted = !arrived && !tail)
        TraceLog.event("finish", "leg" to legIdx, "riding" to riding)
        TraceLog.stop()
        NodeLog.current.value = null
        clearRun()
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        stepSensor(false)
        power = null; highSince = 0L; coolUntil = 0L
        LiveState.clearPersisted(this)
        if (instance === this || instance == null) {
            LiveState.snapshot.value = null
            // 显式取消，避免部分系统 stopForeground 后还留着流体云的壳或提醒通知
            LiveNotifier.clear(this)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Diag.mark(this, "App 从最近任务里被划掉（服务应继续）")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        holdCpu(false)
        if (screenRegistered) { runCatching { unregisterReceiver(screenRx) }; screenRegistered = false }
        if (!finishing) { Diag.mark(this, "服务被系统停止（非主动结束）"); TraceLog.stop() }
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        // 只有自己还是当前实例（或没有更新的实例）时才清内存里的看板，免得旧实例的 onDestroy 把新实例刚设的状态清掉。
        // 不是主动结束（被系统停掉、之后会恢复）：本地存的那份留着，重开 App 时还能显示
        val mine = instance === this || instance == null
        if (instance === this) { instance = null; TraceLog.onCell = null }
        if (mine) LiveState.snapshot.value = null
        scope.cancel()
        super.onDestroy()
    }
}

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_BOARDED = "com.busnotice.commute.BOARDED"
        const val ACTION_STOP = "com.busnotice.commute.STOP"
        const val ACTION_DISMISSED = "com.busnotice.commute.DISMISSED"
        const val ACTION_ALIGHT_ACK = "com.busnotice.commute.ALIGHT_ACK"
        const val ACTION_STAMP = "com.busnotice.commute.STAMP"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = CommuteService.instance
        // 演示回放中：「关闭」结束回放，其他按钮不做事（不能去碰真实行程）
        if (svc == null && DemoPlayer.running) {
            if (intent.action == ACTION_STOP) DemoPlayer.stop(ctx)
            return
        }
        when {
            intent.action == ACTION_BOARDED && svc != null -> svc.onBoarded()
            intent.action == ACTION_ALIGHT_ACK && svc != null -> svc.ackAlight()
            intent.action == ACTION_STAMP && svc != null -> svc.stampCurrentNode()
            intent.action == ACTION_DISMISSED -> {
                // 被划掉（或被系统移除）：行程照常进行，胶囊先隐藏，等下一次提醒或打开 App 再出来
                Diag.mark(ctx, "胶囊被移除（划掉或系统移除），行程继续在后台")
                if (svc != null) LiveNotifier.hidden = true
            }
            intent.action == DepartAdvisor.ACTION_MUTE -> DepartAdvisor.mute(ctx)
            intent.action == ACTION_STOP -> CommuteService.stop(ctx)
            else -> CommuteService.stop(ctx)
        }
    }
}
