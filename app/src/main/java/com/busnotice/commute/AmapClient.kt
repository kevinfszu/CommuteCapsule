package com.busnotice.commute

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 高德 Web API：只在用户点「查询」时请求一次，不后台轮询 */
object AmapClient {
    private const val MAX_BYTES = 1024 * 1024

    suspend fun search(key: String, city: String, keywords: String): String = withContext(Dispatchers.IO) {
        val conn = runCatching {
            (URL(AmapData.searchUrl(key, city, keywords)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CommuteCapsule")
            }
        }.getOrElse { throw IllegalStateException("查询地址无效：${it.message ?: "无法建立连接"}") }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("查询失败：HTTP $code")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw IllegalStateException("返回内容超过 1 MiB，已终止")
                    out.write(buf, 0, n)
                }
            }
            if (total == 0) throw IllegalStateException("高德返回了空内容")
            String(out.toByteArray(), Charsets.UTF_8)
        } catch (e: java.net.SocketTimeoutException) {
            throw IllegalStateException("查询超时，请检查网络后重试")
        } finally {
            conn.disconnect()
        }
    }
}
