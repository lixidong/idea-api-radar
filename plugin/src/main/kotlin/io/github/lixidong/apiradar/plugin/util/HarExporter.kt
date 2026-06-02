package io.github.lixidong.apiradar.plugin.util

import com.google.gson.GsonBuilder
import io.github.lixidong.apiradar.plugin.model.HttpEvent
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把事件列表序列化为 HAR 1.2 文件，便于和 Chrome DevTools / Postman 互通。
 *
 * <p>实现限定：只填充常用字段，省略 cache / pageref / serverIPAddress 等。
 */
object HarExporter {

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun toHarJson(events: List<HttpEvent>): String {
        val entries = events.map(::toEntry)
        val har = mapOf(
            "log" to mapOf(
                "version" to "1.2",
                "creator" to mapOf("name" to "API Radar", "version" to "0.1"),
                "entries" to entries
            )
        )
        return gson.toJson(har)
    }

    private fun toEntry(e: HttpEvent): Map<String, Any?> = mapOf(
        "startedDateTime" to iso.format(Date(e.timestamp)),
        "time" to e.duration,
        "request" to mapOf(
            "method" to (e.method ?: "GET"),
            "url" to (e.url ?: ""),
            "httpVersion" to "HTTP/1.1",
            "headers" to headersList(e.requestHeaders),
            "queryString" to parseQuery(e.url),
            "headersSize" to -1,
            "bodySize" to (e.requestBody?.toByteArray()?.size ?: 0),
            "postData" to e.requestBody?.let {
                mapOf(
                    "mimeType" to (e.requestHeaders["Content-Type"]
                        ?: e.requestHeaders["content-type"] ?: "text/plain"),
                    "text" to it
                )
            }
        ),
        "response" to mapOf(
            "status" to e.responseCode,
            "statusText" to "",
            "httpVersion" to "HTTP/1.1",
            "headers" to headersList(e.responseHeaders),
            "content" to mapOf(
                "size" to (e.responseBody?.toByteArray()?.size ?: 0),
                "mimeType" to (e.responseHeaders["Content-Type"]
                    ?: e.responseHeaders["content-type"] ?: "text/plain"),
                "text" to (e.responseBody ?: "")
            ),
            "redirectURL" to "",
            "headersSize" to -1,
            "bodySize" to (e.responseBody?.toByteArray()?.size ?: 0)
        ),
        "cache" to emptyMap<String, Any>(),
        "timings" to mapOf(
            "send" to 0,
            "wait" to e.duration,
            "receive" to 0
        ),
        "_error" to e.error
    )

    private fun headersList(map: Map<String, String>): List<Map<String, String>> =
        map.map { (k, v) -> mapOf("name" to k, "value" to v) }

    private fun parseQuery(url: String?): List<Map<String, String>> {
        if (url.isNullOrEmpty()) return emptyList()
        return try {
            val q = URI(url).rawQuery ?: return emptyList()
            q.split('&').mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx < 0) mapOf("name" to kv, "value" to "")
                else mapOf("name" to kv.substring(0, idx), "value" to kv.substring(idx + 1))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
