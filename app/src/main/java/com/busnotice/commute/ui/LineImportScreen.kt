package com.busnotice.commute.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.ImportedJsonSource
import com.busnotice.commute.JsonTransit
import com.busnotice.commute.Transit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「线路导入」：查看已导入线路，导入 JSON 或用高德生成，也可清空全部 */
@Composable
fun LineImportScreen(
    onBack: () -> Unit,
    onOpenAmap: () -> Unit,
    onOpenLine: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    var version by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var lastImport by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val sourceTitle = remember(version) { Transit.static.title }
    val hasImport = remember(version) { ImportedJsonSource.hasData }
    val groups = remember(version) { ImportedJsonSource.lines.groupBy { it.name } }
    val meta = remember(version) { ImportedJsonSource.meta }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        lastImport = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ctx.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取文件")
                    Transit.importJson(ctx, text)
                }
            }
            importing = false
            result.onSuccess {
                version++
                lastImport = "已导入 ${it.lineCount} 条线路、${it.stationCount} 个站点（同名线路已覆盖）"
                confirmClear = false
                Toast.makeText(
                    ctx, "已导入 ${it.lineCount} 条线路、${it.stationCount} 个站点", Toast.LENGTH_LONG,
                ).show()
            }.onFailure {
                Toast.makeText(ctx, "导入失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    GlassPage(title = "线路导入", onBack = onBack) {
        lastImport?.let { msg ->
            item {
                GlassCard {
                    Text(msg, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Fuse.accent(dark))
                }
            }
        }
        item {
            GlassCard {
                SectionLabel("当前数据源")
                Text("线路和站点：$sourceTitle", fontSize = 14.sp, color = ink, modifier = Modifier.padding(top = 8.dp))
                val note = remember(version) { noteText(meta) }
                if (note.isNotEmpty()) {
                    Text(
                        note, fontSize = 12.sp, lineHeight = 17.sp, color = dim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        item {
            GlassCard(padding = 0.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    SectionLabel("已导入的线路（${groups.size} 条）")
                    if (!hasImport) {
                        Text(
                            "还没有导入线路。当前使用内置演示数据，用下面的方式添加。",
                            fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                if (hasImport) {
                    groups.entries.forEachIndexed { i, (name, dirs) ->
                        if (i > 0) {
                            HorizontalDivider(
                                Modifier.padding(start = 16.dp), thickness = 1.dp, color = Fuse.hairline(dark),
                            )
                        }
                        GlassRow(
                            headline = name,
                            supporting = lineSummary(dirs),
                            onClick = { onOpenLine(name) },
                        )
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionLabel("新增线路")
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PillButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !importing,
                        secondary = true,
                    ) { Text(if (importing) "导入中…" else "导入 JSON 文件") }
                    PillButton(
                        onClick = onOpenAmap,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !importing,
                        secondary = true,
                    ) { Text("用高德查询并生成") }
                }
            }
        }
        if (hasImport) {
            item {
                GlassCard {
                    SectionLabel("危险操作")
                    if (!confirmClear) {
                        DialogButton(
                            "清空全部线路", danger = true,
                            onClick = { confirmClear = true },
                        )
                    } else {
                        Text(
                            "清空后所有已导入线路都会移除，并恢复内置演示数据。",
                            fontSize = 12.sp, lineHeight = 17.sp, color = dim,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DialogButton("取消", onClick = { confirmClear = false })
                            DialogButton("确认清空", danger = true, onClick = {
                                Transit.clearImported(ctx)
                                version++
                                Toast.makeText(ctx, "已清空线路，恢复默认数据源", Toast.LENGTH_SHORT).show()
                                lastImport = null
                                confirmClear = false
                            })
                        }
                    }
                }
            }
        }
        item {
            Text(
                "JSON 格式见 docs/DATA_SOURCE.md。若行程进行中，请在导入后结束并重新开始。",
                fontSize = 12.sp, lineHeight = 17.sp, color = dim,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** 一条线路（两个方向）的摘要：站数、方向数、首末班范围、单站用时 */
private fun lineSummary(dirs: List<JsonTransit.Line>): String {
    val stations = dirs.maxOfOrNull { it.stops.size } ?: 0
    val first = dirs.minOfOrNull { it.firstMin } ?: 0
    val last = dirs.maxOfOrNull { it.lastMin } ?: 0
    val hop = dirs.firstOrNull()?.hopSec ?: 0
    return "$stations 站 · ${dirs.size} 个方向 · ${hhmm(first)}–${hhmm(last)} · 单站 $hop 秒"
}

/** 数据说明小字：来源、更新时间和备注合并成一行 */
private fun noteText(meta: JsonTransit.Meta?): String {
    if (meta == null) return ""
    val source = meta.source.trim()
    val updated = meta.updated.trim()
    val note = meta.note.trim()
    val head = when {
        source.isEmpty() -> ""
        updated.isEmpty() -> "数据：$source"
        else -> "数据：$source（$updated）"
    }
    return listOf(head, note).filter { it.isNotEmpty() }.joinToString(" · ")
}
