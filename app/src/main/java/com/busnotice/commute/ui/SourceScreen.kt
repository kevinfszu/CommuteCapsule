package com.busnotice.commute.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.ImportedJsonSource
import com.busnotice.commute.Transit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「数据源」：看当前用的是哪个数据源、运行时导入 JSON 或在线生成 */
@Composable
fun SourceScreen(onBack: () -> Unit, onOpenAmap: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    var version by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var lastImport by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val sourceTitle = remember(version) { Transit.static.title }
    val hasImport = remember(version) { ImportedJsonSource.hasData }
    val lineStations = remember(version) { ImportedJsonSource.lineStations }
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
                lastImport = "已导入 ${it.lineCount} 条线路、${it.stationCount} 个站点"
                confirmDelete = false
                Toast.makeText(
                    ctx, "已导入 ${it.lineCount} 条线路、${it.stationCount} 个站点", Toast.LENGTH_LONG,
                ).show()
            }.onFailure {
                Toast.makeText(ctx, "导入失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    GlassPage(title = "数据源", onBack = onBack) {
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
                Text(
                    "实时到站：${Transit.realtime?.title ?: "无（使用学习到的时刻表与估算数据）"}",
                    fontSize = 14.sp, color = ink, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            GlassCard {
                SectionLabel(if (hasImport) "已导入的线路（${lineStations.size} 条）" else "已导入的线路")
                if (!hasImport) {
                    Text(
                        "当前使用内置演示线路，导入后立即替换。",
                        fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        lineStations.forEach { (name, stationN) ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(ink.copy(alpha = 0.05f))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
                                Spacer(Modifier.weight(1f))
                                Text("$stationN 站", fontSize = 12.sp, color = dim)
                            }
                        }
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionLabel("导入或生成")
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
        if (meta != null) {
            item {
                GlassCard {
                    SectionLabel("数据说明")
                    if (meta.source.isNotBlank()) Text(
                        "数据：${meta.source}" + (if (meta.updated.isNotBlank()) "（${meta.updated}）" else ""),
                        fontSize = 12.sp, lineHeight = 17.sp, color = dim, modifier = Modifier.padding(top = 6.dp),
                    )
                    if (meta.note.isNotBlank()) Text(
                        meta.note, fontSize = 12.sp, lineHeight = 17.sp, color = dim,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        if (hasImport) {
            item {
                GlassCard {
                    SectionLabel("危险操作")
                    if (!confirmDelete) {
                        DialogButton(
                            "删除导入数据并恢复默认", danger = true,
                            onClick = { confirmDelete = true },
                        )
                    } else {
                        Text(
                            "删除后回到默认数据，已导入的线路会全部移除。",
                            fontSize = 12.sp, lineHeight = 17.sp, color = dim,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DialogButton("取消", onClick = { confirmDelete = false })
                            DialogButton("确认删除", danger = true, onClick = {
                                Transit.clearImported(ctx)
                                version++
                                Toast.makeText(ctx, "已恢复默认数据源", Toast.LENGTH_SHORT).show()
                                lastImport = null
                                confirmDelete = false
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
