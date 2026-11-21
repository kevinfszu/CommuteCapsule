# 到站打卡：本次行程修正 + 站间时长学习

## 1. 目标

上车后允许用户手动选择「刚到达的站点」并记录到站时间：

1. **本次行程**：立刻纠正后续的「当前站 / 下一站 / 还有几站 / 预计到达时间」，并影响到站提醒。
2. **长期学习**：把相邻两次打卡的间隔沉淀为「线路 + 区间」的行车时长样本，提高以后同线路的到站推算精度。
3. 用户可能每站都打卡，也可能只打一两站；两种都要能用，且允许撤销误点。

## 2. 现状（代码事实）

* `NodeLog`（[NodeLog.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/NodeLog.kt)）只有 6 种节点（DEPART/BOARD/…/ARRIVE）与 5 种来源，时间轴面向「整趟流程」，**不适合塞入每站一条记录**；站点打卡单独存，不进时间轴。
* 地铁站序与进度：`MetroRoutes.Route(stations, hops)` 缓存于 `metroRoutes: ConcurrentHashMap<Int, MetroRoute>`（[CommuteService.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/CommuteService.kt#L366-L395)），`stations[0]` = 上车站、`stations[hops]` = 下车站。
* 唯一推算入口：`metroProgress(mr, total, now)`（[CommuteService.kt#L1359-L1388](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/CommuteService.kt#L1359-L1388)）。它同时驱动：
  * 到站判定 `passed/remaining/eta`（L1477-L1485、L1462-L1466）
  * 通知/详情文案「当前 X · 下一站 Y · 还有 N 站」（L1590-L1603）
  * 流体云标题与站点进度（L1606-L1633）
  * 因此**只改这一个函数**即可让本次修正全链路生效。
* 已有「定位贴站」锚点 `metroAnchorIdx/At`（L1332-L1334、L1425-L1433），按累计平均速度外推；但地下定位经常贴不上站，正需要人工打卡补位。
* 公交进度由 `BusRide.snap` 的沿线投影给出（L1440-L1460），`busMaxSeg` 防回退（L1477-L1485）。
* 学习现状（[Learn.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/Learn.kt)）：`recordRide` 按 `tripId#legIdx` 存整段分钟数（L107-L113），`rideMin` 取中位数（L116-L120）；`resolve` 的优先级是 实测中位数 > 手填 > 地铁估算 > 默认（L137-L144）。**没有任何站间粒度的学习。**
* 运行状态恢复：`saveRun/restoreRun`（L1939-L2009）逐字段存取，新增打卡列表需同步加入。
* UI：乘车中时 `LiveBoard` 的按钮行只有「确认上车 / 撤销上车」，乘车中该行是空的（[LiveBoard.kt#L271-L280](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ui/LiveBoard.kt#L271-L280)），正好放「到站打卡」。
* 测试只跑纯 JVM 逻辑（无 Robolectric），所以新算法必须抽成不依赖 Context 的纯函数才能测。

## 3. 总体设计

```
上车(boardedAt)
   │  打卡：站点 idx + 时刻 at
   ▼
stopMarks: [Mark(idx, at)]  ──本次修正──▶ metroProgress：passed / ETA / 文案 / 提醒
   │
   └──下车/换段──▶ flushStopLearn() ──长期──▶ Learn 站间样本
                                          ├ 相邻打卡(span=1) → 区间样本 line|A|B
                                          └ 跨站打卡(span>1) → 该线「每站秒数」样本
```

**短期修正**：手动打卡比任何推算都可信。`metroProgress` 增加最高优先级分支：以最近一次打卡为锚点，用「上车到该站的平均每站用时」外推后续站点，并按学习到的逐区间时长累加剩余时间。

**长期学习**：只在本次乘车结束时落库（避免打卡后撤销污染样本）；撤销的打卡自然不会被学习。样本以线路（名称+方向）为键，不绑定 tripId/legIdx，换行程、重建线路后仍可用。

**兼容**：没有站点数据（地铁路线没取到、公交站点没取到）时按钮隐藏；打卡只影响本段，换段自动清空。

## 4. 具体改动

### 4.1 新增 `app/src/main/java/com/busnotice/commute/RideStops.kt`（纯逻辑，可测）

```kotlin
/** 一次到站打卡：idx = 本段站点下标（0 = 上车站），at = 毫秒时间戳 */
data class StopMark(val idx: Int, val at: Long)

object RideStops {
    /** 线路学习键：地铁「名称|方向」，公交「名称|终点站」 */
    fun lineKey(c: Candidate): String

    /** 上车到最近打卡的平均每站用时（秒）；idx<=0 或时间倒挂返回 null，夹在 45..300 秒 */
    fun perHopSec(boardedAt: Long, marks: List<StopMark>): Int?

    /** 打卡后的已过站数 + 预计到下车站时刻（调用方保证 marks 非空且 idx 合法） */
    fun progress(boardedAt: Long, marks: List<StopMark>, hops: Int, now: Long, perHopFallbackSec: Int): Pair<Int, Long>

    /** 学习用：把上车锚点 + 打卡序列切成区间；span==1 为精确区间，span>1 为平均 */
    data class Interval(val fromIdx: Int, val toIdx: Int, val sec: Int, val span: Int)
    fun intervals(boardedAt: Long, marks: List<StopMark>): List<Interval>
}
```

规则：

* 合法样本：单站 40..600 秒，非法直接丢弃。
* `perHopSec` 用「累计平均」`(last.at - boardedAt) / last.idx`，比只看最后两站更稳。
* `progress` 的 passed 上限 `hops-1`（到站后由现有下车流程处理）；ETA = `last.at + (hops-last.idx)*perHop`。

### 4.2 `Learn.kt` 增量

```kotlin
private const val STOPS = "learn_stops_v1"     // 新增 prefs
private const val STOP_AVG = "a|"              // key: a|lineKey
private const val STOP_PAIR = "p|"             // key: p|lineKey|from|to

/** 落库一次乘车：相邻打卡 → 区间样本；跨站 → 每站秒数样本。撤销后的列表不会传进来 */
fun recordStops(ctx: Context, lineKey: String, names: List<String>, boardedAt: Long, atAlight: Long, marks: List<StopMark>)

fun stopPerHopSec(ctx: Context, lineKey: String): Int?          // ≥2 个样本取中位数
fun stopPairSec(ctx: Context, lineKey: String, from: String, to: String): Int?
fun stopPairTable(ctx: Context, lineKey: String): Map<String, Int>   // "from|to" → 中位数，进站时加载一次
```

* 区间样本上限 8 个/键，每站样本上限 30 个/线路；中位数取排序后中间值（样本 <2 返回 null）。
* `names` 用于把 idx 转成站名（重命名/站序微调时仍能对上）；`to` 用下车站名补最后一段。
* 不新增清空入口：现有「设置 → 重置学习数据」若存在则复用，不存在本期不加（避免范围蔓延）。

### 4.3 `CommuteService.kt`

**新增状态**

```kotlin
private val stopMarks = mutableListOf<StopMark>()
private var stopFlushed = false
private var stopLineKey = ""
private var stopPairSec: Map<String, Int> = emptyMap()   // 进站/取到站序时加载
```

**对外接口（companion 共享给 UI）**

```kotlin
companion object {
    val stopChoices = MutableStateFlow<List<String>>(emptyList())  // 可打卡站点名
    val stopMarksUi = MutableStateFlow<List<StopMark>>(emptyList()) // 已打卡（含时刻）
}
fun markStop(idx: Int)            // 记当前时刻；同站或更晚站的旧打卡先删除
fun undoLastStopMark()            // 撤销最近一次
```

**发布与重置**

* 新增 `publishStops()`：取当前段站点名（地铁 `metroRoutes[legIdx].stations.dropLast(1)`；公交 `busRoute.dropLast(1)`），写 `stopChoices`；同时刷新 `stopMarksUi`。
* 调用点：地铁站序写入（L380/L392 之后）、公交 `busRoute` 取得（L1409 之后）、`onBoarded`（L841-L848）、`advanceLeg`（L869-L879）、`restoreRun`（L2007 附近）、`finish/stop`（清空两个 Flow）。
* `onBoarded/advanceLeg/undoBoard`：清空 `stopMarks`、`stopFlushed=false`、`stopPairSec`（撤销上车 L782-L799 同）。
* `markStop(idx)`：`idx !in 0..hops-1` 忽略；删除 `idx` 及之后的旧打卡后追加；`publishStops()`；`kick()`；`saveRun(deadlineAt)`。
* `undoLastStopMark()`：移除最后一条 → `publishStops()` → `saveRun`。

**本次修正：`metroProgress`（L1359-L1388）**

在计算完 `perHop` 之后、`fpEtaAt` 分支之前插入最高优先级分支：

```kotlin
val marks = stopMarks.filter { it.idx in 0 until hops }
if (marks.isNotEmpty() && marks.last().idx > 0) {
    val per = (RideStops.perHopSec(boardedAt, marks) ?: (perHop / 1000).toInt()) * 1000L
    // 逐个区间累加：有学习样本用区间时长，没有用平均每站
    var etaAt = marks.last().at; var passed = marks.last().idx
    val lastIdx = marks.last().idx
    val names = mr.stations.map { it.name }
    var remain = marks.last().at
    for (i in lastIdx until hops) {
        val pair = stopPairSec["${names[i]}|${names[i + 1]}"]?.times(1000L)
        remain += pair ?: per
    }
    return RideStops.progress(boardedAt, marks, hops, now, (per / 1000).toInt()).first to remain
}
```

* 手动锚点存在时**不再采信 `fpEtaAt`**（用户看到的站比基站模型准）。
* 没有手动打卡时保持原逻辑；但 `planned` 前叠加学习先验：
  `val learned = Learn.stopPerHopSec(this, stopLineKey)?.times(1000L) ?: 0L; val planned = maxOf(total / hops, learned).coerceAtLeast(60_000L)`。
* 公交：`passed/remaining` 用 `maxOf(busMaxSeg, marks.last().idx)`（L1477-L1485 与 L1618-L1623、L1628 处统一取一个 `busPassed`）；不改 `busEtaAt`（公交有 GPS 距离估计更准）。

**长期学习：`flushStopLearn(at: Long)`**

* 触发点：`markAlight(...)` 的最前面（L1542/L1550 之前，用 `now` 作为下车站时刻）；若 `stopMarks` 为空或 `stopFlushed` 已置位则跳过；写完置 `stopFlushed=true`。
* 内容：`RideStops.intervals(boardedAt, stopMarks + StopMark(hops, at))` 逐条写 `Learn.recordStops(...)`；`names` 来自当前 `metroRoutes[legIdx]` 或 `busRoute`。
* 若最后一段无途经数据（`metroRoutes` 未取到）则只记录平均（无法映射站名时跳过）。

**持久化**

* `saveRun` 增加 `.putString("stopMarks", stopMarks.joinToString(",") { "${it.idx}:${it.at}" })`。
* `restoreRun` 解析该字段（容错：非法项丢弃），并 `publishStops()`。

### 4.4 `LiveBoard.kt`

* 在按钮行（L271-L280）内新增：`s.riding && 站点列表非空` 时显示 `Pill("到站打卡", 白 12%, ...)`，点击打开 `AlertDialog`。
* 弹窗（复用 Glass 风格：`AlertDialog` + 深/浅色文字）：
  * 标题「现在到哪一站？」，副标题「记录当前时刻，用来校正后面的到站时间」。
  * 站点列表 `LazyColumn`（高度上限 400dp），从推算当前站开始列出到「下车站的上一站」；已打卡站显示 `✓ 08:12`。
  * 点选即 `CommuteService.instance?.markStop(i)` 并关闭。
  * 底部：`上次打卡：${name} ${hhmm}` + 「撤销」文字按钮（调 `undoLastStopMark()`）。
* 乘车中且已有打卡时，按钮下方显示一行 12sp 白 55% 文案：「已打卡 2 站 · 最近 体育西路 08:12」，点整行可打开弹窗撤销。
* 通过 `CommuteService.stopChoices.collectAsState()` / `stopMarksUi.collectAsState()` 驱动，不依赖 LiveSnapshot 刷新频率。

### 4.5 文档

* `docs/ui-audit/tasks.md` 追加一节「到站打卡」记录实现与验证结论。
* 不加新文档文件；学习键格式写在 `Learn.kt` 顶部注释即可。

## 5. 假设与决定

1. **只允许打卡到「下车站的上一站」**：下车站由「我已下车 / 自动到站」处理，避免两套到达逻辑打架。
2. **打卡不写入 `NodeLog` 时间轴**：站点太多会淹没 6 个流程节点；乘车中界面单独展示打卡记录。
3. **地铁、公交都支持打卡**；长期区间学习**只在地铁消费**（公交有 GPS 投影，按站学习反而引入噪声，但样本仍会记录，供将来使用）。
4. **学习在下车时一次性落库**，不在打卡瞬间写，天然规避「点错又撤销」污染样本。
5. **撤销只撤最近一次**，且不做时间倒流的自动清理；打卡更早的站会连带删除更晚的旧打卡（保证单调）。
6. **没有站点数据就不显示按钮**，不提供「手输站名」的兜底（保持本期范围）。
7. 进程被杀后靠 `run_state` 恢复打卡列表；恢复后重新取站序，`idx` 越界项直接丢弃。
8. 不改 `Learn.ride` 的整段中位数优先级，只新增站间粒度；两者互补：整段管「这趟坐多久」，站间管「现在到哪了」。

## 6. 验证

**自动化**

* 新增 `app/src/test/java/com/busnotice/commute/RideStopsTest.kt`：
  * `perHopSec`：单站打卡、两站打卡、时间倒挂、越界夹取。
  * `progress`：锚点为 2 站、now 恰在锚点、now 远超 ETA（封顶 hops-1）。
  * `intervals`：相邻 span=1 → 精确；跨 3 站 → 平均；非法秒数丢弃。
* 命令：`gradle :app:testDebugUnitTest`（本次改动全部为纯 JVM 可测逻辑）。

**真机**

1. 首页开始一条行程 → 手工「确认上车」→ 乘车中界面出现「到站打卡」，点开能看到本段站名。
2. 打卡第 2 站：详情/流体云从「当前上车站」跳到该站，剩余站数、预计到达同步减少。
3. 打卡更晚一站：文案继续前进；点「撤销」回到上一状态。
4. 杀掉 App 重新打开（行程未结束）：打卡记录仍在，站名与进度一致。
5. 到达下车后：`run-as` 查看 `shared_prefs/learn_stops_v1.xml` 出现 `p|线路|A|B` 与 `a|线路` 样本。
6. 再跑一次同线路：未打卡时「当前站」推进速度使用学到的每站秒数（对比日志或界面变化）。

## 7. 影响面与风险

* `metroProgress` 是提醒核心，改动必须保持「没有打卡时行为完全不变」；用现有 `DecisionTest/RidePriorityTest` 回归。
* `saveRun` 增加字段不改变 `sv` 版本号，旧进度文件缺字段时按空列表恢复。
* 站名作为学习键：换城市/同名线路可能冲突；`lineKey` 已含方向/终点站，冲突概率可接受。
* 打卡后长距离异常（用户点到很远的站）由 40–600 秒/站校验拦截，不写入学习。
