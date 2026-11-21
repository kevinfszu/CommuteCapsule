package com.busnotice.commute.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.ImportedJsonSource
import com.busnotice.commute.RemoteTransit
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
    var dataUrl by remember { mutableStateOf(Transit.dataUrl()) }
    val sourceTitle = remember(version) { Transit.static.title }
    val hasImport = remember(version) { ImportedJsonSource.hasData }
    val meta = remember(version) { ImportedJsonSource.meta }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
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
                Text("线路和站点：$sourceTitle", fontSize = 14.sp)
                Text("实时到站：${Transit.realtime?.title ?: "无（使用学习到的时刻表与估算数据）"}", fontSize = 14.sp)
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
                GlassField(
                    dataUrl, { dataUrl = it }, label = "数据地址",
                    supportingText = { Text("返回 JSON 的 http/https 地址，导入成功后将自动保存") },
                    singleLine = true,
                )
                PillButton(
                    onClick = {
                        importing = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val text = RemoteTransit.fetch(dataUrl)
                                    Transit.importJson(ctx, text).also { Transit.setDataUrl(ctx, dataUrl) }
                                }
                            }
                            importing = false
                            result.onSuccess {
                                version++
                                region = Transit.region
                                Toast.makeText(
                                    ctx, "已导入 ${it.lineCount} 条线路、${it.stationCount} 个站点", Toast.LENGTH_LONG,
                                ).show()
                            }.onFailure {
                                Toast.makeText(ctx, "导入失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !importing && dataUrl.isNotBlank(),
                    secondary = true,
                ) { Text(if (importing) "导入中…" else "从链接导入") }
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
                    PillButton(
                        onClick = {
                            Transit.clearImported(ctx)
                            version++
                            region = Transit.region
                            Toast.makeText(ctx, "已恢复默认数据源", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        secondary = true,
                    ) { Text("删除导入数据并恢复默认") }
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
}
