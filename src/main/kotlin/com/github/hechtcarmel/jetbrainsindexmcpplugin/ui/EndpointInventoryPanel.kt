package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.SwingConstants

class EndpointInventoryPanel : JBPanel<EndpointInventoryPanel>(BorderLayout()) {
    private val endpointComboBox = JComboBox<EndpointInventoryRow>()
    private val copySelectedUrlButton = JButton("Copy URL")
    private val copySelectedRowButton = JButton("Copy Row")
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

        endpointComboBox.apply {
            renderer = EndpointRowRenderer()
            maximumRowCount = 12
            addActionListener {
                updateSelectedEndpointButtons()
            }
        }

        copySelectedUrlButton.apply {
            addActionListener {
                selectedRow()?.url?.takeIf { it.isNotBlank() }?.let { url ->
                    CopyPasteManager.getInstance().setContents(StringSelection(url))
                }
            }
        }

        copySelectedRowButton.apply {
            addActionListener {
                selectedRow()?.copyText?.takeIf { it.isNotBlank() }?.let { text ->
                    CopyPasteManager.getInstance().setContents(StringSelection(text))
                }
            }
        }

        val selectorPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JBLabel("Endpoint URL:"))
            add(endpointComboBox)
            add(copySelectedUrlButton)
            add(copySelectedRowButton)
        }

        val headerPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(selectorPanel)
            add(toggleButton)
        }

        rowsPanel.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 16, 0, 0)
            isOpaque = false
            isVisible = false
        }

        add(headerPanel, BorderLayout.NORTH)
        add(rowsPanel, BorderLayout.CENTER)
    }

    fun setRows(rows: List<EndpointInventoryRow>) {
        val selectedId = selectedRow()?.id
        this.rows = rows
        isVisible = rows.isNotEmpty()
        endpointComboBox.model = DefaultComboBoxModel(rows.toTypedArray())
        val restoredIndex = rows.indexOfFirst { it.id == selectedId }
        if (restoredIndex >= 0) {
            endpointComboBox.selectedIndex = restoredIndex
        } else if (rows.isNotEmpty()) {
            endpointComboBox.selectedIndex = 0
        }
        rowsPanel.removeAll()
        rows.forEach { row -> rowsPanel.add(createRow(row)) }
        updateSelectedEndpointButtons()
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
        toggleButton.text = "$icon Endpoint details: $workspaceCount workspace, $repoCount repos"
        toggleButton.toolTipText = if (expanded) {
            "Collapse endpoint URLs"
        } else {
            "Expand endpoint URLs"
        }
    }

    private fun selectedRow(): EndpointInventoryRow? =
        endpointComboBox.selectedItem as? EndpointInventoryRow

    private fun updateSelectedEndpointButtons() {
        val row = selectedRow()
        copySelectedUrlButton.isEnabled = row?.url?.isNotBlank() == true
        copySelectedRowButton.isEnabled = row?.copyText?.isNotBlank() == true
        endpointComboBox.toolTipText = row?.rootPath?.ifBlank { null }
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

    private class EndpointRowRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val row = value as? EndpointInventoryRow
            if (row != null) {
                text = endpointLabel(row)
                toolTipText = row.rootPath.ifBlank { null }
            }
            return component
        }

        private fun endpointLabel(row: EndpointInventoryRow): String {
            val scope = when (row.scopeKind) {
                EndpointScopeKind.WORKSPACE -> "workspace"
                EndpointScopeKind.REPO -> "repo"
            }
            val url = row.url.ifBlank { "(server URL pending)" }
            return "$scope: ${row.scopeName} - $url"
        }
    }
}
