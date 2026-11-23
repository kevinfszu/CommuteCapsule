package com.busnotice.commute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.HeadwayBand

/** 分时段发车间隔编辑器：每段可改起止时间与间隔，可增删；供导入页与线路详情共用 */
@Composable
fun HeadwayBandsEditor(
    bands: List<HeadwayBand>,
    onChange: (List<HeadwayBand>) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)

    fun update(index: Int, band: HeadwayBand) {
        onChange(bands.toMutableList().also { it[index] = band })
    }

    Column(modifier.fillMaxWidth()) {
        bands.forEachIndexed { index, band ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = if (dark) 0.06f else 0.38f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactTimeField(
                        minute = band.from, enabled = enabled, modifier = Modifier.weight(1f),
                        onCommit = { update(index, band.copy(from = it)) },
                    )
                    Text("–", fontSize = 14.sp, color = dim, modifier = Modifier.padding(horizontal = 8.dp))
                    CompactTimeField(
                        minute = band.to, enabled = enabled, modifier = Modifier.weight(1f),
                        onCommit = { update(index, band.copy(to = it)) },
                    )
                    if (enabled) {
                        Spacer(Modifier.width(8.dp))
                        RoundButton(
                            onClick = { onChange(bands.filterIndexed { i, _ -> i != index }) },
                            dark = dark, size = 30.dp,
                        ) { Text("×", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = dim) }
                    }
                }
                if (enabled) {
                    HeadwayStepper("间隔", band.headwaySec) { sec -> update(index, band.copy(headwaySec = sec)) }
                } else {
                    Text(
                        "${band.headwaySec / 60} 分 ${band.headwaySec % 60} 秒",
                        fontSize = 14.sp, color = ink, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        val canAdd = bands.isEmpty() || (bands.lastOrNull()?.to ?: 0) < 1440
        if (enabled && canAdd) {
            Spacer(Modifier.height(10.dp))
            PillButton(
                onClick = { onChange(bands + nextBand(bands)) },
                modifier = Modifier.fillMaxWidth(),
                secondary = true,
            ) { Text("＋ 添加时段", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** 新时段接在最后一段之后；空表给 06:00–24:00 */
private fun nextBand(bands: List<HeadwayBand>): HeadwayBand {
    val last = bands.lastOrNull() ?: return HeadwayBand(6 * 60, 1440, 360)
    return HeadwayBand(last.to, (last.to + 60).coerceAtMost(1440), last.headwaySec)
}

/**
 * 校验并整理分时段表：按开始时间排序，检查起止与间隔，拒绝重叠。
 * 返回 null 表示有错误，错误文案在 second；空表返回空表 + 无错误。
 */
fun checkBands(bands: List<HeadwayBand>): Pair<List<HeadwayBand>?, String?> {
    if (bands.isEmpty()) return emptyList<HeadwayBand>() to null
    val sorted = bands.sortedBy { it.from }
    for (b in sorted) {
        if (b.to <= b.from) return null to "结束时间需晚于开始时间"
        if (b.headwaySec !in 30..1800) return null to "发车间隔需在 30 秒到 30 分钟之间"
    }
    for (i in 1 until sorted.size) {
        if (sorted[i].from < sorted[i - 1].to) return null to "时段重叠：请调整到上一段结束之后"
    }
    return sorted to null
}

/** 分钟 → HH:mm；1440 显示 24:00，方便时段跨到末班 */
internal fun bandClockText(minute: Int): String {
    val m = if (minute == 1440) 1440 else ((minute % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

private val BAND_CLOCK = Regex("^(\\d{1,2}):(\\d{2})$")

private fun parseBandClock(raw: String): Int? {
    val m = BAND_CLOCK.matchEntire(raw.trim()) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val mm = m.groupValues[2].toIntOrNull() ?: return null
    if (mm !in 0..59) return null
    if (h == 24 && mm != 0) return null
    if (h !in 0..24) return null
    return h * 60 + mm
}

/** 紧凑时间输入：只允许数字和冒号，失焦或回车时提交，非法输入回退原值 */
@Composable
private fun CompactTimeField(
    minute: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onCommit: (Int) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    var text by remember(minute) { mutableStateOf(bandClockText(minute)) }
    val focus = LocalFocusManager.current

    fun commit() {
        val parsed = parseBandClock(text)
        if (parsed == null) text = bandClockText(minute) else onCommit(parsed)
    }

    BasicTextField(
        value = text,
        onValueChange = { raw -> text = raw.filter { it.isDigit() || it == ':' }.take(5) },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink,
            textAlign = TextAlign.Center, fontFeatureSettings = "tnum",
        ),
        cursorBrush = SolidColor(Fuse.accent(dark)),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focus.clearFocus()
        }),
        modifier = modifier.height(38.dp).clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = if (dark) 0.10f else 0.55f))
            .onFocusChanged { if (!it.isFocused) commit() }
            .padding(horizontal = 4.dp, vertical = 9.dp),
        decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { inner() } },
    )
}
