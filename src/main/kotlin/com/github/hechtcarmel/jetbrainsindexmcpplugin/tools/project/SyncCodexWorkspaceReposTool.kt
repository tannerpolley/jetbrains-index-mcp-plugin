package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.CodexWorkspaceSyncService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceSyncResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

class SyncCodexWorkspaceReposTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.SYNC_CODEX_WORKSPACE_REPOS

    override val description = """
        Discover open Codex workspace roots from Codex desktop state, expand Git worktrees, and attach missing Git repo roots to the current IntelliJ Workspace project.

        Parameters: dryRun (optional, default false), codex_state_path (optional), includeWorktrees (optional, default true), project_path (optional workspace project path).
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .booleanProperty("dryRun", "Preview the Codex repo sync without attaching missing repos. Default: false.")
        .stringProperty("codex_state_path", "Absolute path to the Codex global state JSON file. Defaults to the current user's Codex state.")
        .booleanProperty("includeWorktrees", "Include Git worktrees for each discovered repo. Default: true.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val options = CodexWorkspaceSyncService.Options(
            dryRun = arguments["dryRun"]?.jsonPrimitive?.booleanOrNull ?: false,
            codexStatePath = optionalStringArg(arguments, "codex_state_path"),
            includeWorktrees = arguments["includeWorktrees"]?.jsonPrimitive?.booleanOrNull ?: true
        )

        val result = try {
            val prepared = CodexWorkspaceSyncService.prepare(project, options)
            if (options.dryRun) {
                CodexWorkspaceSyncService.buildResult(prepared, attached = emptyList(), errors = emptyList())
            } else {
                edtAction {
                    ApplicationManager.getApplication().runWriteAction<CodexWorkspaceSyncResult> {
                        CodexWorkspaceSyncService.applyPrepared(project, prepared)
                    }
                }
            }
        } catch (e: Exception) {
            return createErrorResult(e.message ?: "Codex workspace sync failed.")
        }

        return createJsonResult(result)
    }
}
