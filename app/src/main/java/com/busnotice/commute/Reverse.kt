package com.busnotice.commute

/** 根据去程生成回程：段顺序倒过来，每路公交换成反方向、在原下车站上车 */
object Reverse {
    data class Result(val trip: Trip, val warnings: List<String>)

    /** 网络请求，需在 IO 线程调用 */
    fun build(src: Trip): Result {
        val warnings = mutableListOf<String>()
        val n = src.legs.size
        val legs = src.legs.reversed().mapIndexed { j, leg ->
            val cands = leg.candidates.mapNotNull { c -> reverseCandidate(c, warnings) }
            val walk = if (j == 0) leg.transferWalkMin else src.legs[n - j].transferWalkMin
            Leg(cands, rideMin = leg.rideMin, rideMinManual = leg.rideMinManual, transferWalkMin = walk)
        }.filter { it.candidates.isNotEmpty() }
        val trip = Trip(
            name = if (src.name.contains("上班")) "下班" else src.name + "·回程",
            legs = legs, dayMask = src.dayMask, chinaWorkday = src.chinaWorkday,
            startMinute = 18 * 60, endMinute = 18 * 60 + 30, warmupMin = src.warmupMin,
        )
        return Result(trip, warnings)
    }

    private fun reverseCandidate(c: Candidate, warnings: MutableList<String>): Candidate? = when (c.type) {
        Candidate.Type.SHUTTLE -> { warnings += "${c.label()} 为自定义通勤班车，回程请手动添加"; null }
        Candidate.Type.METRO -> {
            if (c.alightName.isEmpty()) { warnings += "${c.label()} 未填写下车站，回程请手动添加"; null }
            else runCatching {
                val st = Transit.search(c.alightName).stations
                    .firstOrNull { it.isSubway && it.name == c.alightName }
                    ?: Transit.search(c.alightName).stations.first { it.isSubway }
                // 反方向：优先找「开往原上车站」的方向；环线（内环/外环）没有同名终点，就取另一个方向
                val dirs = runCatching { Transit.subwayDirs(st.lat, st.lng, st.name, c.regionId) }
                    .getOrNull().orEmpty().filter { Transit.sameLine(c.name, it) }
                val rev = if (c.metroDir.isEmpty()) null
                else dirs.firstOrNull { it.dest == c.stopName }
                    ?: dirs.firstOrNull { it.dest != c.metroDir }
                val wdBands = rev?.weekdayBands?.takeIf { it.isNotEmpty() }
                val weBands = rev?.weekendBands?.takeIf { it.isNotEmpty() }
                // 拿不到方向数据时，环线按内环⇄外环直接翻转
                val revDest = rev?.dest ?: when {
                    c.metroDir.contains("内环") -> c.metroDir.replace("内环", "外环")
                    c.metroDir.contains("外环") -> c.metroDir.replace("外环", "内环")
                    else -> c.metroDir
                }
                c.copy(
                    stopName = st.name, lat = st.lat, lng = st.lng,
                    alightName = c.stopName, alightLat = c.lat, alightLng = c.lng,
                    metroDir = revDest,
                    weekdayBands = wdBands ?: c.weekdayBands,
                    weekendBands = weBands ?: c.weekendBands,
                )
            }.getOrElse { warnings += "未找到地铁站「${c.alightName}」"; null }
        }
        Candidate.Type.BUS -> {
            if (c.alightName.isEmpty()) { warnings += "${c.label()} 未选择下车站，回程请手动添加"; null }
            else runCatching {
                val fwd = Transit.lineDetail(c.lineId, c.regionId)
                val revId = fwd.reverseLineId.ifEmpty {
                    Transit.search(c.name, c.regionId).lines.firstOrNull { it.name == c.name }
                        ?.dirs?.firstOrNull { it.lineId != c.lineId }?.lineId ?: ""
                }
                require(revId.isNotEmpty())
                val rev = Transit.lineDetail(revId, c.regionId)
                val st = rev.stations.first { it.name == c.alightName }
                c.copy(
                    stopName = st.name, lat = st.lat, lng = st.lng, lineId = revId, stationId = st.stopId,
                    lastStop = rev.lastStop, stopIndex = st.order, alightName = c.stopName,
                )
            }.getOrElse { warnings += "${c.label()} 反向未找到「${c.alightName}」站"; null }
        }
    }
}
