package com.busnotice.commute

import android.content.Context
import java.time.LocalDate
import java.util.Locale

/**
 * 「算法学习」页面的只读快照：只读本机 SharedPreferences，不写任何数据，也不参与预测。
 */
object LearnReport {
    data class Line(val label: String, val value: String, val note: String, val ready: Boolean)
    data class LegLine(val index: Int, val title: String, val ride: String, val source: String, val samples: String)
    data class TripBlock(val name: String, val habit: Line, val walks: List<Line>, val legs: List<LegLine>, val nodes: List<Line>)
    data class StopBlock(val line: String, val perHop: String?, val pairs: List<Pair<String, String>>)
    data class MetroBlock(val stop: String, val line: String, val dir: String, val peak: Boolean, val offset: String)
    data class PlaceBlock(val name: String, val progress: String, val confirmed: Boolean)
    data class Report(val trips: List<TripBlock>, val stops: List<StopBlock>, val metros: List<MetroBlock>, val places: List<PlaceBlock>)

    fun build(ctx: Context): Report {
        val dow = LocalDate.now().dayOfWeek.value
        val trips = TripStore.all(ctx).map { trip ->
            val hs = Learn.habitStat(ctx, trip.id, dow)
            val typical = hs.typical
            val habit = if (typical != null) {
                Line("习惯上车时刻", mm(typical), "${hs.days} 天样本 · 今天按这个判断", true)
            } else {
                Line("习惯上车时刻", "样本不足", "${hs.days} 天样本 · 满 ${hs.needDays} 天后生效", false)
            }

            val walks = listOf(
                walkLine(ctx, trip, "first", "首段步行"),
                walkLine(ctx, trip, "last", "末段步行", "当前兜底 8 分钟"),
            )

            val legs = trip.legs.mapIndexed { i, leg ->
                val r = Learn.ride(ctx, trip.id, i, leg)
                val st = Learn.rideStat(ctx, trip.id, i)
                val note = buildString {
                    append(if (st.values.isEmpty()) "暂无实测" else "${st.values.size} 次实测")
                    if (r.source == Learn.Ride.Source.DEFAULT && leg.isMetroOnly()) append(" · 运行时可能按站数估算")
                }
                LegLine(i, leg.title().ifEmpty { "未设置" }, "${r.minutes} 分钟", sourceText(r.source), note)
            }

            val nodes = mutableListOf<Line>()
            trip.legs.indices.forEach { i ->
                val suffix = if (trip.legs.size > 1) " ${i + 1}" else ""
                nodes += statLine("进站→上车$suffix", Learn.nodeStat(ctx, Learn.Metric.WAIT, "${trip.id}#$i"))
                nodes += statLine("下车→出站$suffix", Learn.nodeStat(ctx, Learn.Metric.EXIT_WALK, "${trip.id}#$i"))
                nodes += statLine("提醒→确认下车$suffix", Learn.nodeStat(ctx, Learn.Metric.REACT, "${trip.id}#$i"))
                if (i > 0) nodes += statLine("换乘步行$suffix", Learn.nodeStat(ctx, Learn.Metric.TRANSFER, "${trip.id}#$i"))
                Learn.busSpeed(ctx, trip.id, i)?.let {
                    nodes += Line("公交沿线速度$suffix", "${it.toInt()} 米/分钟", "指数平均", true)
                }
            }
            nodes += statLine("下车→到达", Learn.nodeStat(ctx, Learn.Metric.LAST_WALK, trip.id))

            TripBlock(trip.name, habit, walks, legs, nodes)
        }

        val stops = Learn.stopStats(ctx).map { s ->
            StopBlock(
                line = lineName(s.lineKey),
                perHop = s.perHop.median?.let { "$it 秒/站" },
                pairs = s.pairs.map { (k, v) -> k to (v.median?.let { "$it 秒" } ?: "样本不足") }.take(8),
            )
        }

        val metros = Learn.metroStats(ctx).map { m ->
            MetroBlock(
                stop = m.stop, line = m.line, dir = m.dir, peak = m.peak,
                offset = when {
                    m.sec == 0 -> "基本准时"
                    m.sec > 0 -> "实际约晚 ${m.sec} 秒"
                    else -> "实际约早 ${-m.sec} 秒"
                },
            )
        }

        val places = listOf(
            place(ctx, Places.Kind.HOME, "家"),
            place(ctx, Places.Kind.WORK, "公司"),
        )

        return Report(trips, stops, metros, places)
    }

    private fun walkLine(ctx: Context, trip: Trip, kind: String, label: String, fallback: String? = null): Line {
        val all = WalkLearn.samples(ctx, trip.id, kind)
        val valid = all.filter { !it.partial }.map { it.durSec }
        val med = if (valid.size >= 3) valid.sorted()[valid.size / 2] else null
        return if (med != null) {
            Line(label, dur(med), "${valid.size} 次 · 中位数", true)
        } else {
            Line(label, "样本不足", "${valid.size}/3 次" + (fallback?.let { " · $it" } ?: ""), false)
        }
    }

    private fun statLine(label: String, st: Learn.Stat): Line {
        val med = st.median
        return if (med != null) {
            Line(label, dur(med), "${st.values.size} 次 · 中位数", true)
        } else {
            Line(label, "样本不足", "${st.values.size}/${st.need} 次", false)
        }
    }

    private fun place(ctx: Context, kind: Places.Kind, name: String): PlaceBlock {
        val p = Places.progressOf(ctx, kind)
        return PlaceBlock(name, "$p/3", p >= 3)
    }

    private fun sourceText(s: Learn.Ride.Source): String = when (s) {
        Learn.Ride.Source.LEARNED -> "实测"
        Learn.Ride.Source.MANUAL -> "手填"
        Learn.Ride.Source.ESTIMATE -> "按站数估算"
        Learn.Ride.Source.DEFAULT -> "预设"
    }

    private fun lineName(key: String): String {
        val i = key.indexOf('|')
        return if (i <= 0) key else "${key.substring(0, i)} · ${key.substring(i + 1)}"
    }

    private fun mm(min: Int): String = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)

    private fun dur(sec: Int): String = when {
        sec < 60 -> "$sec 秒"
        sec % 60 == 0 -> "${sec / 60} 分钟"
        else -> "${sec / 60} 分 ${sec % 60} 秒"
    }
}
