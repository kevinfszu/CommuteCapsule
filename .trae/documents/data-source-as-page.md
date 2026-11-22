# 数据源改为独立页面 + 修复二级页返回层级

## Summary

1. 把「数据源」从 `AlertDialog` 改为与「算法学习」「行程记录」一致的 `GlassPage` 页面；「用高德查询并生成」也一并改为独立页面，返回时回到「数据源」。
2. 修复二级页面按系统返回键直接回首页的问题：`App.kt` 里全局 `BackHandler` 目前写死 `screen = Screen.Home`，需要按当前页面回退一级。

## Current State Analysis

- [App.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ui/App.kt#L51)：
  `BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }`。
  顶栏返回按钮走各页传入的 `onBack`（Records/Learn 正确回 Settings），但系统返回键被全局拦截后一律回 Home，造成「行程记录返回直接回首页」。
- [Settings.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ui/Settings.kt#L46)：`sourceOpen` 状态 + 文件末尾 `if (sourceOpen) SourceDialog(...)`，是数据源唯一的入口。
- [SourceDialog.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ui/SourceDialog.kt#L41)：`AlertDialog`，内容依次为���导入成功提示、当前数据源两行、已导入线路列表（含站数）、两个导入入口、数据说明、删除二次确认、格式说明。
- [AmapImportDialog.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ui/AmapImportDialog.kt#L62)：嵌套 `AlertDialog`，内容 `heightIn(max=460.dp).verticalScroll(contentScroll)`，确认按钮在弹窗底部；状态与逻辑完整（查询、反方向、勾站、单站用时、首末班、生成合并、错误条 + 自动滚顶）。
- [GlassKit.kt](file:///e:/001-Program/CommuteCapsule/app/src/main/java/com/busnotice/commute/ui/GlassKit.kt#L85)：`GlassPage(title, onBack, actions, state, horizontalPadding, content)` 只有顶栏和 `LazyColumn`，没有底部操作区。
- 页面命名与结构参考：`LearnScreen(onBack)`、`RecordsScreen(onBack)`，均为 `GlassPage + item { GlassCard { ... } }`。

## Proposed Changes

### 1. `ui/App.kt`：新增两个页面 + 修复返回栈

- `Screen` 增加 `data object DataSource`、`data object AmapImport`。
- 新增统一回退函数（放在 `App` 内、`BackHandler` 之前）：

```kotlin
fun goBack() {
    screen = when (screen) {
        Screen.Settings -> Screen.Home
        Screen.Records, Screen.Learn, Screen.DataSource -> Screen.Settings
        Screen.AmapImport -> Screen.DataSource
        else -> Screen.Home   // Home / Edit / Pick* / EditShuttle 维持原有语义
    }
}
BackHandler(enabled = screen != Screen.Home) { goBack() }
```

- `when` 分支：
  - `Screen.DataSource -> SourceScreen(onBack = { screen = Screen.Settings }, onOpenAmap = { screen = Screen.AmapImport })`
  - `Screen.AmapImport -> AmapImportScreen(onBack = { screen = Screen.DataSource }, onDone = { screen = Screen.DataSource })`
- 各页已有的 `onBack` 保持显式传值，不强行改成统一 `goBack`（`RecordsScreen`/`LearnScreen` 内部还有详情层自己的 `BackHandler`，不要动）。

### 2. `ui/GlassKit.kt`：GlassPage 支持可选底部操作区

仅为高德生成页的「生成并导入」提供固定底部按钮，其他页面默认不渲染：

```kotlin
fun GlassPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    horizontalPadding: Dp = 16.dp,
    bottomBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
)
```

`Column` 内 `LazyColumn` 之后：

```kotlin
if (bottomBar != null) {
    Box(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) { bottomBar() }
}
```

### 3. 新建 `ui/SourceScreen.kt`，删除 `ui/SourceDialog.kt`

`@Composable fun SourceScreen(onBack: () -> Unit, onOpenAmap: () -> Unit)`，用 `GlassPage(title = "数据源", onBack = onBack)`，LazyColumn 按卡片拆分：

1. **导入成功提示**（仅 `lastImport != null` 时）：`GlassCard` 内一行加粗文字，颜色 `Fuse.accent(dark)`。
2. **当前数据源**：`SectionLabel("当前数据源")` + 「线路和站点：…」「实时到站：…」两行（沿用现有文案）。
3. **已导入的线路**：卡片头 `SectionLabel("已导入的线路（N 条）")`；无数据时一行「当前使用内置演示数据，导入后立即替换」；有数据时每行「线路名 + Spacer(weight) + N 站」；保留 `maxOf` 站数取自 `ImportedJsonSource.lineStations`。
4. **导入方式**：两个 `PillButton`（`secondary = true`）——
   - 「导入 JSON 文件」：沿用现有 `rememberLauncherForActivityResult` + `Transit.importJson`，导入中显示「导入中…」且禁用；成功设置 `lastImport`、`version++`。
   - 「用高德查询并生成」：`onClick = onOpenAmap`（不再是 `amapOpen` 弹窗）。
5. **数据说明**（`meta != null`）：沿用来源/更新时间/说明与「线路格式见仓库 docs/DATA_SOURCE.md」的一行小字。
6. **删除导入数据**（仅 `hasImport`）：保留两步确认（说明 + 取消/确认删除），`Transit.clearImported` 后 `version++`、清空 `lastImport`，Toast「已恢复默认数据源」。

页面没有「完成」按钮，顶栏返回即退出。

### 4. 新建 `ui/AmapImportScreen.kt`，删除 `ui/AmapImportDialog.kt`

签名：`@Composable fun AmapImportScreen(onBack: () -> Unit, onDone: () -> Unit)`。**保留全部业务状态与逻辑**（key/city/keywords、searching/generating/error/raw/hits、selectedId/keepStops/hopMin/hopSec/clocks、`select`、`search`、`generate`、`fail`、结果折叠与「换一条」），只改容器：

- 外层从 `AlertDialog` 换成 `GlassPage(title = "用高德生成", onBack = onBack, state = listState, bottomBar = { ... })`。
- `contentScroll` 改为 `val listState = rememberLazyListState()`，传给 `GlassPage`；`fail()` 里 `scope.launch { listState.animateScrollToItem(0) }`。
- 内容拆成 item：
  1. 说明卡（「使用高德『公交信息查询 API』…」）+ 错误条（红底 10%、`Fuse.MetroRed`、圆角 12dp）。
  2. 输入卡：Key / 城市 / 线路关键字三个 `GlassField` + 「查询」按钮（`enabled = key/city/keywords 非空 && !searching && !generating`）。
  3. 结果卡：标题行 + 「换一条」；选中后只显示选中项；未选中显示全部；行左侧 `RadioButton`。
  4. 站点卡：`SectionLabel("保留的站点（已选 x/y 站…）")` + 全部站点的 `Checkbox` 行；**去掉 `heightIn(190.dp)` + 内层 `verticalScroll`**，随页面滚动，避免滚动套滚动。
  5. 参数卡：单站用时（分/秒）+ 每个方向的始发站首末班（`clocks` 现有结构）。
  6. 脚注：环线说明、未开通站需取消勾选等现有文案。
- `bottomBar`：`PillButton(onClick = { generate() }, modifier = Modifier.fillMaxWidth(), enabled = canGenerate) { Text(if (generating) "生成中…" else "生成并导入") }`。
- 生成成功：`Toast` 后调用 `onDone()`（回到数据源页面，页面重新读取数据即是最新合并结果）。

### 5. `ui/Settings.kt`：入口改为页面跳转

- `SettingsScreen` 签名增加 `onOpenSource: () -> Unit = {}`。
- 「数据源」`GlassRow.onClick = onOpenSource`。
- 删除 `var sourceOpen` 与文件末尾 `if (sourceOpen) SourceDialog(...)`。
- 保留顶部注释里「设置二级页」的说法（数据源现在也是二级页）。

### 6. 文档

- `CHANGELOG.md` [未发布] 增加两条：数据源与高德生成改为独立页面；修复二级页面系统返回键回首页的问题。
- `docs/DATA_SOURCE.md` 把「菜单「数据源」→ …」的表述改为「设置 → 数据源」，其余不变。

## Assumptions & Decisions

- 已确认：「用高德查询并生成」也改为页面，返回回数据源。
- 「行程记录」「算法学习」的顶栏返回本来就是对的，只修系统返回键；详情层内部返回逻辑不动。
- 数据源页面返回目标固定为「设置」；从高德页返回固定为「数据源」，不引入通用返回栈（当前导航是单 `Screen` 状态机）。
- 高德页成功生成后的去向：回数据源页面（用户可继续添加），不直接回首页。
- 删除操作仍保留页面内的两步确认，不改成弹窗。
- 不在本轮重做视觉：颜色、圆角、文案沿用现有实现。

## Verification

1. 本地单测：`$env:VERSION_NAME='0.4.2-local'; gradle :app:testDebugUnitTest`（编译校验，不打包）。
2. 按工作区规则推送触发 GitHub Actions，下载 APK 安装真机验证：
   - 设置 → 数据源：以整页打开，顶栏「数据源」；系统返回与顶栏返回都回到设置。
   - 数据源 → 用高德查询并生成：整页打开；返回回到数据源。
   - 行程记录：进入详情后返回 → 列表；再返回 → 设置（不再直接回首页）。
   - 算法学习：返回 → 设置。
   - 数据源页：导入 JSON、删除二次确认、成功提示、线路站数显示正常。
   - 高德页：查询、错误条滚顶、选方向折叠、勾站、生成并导入成功后在数据源页看到合并结果。
3. 截图核对深浅色下顶栏、底部按钮、错误条对比度。
