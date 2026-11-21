# 在 App 内用高德 Web API 生成线路数据源

## 1. 目标

在「设置 → 数据源」里增加一条能力：用户填入自己的高德 Web 服务 Key，按关键字查询线路 → 选择线路 → 勾选实际停靠的站点 → App 直接把这条线路（含双向、首末班、分时段间隔、线路色）生成成 `docs/DATA_SOURCE.md` 约定的 JSON 并立即导入生效。

* 不再依赖开发期离线脚本，用户自己就能补线路
* 必须保留高德 `uicolor`（写入 `lines[].color`，`JsonTransit` 已同时接受 `color` / `uicolor`）
* 生成时默认合并到现有导入数据，同名线路覆盖（用户已确认）
* 生成前列出站点、默认全选，可取消勾选未开通车站（用户已确认）
* 高德缺的「单站用时 hopSec」与「始发站首末班」在生成弹窗里人工补（用户已确认，不做后续编辑页）；站点级首末班仍按站序推算

## 2. 现状（已核对）

| 位置                                                                                                                           | 现状                                                                  |
| ---------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| [SourceDialog.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ui/SourceDialog.kt)          | 「数据源」弹窗：区域编号、导入 JSON 文件、从链接导入、删除导入数据                                |
| [Transit.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/Transit.kt#L123-L206)             | `importJson(ctx, text)` 校验→落盘→切换数据源；prefs 里已有 `region` / `data_url` |
| [ImportedJsonSource.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ImportedJsonSource.kt) | 只暴露解析后的 `Doc`，**没有**读取原始 JSON 文本的入口（合并需要）                           |
| [JsonTransit.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/JsonTransit.kt#L373-L377)     | `parseColor` 已支持 `color` 优先、`uicolor` 兜底                            |
| [RemoteTransit.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/RemoteTransit.kt)           | 现成的 `HttpURLConnection` 拉取写法（超时、2 MiB 上限、UA）                        |
| `data/amap/analysis.md` + `data/amap/gz-13号线.json`                                                                           | 高德原始响应的真实结构（下面第 3 节按实测字段设计）                                         |

高德接口：`GET https://restapi.amap.com/v3/bus/linename?key=&city=&keywords=&extensions=all`，返回 `buslines[]`，每条方向一条记录。

实测字段（来自 `data/amap/gz-13号线.json`、`gz-11号线.json`）：

* `id`、`name`（`地铁13号线(新沙--天河公园)`、`地铁11号线内环(赤沙--赤沙)`）、`type`（`地铁`/`公交`）
* `start_time` / `end_time`（`0614` / `2256`，该方向始发站首末班）
* `uicolor`（`F9BE58`、`818530`）、`loop`（`1`/`0`）、`direc`（反方向记录的 `id`）
* `busstops[]`：`id`、`name`、`location`（`lng,lat`，**GCJ-02**）、`sequence`
* `timedesc`：URL 编码 JSON；`rule_group[].date.day_type` 是字符串数组（`["weekday","1","2",…]` / `weekend`），`time_group[]` 内是 `start_time` `"06:00:00"`、`end_time` `"09:00:00"`、`interval_time` `"00:06:00"`。11 号线该字段是 `"\"\""`（空）

## 3. 方案

### 3.1 新增 `AmapData.kt`（纯 JVM 逻辑，可单测）

`app/src/main/java/com/busnotice/commute/AmapData.kt`

```kotlin
object AmapData {
    const val HOST = "https://restapi.amap.com/v3/bus/linename"

    /** 拼查询地址；keywords / city 做 URL 编码 */
    fun searchUrl(key: String, city: String, keywords: String): String

    /** 查询结果里的一条方向记录 */
    data class Hit(
        val amapId: String, val rawName: String, val name: String,   // name 如「13号线」「11号线」
        val dirLabel: String,                                        // 环线：内环/外环；否则空
        val subway: Boolean, val loop: Boolean,
        val stopCount: Int, val firstMin: Int, val lastMin: Int,
        val colorHex: String, val reverseAmapId: String,
    )

    /** status!=1 抛 IllegalStateException（带 info/infocode 的中文说明）；无 buslines 返回空表 */
    fun parseHits(json: String): List<Hit>

    /** 选中两条方向 + 勾选保留的站名 + 人工补的 hopSec / 每方向始发站首末班，生成 lines 数组 */
    fun buildLines(
        rawJson: String, hits: List<Hit>, keepStops: Set<String>, region: String,
        hopSec: Int,                       // 单站用时（秒），20–900，用户可改
        clocks: Map<String, Pair<Int, Int>>, // amapId → (首班分钟, 末班分钟)，缺省用高德值
    ): String

    /** 合并：existing 为 null 则整份替换；否则删除同名线路（两个方向）后追加 */
    fun merge(existing: String?, generated: String): String

    /** 站名清洗：去掉末尾「(…‑…)」、剥离内环/外环、去掉「地铁」前缀 */
    fun cleanName(raw: String): Pair<String, String>   // name to dirLabel

    /** GCJ-02 → WGS-84（迭代反解 3 次），同时提供 wgsToGcj 供单测往返 */
    fun gcjToWgs(lat: Double, lng: Double): Pair<Double, Double>
    fun wgsToGcj(lat: Double, lng: Double): Pair<Double, Double>
}
```

生成规则（逐项对应 `DATA_SOURCE.md`）：

| 字段                  | 生成方式                                                                                                                                     |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `id`                | `amap-<高德 id>`，保证与已有数据不冲突                                                                                                                |
| `name`              | `cleanName` 结果，如 `13号线`、`11号线`；双向同名                                                                                                      |
| `subway`            | `type == "地铁"`                                                                                                                           |
| `loop` / `dirLabel` | `loop == "1"`；`dirLabel` 取清洗出的 `内环/外环`                                                                                                   |
| `reverseId`         | `amap-<反方向 amapId>`；在本次查询结果的其它记录里按 `direc` 双向匹配，环线再按同名 + `内环/外环` 兜底；找不到就不写 `reverseId`（仍是合法单方向数据）                                        |
| `first` / `last`    | 用户可改，默认取 `start_time` / `end_time` 的 `HHmm` → `HH:mm`（每个方向一组，独立）                                                                       |
| `stops[]`           | `busstops` 按 `sequence` 升序；`location` 拆 `lng,lat` 后 GCJ→WGS；只保留 `keepStops` 里的站；环线首尾同名去掉末尾重复                                             |
| `stops[].first/last` | 不写，按站序推算（`始发站首末班 + 站序 × hopSec`），本次不提供逐站编辑                                                                                              |
| `color`             | `uicolor`（6 位十六进制才写）                                                                                                                     |
| `service`           | `timedesc` 解码后按 `day_type` 分 weekday/weekend；`time_group` 转 `{from,to,headwaySec}`（`interval_time` `00:06:00` → `360`）；空/非法就省略整段，交给引擎默认值 |
| `hopSec`            | 用户填的单站用时（分+秒），默认地铁 140 / 公交 90；写入 `lines[].hopSec`                                                                                      |
| `region`            | 传当前 `Transit.region`                                                                                                                     |
| `meta`              | `source = "高德开放平台（App 内生成）"`、`updated = 今天`                                                                                              |

`merge` 行为：

1. `existing` 为空 / `JsonTransit.parse` 失败 → 直接用新文档（替换）
2. 否则把新 `lines` 中所有 `name` 从旧 `lines` 里删掉（同名两个方向一起删），再追加，保留旧 `region`/其它线路
3. `meta` 更新为本次生成的来源与日期

### 3.2 新增 `AmapClient.kt`（网络）

```kotlin
object AmapClient {
    suspend fun search(key: String, city: String, keywords: String): String
}
```

* 仿 `RemoteTransit`：`Dispatchers.IO`、连接 8 s / 读取 20 s、`Accept: application/json`、`UA: CommuteCapsule`、1 MiB 上限
* 只接受 `http/https`；`key` 只进 URL，**不写日志**
* 失败抛 `IllegalStateException`（`HTTP 403`、超时、`status!=1` 的中文说明由 `AmapData.parseHits` 统一转）

### 3.3 `Transit.kt`：记住 Key 与城市

* prefs 新增 `amap_key`、`amap_city`
* `fun amapKey(): String` / `fun setAmapKey(ctx, key)`
* `fun amapCity(): String` / `fun setAmapCity(ctx, city)`
* `ImportedJsonSource` 新增 `fun rawText(ctx: Context): String?`（读 `imported_transit.json`），供合并用

### 3.4 新增 `ui/AmapImportDialog.kt`

`@Composable fun AmapImportDialog(dark: Boolean, onDismiss: () -> Unit, onImported: (ImportSummary) -> Unit)`

一个 `AlertDialog`（沿用 `DialogShape` / `Fuse.surface`），内容自上而下：

1. **高德 Key**：`GlassField` 单行，初始值 `Transit.amapKey()`；说明「高德开放平台 → 应用管理 → Web 服务 Key」
2. **城市**：单行，初始值 `Transit.amapCity()`；说明「城市名或 adcode，如 广州 / 440100」
3. **线路关键字** + 「查询」`PillButton`（三者非空才可用；查询中显示「查询中…」）
4. **结果列表**：每条一行，显示清洗后的名称 + 方向（终点站或内/外环）+ `N 站` + 首末班；点选高亮（单选）
5. 选中后展开 **站点勾选**：默认全选，每站一个 `Checkbox`，上方显示「已选 n/m 站」；下方提示「未开通的车站请取消勾选」
6. **单站用时**（高德没有，人工补）：一个 `GlassField`，按当前线路默认 140 秒（地铁）/ 90 秒（公交），用「分 + 秒」两格输入，范围 20–900 秒；说明「相邻两站平均行车时间，影响站点首末班与到站推算」
7. **始发站首末班**（可改）：每个方向一行，标签用该方向终点站名（如「开往 新沙」），两个 `HH:mm` 输入，默认取高德 `start_time` / `end_time`；格式非法时阻止生成并提示
8. **「生成并导入」**：按勾选 + 人工补的值生成 → `Transit.importJson` → `Toast`「已导入 X 条线路、Y 个站点」→ 调 `onImported(summary)`（关闭本弹窗，设置页数据源标题随之刷新）
9. 错误就地红字显示（如 `INVALID_USER_KEY` → 「Key 无效或未开通公交查询服务」）
10. 成功后写回 `setAmapKey` / `setAmapCity`

导入在主线程外执行（`withContext(Dispatchers.IO)`），与 `SourceDialog` 现有写法一致。

### 3.5 `ui/SourceDialog.kt`：入口

在「导入 JSON 文件」按钮下方加一个 `PillButton`「用高德查询并生成」，点击 `amapOpen = true`；`if (amapOpen) AmapImportDialog(...)`（成功后关闭自身并 `version++`，保持「数据源」弹窗开着，能看到更新后的标题）。

### 3.6 `docs/DATA_SOURCE.md`

在「在 App 内导入 JSON（最快）」之后新增小节「在 App 内用高德生成」：Key 填哪、城市怎么填、查询→选线→勾站→补单站用时与始发站首末班→生成的流程、合并/覆盖规则、`uicolor` 会写入 `color`、未开通站需自行取消勾选。

## 4. 决策与假设

* **Key 由用户提供**，只存 `SharedPreferences`（应用私有），不上传、不写日志；`RemoteTransit` 同款网络栈，无第三方 SDK
* **只支持未开启「数字签名」的 Web 服务 Key**；若 Key 开了签名校验，高德返回 `INVALID_USER_SCODE`，界面提示去控制台关闭签名或换 Key
* **公交线路也能生成**（`type=公交` → `subway=false`），同一套流程，不额外做地铁专用界面
* **站点勾选对双向同时生效**（按站名过滤），保证两方向站序一致
* 勾选后某方向不足 2 站 → 阻止生成并提示
* 高德无站点级首末班，按站序推算（`始发站首末班 + 站序 × hopSec`），不提供逐站编辑
* 高德无 `hopSec`：生成弹窗里人工给一个整线值（默认地铁 140 / 公交 90），写入 `lines[].hopSec`；后续学习到的站间时长仍会在此基础上修正
* 始发站首末班默认取高德，可在生成弹窗内按方向改；改了只影响该方向的 `first`/`last`，站内其它站仍按站序推算
* 生成的数据不缓存高德原始响应（遵守其服务条款），只在内存里处理

## 5. 测试与验证

### 单测（JVM，新增 `AmapDataTest.kt`）

用 `data/amap` 里的实测结构做样本字符串（不新增二进制夹具）：

1. `parseHits`：13 号线双向两条 → `name=13号线`、`subway=true`、`colorHex=818530`、首末班 `06:00`/`22:30`、`stopCount=15`
2. `cleanName`：`地铁13号线(新沙--天河公园)` → `13号线`/空；`地铁11号线内环(赤沙--赤沙)` → `11号线`/`内环`
3. `timedesc`：13 号线 → 工作日 4 段（360/420/540/660 秒）、周末 3 段
4. `gcjToWgs`：`wgsToGcj` 往返误差 < 3 m
5. `buildLines`：产物能被 `JsonTransit.parse` 解析；2 条记录 `reverseId` 互相指向；环线末尾重复站被去掉；`color` 保留；`service` 段落正确
6. `buildLines` 人工补充：传 `hopSec=150` 与某方向的首末班覆盖 → 产物 `hopSec=150`，两条方向各自取到自己的 `first`/`last`（未覆盖的一方保持高德值）
7. `merge`：先有 11 号线，合并 13 号线 → 两条都在；再合并一次 13 号线 → 仍是 2 条（同名被替换，不重复）

### 真机

1. 按工作区规则**本地只跑单测**，安装包走 GitHub Actions：提交推送 → 下载产物（签名已统一为 `99732BE4…`）安装
2. 设置 → 数据源 → 用高德查询并生成：填入用户自己的 Key 与「广州」，查 `13号线`，选「地铁13号线(新沙--天河公园)」，取消勾选未开通站，把单站用时改成 150 秒、改一下某个方向的首班时间，生成
3. 回到首页新建行程，确认候选线路带出 13 号线、站序/首末班/间隔正确、线路色为高德 `uicolor`
4. 再生成 11 号线，确认 13 号线仍在（合并生效）
5. 错误路径：填一个错误 Key，确认提示而不是崩溃
