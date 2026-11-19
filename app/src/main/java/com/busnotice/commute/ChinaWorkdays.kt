package com.busnotice.commute

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * 中国法定工作日：按年从 timor.tech 拉取，成功后才落盘；之后离线读缓存，不重复请求。
 * 没拿到某年数据时按周一至周五兜底，拉取成功后重排闹钟。
 */
object ChinaWorkdays {
    private const val BASE = "https://timor.tech/api/holiday/year/"
    private const val PREFS = "china_workdays"   // year_2026 -> 原始 JSON；try_2026 -> 上次尝试时间
    private const val RETRY_MS = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<Int, Year>()
    private val fetching = ConcurrentHashMap.newKeySet<Int>()
    private var app: Context? = null

    data class Year(val offDays: Set<LocalDate>, val workDays: Set<LocalDate>) {
        fun isWorkday(date: LocalDate): Boolean = when {
            date in workDays -> true
            date in offDays -> false
            else -> ChinaWorkdays.defaultIsWorkday(date)
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val now = LocalDate.now()
        ensure(now.year)
        if (now.monthValue == 12) ensure(now.year + 1) // 元旦前拿到明年安排
    }

    fun isWorkday(date: LocalDate): Boolean {
        val known = cachedYear(date.year)
        if (known != null) return known.isWorkday(date)
        ensure(date.year)
        return defaultIsWorkday(date)
    }

    /** 缺数据才拉取；失败后 6 小时内不重试，避免频繁请求 */
    fun ensure(vararg years: Int) {
        val ctx = app ?: return
        years.forEach { y ->
            if (cachedYear(y) != null) return@forEach
            if (!fetching.add(y)) return@forEach
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("try_$y", 0L) < RETRY_MS) {
                fetching.remove(y)
                return@forEach
            }
            prefs.edit().putLong("try_$y", now).apply()
            scope.launch {
                try {
                    val raw = RemoteTransit.fetch("$BASE$y/")
                    val parsed = parse(y, raw)
                    if (parsed == null) {
                        Diag.mark(ctx, "节假日数据格式异常（$y）")
                    } else {
                        prefs.edit().putString("year_$y", raw).apply()
                        cache[y] = parsed
                        Handler(Looper.getMainLooper()).post { Scheduler.rescheduleAll(ctx) }
                    }
                } catch (e: Exception) {
                    Diag.mark(ctx, "节假日获取失败（$y）：${e.message ?: e.javaClass.simpleName}")
                } finally {
                    fetching.remove(y)
                }
            }
        }
    }

    private fun cachedYear(y: Int): Year? {
        cache[y]?.let { return it }
        val ctx = app ?: return null
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("year_$y", null) ?: return null
        val parsed = parse(y, raw) ?: return null
        cache[y] = parsed
        return parsed
    }

    /** timor.tech：holiday 为 true 是放假，false 是调休上班 */
    internal fun parse(year: Int, raw: String): Year? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 0) return null
        val holiday = root.optJSONObject("holiday") ?: return null
        val off = mutableSetOf<LocalDate>()
        val work = mutableSetOf<LocalDate>()
        val keys = holiday.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = holiday.optJSONObject(key) ?: continue
            val text = item.optString("date").takeIf { it.length == 10 } ?: "$year-$key"
            val day = runCatching { LocalDate.parse(text) }.getOrNull() ?: continue
            if (day.year != year) continue
            if (item.optBoolean("holiday", false)) off += day else work += day
        }
        return if (off.isEmpty() && work.isEmpty()) null else Year(off, work)
    }

    internal fun defaultIsWorkday(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
}
