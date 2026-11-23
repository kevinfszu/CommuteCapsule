# 通勤胶囊：按评估报告修复（实施 Spec）

## 1. 目标

把 `.trae/documents/通勤胶囊功能与真实意图对齐评估.md` 里的 P0–P4 全部落地，使 App 回到原始目的：**防止睡着 / 走神坐过站**，并以「人工打卡为主、学习收敛、推算兜底」的方式给出四层时刻。

四层时刻（任何一次人工打卡后同时收敛）：

| 层级 | 内容 | 承载字段 |
| --- | --- | --- |
| L0 | 当前段后面每一站到站时刻 | `LiveSnapshot.stopEta` |
| L1 | 后面每个关键节点时刻（换乘下车 / 换乘上车 / 终点下车 / 出站 / 到达） | `LiveSnapshot.nodeEta` |
| L2 | 当前行程段结束时刻 | `LiveSnapshot.legEndAt` |
| L3 | 整趟行程结束时刻 | `LiveSnapshot.tripEndAt` |

逐级重算要求（用户补充，硬约束）：

- 站点打卡后：更新 L0 后续各站 → L1 下一节点 → L2 本段结束 → L3 全程结束。
- 节点打卡后：更新 L1 下一节点 → L2 本段结束 → L3 全程结束。

## 2. 契约与边界

- 唯一需求契约：`通勤胶囊功能与真实意图对齐评估.md`（已批准）。
- 时间口径统一为「车辆到站刚停稳」。
- 样本可信度分级：`MANUAL / TAP / BACKFILL`（人工、刷闸机、补录）> `AUTO`（定位 / 基站 / 加速度）> `ESTIMATED`（按时间推算）。
- 学习样本只收人工端点；`AUTO / ESTIMATED` 不进入学习，避免兜底数据自我强化。
- 不在本地编译（仓库规则）；需要编译时推送到 GitHub 触发 Actions。
- 未经用户明确要求不 commit / push。

## 3. 变更清单（按文件）

### 3.1 新增 `app/src/main/java/com/busnotice/commute/TimelineProjection.kt`

纯投影对象，不依赖 `Context`、不做 IO，便于复用与单测。

```kotlin
object TimelineProjection {
    data class StopEta(val idx: Int, val name: String, val at: Long)
    data class NodeEta(val kind: NodeLog.Kind, val leg: Int, val at: Long)
    data class Result(val stops: List<StopEta>, val nodes: List<NodeEta>, val legEndAt: Long?, val tripEndAt: Long?)
    data class Input(...)   // trip / legIdx / now / riding / alighted / boardedAt / stopNames / marks / legEta
                            // nodeAt / pairSec / hopFallbackSec / rideSec / boardAt / exitWalkSec / lastWalkSec / exitLeg
    fun compute(i: Input): Result
}
```

`compute()` 关键规则：

- **L0**：仅当 `riding && !alighted && stopNames.size >= 2`。以最后一次 `StopMark` 为锚，逐站累加 `pairSec ?: hopFallbackSec`，单区间夹取 `30..600` 秒。无锚点时丢弃 `at <= now` 的站，避免把已过站当「后续各站」。
- **未上车 / 换乘等待**：`base = 上一段 ALIGHT ?: now`；先给 `BOARD(legIdx) = nodeAt ?: boardAt(legIdx, base)`，再给 `ALIGHT = nodeAt ?: board + rideSec`。
- **已下车**：`legEnd = 已记录 ALIGHT ?: legEta`。
- **后续各段**：`for (j in nextLeg..last)` 生成 BOARD / ALIGHT。
- **尾段**：`exitLeg == last` 时生成 EXIT（已记录或 `cursor + exitWalkSec`），再生成 ARRIVE（已记录或 `cursor + lastWalkSec`）。
- **中间段已记录的 EXIT** 也补入。
- 输出前用 `LinkedHashMap<Pair<Kind,Int>, NodeEta>` 去重并过滤 `at <= 0`。

### 3.2 `CommuteService.kt`

