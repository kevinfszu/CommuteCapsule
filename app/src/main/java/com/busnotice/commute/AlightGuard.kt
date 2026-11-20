package com.busnotice.commute

/**
 * 「到站前提醒」的阶段判定（纯逻辑，方便单测）。
 * 换乘站与终点站共用同一套阶段，不因终点站而降低强度。
 */
object AlightGuard {

    enum class Stage { NONE, T2, T1, AT, OVERSHOOT }

    data class Input(
        val remaining: Int,        // 还有几站；<= 0 视为已到
        val atStation: Boolean,    // 定位/时间/播报确认已在目标站
        val etaKnown: Boolean,     // 到站时间不是纯兜底
        val now: Long,
        val eta: Long,
        val leadMs: Long,          // 个人反应时长（提前多久进入 T-1）
        val acked: Boolean,
        val overshoot: Boolean,
        val far: Boolean,          // 新鲜可靠定位显示离下车站还很远
    )

    fun stage(i: Input): Stage {
        if (i.acked) return Stage.NONE
        if (i.overshoot) return Stage.OVERSHOOT
        if (i.far) return Stage.NONE
        if (i.atStation) return Stage.AT
        if (i.etaKnown && i.now >= i.eta - i.leadMs) return Stage.T1
        if (i.remaining == 1) return Stage.T1
        if (i.remaining == 2) return Stage.T2
        return Stage.NONE
    }

    fun intervalMs(stage: Stage): Long = when (stage) {
        Stage.T1 -> 30_000L
        Stage.AT -> 15_000L
        Stage.OVERSHOOT -> 20_000L
        else -> 0L
    }

    /** 响铃次数上限；到站阶段最多 6 次，过站最多 3 次，之后只留通知 */
    fun maxRings(stage: Stage): Int = when (stage) {
        Stage.AT -> 6
        Stage.OVERSHOOT -> 3
        else -> 0
    }

    fun shouldRing(stage: Stage): Boolean = stage == Stage.AT || stage == Stage.OVERSHOOT

    /** 文案：换乘站与终点站完全一致 */
    fun title(stage: Stage, name: String): String = when (stage) {
        Stage.T2 -> "还有 2 站 · 准备下车"
        Stage.T1 -> "下一站下车「$name」"
        Stage.AT -> "现在下车「$name」"
        Stage.OVERSHOOT -> "「$name」已过站"
        Stage.NONE -> ""
    }

    fun text(stage: Stage): String = when (stage) {
        Stage.T2 -> "下一站就是「下车」站，提前收拾好"
        Stage.T1 -> "请准备下车"
        Stage.AT -> "请立即下车；点「我已下车」停止提醒"
        Stage.OVERSHOOT -> "请下车返回"
        Stage.NONE -> ""
    }
}
