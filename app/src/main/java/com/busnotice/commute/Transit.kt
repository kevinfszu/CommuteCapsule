package com.busnotice.commute

import android.content.Context

/*
 * 公交 / 地铁数据接口层。
 *
 * 引擎和界面只认这里的数据类和 [Transit] 门面，不关心数据从哪来。
 * 数据源分两类能力：
 *  - 静态数据（[StaticSource]）：搜线路 / 站点、线路站序和坐标、地铁首末班、地铁途经站；
 *  - 实时数据（[RealtimeSource]）：某站的来车。可以没有——没有时引擎退回学到的时刻表和估算。
 * 所有坐标统一 WGS-84（和手机 GPS 一致），其他坐标系由各数据源自行转换。
 * region 是数据源自己的「城市 / 区域」编号，由用户配置，代码里不写任何默认城市。
 */

// ---------- 统一数据格式 ----------

data class NearLine(val lineId: String, val name: String, val lastStop: String, val stopIndex: Int)

data class NearStop(
    val stopId: String, val name: String, val distanceM: Int, val isSubway: Boolean,
    val siteId: String, val sameNameId: String,
    val lines: List<NearLine>,
)

data class StopPoint(val stopId: String, val name: String, val order: Int, val lat: Double, val lng: Double)

/** label 只在环线填方向名（如 内环/外环），普通线路留空由界面显示「开往 终点」 */
data class LineDir(
    val lineId: String, val firstStop: String, val lastStop: String, val label: String = "",
)

data class SearchLine(val name: String, val isSubway: Boolean, val dirs: List<LineDir>)

data class SearchStation(
    val stopId: String, val name: String, val lat: Double, val lng: Double,
    val isSubway: Boolean, val siteId: String, val sameNameId: String,
)

data class SearchResult(val lines: List<SearchLine>, val stations: List<SearchStation>)

data class LineDetail(
    val lineId: String, val name: String, val firstStop: String, val lastStop: String,
    val stations: List<StopPoint>, val reverseLineId: String,
    val hopSec: Int = 0,   // 平均每站用时；0 = 数据源没提供，界面用回退值
    val colorHex: String = "",   // 数据源自带的线路色（如高德 uicolor），空 = 界面按线路号取色
)

data class BusEta(
    val busId: String,
    val travelSec: Int,      // 预计还有多少秒到达等车站，<0 表示未知
    val stopsAway: Int,      // 还有几站
    val distanceM: Int,      // 距等车站的路程（米）
)

data class LineRealtime(val buses: List<BusEta>, val tip: String) {
    companion object { val EMPTY = LineRealtime(emptyList(), "") }
}

/** 地铁站某线某方向的首末班与分时段发车间隔 */
data class SubwayDir(
    val line: String, val shortName: String, val dest: String, val firstMin: Int, val lastMin: Int,
    val dirId: String = "", val order: Int = -1,   // 该方向的编号、本站是这个方向的第几站
    val weekdayBands: List<HeadwayBand> = emptyList(),
    val weekendBands: List<HeadwayBand> = emptyList(),
    val colorHex: String = "",
)

data class MetroStation(val name: String, val lat: Double, val lng: Double)
data class MetroRoute(val stations: List<MetroStation>, val durSec: Int) {
    val hops get() = stations.size - 1
}

/** 运行时导入结果，用于界面提示 */
data class ImportSummary(val lineCount: Int, val stationCount: Int)

/** 查地铁途经站 */
data class MetroQuery(
    val region: String, val lineName: String,
    val fromName: String, val fromLat: Double, val fromLng: Double,
    val toName: String, val toLat: Double, val toLng: Double,
    val dir: String = "",
)

/** 查某线路驶向等车站的来车 */
data class RealtimeQuery(
    val region: String, val lineId: String, val stationId: String, val stopIndex: Int,
    val lat: Double, val lng: Double,
)

// ---------- 数据源接口 ----------

interface StaticSource {
    val id: String
    val title: String
    fun search(key: String, region: String): SearchResult
    /** 全部可选线路（按名称归并双向），供「先选线路」的浏览界面使用 */
    fun lines(region: String): List<SearchLine> = emptyList()
    fun lineDetail(lineId: String, region: String): LineDetail
    fun nearbyStops(lat: Double, lng: Double, region: String): List<NearStop> = emptyList()
    fun stationLines(siteId: String, sameNameId: String, region: String): List<NearStop> = emptyList()
    fun subwayDirs(lat: Double, lng: Double, stationName: String, region: String): List<SubwayDir> = emptyList()
    fun metroStops(board: List<SubwayDir>, toLat: Double, toLng: Double, region: String): Int? = null
    fun metroRoute(q: MetroQuery): MetroRoute? = null
}

