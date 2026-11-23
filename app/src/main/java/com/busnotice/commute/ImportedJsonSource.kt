package com.busnotice.commute

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 用户在 App 内导入的 JSON 数据源。
 *
 * 文件保存在应用内部存储，启动时优先于 src/extra 扩展源；删除导入后回退到扩展源或演示城市。
 */
object ImportedJsonSource : StaticSource {
    private const val TAG = "ImportedJsonSource"
    private const val FILE = "imported_transit.json"

    @Volatile
    private var doc: JsonTransit.Doc? = null

    val hasData: Boolean get() = doc != null
    val lineCount: Int get() = doc?.lineCount ?: 0
    val stationCount: Int get() = doc?.stationCount ?: 0

    /** 全部方向记录，供线路导入列表和详情页读取 */
    val lines: List<JsonTransit.Line> get() = doc?.lines.orEmpty()

    /** 已导入的线路名（正反方向合并为一条） */
    val lineNames: List<String> get() = doc?.lines?.map { it.name }?.distinct() ?: emptyList()

    /** 已导入线路及站数（正反方向合并，取站数较多的方向） */
    val lineStations: List<Pair<String, Int>>
        get() = doc?.lines?.groupBy { it.name }?.map { (n, ls) -> n to (ls.maxOfOrNull { it.stops.size } ?: 0) } ?: emptyList()
    val meta: JsonTransit.Meta? get() = doc?.let { JsonTransit.Meta(it.source, it.updated, it.note) }

    override val id = "imported"
    override val title: String
        get() = doc?.let { "导入的 JSON（${it.lineCount} 条线路 / ${it.stationCount} 个站点）" } ?: "导入的 JSON"

    /** 启动时读取已导入的文件；文件不存在或损坏返回 false。 */
    fun load(ctx: Context): Boolean {
        synchronized(this) {
            val file = File(ctx.filesDir, FILE)
            if (!file.exists()) {
                doc = null
                return false
            }
            return runCatching { JsonTransit.parse(file.readText()) }
                .onSuccess { doc = it }
                .onFailure {
                    Log.w(TAG, "导入的数据文件无法解析", it)
                    doc = null
                }
                .isSuccess
        }
    }

    /** 先解析校验，成功后再落盘；解析失败不会覆盖旧数据。 */
    fun import(ctx: Context, text: String): JsonTransit.Doc = synchronized(this) {
        val parsed = JsonTransit.parse(text)
        val target = File(ctx.filesDir, FILE)
        val temp = File(ctx.filesDir, "$FILE.tmp")
        temp.writeText(text)
        if (target.exists() && !target.delete()) Log.w(TAG, "旧数据文件删除失败")
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
        doc = parsed
        parsed
    }

    /** 合并导入：同名线路（两个方向）覆盖，其余保留 */
    fun importMerged(ctx: Context, text: String): JsonTransit.Doc = synchronized(this) {
        import(ctx, JsonTransit.merge(rawText(ctx), text))
    }

    /** 修改同名线路的单站用时、各方向始发站首末班和分时段发车间隔 */
    fun updateLine(
        ctx: Context,
        name: String,
        hopSec: Int,
        clocks: Map<String, Pair<Int, Int>>,
        service: Pair<List<HeadwayBand>, List<HeadwayBand>>? = null,
    ): JsonTransit.Doc = synchronized(this) {
        val text = rawText(ctx) ?: throw IllegalStateException("尚未导入线路")
        import(ctx, JsonTransit.updateLineParams(text, name, hopSec, clocks, service))
    }

    /** 删除同名线路的全部方向记录；删完没有剩余线路时清空文件并返回 false */
    fun deleteLine(ctx: Context, name: String): Boolean = synchronized(this) {
        val text = rawText(ctx)
        when {
            text == null -> false
            else -> {
                val remaining = JsonTransit.deleteLine(text, name)
                if (remaining == null) {
                    clear(ctx)
                    false
                } else {
                    import(ctx, remaining)
                    true
                }
            }
        }
    }

    fun clear(ctx: Context) = synchronized(this) {
        doc = null
        File(ctx.filesDir, FILE).delete()
        File(ctx.filesDir, "$FILE.tmp").delete()
        Unit
    }

    /** 已落盘的原始 JSON（合并新线路时用）；没有导入过返回 null */
    fun rawText(ctx: Context): String? = runCatching {
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()
    }.getOrNull()

    override fun search(key: String, region: String): SearchResult =
        doc?.let { JsonTransit.search(it, key, region) } ?: SearchResult(emptyList(), emptyList())

    override fun lines(region: String): List<SearchLine> =
        doc?.let { JsonTransit.lines(it, region) } ?: emptyList()

    override fun lineDetail(lineId: String, region: String): LineDetail =
        doc?.let { JsonTransit.lineDetail(it, lineId, region) } ?: error("尚未导入线路数据")

    override fun nearbyStops(lat: Double, lng: Double, region: String): List<NearStop> =
        doc?.let { JsonTransit.nearbyStops(it, lat, lng, region) } ?: emptyList()

    override fun stationLines(siteId: String, sameNameId: String, region: String): List<NearStop> =
        doc?.let { JsonTransit.stationLines(it, siteId, sameNameId, region) } ?: emptyList()

    override fun subwayDirs(lat: Double, lng: Double, stationName: String, region: String): List<SubwayDir> =
        doc?.let { JsonTransit.subwayDirs(it, lat, lng, stationName, region) } ?: emptyList()

    override fun metroStops(board: List<SubwayDir>, toLat: Double, toLng: Double, region: String): Int? =
        doc?.let { JsonTransit.metroStops(it, board, toLat, toLng, region) }

    override fun metroRoute(q: MetroQuery): MetroRoute? =
        doc?.let { JsonTransit.metroRoute(it, q) }
}
