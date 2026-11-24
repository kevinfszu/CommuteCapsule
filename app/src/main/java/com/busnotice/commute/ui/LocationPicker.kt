package com.busnotice.commute.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.busnotice.commute.AmapClient
import com.busnotice.commute.AmapData
import com.busnotice.commute.Geocode
import com.busnotice.commute.Loc
import com.busnotice.commute.Transit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 高德选点：静态地图预览 + 使用当前位置 + 关键词搜索 + 附近地点。
 * 地图只做预览，选点靠搜索结果与周边列表；所有坐标返回前已是 WGS-84。
 */
@Composable
fun LocationPicker(
    title: String,
    initLat: Double?,
    initLng: Double?,
    onBack: () -> Unit,
    onPick: (name: String, lat: Double, lng: Double) -> Unit,
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    val key = remember { Transit.amapKey().trim() }
    val city = remember { Transit.amapCity().trim() }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AmapData.PlacePoint>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<AmapData.PlacePoint?>(null) }
    var locating by remember { mutableStateOf(true) }
    var focus by remember {
        mutableStateOf(
            if (initLat != null && initLng != null && !initLat.isNaN() && !initLng.isNaN()) initLat to initLng else null,
        )
    }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 初始点优先（编辑已有行程时），否则等当前定位
    val centerLat = initLat?.takeIf { !it.isNaN() } ?: current?.lat
    val centerLng = initLng?.takeIf { !it.isNaN() } ?: current?.lng

    // 当前定位：名称依次用高德逆地理、系统地理编码，都失败就叫「当前位置」
    LaunchedEffect(Unit) {
        val l = try {
            Loc.current(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (l == null) {
            locating = false
            return@LaunchedEffect
        }
        var label = ""
        if (key.isNotEmpty()) {
            label = try {
                AmapData.parseRegeo(
                    AmapClient.get(AmapData.regeoUrl(key, l.latitude, l.longitude)),
                    l.latitude, l.longitude,
                )?.name.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
        }
        if (label.isBlank()) label = Geocode.nameOf(ctx, l.latitude, l.longitude).orEmpty()
        current = AmapData.PlacePoint(
            name = label.ifBlank { "当前位置" },
            address = "当前位置",
            lat = l.latitude,
            lng = l.longitude,
            distanceM = 0,
        )
        if (focus == null) focus = l.latitude to l.longitude
        locating = false
    }

    // 静态地图预览：跟随最近一次高亮的点；失败只显示占位
    LaunchedEffect(focus, key) {
        val f = focus
        if (f == null || key.isEmpty()) {
            bitmap = null
            return@LaunchedEffect
        }
        val bytes = AmapClient.image(AmapData.staticMapUrl(key, f.first, f.second))
        bitmap = if (bytes == null) null else withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    // 搜索（400 ms 防抖）与附近地点；Key 为空时都不发请求
    LaunchedEffect(query, centerLat, centerLng, key) {
        if (key.isEmpty()) {
            results = emptyList()
            loading = false
            return@LaunchedEffect
        }
        val q = query.trim()
        val cLat = centerLat
        val cLng = centerLng
        if (q.isEmpty() && (cLat == null || cLng == null)) return@LaunchedEffect
        delay(if (q.isEmpty()) 200 else 400)
        loading = true
        error = ""
        val res = try {
            if (q.isEmpty()) {
                if (cLat != null && cLng != null) {
                    AmapData.parseAround(AmapClient.get(AmapData.aroundUrl(key, cLat, cLng)))
                } else {
                    emptyList()
                }
            } else {
                AmapData.parseTips(AmapClient.get(AmapData.inputTipsUrl(key, city, q, cLat, cLng)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
            emptyList()
        }
        results = res
        loading = false
        if (res.isNotEmpty()) focus = res.first().lat to res.first().lng
    }

    BackHandler { onBack() }
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(if (dark) Fuse.DarkBg else Fuse.LightBg)) {
            GlassPage(title = title, onBack = onBack) {
                item {
                    Box(
                        Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = bitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "地图预览",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Text("地图预览不可用", style = Type.BodyDim, color = dim)
                        }
                    }
                }
                if (key.isEmpty()) {
                    item {
                        Text(
                            "请先在「高德导入」里设置 Web 服务 Key，或直接使用当前位置",
                            style = Type.BodyDim, color = dim,
                        )
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().glass(dark)) {
                        GlassRow(
                            headline = if (locating) "定位中…" else "使用当前位置",
                            supporting = current?.name?.takeIf { it != "当前位置" },
                            onClick = {
                                current?.let { onPick(it.name, it.lat, it.lng) }
                            },
                            trailing = {
                                Text(if (locating) "…" else "使用", style = Type.BodyDim, color = ink)
                            },
                        )
                    }
                }
                item {
                    GlassField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "搜索地点，如「华师」",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Fuse.accent(dark), modifier = Modifier.size(22.dp))
                        }
                    }
                }
                if (error.isNotBlank()) {
                    item { Text(error, style = Type.BodyDim, color = Fuse.Amber, modifier = Modifier.padding(vertical = 4.dp)) }
                }
                if (key.isNotEmpty()) {
                    sectionHeader(if (query.isBlank()) "附近地点" else "搜索结果")
                    if (!loading && results.isEmpty()) {
                        item {
                            Text(
                                if (query.isBlank()) "附近没有可用的地点，试试搜索" else "没有找到匹配地点",
                                style = Type.BodyDim, color = dim, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            )
                        }
                    }
                    glassGroup(results) { p ->
                        GlassRow(
                            headline = p.name,
                            supporting = pointSubtitle(p),
                            onClick = { onPick(p.name, p.lat, p.lng) },
                            trailing = { Text("选择", style = Type.BodyDim, color = ink) },
                        )
                    }
                }
            }
        }
    }
}

private fun pointSubtitle(p: AmapData.PlacePoint): String? {
    val addr = p.address.takeIf { it.isNotBlank() && it != p.name }
    val dist = p.distanceM?.takeIf { it >= 0 }?.let {
        if (it < 1000) "$it 米" else "%.1f 公里".format(it / 1000.0)
    }
    return listOfNotNull(addr, dist).joinToString(" · ").ifBlank { null }
}
