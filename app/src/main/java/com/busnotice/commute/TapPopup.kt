package com.busnotice.commute

/**
 * 从无障碍读到的屏幕文字里认「刚刚这一下刷卡」（纯逻辑，方便单测）。
 *
 * 屏幕文字是整屏拼起来的：拉下通知栏时，几分钟前的「消费成功」还挂着，别的通知（聊天、积分）又带着「刚刚」，
 * 只看「整段里有没有刚刚」会把旧扣费当成新刷卡。所以要看「这一条」刷卡记录自己的时间戳：
 *  - 单条弹窗：「消费成功 本次消费：2.00 元 刚刚」→ 时间戳紧跟在后面；
 *  - 通知栏分组：「2 刚刚 某某交通卡低额提醒 … 消费成功 本次消费：0.80 元」→ 时间戳在这一组前面，取它前面最近的一个；
 *  - 岭南通 / 羊城通：「07:15 行程中」「08:06 刷卡完成，交易金额：5.60元」→ 用记录里的钟点，和现在相差 10 分钟内算新的；
 *  - 最近的时间戳是「x 分钟前」/ 更早的钟点 → 旧记录，不算。
 */
object TapPopup {
    data class Hit(val kind: TransitSignal.Kind, val sig: String, val ctx: String, val at: Long? = null)

    private val TIME = Regex("刚刚|\\d+\\s*分钟前|\\d+\\s*小时前|昨天|(?<![\\d:])\\d{1,2}:\\d{2}(?![\\d:])")
    private val CLOCK = Regex("(?<![\\d:])(\\d{1,2}):(\\d{2})(?![\\d:])")
    private val EXIT = Regex("(?<!判断)已出站|出站成功|刷卡出站")
    private val ENTER = Regex("(?<!判断)已进站|进站成功|刷卡进站|行程中")
    private val SPEND = Regex("本次消费[：:]\\s*([\\d.]+)\\s*元")
    private val PAY = Regex("刷卡完成|交易金额[：:]\\s*([\\d.]+)\\s*元")
    private val SPEND_PLAIN = Regex("消费成功")
    private const val LOOK_BACK = 120
    private const val CLOCK_WINDOW_MS = 10 * 60_000L

    /**
     * [start, end) 这条记录是不是刚刚的。
     * 紧跟着的时间戳（4 个字以内）优先；没有就看前面 120 字内最近的时间戳；一个时间戳都没有返回 [noStamp]。
     */
    fun fresh(text: String, start: Int, end: Int, noStamp: Boolean): Boolean {
        val after = text.substring(end)
        val next = TIME.find(after)
        if (next != null && after.substring(0, next.range.first).isBlank() && next.range.first <= 4) return next.value == "刚刚"
        val prev = TIME.findAll(text.substring(maxOf(0, start - LOOK_BACK), start)).lastOrNull() ?: return noStamp
        return prev.value == "刚刚"
    }

