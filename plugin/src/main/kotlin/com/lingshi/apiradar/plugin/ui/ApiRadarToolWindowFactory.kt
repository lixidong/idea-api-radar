package com.lingshi.apiradar.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.lingshi.apiradar.plugin.model.HttpEvent
import com.lingshi.apiradar.plugin.service.ApiRadarSettings
import com.lingshi.apiradar.plugin.service.EventBus
import com.lingshi.apiradar.plugin.settings.ApiRadarConfigurable
import com.lingshi.apiradar.plugin.util.CurlBuilder
import com.lingshi.apiradar.plugin.util.HarExporter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

class ApiRadarToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ApiRadarPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.root, "Requests", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class ApiRadarPanel(private val project: Project) {

    private val events = mutableListOf<HttpEvent>()

    // === Filter state ===
    private val searchField = SearchTextField().apply {
        textEditor.toolTipText = "按 URL / Method / Status 过滤（不区分大小写）"
    }
    private var showOnlyErrors = false
    private var showOnlySlow = false

    private class ReadOnlyTableModel(columns: Array<String>) :
        DefaultTableModel(columns as Array<out Any>, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            3 -> Integer::class.java          // Status
            4 -> java.lang.Long::class.java   // Duration
            else -> String::class.java
        }
    }

    private val tableModel = ReadOnlyTableModel(
        arrayOf("Time", "Method", "URL", "Status", "Duration(ms)", "Source")
    )
    private val rowSorter = TableRowSorter(tableModel)
    private val table = JBTable(tableModel).apply {
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        rowSorter = this@ApiRadarPanel.rowSorter
        setDefaultRenderer(Any::class.java, CellRenderer())
        setDefaultRenderer(Integer::class.java, CellRenderer())
        setDefaultRenderer(java.lang.Long::class.java, CellRenderer())
    }

    // === Detail panels ===
    private val requestHeadersTable = HeadersTable()
    private val responseHeadersTable = HeadersTable()
    private val requestBodyPanel = BodyPanel()
    private val responseBodyPanel = BodyPanel()
    private val errorArea = JTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        foreground = Color(0xCC4B37)
    }
    private val portLabel = JLabel("Port: -")

    val root: JPanel = JPanel(BorderLayout()).apply {
        add(buildToolbar(), BorderLayout.NORTH)
        add(buildMainSplit(), BorderLayout.CENTER)
    }

    init {
        installRowFilter()
        startListening()
        bindSelection()
        installPopup()
    }

    // ===== Toolbar =====
    private fun buildToolbar(): JPanel {
        val group = DefaultActionGroup().apply {
            add(PauseToggleAction())
            add(ClearAction())
            addSeparator()
            add(ErrorsOnlyAction())
            add(SlowOnlyAction())
            add(AutoScrollAction())
            addSeparator()
            add(ExportHarAction())
            add(SettingsAction())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_CONTENT, group, true
        )
        toolbar.targetComponent = table

        val container = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            add(portLabel, BorderLayout.EAST)
            border = BorderFactory.createEmptyBorder(2, 2, 2, 4)
        }
        return container
    }

    private fun installRowFilter() {
        rowSorter.rowFilter = object : javax.swing.RowFilter<DefaultTableModel, Int>() {
            override fun include(entry: Entry<out DefaultTableModel, out Int>): Boolean {
                val idx = entry.identifier
                if (idx < 0 || idx >= events.size) return true
                val event = events[idx]
                val needle = searchField.text?.trim()?.lowercase().orEmpty()
                if (needle.isNotEmpty()) {
                    val haystack = (event.method.orEmpty() + " " +
                            event.url.orEmpty() + " " +
                            event.responseCode.toString()).lowercase()
                    if (!haystack.contains(needle)) return false
                }
                if (showOnlyErrors && (event.responseCode in 200..399 && event.error.isNullOrEmpty())) {
                    return false
                }
                if (showOnlySlow && event.duration < ApiRadarSettings.getInstance().slowThresholdMs) {
                    return false
                }
                return true
            }
        }
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rowSorter.sort()
            override fun removeUpdate(e: DocumentEvent) = rowSorter.sort()
            override fun changedUpdate(e: DocumentEvent) = rowSorter.sort()
        })
    }

    // ===== Main split & details =====
    private fun buildMainSplit(): OnePixelSplitter = OnePixelSplitter(false, 0.4f).apply {
        firstComponent = JScrollPane(table)
        secondComponent = buildDetailTabs()
    }

    private fun buildDetailTabs(): JTabbedPane = JTabbedPane().apply {
        addTab("Request Headers", JScrollPane(requestHeadersTable))
        addTab("Request Body", requestBodyPanel)
        addTab("Response Headers", JScrollPane(responseHeadersTable))
        addTab("Response Body", responseBodyPanel)
        addTab("Error", JScrollPane(errorArea))
    }

    private fun startListening() {
        val bus = service<EventBus>()
        val port = if (bus.port == 0) bus.start() else bus.port
        portLabel.text = "Port: $port  "

        bus.addListener { event ->
            ApplicationManager.getApplication().invokeLater {
                events.add(event)
                tableModel.addRow(
                    arrayOf<Any>(
                        formatTime(event.timestamp),
                        event.method ?: "",
                        event.url ?: "",
                        event.responseCode,
                        event.duration,
                        event.source ?: ""
                    )
                )
                if (ApiRadarSettings.getInstance().autoScroll) {
                    val last = table.rowCount - 1
                    if (last >= 0) table.scrollRectToVisible(table.getCellRect(last, 0, true))
                }
            }
        }
    }

    private fun bindSelection() {
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val event = currentEvent() ?: return@addListSelectionListener
            requestHeadersTable.show(event.requestHeaders)
            responseHeadersTable.show(event.responseHeaders)
            requestBodyPanel.show(event.requestBody, contentTypeOf(event.requestHeaders))
            responseBodyPanel.show(event.responseBody, contentTypeOf(event.responseHeaders))
            errorArea.text = event.error.orEmpty()
        }
    }

    private fun currentEvent(): HttpEvent? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        val modelRow = table.convertRowIndexToModel(viewRow)
        if (modelRow < 0 || modelRow >= events.size) return null
        return events[modelRow]
    }

    private fun selectedEvents(): List<HttpEvent> {
        val rows = table.selectedRows
        if (rows.isEmpty()) return emptyList()
        val result = ArrayList<HttpEvent>(rows.size)
        for (vr in rows) {
            val mr = table.convertRowIndexToModel(vr)
            events.getOrNull(mr)?.let { result.add(it) }
        }
        return result
    }

    private fun visibleEvents(): List<HttpEvent> {
        val count = table.rowCount
        val result = ArrayList<HttpEvent>(count)
        for (vr in 0 until count) {
            val mr = table.convertRowIndexToModel(vr)
            events.getOrNull(mr)?.let { result.add(it) }
        }
        return result
    }

    // ===== Popup menu =====
    private fun installPopup() {
        table.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component?, x: Int, y: Int) {
                val viewRow = table.rowAtPoint(java.awt.Point(x, y))
                if (viewRow >= 0 && !table.isRowSelected(viewRow)) {
                    table.setRowSelectionInterval(viewRow, viewRow)
                }
                buildContextMenu().show(comp, x, y)
            }
        })
    }

    private fun buildContextMenu(): JPopupMenu = JPopupMenu().apply {
        add(menuItem("Copy URL") {
            currentEvent()?.url?.let(::copyToClipboard)
        })
        add(menuItem("Copy as cURL") {
            currentEvent()?.let { copyToClipboard(CurlBuilder.toCurl(it)) }
        })
        add(menuItem("Copy Request Body") {
            currentEvent()?.requestBody?.let(::copyToClipboard)
        })
        add(menuItem("Copy Response Body") {
            currentEvent()?.responseBody?.let(::copyToClipboard)
        })
        addSeparator()
        add(menuItem("Export Selected as HAR…") {
            exportHar(selectedEvents(), suffix = "selected")
        })
    }

    private fun menuItem(text: String, onClick: () -> Unit): javax.swing.JMenuItem =
        javax.swing.JMenuItem(text).apply { addActionListener { onClick() } }

    // ===== Helpers =====
    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(text), null)
    }

    private fun contentTypeOf(headers: Map<String, String>): String? =
        headers["Content-Type"] ?: headers["content-type"]

    private fun formatTime(ts: Long): String {
        if (ts == 0L) return ""
        return java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date(ts))
    }

    private fun exportHar(items: List<HttpEvent>, suffix: String) {
        if (items.isEmpty()) {
            Messages.showInfoMessage(project, "没有可导出的请求", "API Radar")
            return
        }
        val descriptor = FileSaverDescriptor("Export HAR", "选择保存位置", "har")
        val dialog = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
        val baseDir: java.nio.file.Path? = null
        val wrapper = dialog.save(baseDir, "api-radar-$suffix.har") ?: return
        val target = wrapper.file.toPath()
        Files.writeString(target, HarExporter.toHarJson(items))
        Messages.showInfoMessage(project, "已导出 ${items.size} 条到 $target", "API Radar")
    }

    // ===== Renderer =====
    private inner class CellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column
            ) as JLabel
            val modelRow = table.convertRowIndexToModel(row)
            val event = events.getOrNull(modelRow)

            // 默认重置
            comp.font = comp.font.deriveFont(Font.PLAIN)
            if (!isSelected) comp.foreground = table.foreground

            if (event != null) {
                if (event.duration >= ApiRadarSettings.getInstance().slowThresholdMs) {
                    comp.font = comp.font.deriveFont(Font.BOLD)
                }
                if (column == 3 && !isSelected) {
                    comp.foreground = statusColor(event.responseCode, event.error)
                    comp.font = comp.font.deriveFont(Font.BOLD)
                }
                if (column == 3 && event.responseCode == 0) {
                    comp.text = if (event.error.isNullOrEmpty()) "" else "ERR"
                }
            }
            return comp
        }
    }

    private fun statusColor(code: Int, error: String?): Color {
        if (!error.isNullOrEmpty()) return Color(0xCC4B37)
        return when (code) {
            in 200..299 -> Color(0x2E7D32)
            in 300..399 -> Color(0x808080)
            in 400..499 -> Color(0xE08E0B)
            in 500..599 -> Color(0xCC4B37)
            else -> Color(0x808080)
        }
    }

    // ===== Actions =====
    private inner class PauseToggleAction :
        ToggleAction("Pause Capture", "暂停/恢复 API 采集", AllIcons.Actions.Pause) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = ApiRadarSettings.getInstance().paused
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            val s = ApiRadarSettings.getInstance()
            s.paused = state
            service<EventBus>().broadcastControl(s.toControlJson())
        }
    }

    private inner class ClearAction :
        AnAction("Clear", "清空已捕获的请求记录", AllIcons.Actions.GC) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            events.clear()
            tableModel.rowCount = 0
            requestHeadersTable.show(emptyMap())
            responseHeadersTable.show(emptyMap())
            requestBodyPanel.show(null, null)
            responseBodyPanel.show(null, null)
            errorArea.text = ""
        }
    }

    private inner class ErrorsOnlyAction :
        ToggleAction("Errors Only", "只显示 4xx/5xx 或异常的请求", AllIcons.General.Error) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = showOnlyErrors
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            showOnlyErrors = state; rowSorter.sort()
        }
    }

    private inner class SlowOnlyAction :
        ToggleAction("Slow Only", "只显示超过慢请求阈值的请求", AllIcons.Actions.Profile) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = showOnlySlow
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            showOnlySlow = state; rowSorter.sort()
        }
    }

    private inner class AutoScrollAction :
        ToggleAction("Auto Scroll", "新请求到达时自动滚动到底部", AllIcons.RunConfigurations.Scroll_down) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = ApiRadarSettings.getInstance().autoScroll
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            ApiRadarSettings.getInstance().autoScroll = state
        }
    }

    private inner class ExportHarAction :
        AnAction("Export HAR", "把当前过滤后可见的请求导出为 HAR 文件", AllIcons.ToolbarDecorator.Export) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            exportHar(visibleEvents(), suffix = "visible")
        }
    }

    private inner class SettingsAction :
        AnAction("Settings", "打开 API Radar 配置", AllIcons.General.Settings) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ApiRadarConfigurable::class.java)
        }
    }
}
