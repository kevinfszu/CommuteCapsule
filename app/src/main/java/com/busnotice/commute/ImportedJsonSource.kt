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
    val meta: JsonTransit.Meta? get() = doc?.let { JsonTransit.Meta(it.source, it.updated, it.note) }

    override val id = "imported"
    override val title: String
        get() = doc?.let { "导入的 JSON（${it.lineCount} 条线路 / ${it.stationCount} 站）" } ?: "导入的 JSON"

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

    fun clear(ctx: Context) = synchronized(this) {
        doc = null
        File(ctx.filesDir, FILE).delete()
        File(ctx.filesDir, "$FILE.tmp").delete()
        Unit
    }

    override fun search(key: String, region: String): SearchResult =
        doc?.let { JsonTransit.search(it, key, region) } ?: SearchResult(emptyList(), emptyList())

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
