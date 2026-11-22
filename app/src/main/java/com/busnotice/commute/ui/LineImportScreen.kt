@file:OptIn(ExperimentalMaterial3Api::class)

package com.busnotice.commute.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
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

    GlassPage(
        title = "线路导入",
        onBack = onBack,
        actions = {
            // ＋ 新增：两种导入方式收进菜单
            Box {
                RoundButton(
                    onClick = { addMenu = true }, dark = dark,
                    modifier = Modifier.semantics { contentDescription = "新增线路" },
                ) { Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink) }
                DropdownMenu(
                    expanded = addMenu, onDismissRequest = { addMenu = false },
                    shape = MenuShape, containerColor = Fuse.surface(dark),
                ) {
                    DropdownMenuItem(
                        text = { Text("导入 JSON 文件") },
                        leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp), tint = dim) },
                        enabled = !importing,
                        onClick = {
                            addMenu = false
                            picker.launch(arrayOf("*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("用高德查询并生成") },
                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = dim) },
                        enabled = !importing,
                        onClick = {
                            addMenu = false
                            onOpenAmap()
                        },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // ⋯ 更多：清空全部线路
            Box {
                RoundButton(
                    onClick = { moreMenu = true }, dark = dark,
                    modifier = Modifier.semantics { contentDescription = "更多" },
                ) { Text("⋯", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = dim) }
                DropdownMenu(
                    expanded = moreMenu, onDismissRequest = { moreMenu = false },
                    shape = MenuShape, containerColor = Fuse.surface(dark),
                ) {
                    DropdownMenuItem(
                        text = { Text("清空全部线路", color = if (hasImport) Fuse.MetroRed else dim) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete, null, Modifier.size(18.dp),
                                tint = if (hasImport) Fuse.MetroRed else dim,
                            )
                        },
                        enabled = hasImport,
                        onClick = {
                            moreMenu = false
                            confirmClear = true
                        },
                    )
                }
            }
        },
    ) {
        if (importing) {
            item {
                GlassCard {
                    Text("正在导入 JSON…", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Fuse.accent(dark))
                }
            }
        }
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
                Text(
                    "JSON 格式见 docs/DATA_SOURCE.md。若行程进行中，请在导入后结束并重新开始。",
                    fontSize = 12.sp, lineHeight = 17.sp, color = dim,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item {
            GlassCard(padding = 0.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    SectionLabel("已导入的线路（${groups.size} 条）")
                    if (!hasImport) {
                        Text(
                            "还没有导入线路。点右上角 ＋ 导入 JSON 或用高德生成。当前使用内置演示数据。",
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
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            shape = DialogShape, containerColor = Fuse.surface(dark),
            titleContentColor = ink, textContentColor = ink,
            title = { Text("清空全部线路？") },
            text = { Text("清空后所有已导入线路都会移除，并恢复内置演示数据。") },
            confirmButton = {
                DialogButton("清空", danger = true, onClick = {
                    Transit.clearImported(ctx)
                    version++
                    lastImport = null
                    confirmClear = false
                    Toast.makeText(ctx, "已清空线路，恢复默认数据源", Toast.LENGTH_SHORT).show()
                })
            },
            dismissButton = { DialogButton("取消", onClick = { confirmClear = false }) },
        )
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
