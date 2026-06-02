package com.lingshi.apiradar.plugin.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * API Radar 全局可持久化配置。
 *
 * <p>所有字段都可在 Settings 页面修改，并在变更后通过 EventBus 广播给已连接的 agent。
 */
@Service(Service.Level.APP)
@State(name = "ApiRadarSettings", storages = [Storage("apiRadar.xml")])
class ApiRadarSettings : PersistentStateComponent<ApiRadarSettings> {

    /** 暂停采集（agent 端零开销直接放行）。 */
    var paused: Boolean = false

    /** 单 body 最大采集字节数（超出截断），默认 10MB。 */
    var maxBodyBytes: Int = 10 * 1024 * 1024

    /**
     * Content-Length 超过该值则不读 body（避免大文件下载阻塞或占用内存）。
     * 0 表示不跳过，全部读取。
     */
    var skipBodyOverBytes: Int = 0

    /** URL 白名单 glob，多条；为空表示不限制。 */
    var includes: MutableList<String> = mutableListOf()

    /** URL 黑名单 glob，多条；为空表示不过滤。 */
    var excludes: MutableList<String> = mutableListOf()

    /** Duration 超过该毫秒数即视为慢请求，UI 上加粗显示。 */
    var slowThresholdMs: Int = 1000

    /** 详情面板的 JSON Body 默认是否美化（识别 application/json 时生效）。 */
    var prettyJsonByDefault: Boolean = true

    /** ToolWindow 表格是否自动滚动到最新行。 */
    var autoScroll: Boolean = true

    override fun getState(): ApiRadarSettings = this

    override fun loadState(state: ApiRadarSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    /** 序列化为 agent 可解析的 control JSON。 */
    fun toControlJson(): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"paused\":").append(paused).append(',')
        sb.append("\"maxBodyBytes\":").append(maxBodyBytes).append(',')
        sb.append("\"skipBodyOverBytes\":").append(effectiveSkipBodyOverBytes()).append(',')
        appendStringArray(sb, "includes", includes); sb.append(',')
        appendStringArray(sb, "excludes", excludes)
        sb.append('}')
        return sb.toString()
    }

    private fun effectiveSkipBodyOverBytes(): Int =
        if (skipBodyOverBytes <= 0) Int.MAX_VALUE else skipBodyOverBytes

    private fun appendStringArray(sb: StringBuilder, key: String, list: List<String>) {
        sb.append('"').append(key).append("\":[")
        list.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append('"').append(escape(s)).append('"')
        }
        sb.append(']')
    }

    private fun escape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.toString()
    }

    companion object {
        fun getInstance(): ApiRadarSettings =
            ApplicationManager.getApplication().getService(ApiRadarSettings::class.java)
    }
}
