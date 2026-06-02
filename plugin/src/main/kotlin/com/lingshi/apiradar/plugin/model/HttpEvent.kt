package com.lingshi.apiradar.plugin.model

/**
 * 与 agent 上报的 JSON 结构一一对应。
 */
data class HttpEvent(
    val id: String = "",
    val timestamp: Long = 0,
    val source: String? = null,
    val method: String? = null,
    val url: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    val responseCode: Int = 0,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String? = null,
    val duration: Long = 0,
    val error: String? = null,
)
