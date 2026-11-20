package com.busnotice.commute

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 从链接拉取线路 JSON：只在用户点「拉取」时运行，不后台更新 */
object RemoteTransit {
    private const val MAX_BYTES = 2 * 1024 * 1024

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val target = url.trim()
        require(target.startsWith("http://") || target.startsWith("https://")) { "只支持 http/https 地址" }
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CommuteCapsule")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("获取失败：HTTP $code")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw IllegalStateException("数据超过 2 MiB，已终止")
                    out.write(buf, 0, n)
                }
            }
            if (total == 0) throw IllegalStateException("获取失败：内容为空")
            String(out.toByteArray(), Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }
}
