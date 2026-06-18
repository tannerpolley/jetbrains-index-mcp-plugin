package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.ui.McpToolWindowPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Container

class RefreshAction : AnAction(
    McpBundle.message("toolWindow.refresh"),
    "Refresh server status and history",
    AllIcons.Actions.Refresh
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(McpConstants.TOOL_WINDOW_ID)
        toolWindow?.contentManager?.contents?.forEach { content ->
            findMcpPanel(content.component)?.refresh()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private fun findMcpPanel(component: Component): McpToolWindowPanel? {
        if (component is McpToolWindowPanel) return component
        if (component is Container) {
            for (child in component.components) {
                val match = findMcpPanel(child)
                if (match != null) return match
            }
        }
        return null
    }
}
