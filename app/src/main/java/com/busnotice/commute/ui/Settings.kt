package com.busnotice.commute.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** 设置：权限、数据源、更新、导出、演示，都从首页一次点击进来 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRecords: () -> Unit = {},
    onOpenLearn: () -> Unit = {},
    onOpenSource: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    var version by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }
    val perms = remember(version) { permChecks(ctx) { p -> launcher.launch(p) } }
    val days = remember(version) { TraceLog.dayCount(ctx) }
    val tripOn = CommuteService.instance != null

    GlassPage(title = "设置", onBack = onBack) {
        item {
            GlassCard {
                SectionLabel("权限与设置")
                Column(Modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Spacer(Modifier.height(2.dp))
                    perms.filter { !it.optional }.forEach { PermItem(it) }
                    val opt = perms.filter { it.optional }
                    if (opt.isNotEmpty()) {
                        Text("可选", fontSize = 12.sp, color = dim)
                        opt.forEach { PermItem(it) }
                    }
                }
            }
        }
        item {
            GlassCard(padding = 0.dp) {
                GlassRow(
                    headline = "数据源",
                    supporting = "线路、站点与实时到站的来源",
                    leading = { Icon(Icons.Default.Place, null, tint = dim, modifier = Modifier.size(20.dp)) },
                    onClick = onOpenSource,
                )
            }
        }
        item {
            GlassCard(padding = 0.dp) {
                GlassRow(
                    headline = "算法学习",
                    supporting = "查看已学到的乘车、候车、步行与站间时间",
                    leading = { Icon(Icons.Default.Star, null, tint = dim, modifier = Modifier.size(20.dp)) },
                    onClick = onOpenLearn,
                )
                GlassRow(
                    headline = "行程记录",
                    supporting = "查看每趟行程的详情，可删除异常记录并回滚学习样本",
                    leading = { Icon(Icons.Default.List, null, tint = dim, modifier = Modifier.size(20.dp)) },
                    onClick = onOpenRecords,
                )
                GlassRow(
                    headline = "检查更新",
                    supporting = "当前 v${Updater.currentName(ctx)}",
                    leading = { Icon(Icons.Default.Refresh, null, tint = dim, modifier = Modifier.size(20.dp)) },
                    onClick = { Updater.force.intValue++ },
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
        item {
            GlassCard(padding = 0.dp) {
                listOf(60, 30).forEach { sp ->
                    val len = if (sp >= 60) "约 1 分钟" else "约 2 分钟"
                    GlassRow(
                        headline = "演示回放（$len）",
                        supporting = if (tripOn) "行程进行中不可用" else "上班路线：逸景翠园 → 广州国际金融城（模拟时刻）",
                        leading = { Icon(Icons.Default.PlayArrow, null, tint = if (tripOn) dim.copy(alpha = 0.4f) else dim, modifier = Modifier.size(20.dp)) },
                        // 回放在设置二级页里启动，回到主页才能看到看板/胶囊
                        onClick = if (tripOn) null else ({ DemoPlayer.start(ctx, sp); onBack() }),
                    )
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