- `LiveSnapshot` 新增尾部字段：`stopEta / nodeEta / legEndAt / tripEndAt`（全部有默认值，兼容旧调用点）。
- `LiveState.toJson / fromJson`：`stopEta`、`nodeEta` 用嵌套 `JSONArray`；`legEndAt`、`tripEndAt` 用 `-1` 表示空。
- 新增私有方法：
  - `boardGapSec(i)`：`i == 0` 用 `Learn.waitSec ?: trip.boardEstimateSec`；换乘段用 `Learn.transferWalkSec ?: (transferWalkMin*60 + 180)`。
  - `boardTimeAfter(i, base)`：间隔推算 `base + boardGapSec*1000`，与 `Learn.boardClock` 历史中位融合；两者相差 > 20 分钟只用间隔推算。
  - `projectNow(now)`：构造 `TimelineProjection.Input`；`alighted = alightLeg == legIdx`；`exitLeg = last if 最后一段 isMetroOnly`；`hopFallback = maxOf(rideMs/hops, Learn.stopPerHopSec).coerceIn(45, 600)`。
  - `withEta(s)`：发布前挂载投影结果。
  - `refreshEta()`：人工打卡后立即重发看板；`!::trip.isInitialized || finishing || deadlineAt <= 0L` 时直接返回。
- 三处 `LiveState.publish(this, LiveSnapshot(...))` → `LiveState.publish(this, withEta(LiveSnapshot(...)), deadlineAt)`。
- `refreshEta()` 调用点：`markStop`、`undoLastStopMark`、`stampCurrentNode`、`setNodeAt`、`undoNodeIfFresh`、`ackAlight`、`onBoarded`、`advanceLeg`、`undoBoard`、`markAlight`、`undoBoard`、`syncNodeCurrent` 后续保存处。
- `syncNodeCurrent()` 重写：`lastMetroAlighted && !exited → EXIT` 优先于 `riding → ALIGHT`；出站后再给 `ARRIVE`。
- `stampCurrentNode()` 重写：
  - `tail || (lastLeg && alightLeg == legIdx && exited)` → 记 `ARRIVE` 并 `userFinish()`；
  - `lastLeg && alightLeg == legIdx && !exited` → 记 `EXIT(MANUAL)`。
- `metroBoardAt()` 增加 `stopped(at)` 回退 25 秒（不早于 `stationAt`），moved / cell / mag / time 四条自动上车路径统一「停稳」口径。
- `onBoarded()`：`legIdx > 0 && (manual || boardingByTap)` 时调用 `Learn.recordBoardClock(this, trip.id, legIdx, at)`。
- `learnNodes()` 重写：
  - `at()` 排除 `ESTIMATED`；`manual()` 只收 `MANUAL / TAP / BACKFILL`；
  - WAIT / TRANSFER / REACT / LAST_WALK 要求人工端点；
  - 新增全程 `Learn.Metric.TRIP`（`DEPART` 与 `ARRIVE` 均非推算）。
- `settleNodes()` 的 `NodeLog.saveLastRun` 增加 `markNames = stopNamesOf(legIdx)`。

### 3.3 `NodeLog.kt`

- `RunRecord` 新增 `val markNames: List<String> = emptyList()`（位于 `marks` 与 `learned` 之间）。
- `toJson` 用 `JSONArray` 存 `markNames`；`fromJson` 解析（过滤空串）。
- `saveLastRun` 改为命名参数并新增 `markNames` 参数，避免位置参数错配。

### 3.4 `Learn.kt`

- `Metric` 新增 `TRIP(1, 300..10800)`。
- `rollback` 新增 `"clock" -> dropToken(ctx, BOARD_CLOCK, p[1], p[2], last = false)`。
- 换乘站上车时刻学习：

```kotlin
fun recordBoardClock(ctx, tripId, legIdx, at)  // 仅 legIdx > 0；同一天只留第一条；journalNote("clock…")
fun boardClock(ctx, tripId, legIdx): Int?      // 中位数，样本 < 2 返回 null
fun boardClockStat(ctx, tripId, legIdx): Stat
```

### 3.5 `LearnReport.kt`

