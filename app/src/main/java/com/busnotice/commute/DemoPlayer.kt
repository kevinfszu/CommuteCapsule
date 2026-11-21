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
 * 30× 匀速约 2 分钟，15× 约 4 分钟。正在跑真实行程时不能启动。
 */
object DemoPlayer {
    const val TRIP_ID = "demo-replay"
    const val BADGE = "演示回放 · 上班路线"

    private const val L11 = "11号线"
    private const val L5 = "5号线"
    private const val DEST = "广州国际金融城"
    private val L11_STOPS = listOf("逸景路", "上涌", "大塘", "龙潭", "赤沙", "琶洲", "员村")
    private val L5_STOPS = listOf("员村", "科韵路")

    // 演示时间线（压缩过的一趟上班路：步行 5 分钟、11 号线 6 站、换乘 4 分钟、5 号线 1 站、步行 18 分钟）
    private fun s(h: Int, m: Int, sec: Int = 0) = h * 3600 + m * 60 + sec
    private val PRE = s(8, 0)             // 出发前：显示倒计时
    private val T0 = s(8, 3)              // 现在走，步行去逸景路站
    private val IN = s(8, 8)              // 已进站
    private val RIDE = s(8, 10)           // 列车进站、上车
    private val XFER = s(8, 24)           // 到员村（6 站 × 约 2 分 20 秒）
    private const val WALK = 4            // 员村站内换乘步行分钟
    private val L5ARR = s(8, 30)          // 5 号线列车到站、上车
    private val L5OFF = s(8, 32)          // 科韵路下车
    private val ARRIVE = s(8, 50)         // 步行到广州国际金融城
    private val END = s(8, 55)

