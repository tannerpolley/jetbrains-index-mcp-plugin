package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton

class EndpointInventoryPanel : JBPanel<EndpointInventoryPanel>() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 24, 4, 0)
        isOpaque = false
        isVisible = false
    }

    fun setRows(rows: List<EndpointInventoryRow>) {
        removeAll()
        isVisible = rows.isNotEmpty()
        rows.forEach { row -> add(createRow(row)) }
        revalidate()
        repaint()
    }

    private fun createRow(row: EndpointInventoryRow): JBPanel<JBPanel<*>> {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            toolTipText = row.rootPath.ifBlank { null }
        }

        panel.add(JBLabel(row.scopeName).apply {
            toolTipText = row.rootPath.ifBlank { null }
        })
        panel.add(JBLabel(row.url.ifBlank { "(server URL pending)" }).apply {
            foreground = if (row.state == EndpointInventoryState.RUNNING && row.url.isNotBlank()) {
                JBColor.BLUE
            } else {
                JBColor.GRAY
            }
            toolTipText = row.rootPath.ifBlank { null }
        })
        panel.add(JBLabel(row.state.name))
        panel.add(JButton("Copy URL").apply {
            isEnabled = row.url.isNotBlank()
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(row.url))
            }
        })
        panel.add(JButton("Copy Row").apply {
            isEnabled = row.copyText.isNotBlank()
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(row.copyText))
            }
        })

        return panel
    }
}
