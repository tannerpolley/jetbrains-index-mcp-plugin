package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.SwingConstants

class EndpointInventoryPanel : JBPanel<EndpointInventoryPanel>(BorderLayout()) {
    private val toggleButton = JButton()
    private val rowsPanel = JBPanel<JBPanel<*>>()
    private var rows: List<EndpointInventoryRow> = emptyList()
    private var expanded = false

    init {
        border = JBUI.Borders.empty(2, 24, 2, 0)
        isOpaque = false
        isVisible = false

        toggleButton.apply {
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            horizontalAlignment = SwingConstants.LEFT
            addActionListener {
                expanded = !expanded
                updateExpandedState()
            }
        }

        rowsPanel.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 16, 0, 0)
            isOpaque = false
            isVisible = false
        }

        add(toggleButton, BorderLayout.NORTH)
        add(rowsPanel, BorderLayout.CENTER)
    }

    fun setRows(rows: List<EndpointInventoryRow>) {
        this.rows = rows
        isVisible = rows.isNotEmpty()
        rowsPanel.removeAll()
        rows.forEach { row -> rowsPanel.add(createRow(row)) }
        updateExpandedState()
        revalidate()
        repaint()
    }

    internal fun isExpandedForTest(): Boolean = expanded

    private fun updateExpandedState() {
        rowsPanel.isVisible = expanded && rows.isNotEmpty()
        val repoCount = rows.count { it.scopeKind == EndpointScopeKind.REPO }
        val workspaceCount = rows.count { it.scopeKind == EndpointScopeKind.WORKSPACE }
        val icon = if (expanded) "v" else ">"
        toggleButton.text = "$icon Endpoints: $workspaceCount workspace, $repoCount repos"
        toggleButton.toolTipText = if (expanded) {
            "Collapse endpoint URLs"
        } else {
            "Expand endpoint URLs"
        }
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
