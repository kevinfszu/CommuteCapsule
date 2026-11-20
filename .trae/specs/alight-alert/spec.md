# 换乘站与终点站到站提醒同级化（方案 B）

## 1. 背景与目标

当前实现只把「行程最后一段」当作真正的乘车段：上车后 `riding = true`，`rideTick` 负责到站提醒与结束；
中间各段上车只是 `legIdx++` 进入 connecting 状态，靠估算时间给一次「赶车提醒」（通知 id 1004），
既不是「下车提醒」，也没有任何过站兜底。

本机用户的实际用途是**防止坐过站**（换乘站、终点站同等重要），因此统一为：

- 每一段上车都进入同一套「车上」状态，走同一套到站流程；
- 到站提醒文案对换乘站与终点站完全一致；
- 增加过站告警（坐过了立即告知）。

## 2. 状态机语义（核心）

| 字段 | 旧语义 | 新语义 |
| --- | --- | --- |
| `legIdx` | 正在等/要上的这一段 | 正在坐或正在等的这一段 |
| `riding` | 仅最后一段上车后为 true | 任意一段上车后为 true |
| 中间段到站 | `legIdx++` + connecting 估算 | `markAlight` + `advanceLeg` |
| `alightAt` / `alightLeg` | 仅用于换乘估算 | 记录「哪一段、何时」下车，供换乘步行估算与去重 |
| 终点到站 | `endTrip` | `endTrip`（不变） |

`advanceLeg(now)`：结束本段（若是地铁则 `endMetroRide`）→ 清基站推算缓存 → `legIdx++` → `riding = false`
→ 重置本段检测状态（`nearSince / alertedKey / stopAlerted / stationAt / arriveAnchorAt / arriveAlertedAt /
arriveAgainAt / overshootAlerted / deferredTap* / metroExitSeen` 等）→ 保留 `alightAt` 并令
`alightLeg = legIdx - 1` → 地铁换地铁时直接 `stationAt = now`（人已在站厅）→ 重启主循环并 `saveRun`。

## 3. 改动清单（文件 / 函数）

### 3.1 `app/src/main/java/com/busnotice/commute/CommuteService.kt`

- 字段：新增 `arriveAgainAt`、`overshootAlerted`、`alightLeg`、`legAlight`（每段下车站坐标）；
  删除 `transferAlerted`。
- `onBoarded`：不再限制只有最后一段；任意段上车都 `riding = true`、`boardedAt = now`，
  重置 `alightAt / alightLeg / arriveAlertedAt / arriveAgainAt / overshootAlerted`；
  截止时间按段区分。
- 新增 `advanceLeg(now)`：见第 2 节。
- `prefetch`：对**每一段**调用 `resolveAlight(leg, next)` 写入 `legAlight[i]`。
- 新增 `resolveAlight` / `legAlightName`：下车站坐标取「已填坐标 > 线路站点名 > `Transit.search` > 下一段首站」；
  站名取「`alightName` > 下一段首站名 > “下车站”」。
- `tick`：删除 connecting / expectArrive / 旧换乘分支；`if (riding) return rideTick(...)` 对任意段生效；
  步行回退使用 `alightAt`；`Decision.decide(options)` 不再传 connecting。
- `rideTick`：
  - `dest = legAlight[legIdx] ?: br?.last()`，`name = legAlightName(legIdx)`；
  - 到站按 `legIdx >= legs.lastIndex` 分为 `endTrip` 与 `markAlight + advanceLeg`；
  - 到站提醒：`即将到达“{name}”` / `准备下车`，id `1005`；
  - 二次确认：`now >= eta - 25s` 时再提醒一次（每段一次）；
  - 过站告警：`“{name}”已过站` / `请下车返回`，id `1007`；
  - `byTime` 在过站后延后到 `eta + 12min` 才转段，避免状态丢失；
  - 胶囊副标题改为 `第 N/M 段`。
