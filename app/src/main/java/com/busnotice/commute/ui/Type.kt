package com.busnotice.commute.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 全站唯一字阶来源：按「角色」而不是「尺寸」命名。
 *
 * 规则：
 * - 卡片/小节标题用 [Section]（15/600），颜色由调用处给 `Fuse.ink(dark).copy(alpha = 0.78f)`；
 * - 列表项主标题用 [ItemTitle]（17/600）；
 * - 正文 14、说明 13、注脚 12；
 * - 11sp 只允许出现在 LiveBoard 黑底看板；
 * - 所有数字用 [Metric] / [MetricLarge] / [MetricMini]，不要手写 fontSize + Bold + tnum。
 */
object Type {
    val Display = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
    val PageTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)

    /** 对话框标题：Material 默认 24sp 偏大，收一档到 20/600 */
    val DialogTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    val ItemTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val Section = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    val Body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
    val BodyDim = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp)

    /** 行内数值：与正文同级，靠等宽和右对齐区分，不再放大加粗（算法学习专用，替代 Metric） */
    val Value = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum", lineHeight = 20.sp,
    )

    /** 仅 LiveBoard 黑底看板使用，不参与全站字阶 */
    val Micro = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 14.sp)

    val Metric = TextStyle(
        fontSize = 16.sp, fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum", lineHeight = 20.sp,
    )
    val MetricLarge = TextStyle(
        fontSize = 26.sp, fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum", lineHeight = 30.sp,
    )
    val MetricMini = TextStyle(
        fontSize = 13.sp, fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum", lineHeight = 18.sp,
    )
}
