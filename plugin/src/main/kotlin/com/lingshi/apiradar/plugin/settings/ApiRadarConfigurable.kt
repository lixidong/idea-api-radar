package com.lingshi.apiradar.plugin.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.lingshi.apiradar.plugin.service.ApiRadarSettings
import com.lingshi.apiradar.plugin.service.EventBus
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → API Radar 配置面板。
 *
 * <p>修改保存后，会立刻通过 EventBus 把最新配置广播给已连接的 agent，实现热更新。
 */
class ApiRadarConfigurable : Configurable {

    private val maxBodyBytesField = JBTextField()
    private val skipBodyOverBytesField = JBTextField()
    private val slowThresholdField = JBTextField()
    private val includesArea = JBTextArea(4, 40)
    private val excludesArea = JBTextArea(4, 40)
    private val prettyJsonCheck = javax.swing.JCheckBox("详情面板默认美化 JSON")
    private val autoScrollCheck = javax.swing.JCheckBox("自动滚动到最新行")
    private var rootPanel: JPanel? = null

    override fun getDisplayName(): String = "API Radar"

    override fun createComponent(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("单 body 最大采集字节 (maxBodyBytes):"), maxBodyBytesField, 1, false)
            .addTooltip("超过该值的请求/响应 body 仅保留前 N 字节，并标记 [TRUNCATED]")
            .addLabeledComponent(JBLabel("跳过 body 阈值 (skipBodyOverBytes):"), skipBodyOverBytesField, 1, false)
            .addTooltip("响应 Content-Length 超过该值则完全不读 body。填 0 表示不跳过。")
            .addLabeledComponent(JBLabel("慢请求阈值 (ms):"), slowThresholdField, 1, false)
            .addTooltip("duration 超过该值时表格行加粗显示")
            .addComponent(prettyJsonCheck)
            .addComponent(autoScrollCheck)
            .addLabeledComponent(JBLabel("URL 白名单 (includes，每行一条 glob，* 匹配任意):"), includesArea, 1, true)
            .addLabeledComponent(JBLabel("URL 黑名单 (excludes，每行一条 glob，* 匹配任意):"), excludesArea, 1, true)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        panel.border = JBUI.Borders.empty(10)
        rootPanel = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val s = ApiRadarSettings.getInstance()
        return maxBodyBytesField.text.trim() != s.maxBodyBytes.toString()
                || skipBodyOverBytesField.text.trim() != s.skipBodyOverBytes.toString()
                || slowThresholdField.text.trim() != s.slowThresholdMs.toString()
                || prettyJsonCheck.isSelected != s.prettyJsonByDefault
                || autoScrollCheck.isSelected != s.autoScroll
                || textToList(includesArea.text) != s.includes
                || textToList(excludesArea.text) != s.excludes
    }

    override fun apply() {
        val s = ApiRadarSettings.getInstance()
        s.maxBodyBytes = parseIntOr(maxBodyBytesField.text, s.maxBodyBytes)
        s.skipBodyOverBytes = parseIntOr(skipBodyOverBytesField.text, s.skipBodyOverBytes)
        s.slowThresholdMs = parseIntOr(slowThresholdField.text, s.slowThresholdMs)
        s.prettyJsonByDefault = prettyJsonCheck.isSelected
        s.autoScroll = autoScrollCheck.isSelected
        s.includes = textToList(includesArea.text).toMutableList()
        s.excludes = textToList(excludesArea.text).toMutableList()

        // 把最新配置推给 agent
        service<EventBus>().broadcastControl(s.toControlJson())
    }

    override fun reset() {
        val s = ApiRadarSettings.getInstance()
        maxBodyBytesField.text = s.maxBodyBytes.toString()
        skipBodyOverBytesField.text = s.skipBodyOverBytes.toString()
        slowThresholdField.text = s.slowThresholdMs.toString()
        prettyJsonCheck.isSelected = s.prettyJsonByDefault
        autoScrollCheck.isSelected = s.autoScroll
        includesArea.text = s.includes.joinToString("\n")
        excludesArea.text = s.excludes.joinToString("\n")
    }

    override fun disposeUIResources() {
        rootPanel = null
    }

    private fun parseIntOr(s: String, fallback: Int): Int =
        s.trim().toIntOrNull() ?: fallback

    private fun textToList(text: String): List<String> =
        text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
}
