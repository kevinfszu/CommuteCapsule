package com.busnotice.commute

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 演示回放（录屏做宣传片用）。
 *
 * 一趟压缩过的上班行程：步行到 11 号线逸景路站 → 外环 6 站到员村 → 站内换乘 5 号线 1 站到科韵路
 * → 出站步行到广州国际金融城。站名取自公开资料，时刻为模拟值；不读真实数据、不联网、不写行程记录。
 *
 * 看板、节点时间轴、到站提醒都走和真实行程同一套界面与文案；演示里的按钮可点，
 * 但只推进这段模拟，不会去碰真实行程。固定 30×，整段约 1 分钟。
 */
object DemoPlayer {
    const val TRIP_ID = "demo-replay"
    const val BADGE = "演示回放 · 上班路线"

    private const val L11 = "11号线"
    private const val L5 = "5号线"
    private const val DEST = "广州国际金融城"
    private val L11_STOPS = listOf("逸景路", "上涌", "大塘", "龙潭", "赤沙", "琶洲", "员村")
    private val L5_STOPS = listOf("员村", "科韵路")

    // 演示时间线（29 分钟，30× 播放约 58 秒）
    private fun s(h: Int, m: Int, sec: Int = 0) = h * 3600 + m * 60 + sec
    private val PRE = s(8, 9)             // 出发前：显示倒计时
    private val T0 = s(8, 12)             // 现在走，步行去逸景路站
    private val IN = s(8, 16)             // 已进站
    private val RIDE = s(8, 18)           // 列车进站、上车
    private val XFER = s(8, 28)           // 到员村（6 站约 10 分钟）
    private const val WALK = 3            // 员村站内换乘步行分钟
    private val L5ARR = s(8, 31)          // 5 号线列车到站、上车
    private val L5OFF = s(8, 33)          // 科韵路下车
    private val ARRIVE = s(8, 37)         // 出站步行到广州国际金融城
    private val END = s(8, 38)

    /** 匀速播放倍率；整段约 1 分钟 */
    private const val RATE = 30.0
    private const val COUNTDOWN = 5

    val trip = Trip(
        id = TRIP_ID, name = "上班", startMinute = 8 * 60, endMinute = 9 * 60,
        legs = listOf(
            Leg(
                listOf(Candidate(
                    Candidate.Type.METRO, L11, L11_STOPS.first(), 0.0, 0.0,
                    alightName = L11_STOPS.last(), metroDir = "外环",
                )),
                rideMin = 10,
            ),
            Leg(
                listOf(Candidate(
                    Candidate.Type.METRO, L5, L5_STOPS.first(), 0.0, 0.0,
                    alightName = L5_STOPS.last(), metroDir = "黄埔新港",
                )),
                rideMin = 2, transferWalkMin = WALK,
            ),
        ),
    )

    // 首页在演示期间显示的行程卡：上班 + 下班返程，不露真实行程
    val showTrips = listOf(
        trip,
        Trip(
            id = "demo-return", name = "下班",
            startMinute = 18 * 60, endMinute = 19 * 60,
            legs = listOf(
                Leg(
                    listOf(Candidate(
                        Candidate.Type.METRO, L5, L5_STOPS.last(), 0.0, 0.0,
                        alightName = L5_STOPS.first(), metroDir = "滘口",
                    )),
                    rideMin = 2,
                ),
                Leg(
                    listOf(Candidate(
                        Candidate.Type.METRO, L11, L11_STOPS.last(), 0.0, 0.0,
                        alightName = L11_STOPS.first(), metroDir = "内环",
                    )),
                    rideMin = 10, transferWalkMin = WALK,
                ),
            ),
        ),
    )

    /** 开始前的倒计时（秒），给录屏的人时间回到桌面；null = 没在倒计时 */
    val countdown = MutableStateFlow<Int?>(null)

    val snapshot = MutableStateFlow<LiveSnapshot?>(null)

    // 以下状态只给看板渲染，全部在内存里，不写本机记录
    val nodes = MutableStateFlow<List<NodeLog.Stamp>>(emptyList())
    val currentNode = MutableStateFlow<NodeLog.NodeRef?>(null)
    val stopChoices = MutableStateFlow<List<String>>(emptyList())
    val stopMarksUi = MutableStateFlow<List<StopMark>>(emptyList())
    private val marks = ArrayList<StopMark>()

    private var job: Job? = null
    val running get() = job?.isActive == true

    /** 播放原点：v = PRE + 已播放秒数 × RATE；跳段时重设 */
    @Volatile private var origin = 0L
    /** 「我知道了」抑制的「段 + 阶段」 */
    @Volatile private var muted: Pair<Int, AlightGuard.Stage>? = null
    private val fired = HashSet<Int>()

