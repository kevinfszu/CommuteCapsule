package com.busnotice.commute

import java.time.Instant
import java.time.ZoneId

/**
 * 上车时刻推算。
 *
 * 已知：enter = 刷卡过闸时刻，w = 闸机→站台步行，h = 发车间隔（当前时段），
 * 相位 = 首班时刻。board 定义为「那趟车在本站的发车时刻」。
 *
 * 有相位：board = min{ t_k + offset | t_k + offset ≥ enter + w + close }
 *         t_k = 首班 + Σh（分时段表往后排）
 * 无相位：board = enter + w + h/2（到达相对班次均匀分布）
 *
 * 学到的实测等待（board − enter）按逆方差加权修正模型值：
 *   σ_model = h/√12（无相位，均匀分布）；有相位取 20 秒
 *   σ_obs   = s/√n
 *   wait = (wait_model/σ_model² + mean/σ_obs²) / (1/σ_model² + 1/σ_obs²)
 *
 * 样本只有 1–2 个时 σ_obs 取 1.5σ_model 兜底，权重自然很低，避免一次巧合把估计带偏。
 */
object BoardEst {
    /** 闸机 → 站台的典型步行秒数（不含安检，安检在闸机之前） */
    const val WALK_SEC = 60

    /** 车门关闭提前量：离发车不足这么多秒就赶不到站台 */
    const val CLOSE_SEC = 12

    /** 模糊带：剩余时间落在 close..close+BLUR 之间时，不强行判定赶上/错过 */
    const val BLUR_SEC = 30

    private const val PHASE_SIGMA_SEC = 20.0
    private const val SQRT12 = 3.4641016151377544

    data class Input(
        val enter: Long,
        val headwaySec: Int,
        val walkSec: Int = WALK_SEC,
        val firstMin: Int = -1,             // 一天中的分钟；<0 表示没有相位
        val lastMin: Int = -1,
        val offsetSec: Int = 0,             // 学到的相位偏差：实际 − 推算
        val waitSamples: List<Int> = emptyList(),   // 实测 board − enter（秒）
        val weekday: Boolean = true,
        val bands: List<HeadwayBand> = emptyList(),
    )

    data class Result(
        val at: Long,        // 估计发车时刻
        val lo: Long,        // 最早可能
        val hi: Long,        // 最晚可能
        val phased: Boolean, // 是否用上了相位
        val sure: Boolean,   // 是否确定赶上某一班（false = 模糊或没有相位）
    )

    fun estimate(i: Input): Result {
        val h = i.headwaySec.coerceIn(30, 1800)
        val w = i.walkSec.coerceIn(10, 600)
        val phased = byPhase(i, h, w) ?: freeRun(i, h, w)
        return blend(phased, i, h)
    }

    /** 转成「enter 之后多少秒」，给 NodeLog.Anchors 用 */
    fun offsetSec(r: Result, enter: Long): Int =
        ((r.at - enter) / 1000).toInt().coerceIn(0, 3600)

    /** 从实测等待反解闸机→站台步行：w = median(wait) − h/2 */
    fun walkFromWait(waitSec: List<Int>, headwaySec: Int): Int? {
        if (waitSec.isEmpty()) return null
        val med = waitSec.sorted()[waitSec.size / 2]
        return (med - headwaySec / 2).coerceIn(10, 600)
    }

    private fun byPhase(i: Input, h: Int, w: Int): Result? {
        if (i.firstMin < 0) return null
        val nowSec = Instant.ofEpochMilli(i.enter).atZone(ZoneId.systemDefault()).toLocalTime().toSecondOfDay()
        val first = i.firstMin * 60
        val last = when {
            i.lastMin < 0 -> 24 * 3600 - 60
            i.lastMin < i.firstMin -> i.lastMin * 60 + 86400
            else -> i.lastMin * 60
        }
        val bands = i.bands.ifEmpty { listOf(HeadwayBand(0, 1440, h)) }
        // 需要的「推算发车秒」：留出走站台和关门的时间
        val need = nowSec + w + CLOSE_SEC - i.offsetSec
        var dep = first
        var guard = 0
        while (dep < need && guard < 2000) {
            dep += Decision.headwayAt((dep / 60) % 1440, i.weekday, bands).coerceAtLeast(30)
            guard++
        }
        if (dep > last) return null          // 末班已过，按无相位处理

        val real = dep + i.offsetSec
        val gap = real - (nowSec + w)        // 到站台时离发车还剩多少秒
        val sure = gap > CLOSE_SEC + BLUR_SEC
        val at = i.enter + (real - nowSec) * 1000L
        return Result(
            at = at,
            lo = at,
            hi = if (sure) at else at + h * 1000L,
            phased = true,
            sure = sure,
        )
    }

    private fun freeRun(i: Input, h: Int, w: Int): Result {
        val platform = i.enter + w * 1000L
        return Result(
            at = platform + h * 500L,
            lo = platform,
            hi = platform + h * 1500L,
            phased = false,
            sure = false,
        )
    }

    private fun blend(r: Result, i: Input, h: Int): Result {
        val s = i.waitSamples.filter { it > 0 }
        if (s.isEmpty()) return r

        val model = ((r.at - i.enter) / 1000).toDouble()
        val mean = s.average()
        val n = s.size
        val sd = if (n >= 2) {
            kotlin.math.sqrt(s.sumOf { (it - mean) * (it - mean) } / (n - 1))
        } else 0.0

        val sigmaModel = if (r.phased) PHASE_SIGMA_SEC else h / SQRT12
        val sigmaObs = if (n >= 2) maxOf(sd / kotlin.math.sqrt(n.toDouble()), 10.0) else sigmaModel * 1.5
        val w1 = 1.0 / (sigmaModel * sigmaModel)
        val w2 = 1.0 / (sigmaObs * sigmaObs)
        val wait = (model * w1 + mean * w2) / (w1 + w2)
        val at = i.enter + (wait * 1000).toLong()

        val half = if (n >= 3) maxOf((sd * 1000).toLong(), 15_000L) else 45_000L
        return r.copy(at = at, lo = at - half, hi = at + half)
    }
}
