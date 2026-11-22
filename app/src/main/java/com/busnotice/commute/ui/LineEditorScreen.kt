package com.busnotice.commute.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.ImportedJsonSource
import com.busnotice.commute.JsonTransit
import com.busnotice.commute.Transit

/** 线路详情：查看站点，修改单站用时与各方向始发站首末班，或删除这条线路 */
@Composable
fun LineEditorScreen(lineName: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dirs = remember { ImportedJsonSource.lines.filter { it.name == lineName } }

    val initialHop = dirs.firstOrNull()?.hopSec ?: 140
    var hopMin by remember { mutableStateOf((initialHop / 60).toString()) }
    var hopSecText by remember { mutableStateOf((initialHop % 60).toString().padStart(2, '0')) }
    var clocks by remember {
        mutableStateOf(dirs.associate { it.id to (hhmm(it.firstMin) to hhmm(it.lastMin)) })
    }

    fun save() {
        error = null
        val total = (hopMin.toIntOrNull() ?: -1) * 60 + (hopSecText.toIntOrNull() ?: -1)
        if (total !in 20..900) {
            error = "单站用时请填 20–900 秒"
            return
        }
        val clockMin = HashMap<String, Pair<Int, Int>>()
        for ((id, v) in clocks) {
            val from = parseClock(v.first)
            val to = parseClock(v.second)
            if (from == null || to == null) {
                error = "首末班时间格式应为 HH:mm"
                return
            }
            clockMin[id] = from to to
        }
        runCatching { Transit.updateImportedLine(ctx, lineName, total, clockMin) }
            .onSuccess {
                Toast.makeText(ctx, "已保存「$lineName」", Toast.LENGTH_SHORT).show()
                onBack()
            }
            .onFailure { error = "保存失败：${it.message ?: "未知错误"}" }
    }

    fun delete() {
        runCatching { Transit.deleteImportedLine(ctx, lineName) }
            .onSuccess {
                Toast.makeText(ctx, "已删除「$lineName」", Toast.LENGTH_SHORT).show()
                onDeleted()
            }
            .onFailure { error = "删除失败：${it.message ?: "未知错误"}" }
    }

    val bottomBar: (@Composable () -> Unit)? = if (dirs.isEmpty()) null else {
        {
            PillButton(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("保存修改") }
        }
    }

    GlassPage(title = lineName, onBack = onBack, bottomBar = bottomBar) {
        error?.let { msg ->
            item {
                Text(
                    msg,
                    fontSize = 13.sp, lineHeight = 18.sp, color = Fuse.MetroRed,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Fuse.MetroRed.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        if (dirs.isEmpty()) {
            item {
                GlassCard {
                    Text("线路已不存在。", fontSize = 14.sp, color = ink)
                }
            }
            return@GlassPage
        }
        item {
            GlassCard {
                SectionLabel("线路信息")
                Text(
                    "${if (dirs.first().subway) "地铁" else "公交"} · ${dirs.size} 个方向 · " +
                        "${dirs.first().stops.size} 站",
                    fontSize = 14.sp, color = ink, modifier = Modifier.padding(top = 8.dp),
                )
                dirs.forEach { d ->
                    Text(
                        "${dirTitle(d)} · 首班 ${hhmm(d.firstMin)} · 末班 ${hhmm(d.lastMin)}",
                        fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                SectionLabel("站点（按「${dirTitle(dirs.first())}」方向）", Modifier.padding(top = 12.dp))
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    dirs.first().stops.forEachIndexed { i, stop ->
                        Text(
                            "${i + 1}. ${stop.name}",
                            fontSize = 14.sp, color = ink, modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionLabel("运行参数")
                Text(
                    "单站用时（影响中间站首末班与到站推算）",
                    fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlassField(
                        hopMin, { hopMin = it.filter { c -> c.isDigit() }.take(2) },
                        label = "分", modifier = Modifier.weight(1f),
                    )
                    GlassField(
                        hopSecText, { hopSecText = it.filter { c -> c.isDigit() }.take(2) },
                        label = "秒", modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "20–900 秒；计算时按 分 × 60 + 秒",
                    fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 4.dp),
                )

                SectionLabel("始发站首末班", Modifier.padding(top = 12.dp))
                dirs.forEach { d ->
                    val v = clocks[d.id] ?: (hhmm(d.firstMin) to hhmm(d.lastMin))
                    Text(
                        dirTitle(d),
                        fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlassField(
                            v.first, { t -> clocks = clocks + (d.id to (t to v.second)) },
                            label = "首班", modifier = Modifier.weight(1f), singleLine = true,
                        )
                        GlassField(
                            v.second, { t -> clocks = clocks + (d.id to (v.first to t)) },
                            label = "末班", modifier = Modifier.weight(1f), singleLine = true,
                        )
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionLabel("危险操作")
                if (!confirmDelete) {
                    DialogButton(
                        "删除这条线路", danger = true,
                        onClick = { confirmDelete = true },
                    )
                } else {
                    Text(
                        "删除后两个方向都会移除；若没有剩余线路，将恢复内置演示数据。",
                        fontSize = 12.sp, lineHeight = 17.sp, color = dim,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DialogButton("取消", onClick = { confirmDelete = false })
                        DialogButton("确认删除", danger = true, onClick = { delete() })
                    }
                }
            }
        }
        item {
            Text(
                "修改后正在进行的行程不会自动更新，请在保存后结束并重新开始。",
                fontSize = 12.sp, lineHeight = 17.sp, color = dim,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private fun dirTitle(line: JsonTransit.Line): String =
    if (line.loop) line.dirLabel else "开往 ${line.stops.last().name}"
