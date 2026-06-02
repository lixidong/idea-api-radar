package io.github.lixidong.apiradar.plugin.ui

import com.intellij.ui.table.JBTable
import javax.swing.table.DefaultTableModel

/**
 * 详情面板中以两列表格展示 headers，便于横向阅读和单元格复制。
 */
class HeadersTable : JBTable() {

    private val model = object : DefaultTableModel(arrayOf<Any>("Name", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    init {
        setModel(model)
        rowHeight = 22
        autoResizeMode = AUTO_RESIZE_LAST_COLUMN
        columnModel.getColumn(0).preferredWidth = 200
    }

    fun show(headers: Map<String, String>) {
        model.rowCount = 0
        headers.forEach { (k, v) -> model.addRow(arrayOf<Any>(k, v)) }
    }
}