    val trip = Trip(
        id = TRIP_ID, name = "上班", startMinute = 8 * 60, endMinute = 9 * 60,
        legs = listOf(
            Leg(
                listOf(Candidate(
                    Candidate.Type.METRO, L11, L11_STOPS.first(), 0.0, 0.0,
                    alightName = L11_STOPS.last(), metroDir = "外环",
                )),
                rideMin = 14,
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
                    rideMin = 14, transferWalkMin = WALK,
                ),
            ),
        ),
    )

    /** 开始前的倒计时（秒），给录屏的人时间回到桌面；null = 没在倒计时 */
    val countdown = MutableStateFlow<Int?>(null)
    private const val COUNTDOWN = 8

    val snapshot = MutableStateFlow<LiveSnapshot?>(null)
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun start(ctx: Context, speed: Int) {
        if (CommuteService.instance != null) return
        stop(ctx)
        val app = ctx.applicationContext
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
            val t0 = System.currentTimeMillis()
            // 匀速播放：1 分钟版 = 30×，2 分钟版 = 15×
            val rate = if (speed >= 60) 30.0 else 15.0
            var lastKey = ""
            val fired = HashSet<Int>()
            while (isActive) {
                val v = (PRE + (System.currentTimeMillis() - t0) / 1000.0 * rate).toInt().coerceAtMost(END)
                val f = frame(v)
                snapshot.value = f.first
                val key = f.second.title + f.second.text + f.second.progress
                if (key != lastKey) {
                    lastKey = key
                    LiveNotifier.replace(app, LiveNotifier.build(app, f.second))
                }
                ALERTS.forEachIndexed { i, a ->
                    if (v >= a.at && fired.add(i)) LiveNotifier.alert(app, a.title, a.text, metro = a.metro)
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

    private fun finish(ctx: Context) {
        snapshot.value = null
        countdown.value = null
        job = null
        if (CommuteService.instance == null) runCatching { NotificationManagerCompat.from(ctx).cancel(LiveNotifier.LIVE_ID) }
    }

    private fun hm(v: Int) = "%02d:%02d".format(v / 3600, v / 60 % 60)
    private fun minsTo(v: Int, t: Int) = maxOf(1, ceil((t - v) / 60.0).toInt())

    private class Alert(val at: Int, val title: String, val text: String, val metro: Boolean = false)

    // 和真实行程一样的弹窗（文案格式取自真实提醒）
    private val ALERTS by lazy { listOf(
        Alert(T0, "现在出发 · $L11", "步行约 5 分钟到${L11_STOPS.first()} · 本趟约 7 分钟进站 · 下趟约 13 分钟", true),
        Alert(IN - 60, "即将到达 ${L11_STOPS.first()} · $L11 约 3 分", "步行约 1 分钟到${L11_STOPS.first()} · 本趟约 3 分钟进站 · 下趟约 9 分钟", true),
        Alert(IN, "已进站 · $L11", "本趟约 2 分钟进站 · 下趟约 8 分钟", true),
        Alert(XFER - 90, "即将到达「${L11_STOPS.last()}」", "请准备下车 · 下车后步行约 $WALK 分钟换乘 $L5", true),
        Alert(XFER, "已到${L11_STOPS.last()} · 换乘 $L5", "站内步行约 $WALK 分钟 · $L5 约 6 分钟到", true),
        Alert(L5ARR - 30, "${L5}即将到站", "在${L11_STOPS.last()}站台等车 · 下一站 ${L5_STOPS.last()}", true),
        Alert(L5OFF - 60, "即将到达「${L5_STOPS.last()}」", "请准备下车 · 出站后步行约 18 分钟到$DEST", true),
        Alert(ARRIVE, "已到达 $DEST", "${hm(ARRIVE)} 到达 · 全程约 ${(ARRIVE - T0) / 60} 分钟"),
    ) }

    /** 11 号线本趟 / 下趟（高峰约 5 分 20 秒一班，本趟 08:10 进站） */
    private fun train11Text(v: Int): String {
        val cur = RIDE - v
        val curT = if (cur <= 0) "本趟正在站内" else "本趟约 ${maxOf(1, ceil(cur / 60.0).toInt())} 分钟进站"
        return "$curT · 下趟约 ${ceil((cur + 320) / 60.0).toInt()} 分钟"
    }

    /** 回放到 v 这一秒时看板和胶囊该显示什么 */
    fun frame(v: Int): Pair<LiveSnapshot, LiveNotifier.Visual> {
        val l11 = L11_STOPS
        val l5 = L5_STOPS
        fun snap(leg: Int, head: String, detail: String, mode: Decision.Mode?, progress: Int? = null,
                 riding: Boolean = false, passed: Int? = null, stops: List<String>? = null) = LiveSnapshot(
            TRIP_ID, trip.name, leg, trip.legs.size, trip.legs[leg].title(), head, detail, mode, progress, null, riding,
            stopsPassed = passed, stopsTotal = stops?.let { it.size - 1 },
            curStop = if (passed != null && stops != null) stops[passed] else null,
            fromStop = stops?.first(), toStop = stops?.last(),
        )
        val sn = when {
            v < T0 -> snap(0, "${minsTo(v, T0)} 分钟后出发",
                "按 $L5 倒推 · ${hm(T0)} 出发 · ${hm(XFER)} 到${l11.last()}换乘",
                Decision.Mode.LEAVE_LATER, progress = ((v - PRE) * 100 / (T0 - PRE)))
            v < IN -> snap(0, "约 ${minsTo(v, IN)} 分",
                "步行约 ${minsTo(v, IN)} 分钟到${l11.first()} · ${train11Text(v)}",
                Decision.Mode.METRO, progress = ((v - T0) * 100 / (IN - T0)))
            v < RIDE -> snap(0, "已进站", "${l11.first()} · ${train11Text(v)}", Decision.Mode.METRO)
            v < XFER -> {
                val p = ((v - RIDE) * (l11.size - 1) / (XFER - RIDE)).coerceIn(0, l11.size - 2)
                snap(0, "${minsTo(v, XFER)} 分钟",
                    "下一站 ${l11[p + 1]} · ${hm(XFER)} 到${l11.last()}换乘 $L5",
                    Decision.Mode.METRO, riding = true, passed = p, stops = l11)
            }
            v < XFER + WALK * 60 -> snap(1, "换乘 $L5",
                "${l11.last()}站内步行约 ${minsTo(v, XFER + WALK * 60)} 分钟到 $L5 站台",
                Decision.Mode.METRO)
            v < L5ARR -> snap(1, "等 $L5",
                "$L5 约 ${minsTo(v, L5ARR)} 分钟进站 · 下一站 ${l5.last()}",
                Decision.Mode.METRO)
            v < L5ARR + 30 -> snap(1, "已上车", "$L5 · 下一站 ${l5.last()}", null,
                riding = true, passed = 0, stops = l5)
            v < L5OFF -> snap(1, "1 站", "下一站 ${l5.last()} · 约 ${hm(L5OFF)} 下车", null,
                riding = true, passed = 0, stops = l5)
            v < ARRIVE -> snap(1, "步行 ${minsTo(v, ARRIVE)} 分钟",
                "出站后步行约 ${minsTo(v, ARRIVE)} 分钟到$DEST",
                null, progress = ((v - L5OFF) * 100 / (ARRIVE - L5OFF)))
            else -> snap(1, "已到达", "${hm(ARRIVE)} 到达$DEST · 全程约 ${(ARRIVE - T0) / 60} 分钟", null)
        }
        val line = when {
            v < XFER -> L11
            v < L5OFF -> L5
            v < ARRIVE -> "步行"
            else -> ""
        }
        val metro = v < L5OFF
        val prog = sn.progress ?: sn.stopsPassed?.let { p -> sn.stopsTotal?.let { t -> p * 100 / t } }
        val vis = LiveNotifier.Visual(
            title = if (line.isEmpty()) sn.headline else "${sn.headline} · $line",
            text = sn.detail, short = sn.headline, sub = "演示回放",
            progress = prog, stops = sn.stopsTotal ?: 0,
            trackerRes = if (metro) R.drawable.ic_metro else R.drawable.ic_walk,
            metro = metro, riding = sn.riding,
        )
        return sn to vis
    }
}
