package io.github.lixidong.apiradar.plugin.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

/**
 * 极简 JSON 美化工具：仅供详情面板按需展示，不做严格校验。
 * 无效 JSON 返回原文，调用方据此判断"格式化失败"。
 */
object JsonPretty {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    fun prettyOrSelf(text: String?): String {
        if (text.isNullOrBlank()) return text ?: ""
        return try {
            val tree = JsonParser.parseString(text)
            gson.toJson(tree)
        } catch (_: Throwable) {
            text
        }
    }

    fun looksLikeJson(contentType: String?): Boolean {
        if (contentType == null) return false
        val s = contentType.lowercase()
        return s.contains("json")
    }
}