interface RealtimeSource {
    val id: String
    val title: String
    fun lineRealtime(q: RealtimeQuery): LineRealtime
}

/** 没有配置任何数据源时：什么都查不到，引擎按「无数据」降级 */
object NoSource : StaticSource {
    override val id = "none"
    override val title = "未配置"
    override fun search(key: String, region: String) = SearchResult(emptyList(), emptyList())
    override fun lineDetail(lineId: String, region: String): LineDetail = error("未配置数据源")
}

// ---------- 门面 ----------

object Transit {
    private const val PREFS = "transit"
    private const val KEY_REGION = "region"
    private const val KEY_AMAP_KEY = "amap_key"
    private const val KEY_AMAP_CITY = "amap_city"

    /** 可选扩展入口（src/extra 里的 object Extras { val sources: List<Any> }），没有就跳过 */
    private const val EXTRAS = "com.busnotice.commute.extra.Extras"

    @Volatile var static: StaticSource = NoSource; private set
    @Volatile var realtime: RealtimeSource? = null; private set
    @Volatile var region: String = ""; private set

    @Volatile private var inited = false

    /** 给数据源用的 Application Context（读 assets、存缓存等），init 之后可用 */
    lateinit var appContext: Context; private set

    fun init(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            appContext = ctx.applicationContext
            if (ImportedJsonSource.load(ctx)) {
                // 运行时导入的数据优先于 src/extra，避免导入后仍被扩展源覆盖
                static = ImportedJsonSource
            } else {
                installExtras()
            }
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var r = sp.getString(KEY_REGION, "").orEmpty()
            if (r.isEmpty()) {
                // 升级迁移：从已保存行程里取区域编号，用户不用重新设置
                r = runCatching {
                    TripStore.all(ctx).asSequence().flatMap { it.legs.asSequence() }
                        .flatMap { it.candidates.asSequence() }.map { it.regionId }.firstOrNull { it.isNotEmpty() }
                }.getOrNull().orEmpty()
                if (r.isNotEmpty()) sp.edit().putString(KEY_REGION, r).apply()
            }
            region = r
            clearColorCache()
            inited = true
        }
    }

    /** 导入数据自带区域编号时自动记录（不对外提供手动修改） */
    private fun setRegion(ctx: Context, r: String) {
        region = r.trim()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_REGION, region).apply()
    }

    /** 用户自己提供的高德 Web 服务 Key（只存本机，用于 App 内生成数据源） */
    fun amapKey(): String = appContextOrNull()
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ?.getString(KEY_AMAP_KEY, "").orEmpty()

    fun setAmapKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_AMAP_KEY, key.trim()).apply()
    }

    /** 高德查询用的城市名或 adcode，如「广州」「440100」 */
    fun amapCity(): String = appContextOrNull()
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ?.getString(KEY_AMAP_CITY, "").orEmpty()

    fun setAmapCity(ctx: Context, city: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_AMAP_CITY, city.trim()).apply()
    }

    private fun appContextOrNull(): Context? = if (::appContext.isInitialized) appContext else null

    /** 导入 JSON（与已有线路合并、同名覆盖）并立即切换为当前静态数据源；失败时抛异常，旧数据保持不变。 */
    fun importJson(ctx: Context, text: String): ImportSummary = synchronized(this) {
        val doc = ImportedJsonSource.importMerged(ctx, text)
        static = ImportedJsonSource
        realtime = null
        clearColorCache()
        MetroRoutes.clear(ctx)
        if (region.isEmpty() && doc.region.isNotEmpty()) setRegion(ctx, doc.region)
        ImportSummary(doc.lineCount, doc.stationCount)
    }

    /** 修改已导入线路的单站用时和始发站首末班；失败抛异常。 */
    fun updateImportedLine(
        ctx: Context,
        name: String,
        hopSec: Int,
        clocks: Map<String, Pair<Int, Int>>,
    ): ImportSummary = synchronized(this) {
        val doc = ImportedJsonSource.updateLine(ctx, name, hopSec, clocks)
        static = ImportedJsonSource
        clearColorCache()
        MetroRoutes.clear(ctx)
        ImportSummary(doc.lineCount, doc.stationCount)
    }

    /** 删除一条线路（两个方向）；删空后恢复扩展源/演示城市。 */
    fun deleteImportedLine(ctx: Context, name: String) = synchronized(this) {
        if (ImportedJsonSource.deleteLine(ctx, name)) {
            static = ImportedJsonSource
            clearColorCache()
            MetroRoutes.clear(ctx)
        } else {
            clearImported(ctx)
        }
        Unit
    }

    /** 删除导入的数据，恢复 src/extra 扩展源；没有扩展则回到演示城市。 */
    fun clearImported(ctx: Context) {
        synchronized(this) {
            ImportedJsonSource.clear(ctx)
            clearColorCache()
            MetroRoutes.clear(ctx)
            static = NoSource
            realtime = null
            installExtras()
        }
    }

    /** 加载 src/extra 里的静态/实时数据源；都没有时回退演示城市。 */
    private fun installExtras() {
        val extras = runCatching {
            val k = Class.forName(EXTRAS)
            k.getMethod("getSources").invoke(k.getField("INSTANCE").get(null)) as List<*>
        }.getOrNull().orEmpty()
        for (o in extras) {
            if (o is StaticSource && static === NoSource) static = o
            if (o is RealtimeSource && realtime == null) realtime = o
        }
        // 什么都没接：用内置的虚构「演示城市」，装上就能把流程走一遍
        if (static === NoSource) {
            static = MockSource
            if (realtime == null) realtime = MockSource
        }
    }

    val hasRealtime get() = realtime != null

    /** 线路名 → 数据源自带的线路色（高德 uicolor）。查不到缓存空串，避免反复全表扫描 */
    private val colorCache = HashMap<String, String>()

    fun colorOf(name: String): String {
        val key = name.trim()
        if (key.isEmpty()) return ""
        synchronized(colorCache) {
            colorCache[key]?.let { return it }
            val hex = runCatching {
                val hit = static.search(key, reg("")).lines.firstOrNull { it.name == key }
                    ?: return@runCatching ""
                hit.dirs.firstNotNullOfOrNull { dir -> static.lineDetail(dir.lineId, reg("")).colorHex.ifEmpty { null } }
                    .orEmpty()
            }.getOrDefault("")
            colorCache[key] = hex
            return hex
        }
    }

    private fun clearColorCache() = synchronized(colorCache) { colorCache.clear() }

    private fun reg(r: String) = r.ifEmpty { region }

    fun search(key: String, region: String = ""): SearchResult = static.search(key, reg(region))
    fun lines(region: String = ""): List<SearchLine> = static.lines(reg(region))
    fun lineDetail(lineId: String, region: String = ""): LineDetail = static.lineDetail(lineId, reg(region))
    fun nearbyStops(lat: Double, lng: Double, region: String = ""): List<NearStop> = static.nearbyStops(lat, lng, reg(region))
    fun stationLines(siteId: String, sameNameId: String, region: String = ""): List<NearStop> =
        static.stationLines(siteId, sameNameId, reg(region))
    fun subwayDirs(lat: Double, lng: Double, stationName: String, region: String = ""): List<SubwayDir> =
        static.subwayDirs(lat, lng, stationName, reg(region))
    fun metroStops(board: List<SubwayDir>, toLat: Double, toLng: Double, region: String = ""): Int? =
        static.metroStops(board, toLat, toLng, reg(region))

    fun metroRoute(c: Candidate, toName: String, toLat: Double, toLng: Double): MetroRoute? =
        static.metroRoute(
            MetroQuery(reg(c.regionId), c.name, c.stopName, c.lat, c.lng, toName, toLat, toLng, c.metroDir),
        )

    /** 没有实时数据源时返回空（= 「无车」），引擎退回时刻表 / 估算 */
    fun lineRealtime(c: Candidate, lat: Double, lng: Double): LineRealtime =
        realtime?.lineRealtime(RealtimeQuery(reg(c.regionId), c.lineId, c.stationId, c.stopIndex, lat, lng))
            ?: LineRealtime.EMPTY

    /** 线路名是否对得上：「4号线」「地铁4号线」都算 */
    fun sameLine(name: String, d: SubwayDir): Boolean {
        val n = name.replace("地铁", "").trim()
        return n.isNotEmpty() && (n == d.shortName || n == d.line || "地铁$n" == d.line)
    }
}
