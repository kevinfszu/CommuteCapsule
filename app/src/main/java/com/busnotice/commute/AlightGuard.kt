package com.busnotice.commute

/**
 * 「到站提醒」的阶段判定（纯逻辑，方便单测）。
 * 只在过完最后一个中间站（只剩本段下车站）之后才提醒；换乘站与终点站共用同一套阶段，不因终点站而降低强度。
 */
object AlightGuard {

    enum class Stage { NONE, T1, AT, OVERSHOOT }

    data class Input(
        val remaining: Int,        // 还有几站；0..1 表示下一站就是下车站，-1 表示无站点数据
        val atStation: Boolean,    // 定位/时间/播报确认已在目标站
        val acked: Boolean,
        val overshoot: Boolean,
        val far: Boolean,          // 新鲜可靠定位显示离下车站还很远
    )

    fun stage(i: Input): Stage {
        if (i.acked) return Stage.NONE
        if (i.overshoot) return Stage.OVERSHOOT
        if (i.far) return Stage.NONE
        if (i.atStation) return Stage.AT
        if (i.remaining in 0..1) return Stage.T1
        return Stage.NONE
    }

    fun intervalMs(stage: Stage): Long = when (stage) {
        Stage.T1 -> 20_000L
        Stage.AT -> 15_000L
        Stage.OVERSHOOT -> 20_000L
        else -> 0L
    }

    /** 响铃次数上限；T1 / 到站最多 8 次，过站最多 3 次，之后只留通知 */
    fun maxRings(stage: Stage): Int = when (stage) {
        Stage.T1 -> 8
        Stage.AT -> 8
        Stage.OVERSHOOT -> 3
        else -> 0
    }

    fun shouldRing(stage: Stage): Boolean = stage != Stage.NONE

    /** 文案：换乘站与终点站完全一致 */
    fun title(stage: Stage, name: String): String = when (stage) {
        Stage.T1 -> "下一站下车「$name」"
        Stage.AT -> "现在下车「$name」"
        Stage.OVERSHOOT -> "「$name」已过站"
        Stage.NONE -> ""
    }

    fun text(stage: Stage): String = when (stage) {
        Stage.T1 -> "请准备下车"
        Stage.AT -> "请立即下车"
        Stage.OVERSHOOT -> "请下车返回"
        Stage.NONE -> ""
    }
}
