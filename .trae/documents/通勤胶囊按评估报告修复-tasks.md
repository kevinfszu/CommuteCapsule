# 通勤胶囊：按评估报告修复（任务清单）

对应 Spec：`通勤胶囊按评估报告修复-spec.md`
需求契约：`通勤胶囊功能与真实意图对齐评估.md`

## T1 新增纯投影层（P1 核心）

- [x] 新建 `app/src/main/java/com/busnotice/commute/TimelineProjection.kt`
- [x] 实现 `StopEta / NodeEta / Result / Input / compute()`
- [x] L0 逐站：以最后一次 `StopMark` 为锚，逐区间累加，夹取 30..600 秒
- [x] L1 节点链：未上车 / 已下车 / 后续各段分支
- [x] L2 / L3：尾段 EXIT → ARRIVE，输出 `legEndAt` / `tripEndAt`
- [x] 去重与 `at > 0` 过滤

## T2 服务侧接入四层重算（P1）

- [x] `LiveSnapshot` 增加 `stopEta / nodeEta / legEndAt / tripEndAt`
- [x] `LiveState.toJson / fromJson` 序列化
- [x] 新增 `boardGapSec / boardTimeAfter / projectNow / withEta / refreshEta`
- [x] 三处 `publish` 改为 `withEta(...)`
- [x] 10 处人工打卡点调用 `refreshEta()`

## T3 时间口径与节点链修正（P2）

- [x] `syncNodeCurrent()`：EXIT 优先于 ALIGHT，出站后给 ARRIVE
- [x] `stampCurrentNode()`：出站 / 到达两条路径
- [x] `metroBoardAt()` 自动上车回退「停稳」25 秒（不早于进站）
- [x] `learnNodes()`：只收人工端点；新增 `Metric.TRIP`
- [x] `settleNodes()` 写入 `markNames`

## T4 学习与报表（P2 / P3）

- [x] `NodeLog.RunRecord` 增加 `markNames` 并序列化
- [x] `NodeLog.saveLastRun` 改命名参数
- [x] `Learn.Metric` 新增 `TRIP(1, 300..10800)`
- [x] `Learn.recordBoardClock / boardClock / boardClockStat`
- [x] `Learn.rollback` 支持 `clock` 条目
- [x] `LearnReport` 增加「换乘站上车时刻」「全程时长」，文案改「人工样本 + 波动」

## T5 界面（P3 / P4）

- [x] `LiveBoard` 节点条显示 `≈HH:mm`，节点名语义化
- [x] `LiveBoard` 下一站 ETA、本段 / 全程结束、可折叠后续各站
- [x] `LiveBoard` 新增「现在出站」「现在到达」按钮
- [x] `RecordsScreen` 新增 `WeekCard` 与打卡站名
- [x] `HomeScreen` 出发建议移到行程列表之后

## T6 静态自检

- [x] 8 个改动文件括号平衡检查全部通过
- [x] `metroProgress` / `stopPairSec` / `WalkLearn.durationMedian` / `Learn.*` 签名一致性复核
- [x] `Pill` / `NodeTimeline` 调用参数顺序复核
- [x] `NodeLog.RunRecord` 旧记录（位置参数 4 个）兼容复核

## T7 编译与真机验收

- [x] 推送到 GitHub 触发 Actions，`assembleDebug` + 单元测试（commit `6227b4a`，已推送 `main`）
- [x] `CHANGELOG.md` 不额外改动（用户未明确要求；工作区已有的改动随本次提交一起入库）

移交用户：Actions 构建完成后，在真机上安装并按 Spec 第 4 节 7 条逐条验收（当前环境无法访问 GitHub Actions API，也无法安装到真机）。
