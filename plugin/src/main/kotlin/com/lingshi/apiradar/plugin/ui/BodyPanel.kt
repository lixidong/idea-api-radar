package com.lingshi.apiradar.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.lingshi.apiradar.plugin.service.ApiRadarSettings
import com.lingshi.apiradar.plugin.util.JsonPretty
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultHighlighter

/**
 * 详情面板里展示 Request Body / Response Body 的可复用组件。
 *
 * <p>顶部工具栏：Pretty/Raw 切换 + 当前 body 内全文搜索高亮。
 */
class BodyPanel : JPanel(BorderLayout()) {

    private val textArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val searchField = SearchTextField().apply {
        textEditor.toolTipText = "在当前 body 内搜索（实时高亮）"
    }
    private val highlighter = DefaultHighlighter.DefaultHighlightPainter(Color(255, 230, 80, 160))

    private var rawText: String = ""
    private var contentType: String? = null
    private var pretty: Boolean = ApiRadarSettings.getInstance().prettyJsonByDefault

    init {
        val group = DefaultActionGroup().apply { add(PrettyToggleAction()) }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = textArea

        val top = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        }
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(textArea), BorderLayout.CENTER)

        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyHighlight()
            override fun removeUpdate(e: DocumentEvent) = applyHighlight()
            override fun changedUpdate(e: DocumentEvent) = applyHighlight()
        })
    }

    fun show(text: String?, contentType: String?) {
        rawText = text ?: ""
        this.contentType = contentType
        // 新选中行时，默认按全局设置决定要不要美化；切回 Pretty=true
        pretty = ApiRadarSettings.getInstance().prettyJsonByDefault
        searchField.text = ""
        refreshText()
    }

    private fun refreshText() {
        textArea.text = if (pretty && JsonPretty.looksLikeJson(contentType)) {
            JsonPretty.prettyOrSelf(rawText)
        } else {
            rawText
        }
        textArea.caretPosition = 0
        applyHighlight()
    }

    private fun applyHighlight() {
        textArea.highlighter.removeAllHighlights()
        val needle = searchField.text
        if (needle.isNullOrEmpty()) return
        val haystack = textArea.text
        if (haystack.isEmpty()) return
        val lcHaystack = haystack.lowercase()
        val lcNeedle = needle.lowercase()
        var idx = lcHaystack.indexOf(lcNeedle)
        while (idx >= 0) {
            try {
                textArea.highlighter.addHighlight(idx, idx + needle.length, highlighter)
            } catch (_: Exception) {}
            idx = lcHaystack.indexOf(lcNeedle, idx + needle.length)
        }
    }

    private inner class PrettyToggleAction : ToggleAction(
        "Pretty JSON", "在原文与格式化 JSON 之间切换", AllIcons.Actions.PrettyPrint
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = pretty
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            pretty = state
            refreshText()
        }
    }
}