    fun start(ctx: Context) {
        if (CommuteService.instance != null) return
        stop(ctx)
        val app = ctx.applicationContext
        reset()
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            LiveNotifier.ensureChannels(app)
            for (n in COUNTDOWN downTo 1) {
                countdown.value = n
                LiveNotifier.replace(app, LiveNotifier.build(app, LiveNotifier.Visual(
                    title = "演示将于 $n 秒后开始", text = "可返回桌面等待 · 演示回放", short = "${n}秒", sub = "演示回放",
                    progress = null, stops = 0, trackerRes = R.drawable.ic_metro, metro = true, riding = false,
                )))
                delay(1000)
            }
            countdown.value = null
            origin = System.currentTimeMillis()
            var lastKey = ""
            while (isActive) {
                val v = nowV()
                val f = frame(v)
                snapshot.value = f.first
                publishDemo(v)
                val key = f.second.title + f.second.text + f.second.progress
                if (key != lastKey) {
                    lastKey = key
                    LiveNotifier.replace(app, LiveNotifier.build(app, f.second))
                }
                ALERTS.forEachIndexed { i, a ->
                    if (v < a.at || !fired.add(i)) return@forEachIndexed
                    if (a.stage != null && muted == (a.leg to a.stage)) return@forEachIndexed
                    LiveNotifier.alert(app, a.title, a.text, metro = a.metro, ack = a.stage != null)
                }
                if (v >= END) { delay(5000); break }   // 停在「到达」画面几秒再退出，别一闪而过
                delay(250)
            }
            finish(app)
        }
    }

    fun stop(ctx: Context) {
        val had = job != null
        job?.cancel(); job = null
        if (had) finish(ctx.applicationContext)
    }

    // ------------- 演示里的按钮：只推进模拟 -------------

    /** 确认上车：等车时直接上车；换乘步行时直接上 5 号线 */
    fun onBoarded() {
        if (!running) return
        val v = nowV()
        when {
            v < RIDE -> jump(RIDE)
            v in XFER until L5ARR -> jump(L5ARR)
        }
    }

    /** 我已下车：11 号线只剩员村 1 站时进入换乘；5 号线乘车中进入出站步行 */
    fun ackAlight() {
        if (!running) return
        val v = nowV()
        when {
            v in RIDE until XFER && passedAt(v) >= L11_STOPS.size - 2 -> jump(XFER)
            v in L5ARR until L5OFF -> jump(L5OFF)
        }
    }

    /** 我知道了：只停当前阶段的后续提醒，不记录、不清「我已下车」 */
    fun muteAlarm() {
        if (!running) return
        val v = nowV()
        muted = when {
            v in RIDE until XFER && passedAt(v) >= L11_STOPS.size - 2 -> 0 to AlightGuard.Stage.T1
            v in L5ARR until L5OFF -> 1 to AlightGuard.Stage.T1
            else -> return
        }
    }

    /** 到站打卡：只在 11 号线乘车中可用，时间用模拟时刻 */
    fun markStop(idx: Int) {
        if (!running) return
        val v = nowV()
        if (v !in RIDE until XFER) return
        if (idx !in 0 until L11_STOPS.size - 1) return
        marks += StopMark(idx, atOf(v))
        stopMarksUi.value = marks.toList()
    }

    fun undoLastStopMark() {
        if (!running || marks.isEmpty()) return
        marks.removeAt(marks.size - 1)
        stopMarksUi.value = marks.toList()
    }

    private fun jump(target: Int) {
        origin = System.currentTimeMillis() - ((target - PRE) / RATE * 1000).toLong()
        // 跳过的提醒不要再补弹
        ALERTS.indices.forEach { if (ALERTS[it].at <= target) fired.add(it) }
        muted = null
    }

    private fun reset() {
        marks.clear()
        nodes.value = emptyList()
        currentNode.value = null
        stopChoices.value = emptyList()
        stopMarksUi.value = emptyList()
        muted = null
        fired.clear()
        snapshot.value = null
        countdown.value = null
        origin = 0L
    }

    private fun finish(ctx: Context) {
        snapshot.value = null
        countdown.value = null
        stopChoices.value = emptyList()
        job = null
        if (CommuteService.instance == null) runCatching { NotificationManagerCompat.from(ctx).cancel(LiveNotifier.LIVE_ID) }
    }

    private fun nowV(): Int {
        val o = origin
        if (o <= 0L) return PRE
        return (PRE + (System.currentTimeMillis() - o) / 1000.0 * RATE).toInt().coerceIn(PRE, END)
    }

    private fun hm(v: Int) = "%02d:%02d".format(v / 3600, v / 60 % 60)
    private fun minsTo(v: Int, t: Int) = maxOf(1, ceil((t - v) / 60.0).toInt())

    /** 模拟时刻 → 今天的毫秒时刻，节点条/打卡摘要显示 08:xx 而不是当前真实时间 */
    private fun atOf(v: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, v / 3600)
        c.set(java.util.Calendar.MINUTE, v / 60 % 60)
        c.set(java.util.Calendar.SECOND, v % 60)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 11 号线已过几站（0..5）：和真实一样按时间推进，打卡只会把进度往前推 */
    private fun passedAt(v: Int): Int =
        if (v < RIDE) 0
        else ((v - RIDE) * (L11_STOPS.size - 1) / (XFER - RIDE)).coerceIn(0, L11_STOPS.size - 2)

    /** 实时车辆：本趟/下趟还有几分钟进站 */
    private fun trainText(arr: Int, v: Int, headwaySec: Int): String {
        val cur = arr - v
        val curT = if (cur <= 0) "本趟正在站内" else "本趟约 ${maxOf(1, ceil(cur / 60.0).toInt())} 分钟进站"
        return " · $curT · 下趟约 ${ceil((cur + headwaySec) / 60.0).toInt()} 分钟"
    }

    private val DEMO_NODES by lazy {
        listOf(
            NodeLog.NodeRef(NodeLog.Kind.DEPART, 0) to T0,
            NodeLog.NodeRef(NodeLog.Kind.ENTER, 0) to IN,
            NodeLog.NodeRef(NodeLog.Kind.BOARD, 0) to RIDE,
            NodeLog.NodeRef(NodeLog.Kind.ALIGHT, 0) to XFER,
            NodeLog.NodeRef(NodeLog.Kind.BOARD, 1) to L5ARR,
            NodeLog.NodeRef(NodeLog.Kind.ALIGHT, 1) to L5OFF,
            NodeLog.NodeRef(NodeLog.Kind.EXIT, 1) to L5OFF + 60,
            NodeLog.NodeRef(NodeLog.Kind.ARRIVE, 0) to ARRIVE,
        )
    }

    private fun publishDemo(v: Int) {
        stopChoices.value = when {
            v in RIDE until XFER -> L11_STOPS.dropLast(1)
            v in L5ARR until L5OFF -> L5_STOPS.dropLast(1)
            else -> emptyList()
        }
        currentNode.value = when {
            v < IN -> NodeLog.NodeRef(NodeLog.Kind.ENTER, 0)
            v < RIDE -> NodeLog.NodeRef(NodeLog.Kind.BOARD, 0)
            v < XFER -> NodeLog.NodeRef(NodeLog.Kind.ALIGHT, 0)
            v < L5ARR -> NodeLog.NodeRef(NodeLog.Kind.BOARD, 1)
            v < L5OFF -> NodeLog.NodeRef(NodeLog.Kind.ALIGHT, 1)
            else -> NodeLog.NodeRef(NodeLog.Kind.ARRIVE, 0)
        }
        nodes.value = DEMO_NODES.filter { it.second <= v }.map {
            NodeLog.Stamp(it.first.kind, it.first.leg, atOf(it.second), NodeLog.Src.AUTO)
        }
    }

    private class Alert(
        val at: Int,
        val title: String,
        val text: String,
        val metro: Boolean = true,
        val leg: Int = -1,
        val stage: AlightGuard.Stage? = null,
    )

    // 和真实行程一样的提醒（文案直接取 AlightGuard）
    private val ALERTS by lazy { listOf(
        Alert(T0, "现在出发 · $L11", "步行约 4 分钟到逸景路 · 本趟约 6 分钟进站 · 下趟约 12 分钟"),
        Alert(RIDE + 500, AlightGuard.title(AlightGuard.Stage.T1, L11_STOPS.last()),
            AlightGuard.text(AlightGuard.Stage.T1), leg = 0, stage = AlightGuard.Stage.T1),
        Alert(XFER, AlightGuard.title(AlightGuard.Stage.AT, L11_STOPS.last()),
            AlightGuard.text(AlightGuard.Stage.AT), leg = 0, stage = AlightGuard.Stage.AT),
        Alert(L5ARR, AlightGuard.title(AlightGuard.Stage.T1, L5_STOPS.last()),
            AlightGuard.text(AlightGuard.Stage.T1), leg = 1, stage = AlightGuard.Stage.T1),
        Alert(L5OFF, AlightGuard.title(AlightGuard.Stage.AT, L5_STOPS.last()),
            AlightGuard.text(AlightGuard.Stage.AT), leg = 1, stage = AlightGuard.Stage.AT),
    ) }

    /** 回放到 v 这一秒时看板和胶囊该显示什么 */
    fun frame(v: Int): Pair<LiveSnapshot, LiveNotifier.Visual> {
        val l11 = L11_STOPS
        val l5 = L5_STOPS
        fun snap(leg: Int, head: String, detail: String, mode: Decision.Mode?, progress: Int? = null,
                 riding: Boolean = false, passed: Int? = null, stops: List<String>? = null,
                 alightDue: Boolean = false) = LiveSnapshot(
            TRIP_ID, trip.name, leg, trip.legs.size, trip.legs[leg].title(), head, detail, mode, progress, null, riding,
            stopsPassed = passed, stopsTotal = stops?.let { it.size - 1 },
            alightDue = alightDue,
            curStop = if (passed != null && stops != null) stops[passed.coerceIn(0, stops.lastIndex)] else null,
            fromStop = stops?.first(), toStop = stops?.last(),
        )
        // 打卡只会把进度往前推，不会倒退
        val passed = maxOf(passedAt(v), marks.maxOfOrNull { it.idx } ?: -1).coerceIn(0, l11.size - 2)
        val near = passed >= l11.size - 2
        val sn = when {
            v < T0 -> snap(0, "${minsTo(v, T0)} 分钟后出发",
                "${hm(T0)} 出发 · 步行 4 分钟到${l11.first()} · 本趟 ${hm(RIDE)} 进站",
                Decision.Mode.LEAVE_LATER, progress = ((v - PRE) * 100 / (T0 - PRE)))
            v < IN -> snap(0, "约 ${minsTo(v, IN)} 分",
                "步行约 ${minsTo(v, IN)} 分钟到 ${l11.first()}${trainText(RIDE, v, 320)}",
                Decision.Mode.METRO, progress = ((v - T0) * 100 / (IN - T0)))
            v < RIDE -> snap(0, if (minsTo(v, RIDE) <= 1) "进站" else "约 ${minsTo(v, RIDE)} 分",
                "已到 ${l11.first()}${trainText(RIDE, v, 320)}",
                Decision.Mode.METRO)
            v < XFER -> snap(0, if (near) "即将到达" else "约 ${minsTo(v, XFER)} 分",
                "当前 ${l11[passed]} · 下一站 ${l11[passed + 1]} · 距 ${l11.last()} 还有 ${l11.lastIndex - passed} 站",
                Decision.Mode.METRO, riding = true, passed = passed, stops = l11, alightDue = near)
            v < L5ARR -> snap(1, "约 ${minsTo(v, L5ARR)} 分",
                "步行约 ${minsTo(v, L5ARR)} 分钟到 ${l5.first()}${trainText(L5ARR, v, 300)}",
                Decision.Mode.METRO)
            v < L5OFF -> snap(1, "即将到达",
                "当前 ${l5.first()} · 下一站 ${l5.last()} · 距 ${l5.last()} 还有 1 站",
                null, riding = true, passed = 0, stops = l5, alightDue = true)
            v < ARRIVE -> snap(1, "约 ${minsTo(v, ARRIVE)} 分",
                "出站后步行约 ${minsTo(v, ARRIVE)} 分钟到$DEST",
                null, progress = ((v - L5OFF) * 100 / (ARRIVE - L5OFF)))
            else -> snap(1, "已到达", "${hm(ARRIVE)} 到达$DEST · 全程约 ${(ARRIVE - T0) / 60} 分钟", null)
        }
        val line = when {
            v < XFER -> L11
            v < L5OFF -> L5
            else -> ""
        }
        val title = when {
            sn.riding && sn.stopsTotal != null -> {
                val names = if (v < XFER) l11 else l5
                val cur = (sn.stopsPassed ?: 0).coerceIn(0, names.lastIndex)
                val next = names[(cur + 1).coerceAtMost(names.lastIndex)]
                if (sn.headline == "即将到达") "$line · 即将到达 $next" else "$line · ${names[cur]} → $next"
            }
            v < L5OFF -> "$line · ${if (v < XFER) l11.first() else l5.first()}"
            v < ARRIVE -> "步行 · 前往$DEST"
            else -> "已到达 · $DEST"
        }
        val prog = sn.progress ?: sn.stopsPassed?.let { p -> sn.stopsTotal?.let { t -> p * 100 / t } }
        val vis = LiveNotifier.Visual(
            title = title,
            text = sn.detail,
            short = sn.headline,
            sub = "上班 · 第 ${sn.legIndex + 1}/${trip.legs.size} 段",
            progress = prog,
            stops = sn.stopsTotal ?: 0,
            trackerRes = if (sn.riding) R.drawable.ic_metro else R.drawable.ic_walk,
            metro = v < L5OFF,
            riding = sn.riding,
        )
        return sn to vis
    }
}