- `visual`：删除 connecting / expectArrive 分支；副标题 `第 N/M 段`。
- `jumpByLocation` / `busJumpCheck` / `joinByCell`：改为 `while (legIdx < k) advanceLeg(...)` 再判断上车。
- `applyTransit`：
  - ENTER：`while (legIdx < target) advanceLeg(at)`；
  - EXIT：本段地铁出站即到站，末段 `endTrip`、中间段 `markAlight + advanceLeg`；
  - TAP：判定条件改用 `riding && cur.isMetroOnly()` 等新语义；
  - ARRIVE：提醒文案改为 `即将到达“...”`，站名用 `legAlightName(i)`。
- `inMetroRide` / `busMoving`：按 `riding && legs[legIdx]` 判断当前段。
- `onCell`：EXIT 增加中间段 `markAlight + advanceLeg`。
- `markAlight`：以 `alightLeg == legIdx` 去重（不再用 `legIdx - 1`）。
- 持久化：新增 `arrAgain / overshoot / alightLeg`，写 `sv = 2`；`restoreRun` 在 `sv != 2` 时 `clearRun()`。
- 删除：`connectPassed`、`alightStation`、旧 1004 换乘提醒及其状态。

### 3.2 `app/src/main/java/com/busnotice/commute/ui/LiveBoard.kt`

乘车中标题优先显示本段下车站（`alightName`；换乘段未填时取下一段首站）：

```kotlin
val alightName = leg?.candidates?.firstOrNull { it.alightName.isNotEmpty() }?.alightName
    ?: trip?.legs?.getOrNull(s.legIndex + 1)?.candidates?.firstOrNull()?.stopName
val headText = when {
    s.riding && !alightName.isNullOrEmpty() -> "到 $alightName"
    !main?.stopName.isNullOrEmpty() -> main!!.stopName
    else -> s.legTitle
}
```

### 3.3 `app/src/main/java/com/busnotice/commute/DemoPlayer.kt`

演示回放时间线同步新文案：换乘站与终点站均为 `即将到达“X”` / `准备下车`
（换乘站正文追加「下车后步行…换乘…」）。

## 4. 通知约定

| id | 用途 | 文案 |
| --- | --- | --- |
| 1005 | 到站（换乘/终点） | `即将到达“{下车站}”` / `准备下车` |
| 1006 | 车快到了（站台） | 不变 |
| 1007 | 过站 | `“{下车站}”已过站` / `请下车返回` |

- 中文引号必须保留。
- 渠道 `CHANNEL_POP`，均震动；1004 已废弃。

## 5. 已知取舍

- 移除旧 1004「赶车提醒」，换乘信息并入 1005 正文，实现同级化。
- 过站判定为「时间 + 位置」双条件：地铁 `eta + 5min` 且不在 800m 内；公交 `eta + 3min` 且不在 500m 内。
- 原「围栏 + 计步 + 基站静止」的多信号下车检测被 `rideTick` 的 `byLoc / byTime` 取代（行为变化）。
- `Decision.decide(options, connecting)` 的 connecting 分支已无调用方，保留但不影响行为。

## 6. 验证步骤

1. 静态：三个文件括号平衡检查通过（自写脚本，跳过注释/字符串）。
2. 编译：本地无 JDK/Gradle/gradlew，**未编译验证**；需推送或本地 Android Studio 构建。
3. 功能（真机）：
   - 三段行程：第 1 段到站 → 1005 提醒 → 转第 2 段 → 第 2 段到站 → 1005 → 第 3 段 → 终点 1005 → 结束；
   - 故意坐过换乘站 → 1007 过站告警；
   - 进程被杀后重启 → 不重复弹 1005（`arrAlert / arrAgain` 已存盘）；
   - 旧存档（`sv != 2`）被丢弃且不崩溃。
4. 演示回放：30× 播放一遍，确认换乘与终点文案一致。

## 7. 待批准事项

- 是否推送分支触发 CI 做编译验证（本地无工具链）。
- 是否把本 spec 同步一份到 `.trae/documents/`（仓库现有设计文档惯例）。
