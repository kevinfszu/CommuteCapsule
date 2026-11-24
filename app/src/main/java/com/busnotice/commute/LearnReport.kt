package com.busnotice.commute

import android.content.Context
import java.time.LocalDate
import java.util.Locale

/**
 * 「算法学习」页面的只读快照：只读本机 SharedPreferences，不写任何数据，也不参与预测。
 */
object LearnReport {
    data class Line(
        val label: String,
        val value: String,
        val note: String,
        val ready: Boolean,
        /** 括号里的节点说明，界面上弱化显示 */
        val sub: String? = null,
        /** 地铁线路名，界面用线路特征色的线路牌展示 */
        val badgeNames: List<String> = emptyList(),
        /** 时长类数值的原始秒数；界面按页面级单位开关格式化，非时长项为 null */
        val sec: Int? = null,
    )
    data class TripBlock(
        val name: String,
        val totals: List<Line>,
        val habit: Line,
        val walks: List<Line>,
        val nodes: List<Line>,
        val extras: List<Line>,
    )
    data class StopPairLine(val from: String, val to: String, val sec: Int?, val count: Int)
    data class StopBlock(
        val lineName: String,
        val dir: String,
        /** 已有区间中位数之和；有缺样本的区间时为下限（界面显示「≥」） */
        val totalSec: Int?,
        /** 有区间还没有样本，总耗时只是下限 */
        val partial: Boolean,
        val perHopSec: Int?,
        val pairs: List<StopPairLine>,
    )
    data class MetroBlock(val stop: String, val line: String, val dir: String, val peak: Boolean, val offset: String)
    data class Report(val trips: List<TripBlock>, val stops: List<StopBlock>, val metros: List<MetroBlock>)

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

            val last = trip.legs.lastIndex
            fun boardName(i: Int) = "上车${i + 1}"
            fun alightName(i: Int) = "下车${i + 1}"
            fun legSuffix(i: Int) = if (trip.legs.size > 1) " · 第 ${i + 1} 段" else ""

            // 节间时长：按节点顺序；每段乘车合并进来，括号内是节点说明
            val nodes = mutableListOf<Line>()
            nodes += statLine("步行到站", Learn.nodeStat(ctx, Learn.Metric.APPROACH, "${trip.id}#0"), "出发→进站")
            trip.legs.indices.forEach { i ->
                if (i == 0) {
                    nodes += statLine("候车", Learn.nodeStat(ctx, Learn.Metric.WAIT, "${trip.id}#0"), "进站→${boardName(0)}")
                } else {
                    nodes += statLine("换乘", Learn.nodeStat(ctx, Learn.Metric.TRANSFER, "${trip.id}#$i"),
                        "${alightName(i - 1)}→${boardName(i)}")
                }
                val r = Learn.ride(ctx, trip.id, i, trip.legs[i])
                val rs = Learn.rideStat(ctx, trip.id, i)
                val note = buildString {
                    append(if (rs.values.isEmpty()) sourceText(r.source) else "已学到 · ${rs.values.size} 次样本")
                    if (r.source == Learn.Ride.Source.DEFAULT && trip.legs[i].isMetroOnly()) append(" · 可能按站数估算")
                }
                val metros = if (trip.legs[i].isMetroOnly()) {
                    trip.legs[i].candidates.filter { it.type == Candidate.Type.METRO }.map { it.name }.distinct()
                } else emptyList()
                nodes += Line(
                    if (metros.isEmpty()) trip.legs[i].title() else "乘地铁",
                    "${r.minutes} 分钟", note, true, "${boardName(i)}→${alightName(i)}",
                    badgeNames = metros, sec = r.minutes * 60,
                )
            }
            if (last >= 0) {
                nodes += statLine("出站", Learn.nodeStat(ctx, Learn.Metric.EXIT_WALK, "${trip.id}#$last"),
                    "${alightName(last)}→出站")
            }
            nodes += statLine("到达", Learn.nodeStat(ctx, Learn.Metric.LAST_WALK, trip.id), "出站→到达")

