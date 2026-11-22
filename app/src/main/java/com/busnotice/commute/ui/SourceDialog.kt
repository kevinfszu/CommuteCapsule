package com.busnotice.commute.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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

/** 「数据源」：看当前用的是哪个数据源、改区域编号、运行时导入 JSON */
@Composable
fun SourceDialog(dark: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ink = Fuse.ink(dark)
    var region by remember { mutableStateOf(Transit.region) }
    var version by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var amapOpen by remember { mutableStateOf(false) }
    var lastImport by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val sourceTitle = remember(version) { Transit.static.title }
    val hasImport = remember(version) { ImportedJsonSource.hasData }
    val lineNames = remember(version) { ImportedJsonSource.lineNames }
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
                region = Transit.region
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

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
        title = { Text("数据源") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                lastImport?.let { msg ->
                    Text(
                        msg,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Fuse.accent(dark),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                }
                Text("线路和站点：$sourceTitle", fontSize = 14.sp)
                Text("实时到站：${Transit.realtime?.title ?: "无（使用学习到的时刻表与估算数据）"}", fontSize = 14.sp)
                if (hasImport) {
                    SectionLabel("已导入的线路（${lineNames.size} 条）")
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 168.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        lineStations.forEach { (name, stationN) ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(Fuse.ink(dark).copy(alpha = 0.05f))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
                                Spacer(Modifier.weight(1f))
                                Text("${stationN} 站", fontSize = 12.sp, color = Fuse.dim(dark))
                            }
                        }
                    }
                }
                GlassField(
                    region, { region = it }, label = "区域编号",
                    supportingText = { Text("数据源自带的城市编号，仅在更换城市时修改") },
                )
                PillButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !importing,
                    secondary = true,
                ) { Text(if (importing) "导入中…" else "导入 JSON 文件") }
                PillButton(
                    onClick = { amapOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !importing,
                    secondary = true,
                ) { Text("用高德查询并生成") }
                if (meta != null) {
                    if (meta.source.isNotBlank()) Text(
                        "数据：${meta.source}" + (if (meta.updated.isNotBlank()) "（${meta.updated}）" else ""),
                        fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark),
                    )
                    if (meta.note.isNotBlank()) Text(
                        meta.note, fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark),
                    )
                }
                if (hasImport) {
                    if (!confirmDelete) {
                        DialogButton(
                            "删除导入数据并恢复默认", danger = true,
                            onClick = { confirmDelete = true },
                        )
                    } else {
                        Text(
                            "删除后回到默认数据，已导入的线路会全部移除。",
                            fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DialogButton("取消", onClick = { confirmDelete = false })
                            DialogButton("确认删除", danger = true, onClick = {
                                Transit.clearImported(ctx)
                                version++
                                region = Transit.region
                                Toast.makeText(ctx, "已恢复默认数据源", Toast.LENGTH_SHORT).show()
                                lastImport = null
                                confirmDelete = false
                            })
                        }
                    }
                }
                Text(
                    "JSON 格式见 docs/DATA_SOURCE.md。若行程进行中，请在导入后结束并重新开始。",
                    fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark),
                )
                Spacer(Modifier.height(12.dp))
            }
        },
        confirmButton = {
            DialogButton("完成", primary = true, onClick = {
                if (region.trim() != Transit.region) Transit.setRegion(ctx, region)
                onDismiss()
            })
        },
    )

    if (amapOpen) {
        AmapImportDialog(
            dark = dark,
            onDismiss = { amapOpen = false },
            onImported = {
                amapOpen = false
                version++
                region = Transit.region
            },
        )
    }
}
