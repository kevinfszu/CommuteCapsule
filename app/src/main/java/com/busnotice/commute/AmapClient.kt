package com.busnotice.commute

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 高德 Web API：只在用户点「查询」时请求一次，不后台轮询 */
object AmapClient {
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

    suspend fun search(key: String, city: String, keywords: String): String =
        get(AmapData.searchUrl(key, city, keywords))

    /** 通用 GET：HTTP 错误、超限、空内容都抛带中文说明的异常 */
    suspend fun get(url: String, maxBytes: Int = MAX_BYTES): String = withContext(Dispatchers.IO) {
        val conn = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
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
                    if (total > maxBytes) throw IllegalStateException("返回内容超过 ${maxBytes / 1024 / 1024} MiB，已终止")
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

    /** 静态地图等二进制内容：任何失败返回 null，选点页只是少一张预览图，不打断流程 */
    suspend fun image(url: String, maxBytes: Int = MAX_IMAGE_BYTES): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CommuteCapsule")
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return@runCatching null
                        out.write(buf, 0, n)
                    }
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