            // 非节间项目前单独放一个区块保留，后续可能删除
            val extras = mutableListOf<Line>()
            trip.legs.indices.forEach { i ->
                val suffix = legSuffix(i)
                extras += statLine("提醒→确认下车$suffix", Learn.nodeStat(ctx, Learn.Metric.REACT, "${trip.id}#$i"))
                val bc = Learn.boardClockStat(ctx, trip.id, i)
                val med = bc.median
                val clockLabel = "上车时刻（${boardName(i)}）"
                extras += if (med != null) {
                    Line(clockLabel, mm(med), "已学到 · ${bc.values.size} 次人工样本", true)
                } else {
                    Line(clockLabel, "样本不足", "${bc.values.size}/${bc.need} 次人工样本", false)
                }
                extras += statLine("行程段时长$suffix", Learn.nodeStat(ctx, Learn.Metric.LEG_SPAN, "${trip.id}#$i"))
                Learn.busSpeed(ctx, trip.id, i)?.let {
                    extras += Line("公交沿线速度$suffix", "${it.toInt()} 米/分钟", "指数平均", true)
                }
            }

            val totals = listOf(
                statLine("乘车", Learn.nodeStat(ctx, Learn.Metric.RIDE_SPAN, trip.id),
                    if (last >= 0) "上车1→下车${last + 1}" else "上车→下车"),
                statLine("站内", Learn.nodeStat(ctx, Learn.Metric.STATION_SPAN, trip.id), "进站→出站"),
                statLine("全程", Learn.nodeStat(ctx, Learn.Metric.TRIP, trip.id), "出发→到达"),
            )
            TripBlock(trip.name, totals, habit, walks, nodes, extras)
        }

        val stops = Learn.stopStats(ctx).map { s ->
            // 总耗时只累加已有样本的区间；缺区间时不猜，改为下限（界面加「≥」）
            val known = s.pairs.mapNotNull { it.median }
            StopBlock(
                lineName = linePart(s.lineKey),
                dir = dirPart(s.lineKey),
                totalSec = known.takeIf { it.isNotEmpty() }?.sum(),
                partial = known.size < s.pairs.size,
                perHopSec = s.perHop.median,
                pairs = s.pairs.map { p -> StopPairLine(p.from, p.to, p.median, p.count) },
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

        return Report(trips, stops, metros)
    }

    private fun walkLine(ctx: Context, trip: Trip, kind: String, label: String, fallback: String? = null): Line {
        val all = WalkLearn.samples(ctx, trip.id, kind)
        val valid = all.filter { !it.partial }.map { it.durSec }
        val med = if (valid.size >= 3) valid.sorted()[valid.size / 2] else null
        return if (med != null) {
            Line(label, dur(med), "${valid.size} 次 · 中位数", true, sec = med)
        } else {
            Line(label, "样本不足", "${valid.size}/3 次" + (fallback?.let { " · $it" } ?: ""), false)
        }
    }

    private fun statLine(label: String, st: Learn.Stat, sub: String? = null): Line {
        val med = st.median
        return if (med != null) {
            val spread = if (st.values.size >= 2) (st.values.max() - st.values.min()) / 2 else 0
            Line(
                label, dur(med),
                "已学到 · ${st.values.size} 次人工样本" + if (spread > 0) " · 波动 ±${dur(spread)}" else "",
                true, sub, sec = med,
            )
        } else {
            Line(label, "暂无样本", "跑一次这趟行程后生效", false, sub)
        }
    }

    private fun sourceText(s: Learn.Ride.Source): String = when (s) {
        Learn.Ride.Source.LEARNED -> "实测"
        Learn.Ride.Source.MANUAL -> "手填"
        Learn.Ride.Source.ESTIMATE -> "按站数估算"
        Learn.Ride.Source.DEFAULT -> "预设"
    }

    private fun linePart(key: String): String {
        val i = key.indexOf('|')
        return if (i <= 0) key else key.substring(0, i)
    }

    private fun dirPart(key: String): String {
        val i = key.indexOf('|')
        return if (i <= 0) "" else key.substring(i + 1)
    }

    private fun mm(min: Int): String = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)

    private fun dur(sec: Int): String = when {
        sec < 60 -> "$sec 秒"
        sec % 60 == 0 -> "${sec / 60} 分钟"
        else -> "${sec / 60} 分 ${sec % 60} 秒"
    }
}
