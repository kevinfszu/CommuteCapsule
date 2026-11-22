# 数据源接口

引擎只通过 `Transit` 门面访问数据，数据源实现下面两个接口（见 `app/src/main/java/com/busnotice/commute/Transit.kt`）。

## 在 App 内导入 JSON（最快）

不用改代码、不用重新编译：菜单「数据源」→「导入 JSON 文件」，选一个本地 `.json` 即可；也可以在同一个页面用高德在线查询并生成（见下一节）。

- 导入前会先完整校验，格式有错会提示原因，旧数据保持不变
- 文件保存在 App 内部存储，重启后仍然生效，优先于 `src/extra` 扩展源
- 「删除导入数据，恢复默认」会删掉文件并回到扩展源 / 演示城市
- 导入或删除后，已缓存的途经站会清空；正在进行的行程请结束并重新开始

### 文件格式

```json
{
  "region": "gz",
  "meta": { "source": "数据来源说明", "updated": "2026-11-19", "note": "备注" },
  "lines": [
    {
      "id": "gz13-east", "name": "13号线", "subway": true, "reverseId": "gz13-west",
      "first": "06:00", "last": "22:30", "hopSec": 140,
      "service": {
        "weekday": [
          { "from": "06:00", "to": "09:00", "headwaySec": 360 },
          { "from": "09:00", "to": "17:00", "headwaySec": 420 },
          { "from": "17:00", "to": "20:00", "headwaySec": 540 },
          { "from": "20:00", "to": "22:30", "headwaySec": 660 }
        ],
        "weekend": [
          { "from": "06:00", "to": "10:00", "headwaySec": 480 },
          { "from": "10:00", "to": "20:00", "headwaySec": 360 },
          { "from": "20:00", "to": "22:30", "headwaySec": 480 }
        ]
      },
      "stops": [
        { "name": "鱼珠", "lat": 23.1024745, "lng": 113.4267716 },
        { "name": "裕丰围", "lat": 23.0974375, "lng": 113.4487822 }
      ]
    },
    {
      "id": "gz13-west", "name": "13号线", "subway": true, "reverseId": "gz13-east",
      "first": "06:03", "last": "23:05", "hopSec": 140,
      "service": {
        "weekday": [
          { "from": "06:03", "to": "09:00", "headwaySec": 360 },
          { "from": "09:00", "to": "17:00", "headwaySec": 420 }
        ]
      },
      "stops": [
        { "name": "裕丰围", "lat": 23.0974375, "lng": 113.4487822 },
        { "name": "鱼珠", "lat": 23.1024745, "lng": 113.4267716 }
      ]
    }
  ]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `region` | 否 | 导入时区域编号为空则自动填写 |
| `meta.source` / `meta.updated` / `meta.note` | 否 | 仅作记录 |
| `lines[].id` | 是 | 唯一，方向也各算一条记录 |
| `lines[].name` | 是 | 显示名，两个方向必须相同 |
| `lines[].subway` | 否 | 地铁写 `true`，默认 `false` |
| `lines[].loop` | 否 | 环线写 `true` |
| `lines[].dirLabel` | 环线必填 | 方向名，如 `内环` / `外环`；非环线默认取终点站名 |
| `lines[].reverseId` | 否 | 反方向线路的 `id` |
| `lines[].first` / `last` | 否 | 该方向**始发站**的首末班，`"HH:mm"`，默认 `06:00` / `23:00`；其他站按站序推算 |
| `lines[].hopSec` | 否 | 单站用时（秒），默认地铁 140、其他 90，限制 20–900 |
| `lines[].color` | 否 | 线路色，6 位十六进制（如 `F9BE58`），可带 `#`；也接受高德原始字段名 `uicolor`。省略时按线路号取本地调色板 |
| `lines[].service.weekday` | 否 | 工作日分时段间隔数组，见下文 |
| `lines[].service.weekend` | 否 | 周末分时段间隔数组；省略时复制 `weekday` |
| `lines[].stops[].name` | 是 | 站名 |
| `lines[].stops[].lat` / `lng` | 是 | WGS-84 坐标 |
| `stops[].first` / `last` | 否 | 该站自己的首末班，覆盖按站序推算的值 |

### 分时段发车间隔

`service.weekday` / `service.weekend` 是 band 数组，每段 `{ "from": "HH:mm", "to": "HH:mm", "headwaySec": 360 }`：

- `headwaySec` 单位是**秒**，30–1800；例如 5 分 20 秒写 `320`；
- `from < to`，按时间升序，最多 8 段；`to` 可以写 `24:00`；
- 时段之外的时刻取最近一段的间隔；
- 整段省略时，地铁按高峰 360 秒 / 平峰 540 秒，非地铁 600 / 900，高峰默认 `07:00-09:30`、`17:00-19:30`；
- 站点首末班 = 始发站首末班 + `站序 × hopSec`，站序从 0 开始；
- App 里添加地铁会带出这张表，编辑行程时可以逐段改间隔（分 + 秒），时段沿用数据。

### 方向与环线

- **普通线路**：每个方向一条记录，站序相反，用 `reverseId` 互相指向；方向名默认是终点站名
- **环线**：每个方向也各一条记录，`loop: true` + `dirLabel`（如 `内环` / `外环`）；站序首尾重复同一个站时，末尾重复站会自动去掉
- 地铁方向会参与计算：候选站的方向不同，途经站和用时也不同

