package com.busnotice.commute

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 反向地理编码：只用来给用户看一眼「出发地大概在哪」，失败一律返回 null，不抛异常 */
object Geocode {
    suspend fun nameOf(ctx: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        if (lat.isNaN() || lng.isNaN()) return@withContext null
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(ctx, Locale.CHINA).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { a ->
                // 中文地址顺序：市 + 区 + 道路
                listOfNotNull(a.locality, a.subLocality, a.thoroughfare).distinct().joinToString("")
                    .ifBlank { null }
            }
        }.getOrNull()
    }
}
