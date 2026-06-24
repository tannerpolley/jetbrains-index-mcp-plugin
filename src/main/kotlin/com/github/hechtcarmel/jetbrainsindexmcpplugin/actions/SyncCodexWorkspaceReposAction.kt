package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.CodexMcpRegistrationInstaller
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.CodexWorkspaceSyncService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceSyncResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.ui.McpToolWindowPanel
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Container

class SyncCodexWorkspaceReposAction : AnAction(
    "Sync Codex Repos",
    "Refresh Codex-active and local Workspace repos in the master Workspace",
    AllIcons.Actions.Refresh
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        syncProject(project)
    }

    fun syncProject(project: Project) {
        val app = ApplicationManager.getApplication()

        app.executeOnPooledThread {
            try {
                val prepared = CodexWorkspaceSyncService.prepare(project)
                var result: CodexWorkspaceSyncResult? = null
                app.invokeAndWait({
                    if (!project.isDisposed) {
                        result = app.runWriteAction<CodexWorkspaceSyncResult> {
                            CodexWorkspaceSyncService.applyPrepared(project, prepared)
                        }
                    }
                }, ModalityState.nonModal())
                result?.let { CodexWorkspaceSyncService.persistAppliedChanges(project, it) }
                val syncResult = result ?: CodexWorkspaceSyncService.buildResult(
                    prepared,
                    attached = emptyList(),
                    detached = emptyList(),
                    detachedModules = emptyList(),
                    errors = listOf(
                        com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceSkippedPath(
                            path = project.basePath.orEmpty(),
                            source = "workspace-sync-action",
                            reason = "project_disposed"
                        )
                    )
                )

                val registrationMessage = if (McpSettings.getInstance().autoInstallCodexMcpRegistrations) {
                    val repoScopes = CodexWorkspaceSyncService.buildRegistrationScopes(
                        prepared.plan,
                        prepared.workspaceProjectPath
                    )
                    val registration = CodexMcpRegistrationInstaller.install(
                        dryRun = false,
                        plan = CodexMcpRegistrationInstaller.buildPlan(repoScopes = repoScopes)
                    )
                    " ${registration.message}"
                } else {
                    ""
                }

                notify(
                    project,
                    NotificationType.INFORMATION,
                    "Codex Workspace Sync",
                    "${syncResult.message} Accepted=${syncResult.accepted.size}, attached=${syncResult.attached.size}, localRootsAttached=${syncResult.attachedLocalContentRoots.size}, detached=${syncResult.detached.size}, detachedModules=${syncResult.detachedModules.size}, runConfigsImported=${syncResult.runConfigurationsImported}, runConfigsRemoved=${syncResult.runConfigurationsRemoved}, skipped=${syncResult.skipped.size}.$registrationMessage"
                )
                refreshToolWindow(project)
            } catch (ex: Exception) {
                notify(
                    project,
                    NotificationType.ERROR,
                    "Codex Workspace Sync Failed",
                    ex.message ?: ex.javaClass.simpleName
                )
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && CodexWorkspaceSyncService.shouldAutoSyncProject(project)
    }

    private fun notify(project: Project, type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    private fun refreshToolWindow(project: Project) {
        ApplicationManager.getApplication().invokeLater({
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(McpConstants.TOOL_WINDOW_ID)
            toolWindow?.contentManager?.contents?.forEach { content ->
                findMcpPanel(content.component)?.refresh()
            }
        }, ModalityState.nonModal())
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