## 在 App 内用高德生成

菜单「数据源」→「用高德查询并生成」，不需要自己找 JSON 文件。该功能调用高德「公交信息查询 API」，需要先注册成为[高德开发者](https://lbs.amap.com/)并创建「Web 服务」类型的 Key；Key 只保存在本机，App 不内置也不上传。

1. 填 Key、城市（城市名或 adcode，如 `广州` / `440100`）、线路关键字（如 `13号线`），点「查询」
2. 从结果里选一个方向，App 会自动带上反方向，并显示每条方向的站数、首末班
3. 列站后可以取消勾选尚未开通的站；少于 2 站不能生成
4. 补两个高德不提供的信息：**单站用时**（分 + 秒，20–900 秒）和每个方向**始发站首末班**
5. 点「生成并导入」，立即生效

生成规则：

- 每条方向写一条记录，`id` 为 `amap-<高德线路 id>`，双向用 `reverseId` 互指；站序首尾重复的环线会自动去尾并写入 `dirLabel`
- 高德的 `uicolor` 写入 `color`，线路色由此决定；缺省时回退本地调色板
- `service` 由 `timedesc` 转换：`day_type` 含 `weekday` 的进 `weekday`，其余进 `weekend`；间隔取 `interval_time`
- 高德没有站点级首末班，中间站按 `始发站 + 站序 × hopSec` 推算
- 坐标从 GCJ-02 转成 WGS-84 后写入
- 已有导入数据时**合并**：同名线路覆盖，其他线路保留；合并结果校验失败则退回只写本次生成的线路

Key 注意事项：

- 必须用「Web 服务」类型的 Key，且**未开启数字签名校验**；否则会提示 `INVALID_USER_SCODE`
- 该 Key 需要开通「公交信息查询」服务
- 查询失败时 App 会给出中文原因（Key 无效、服务未开通、次数用尽、IP 白名单等）
- 高德接口数据受其服务协议约束，本仓库不分发、不缓存这些数据

## StaticSource（必需）

| 方法 | 说明 | 不支持时 |
|---|---|---|
| `search(key, region)` | 按关键字搜线路（含方向）和站点 | 必须实现 |
| `lineDetail(lineId, region)` | 线路全部站点（顺序、坐标）和反方向线路 ID | 必须实现 |
| `nearbyStops(lat, lng, region)` | 附近站点及经过的线路 | 返回空：选站时只能搜索 |
| `stationLines(siteId, sameNameId, region)` | 某站（含对面站台）经过的线路 | 返回空 |
| `subwayDirs(lat, lng, stationName, region)` | 地铁站各方向首末班、分时段间隔、方向编号、本站序号 | 返回空：地铁按手填间隔估算 |
| `metroStops(board, toLat, toLng, region)` | 地铁坐几站 | 返回 null |
| `metroRoute(MetroQuery)` | 地铁途经站及坐标、预计用时；`MetroQuery.dir` 是候选方向 | 返回 null：不显示途经站 |

## RealtimeSource（可选）

| 方法 | 说明 |
|---|---|
| `lineRealtime(RealtimeQuery)` | 驶向等车站的车辆：预计秒数、还有几站、距离 |

没有实时数据源时，引擎依次退回：本机学到的时刻表 → 按站距和间隔估算。

## 约定

- 坐标一律 WGS-84（与手机 GPS 一致），其他坐标系请在数据源中自行转换
- `region` 是数据源自带的区域编号，仅作标识；首次加载时会自动记录，无需手动填写
- 网络失败直接抛异常，引擎会捕获并降级
- 线路 / 站点 ID 由数据源自定，引擎只原样保存

## 加载

启动时按以下顺序选择静态数据源：App 内导入的 JSON → `src/extra` 扩展源 → 内置演示城市。

扩展源在 `app/src/extra/java/com/busnotice/commute/extra/Extras.kt` 中列出你的实现，构建时自动编入。`app/src/extra/assets` 里的文件会打进 App，可用 `Transit.appContext.assets` 读取。

## 数据从哪里来

广州地铁没有公开、稳定、允许再分发的班次 API。可行的组合是：

| 内容 | 来源 |
|---|---|
| 站序、坐标、环线关系 | OpenStreetMap / 公开线路图 |
| 双向始发站首末班 | 广州地铁官网「站点信息」页、官方公告 |
| 工作日 / 周末分时段发车间隔 | 广州地铁官方新闻稿（调图公告） |
| 核对与补齐 | 高德开放平台「公交信息查询」的 `timedesc`（开发期离线整理，原始响应不入仓库） |

高德接口的数据受其服务协议约束，本仓库不分发、不缓存其接口数据，也不附带 Key；App 只在你主动点击「查询」时用你自己的 Key 请求，生成的线路只存在本机。

## 数据来源与许可

本仓库不提供真实线路数据。自己整理或导入数据时，请确认来源允许使用（例如 OpenStreetMap 需遵守 ODbL 署名与相同方式共享）。`data/` 目录已加入 `.gitignore`，避免误提交。

## 参考

- `examples/json-source`：最小模板，从 JSON 文件读线路，三步可用
- `MockSource.kt`：内置演示城市，含模拟实时来车
