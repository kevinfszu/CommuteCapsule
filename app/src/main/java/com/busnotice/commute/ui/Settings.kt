package com.busnotice.commute.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.CommuteService
import com.busnotice.commute.DemoPlayer
import com.busnotice.commute.Places
import com.busnotice.commute.TraceLog
import com.busnotice.commute.Updater

/** 设置：权限、线路导入、更新、导出、演示，都从首页一次点击进来 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRecords: () -> Unit = {},
    onOpenLearn: () -> Unit = {},
    onOpenSource: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val dim = Fuse.dim(dark)
    var version by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }
    val perms = remember(version) { permChecks(ctx) { p -> launcher.launch(p) } }
    val days = remember(version) { TraceLog.dayCount(ctx) }
    val tripOn = CommuteService.instance != null
    // 权限通常只在首次配置时处理：默认折叠，缺必需项时在标题行直接点明
    var permOpen by rememberSaveable { mutableStateOf(false) }
    val required = perms.filter { !it.optional }
    val optional = perms.filter { it.optional }
    val firstMissing = required.firstOrNull { !it.granted }
    val permSummary = when {
        firstMissing != null -> "还差：${firstMissing.title} · 必需 ${required.count { it.granted }}/${required.size}"
        optional.any { !it.granted } -> "必需已开启 · 可选 ${optional.count { it.granted }}/${optional.size}"
        else -> "已全部开启 · 通常只需设置一次"
    }

    GlassPage(title = "设置", onBack = onBack) {
        sectionHeader("行程")
        item {
            GlassCard(padding = 0.dp) {
                GlassRow(
                    headline = "行程记录",
                    supporting = "查看每趟行程详情，删除异常记录回滚样本",
                    leading = { Icon(Icons.Default.List, null, tint = dim, modifier = Modifier.size(20.dp)) },
                    onClick = onOpenRecords,
                )
                GlassRow(
                    headline = "算法学习",
                    supporting = "查看已学到的乘车、候车、步行与站间时间",
                    leading = { Icon(Icons.Default.Star, null, tint = dim, modifier = Modifier.size(20.dp)) },
                    onClick = onOpenLearn,
                )
            }
        }
        sectionHeader("线路与数据")
        item {
            GlassCard(padding = 0.dp) {
                GlassRow(
                    headline = "线路导入",
                    supporting = "导入 JSON 或用高德生成，查看、修改、删除线路",
                    leading = { Icon(Icons.Default.Place, null, tint = dim, modifier = Modifier.size(20.dp)) },
                    onClick = onOpenSource,
                )
                if (days > 0) {
                    GlassRow(
                        headline = "导出行程记录",
                        supporting = "共 ${days} 天的记录，可分享为文件",
                        leading = { Icon(Icons.Default.Share, null, tint = dim, modifier = Modifier.size(20.dp)) },
                        onClick = { TraceLog.share(ctx) },
                    )
                }
                GlassRow(
                    headline = "重置常去地点",
                    supporting = "清空「家 / 公司」后重新学习",
                    leading = { Icon(Icons.Default.Place, null, tint = dim, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        Places.reset(ctx)
                        Toast.makeText(ctx, "常去地点已清空，将重新学习", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
        sectionHeader("应用")
        item {
            GlassCard(padding = 0.dp) {
                GlassRow(
                    headline = "检查更新",
                    supporting = "当前 v${Updater.currentName(ctx)}",
                    leading = { Icon(Icons.Default.Refresh, null, tint = dim, modifier = Modifier.size(20.dp)) },
                    onClick = { Updater.force.intValue++ },
                )
                GlassRow(
                    headline = "演示回放（约 1 分钟）",
                    supporting = if (tripOn) "行程进行中不可用" else "上班路线：逸景路 → 广州国际金融城（模拟时刻）",
                    leading = { Icon(Icons.Default.PlayArrow, null, tint = if (tripOn) dim.copy(alpha = 0.4f) else dim, modifier = Modifier.size(20.dp)) },
                    // 回放在设置二级页里启动，回到主页才能看到看板/胶囊
                    onClick = if (tripOn) null else ({ DemoPlayer.start(ctx); onBack() }),
                )
            }
        }
        item {
            GlassCard(padding = 0.dp) {
                GlassRow(
                    headline = "权限与设置",
                    supporting = permSummary,
                    leading = { Icon(Icons.Default.Settings, null, tint = dim, modifier = Modifier.size(20.dp)) },
                    trailing = {
                        Icon(
                            if (permOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            null, tint = dim, modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = { permOpen = !permOpen },
                )
                AnimatedVisibility(visible = permOpen) {
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        required.forEach { PermItem(it) }
                        if (optional.isNotEmpty()) {
                            Text("可选", fontSize = 12.sp, color = dim)
                            optional.forEach { PermItem(it) }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "所有位置与出行记录只保存在本机。",
                fontSize = 12.sp, color = dim,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item {
            Text(
                "CommuteCapsule v${Updater.currentName(ctx)}",
                fontSize = 12.sp, color = dim, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}
