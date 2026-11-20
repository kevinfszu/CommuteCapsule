# 任务清单：换乘站与终点站到站提醒同级化

- [x] **T1 统一状态机（任意段上车即 `riding`）** — `onBoarded`（`if (riding) return`、任意段设 `riding/boardedAt`）、新增 `advanceLeg`
- [x] **T2 每段下车站坐标与站名** — `prefetch` 逐段 `resolveAlight`、`legAlight`、`legAlightName`
- [x] **T3 `tick` / `rideTick` 通用化** — 删除 connecting / expectArrive / 旧换乘分支，`if (riding) return rideTick(...)` 对任意段生效
- [x] **T4 到站流程拆分** — `rideTick` 末段 `endTrip`、中间段 `markAlight + advanceLeg`；`applyTransit(EXIT)`、`onCell(EXIT)` 同步
- [x] **T5 提醒策略** — 1005 `即将到达“X” / 准备下车`（含二次确认）、1007 `“X”已过站 / 请下车返回`
- [x] **T6 跳段 / 补认路径** — `jumpByLocation`、`busJumpCheck`、`joinByCell`、`applyTransit(ENTER)` 改用 `advanceLeg`
- [x] **T7 持久化升级 `sv = 2`** — `saveRun` / `restoreRun` / `clearRun`，新增 `arrAgain / overshoot / alightLeg`
- [x] **T8 UI 与演示回放文案** — `LiveBoard.kt` 乘车中显示「到 X」；`DemoPlayer.kt` 换乘/终点同文案
- [x] **T9 静态一致性检查** — 残留引用 grep 无命中；三个 kt 文件括号平衡 OK
- [x] **T10 编译验证** — 已推送 main（提交 `4cae123`），GitHub Actions `build` 运行 `gradle assembleDebug testDebugUnitTest` 通过（conclusion = success）
- [ ] **T11 真机功能回归** — 阻塞：需真机，用例见 spec 第 6 节（换乘 1005、终点 1005、过站 1007、杀进程重启不重复提醒、旧存档丢弃）

## 备注

- 换乘站（中间段）与终点站（最后一段）使用完全相同的到站提醒文案与流程。
- 中文引号 `“”` 为文案硬性要求，不得替换。
- 旧 1004「赶车提醒」已移除，换乘信息并入 1005 正文。
