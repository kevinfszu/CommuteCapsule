# 通勤胶囊 UI 走查第三轮：入口去重、生效日期、定位地址、行程段排版

## 摘要

针对你提出的 4 个问题做一轮聚焦改造：删除首页底部与右上角重复的入口；把「生效日期」从「7 天 + 法定工作日」两套并排改为「重复方式」二选一的单选；给「离开出发地后唤起」补上可读的出发地名称；重排行程段卡片为「线路 / 上车站 → 下车站 / 方向 / 间隔」三段式，并把「分」统一写成「分钟」。

## 现状分析

### 1. 首页底部入口与右上角重复

- [HomeScreen.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ui/HomeScreen.kt) 列表末尾有 `item(key = "new-trip")` 整行「＋ 新建行程」，标题栏已有 `+` 按钮（`onNew`）。
- 同一文件底部还有一个**无条件**的 `item(key = "perm") { PermRow(...) }`，而列表上部已有 `if (permUrgent)` 的权限行；全齐时底部仍显示，与右上角齿轮进入的设置页重复。
- 空状态里另有「新建第一条行程」按钮，与 `+` 重复。

### 2. 生效日期两种类型并排，视觉别扭

- [TripEditor.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ui/TripEditor.kt#L144-L166)：7 个星期芯片和「法定工作日」芯片分别渲染，语义上却是互斥的两种模式（`chinaWorkday` 为真时 `dayMask` 被忽略），用户无法从排版看出「二选一」，且下方还有一段 3 行的说明文字撑高卡片。

### 3. 定位后没有地址

- [Trip.kt 模型](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/Model.kt#L199-L205) 只存 `leaveLat / leaveLng / leaveRadiusM`，没有名字字段。
- [TripEditor.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ui/TripEditor.kt#L215-L224) 定位成功后只弹「已将当前位置设为出发地」Toast，界面不落任何可核对的信息，用户无法确认定的是不是对的地方。

### 4. 行程段信息全挤在一行小字

- [TripEditor.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ui/TripEditor.kt#L403-L434) 每个候选：标题行 `13号线  地铁`，下一行把「白江上车 · 天河公园下车 · 往天河公园 · 工作日 6–11 分钟 · 周末 6–8 分钟」拼成一段 12sp 文案，真机上折成 3–4 行，上车站/下车站/方向/间隔四类信息没有层级。
- 右侧「编辑」文字 + 关闭图标与左侧线路牌不对齐，段标题「第 2 段」又套一层卡片，视觉噪音大。

## 方案

### 3.1 首页入口去重（HomeScreen.kt）

1. 删除列表末尾的 `item(key = "new-trip")` 整行。
2. 删除底部无条件的 `item(key = "perm")`；只保留 `if (permUrgent)` 的那一条。全齐时首页不出现权限块。
3. 空状态删除「新建第一条行程」按钮，改为一行 13sp 灰字：「点右上角 + 新建第一条行程」。
4. 不改动标题栏 `+` 与齿轮；不改动 `bottom contentPadding`。

### 3.2 生效日期改为「重复方式」二选一（TripEditor.kt）

`Section("生效日期")` 内容替换为：

```
[ 每周 ] [ 法定工作日 ]        ← 两个等宽 GlassChip（weight 1f，minTouch = false，高 48dp）
一周七天：一 二 三 四 五 六 日   ← 仅「每周」时显示，沿用现有等分 Row
自动跳过法定节假日（含调休）      ← 仅「法定工作日」时显示，一行 12sp 灰字
```

- 选中「法定工作日」时把 `chinaWorkday` 置 true，`dayMask` 原样保留，切回「每周」时恢复；
- 切回「每周」时若 `dayMask == 0`，默认置为 `0b0011111`（周一至周五），避免出现「一天都没选」的空状态；
- 原来 3 行的解释文案压缩为一行，详细说明保留在设置页/文档，不再占用编辑页首屏。

### 3.3 出发地显示地址（Model.kt、Places.kt、TripEditor.kt、新增 Geocode.kt）

1. **模型**：`Trip` 增加 `val leaveName: String = ""`；`toJson()` 写入 `leaveName`，`fromJson()` 用 `optString("leaveName", "")`，旧数据无感兼容。
2. **反查地址**：新增 `Geocode.kt`：

```kotlin
object Geocode {
    /** 系统反向地理编码：取「道路 + 区 + 市」，失败返回 null（不抛异常） */
    suspend fun nameOf(ctx: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(ctx, Locale.CHINA).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(a.thoroughfare, a.subLocality, a.locality).distinct().joinToString("")
                    .ifBlank { null }
            }
        }.getOrNull()
    }
}
```

3. **家/公司优先**：`Places` 增加 `internal fun nearestLabel(ctx: Context, lat: Double, lng: Double, maxM: Double = 120.0): String?`，用已确认的 `家 / 公司` 坐标 + `Geo.distanceM` 判断，命中返回「家」或「公司」。
4. **定位**：点「重新定位」后一次协程内完成定位 + 反查，统一写入：

```kotlin
val l = Loc.current(ctx) ?: return@launch
val near = Places.nearestLabel(ctx, l.latitude, l.longitude)
val name = near ?: Geocode.nameOf(ctx, l.latitude, l.longitude) ?: ""
onChange(trip.copy(leaveLat = l.latitude, leaveLng = l.longitude, leaveName = name))
```

5. **展示**：`leaveMode()` 为真时，在「将当前位置设为出发地」按钮下方显示状态块：

```
出发地           家
                 天河路 385 号 · 触发半径 200 米
[ 重新定位 ]
```

- 主行：`leaveName` 非空则显示（家/公司优先，其次地址）；
- 副行：若主行是「家/公司」，副行显示反查到的地址（本地状态缓存，不写库）；无地址时显示「触发半径 200 米」；
- 老数据有坐标但没有 `leaveName` 时，用 `LaunchedEffect` 反查一次仅用于展示，不改动草稿；用户点「重新定位」或保存时才落库。

### 3.4 行程段卡片重排（TripEditor.kt）

采用方案 B（三段式 + 间隔胶囊），并按你的要求把「分」写成「分钟」。每个候选渲染为：

```
[13] 13号线  地铁                          编辑   ✕
白江  ──────▶  天河公园
开往 天河公园 方向
[工作日 6–11 分钟] [周末 6–8 分钟]
```

- **头行**：`LineBadge` + 线路名（15sp 600）+ 方式标签（`地铁 / 公交 / 班车`，11sp 细边框胶囊），右侧保留「编辑」文字按钮与移除图标，垂直居中对齐；
- **第二行**：`上车站 → 下车站`，14sp，站点加粗、箭头用当前 `dim` 色；下车站为空时只显示「白江 上车」；
- **第三行**：方向，12sp 灰字：公交 `开往 ${lastStop} 方向`、地铁 `往 ${metroDir}`、班车显示发车时刻；字段为空时整行省略；
- **第四行**：间隔胶囊，11sp 灰底：`工作日 ${lo}–${hi} 分钟`、`周末 ${lo}–${hi} 分钟`（复用 `scheduleText()` 的数据，文案去掉「分」缩写）；
- 删除原来把四类信息拼成一段的 `Text(...)`；
- 保留段标题「第 N 段」、删除按钮、换乘步行 Stepper、`+ 公交 / + 通勤班车 / + 地铁`、预计乘车时长 Stepper，位置不变；
- 大数字胶囊（`5分`、`12分`）仍保留紧凑写法，不改；本次只统一编辑页与列表里的间隔/时长文案为「分钟」。

## 假设与决定

| 决定 | 依据 |
| --- | --- |
| 首页只保留右上角入口，权限行仅在缺必需权限时出现 | 你选择「只保留右上角」 |
| 出发地显示「家/公司 > 地址 > 已设置」，地址用系统 Geocoder | 你选择「地址 + 家/公司标签」 |
| 段卡采用三段式 + 间隔胶囊，间隔用「分钟」 | 你对方案 B 的文案修正；A 会隐藏间隔，与你关注的「工作日/周末间隔」冲突 |
| 生效日期用二选一，不引入「每天/周末」预设 | 你的原话是「两种类型排版别扭」，先解决互斥关系；预设可下一轮再加 |
| 不改 `dayMask` / `chinaWorkday` 存储结构 | 避免数据迁移风险，纯 UI 重构 |

## 验证步骤

1. 本地签名构建并安装（`gradle --stop` 后 `--no-daemon`，避免 daemon 环境变量不刷新）。
2. 首页：确认无底部「＋ 新建行程」、无权限块；临时关闭一项必需权限，确认权限行出现且点击进入设置页。
3. 编辑页：切「每周 / 法定工作日」，确认芯片互斥、7 天行只在一侧出现；保存后回到首页核对 `daysText()`。
4. 出发地：点「重新定位」，确认出现「家」或「xx路」+ 半径，二次进入编辑页仍显示（已落库）。
5. 段卡：确认每个候选为四行结构、方向与上下车站可读、间隔显示「分钟」、编辑/移除可点。
6. 深色模式复拍首页 + 编辑页，确认新文案与胶囊对比度正常。
7. 更新 `docs/ui-audit/tasks.md` 验证记录。

## 影响面

- 修改：`HomeScreen.kt`、`TripEditor.kt`、`Model.kt`、`Places.kt`
- 新增：`Geocode.kt`
- 不改：`App.kt` 导航、`Decision.kt`、`LiveBoard.kt`（除复用 `lineColor` / `LineBadge` 外不动）