- 每段 `i > 0` 增加「换乘站上车时刻」行（`mm(med)` 或「样本不足 x/y」）。
- 新增「全程时长」行：`statLine("全程时长", Learn.nodeStat(ctx, Learn.Metric.TRIP, trip.id))`。
- `statLine` 文案改为「人工样本」，样本 ≥ 2 时追加「波动 ±…」。

### 3.6 `ui/LiveBoard.kt`

- `NodeTimeline` 新增参数 `eta: List<TimelineProjection.NodeEta> = emptyList()`；未记录节点显示 `≈HH:mm`，颜色降低。
- 实况看板传入 `eta = s.nodeEta`。
- `nodeLabel` 改为显式语义：`换乘上车`、`换乘下车`、`终点下车`。
- 看板新增：下一站预计到站行；本段 / 全程预计结束行；可折叠「后续各站到站时间」列表（`showStopEtas by remember(s.tripId)`）。
- 新增 `val curNodeRef by NodeLog.current.collectAsState()`。
- 按钮区新增「现在出站」（`curNodeRef.kind == EXIT`）、「现在到达」（`curNodeRef.kind == ARRIVE`），均在 `!demo` 下生效，并排在 `!s.riding` 之前。

### 3.7 `ui/RecordsScreen.kt`

- 新增 `WeekCard(records, dark)`：最近 7 天完成次数、平均全程、打卡覆盖率（排除 `aborted` / `test`）。
- 打卡明细增加站名行：`r.markNames.getOrNull(m.idx) ?: "第 ${m.idx + 1} 站"` + 时刻。
- `nodeLabel` 同步「换乘下车 / 换乘上车 / 终点下车」语义。

### 3.8 `ui/HomeScreen.kt`

- 把「出发建议」`DepartBoard` 从行程列表之前移到 `trips` 列表之后，降级为辅助信息：

```kotlin
if (!demoOn && board == null && pendingTrip == null && advice != null) {
    item(key = "depart") { DepartBoard(advice!!, nowTick, dark) }
}
```

## 4. 验收标准

1. 车上点「到站打卡」任意中间站：L0 后续各站、L1 下一节点、L2 本段结束、L3 全程结束**同一帧**内一起变化（`stopEta / nodeEta / legEndAt / tripEndAt` 同时更新）。
2. 补录 / 修正某节点时间：L1 之后节点、L2、L3 立即按新锚点重算。
3. 自动上车判定回退到「停稳」：判定时刻不晚于「开动」，且不早于进站时刻。
4. 学习样本中不含 `AUTO` / `ESTIMATED` 端点；`LearnReport` 显示「人工样本」样本量与波动。
5. 记录页能看到每次打卡的站名与时刻，以及最近 7 天汇总。
6. 最后一段地铁下车后，看板出现「现在出站」；出站后出现「现在到达」；点「现在到达」结束行程。
7. 首页行程列表在前，出发建议在后。

## 5. 验证方式（不本地编译）

1. 静态自检已完成：8 个改动文件括号平衡检查通过（TimelineProjection 18/18、CommuteService 735/735、Learn 124/124、LearnReport 59/59、NodeLog 68/68、LiveBoard 209/209、RecordsScreen 112/112、HomeScreen 220/220）。
2. 需要编译时：按仓库规则推送到 GitHub，触发 Actions 自动编译 `assembleDebug` + 单元测试。
3. 编译通过后安装真机，按第 4 节逐条验收。

## 6. 风险与回滚

- `LiveSnapshot` 新增字段全部带默认值，旧持久化 JSON 由 `opt*` 兜底，不会因升级丢看板。
- `TimelineProjection` 为纯函数，出问题可让 `withEta` / `refreshEta` 直接返回原快照，界面退化为旧行为。
- 学习侧新增 `TRIP` 与 `BOARD_CLOCK` 独立 prefs 键，不影响既有样本；如需清空可单独删键。

## 7. 需要批准的事项

1. 确认本 Spec 的变更清单与验收标准。
2. 确认是否授权推送到 GitHub 触发 Actions 编译（仓库规则禁止本地编译）。
3. 确认是否需要同步更新 `CHANGELOG.md`（当前未改，等待明确要求）。
