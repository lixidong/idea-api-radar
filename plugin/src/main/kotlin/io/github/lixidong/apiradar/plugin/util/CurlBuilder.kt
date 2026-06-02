package io.github.lixidong.apiradar.plugin.util

import io.github.lixidong.apiradar.plugin.model.HttpEvent

/**
 * 把 [HttpEvent] 转换为可在终端执行的 curl 命令。
 *
 * <p>仅供调试场景下复制粘贴，不严格遵循 RFC：
 * <ul>
 *     <li>单引号转义采用经典的 {@code '\''} 写法（bash 兼容）</li>
 *     <li>multipart / 二进制 body 不做特殊处理，按字符串原样塞入 -d</li>
 * </ul>
 */
object CurlBuilder {

    fun toCurl(event: HttpEvent): String {
        val sb = StringBuilder("curl")
        val method = event.method?.uppercase() ?: "GET"
        if (method != "GET") {
            sb.append(" -X ").append(method)
        }
        event.requestHeaders.forEach { (k, v) ->
            sb.append(" -H ").append(quote("$k: $v"))
        }
        if (!event.requestBody.isNullOrEmpty()) {
            sb.append(" --data-raw ").append(quote(event.requestBody))
        }
        sb.append(' ').append(quote(event.url ?: ""))
        return sb.toString()
    }

    private fun quote(s: String): String {
        // bash 安全的单引号包裹：内部的 ' 用 '\'' 替换
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