    /**
     * 记录附近（前 120 字或紧跟后面 4 字）的钟点，换算成今天的时间戳。
     * 和 [now] 相差超过 10 分钟视为旧记录，返回 null。
     */
    fun clockAt(text: String, start: Int, end: Int, now: Long): Long? {
        val after = text.substring(end)
        val next = CLOCK.find(after)
        val before = CLOCK.findAll(text.substring(maxOf(0, start - LOOK_BACK), start)).lastOrNull()
        val m = when {
            next != null && after.substring(0, next.range.first).isBlank() && next.range.first <= 4 -> next
            before != null -> before
            else -> return null
        }
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, h)
            set(java.util.Calendar.MINUTE, min)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val at = cal.timeInMillis
        return if (kotlin.math.abs(now - at) <= CLOCK_WINDOW_MS) at else null
    }

    private fun ctx(text: String, r: IntRange): String =
        text.substring(maxOf(0, r.first - 40), minOf(text.length, r.last + 41))

    /** 认出来的刷卡；旧记录 / 没有刷卡字样返回 null */
    fun read(text: String, now: Long = System.currentTimeMillis()): Hit? {
        // 进站 / 出站弹窗不一定带时间戳：没有时间戳当作新的，带「x 分钟前」或早于 10 分钟的钟点才是旧的
        EXIT.find(text)?.let { m ->
            val at = clockAt(text, m.range.first, m.range.last + 1, now)
            if (at != null) return Hit(TransitSignal.Kind.EXIT, "EXIT:${clockSig(at)}", ctx(text, m.range), at)
            return if (fresh(text, m.range.first, m.range.last + 1, noStamp = true)) Hit(TransitSignal.Kind.EXIT, "EXIT", ctx(text, m.range)) else null
        }
        ENTER.find(text)?.let { m ->
            val at = clockAt(text, m.range.first, m.range.last + 1, now)
            if (at != null) return Hit(TransitSignal.Kind.ENTER, "ENTER:${clockSig(at)}", ctx(text, m.range), at)
            return if (fresh(text, m.range.first, m.range.last + 1, noStamp = true)) Hit(TransitSignal.Kind.ENTER, "ENTER", ctx(text, m.range)) else null
        }
        // 岭南通 / 羊城通：刷卡完成，交易金额：5.60元（可能是地铁出站，也可能是公交上车，由当前段解释）
        PAY.findAll(text).firstOrNull { m ->
            val at = clockAt(text, m.range.first, m.range.last + 1, now)
            at != null || fresh(text, m.range.first, m.range.last + 1, noStamp = false)
        }?.let { m ->
            val at = clockAt(text, m.range.first, m.range.last + 1, now)
            val amount = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: amountAfterPay(text, m.range)
            val sig = "TAP:" + (amount.ifEmpty { "?" }) + (at?.let { ":" + clockSig(it) } ?: "")
            return Hit(TransitSignal.Kind.TAP, sig, ctx(text, m.range), at)
        }
        // 扣费记录必须明确是「刚刚」，或者带 10 分钟内的钟点
        SPEND.findAll(text).firstOrNull { fresh(text, it.range.first, it.range.last + 1, noStamp = false) }?.let { m ->
            return Hit(TransitSignal.Kind.TAP, "TAP:" + m.groupValues[1], ctx(text, m.range))
        }
        if (SPEND.containsMatchIn(text)) return null
        SPEND_PLAIN.findAll(text).firstOrNull { fresh(text, it.range.first, it.range.last + 1, noStamp = false) }?.let { m ->
            return Hit(TransitSignal.Kind.TAP, "TAP", ctx(text, m.range))
        }
        return null
    }

    private fun amountAfterPay(text: String, r: IntRange): String =
        Regex("[\\d.]+\\s*元").find(text.substring(r.last + 1).take(24))?.value?.replace("元", "")?.trim() ?: ""

    private fun clockSig(at: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(at))

    private val AGO = Regex("^\\s*(\\d+)\\s*分钟前")

    /**
     * 当时没读到、几分钟后才在通知栏看到的刷卡（「已出站 3 分钟前」「本次消费：3.60 元 3 分钟前」）：
     * 返回过了几分钟（1–5）；没有或更旧返回 null。只看紧跟着的时间戳。
     */
    fun staleMinutes(text: String): Int? {
        for (re in listOf(EXIT, SPEND)) for (m in re.findAll(text)) {
            val a = AGO.find(text.substring(m.range.last + 1).take(12)) ?: continue
            val n = a.groupValues[1].toIntOrNull() ?: continue
            if (n in 1..5) return n
        }
        return null
    }

    /** 屏幕上有刷卡字样（不管新旧）：认不出来时记一笔，方便以后查 */
    fun mentions(text: String): Boolean =
        EXIT.containsMatchIn(text) || ENTER.containsMatchIn(text) || SPEND_PLAIN.containsMatchIn(text) ||
            SPEND.containsMatchIn(text) || PAY.containsMatchIn(text)

    fun firstMention(text: String): String {
        val m = listOf(EXIT, ENTER, SPEND, PAY, SPEND_PLAIN).mapNotNull { it.find(text) }.minByOrNull { it.range.first } ?: return ""
        return ctx(text, m.range)
    }
}
