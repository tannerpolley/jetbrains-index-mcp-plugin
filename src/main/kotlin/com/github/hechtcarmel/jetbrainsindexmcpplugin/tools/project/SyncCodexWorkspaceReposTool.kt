package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.CodexMcpRegistrationInstaller
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.CodexWorkspaceSyncService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceSyncResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SyncCodexWorkspaceReposTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.SYNC_CODEX_WORKSPACE_REPOS

    override val description = """
        Discover Codex Desktop project roots from Codex desktop state, include Codex-requested Git worktrees and agent config content roots, attach accepted roots to the current IntelliJ Workspace project, detach stale Workspace repo roots that are no longer accepted, prune stale Workspace VCS mappings, and synchronize accepted repo .run configurations into Services.

        Parameters: dryRun (optional, default false), codex_state_path (optional), includeWorktrees (optional, default true), codexProjectRootsOnly (optional, default false), activeWorkspaceRootsOnly (optional, default false), requireMatchingGitHubRemote (optional, default false), includeAgentContentRoots (optional, default true), installCodexMcp (optional, default false), githubOwner (optional), project_path (optional workspace project path).
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .booleanProperty("dryRun", "Preview the Codex repo reconciliation without attaching or detaching repos. Default: false.")
        .stringProperty("codex_state_path", "Absolute path to the Codex global state JSON file. Defaults to the current user's Codex state.")
        .booleanProperty("includeWorktrees", "Include Git worktrees only when their paths also appear in Codex state. Default: true.")
        .booleanProperty("codexProjectRootsOnly", "Only read the Codex Desktop Projects list from project-order, falling back to active-workspace-roots when missing. Default: false.")
        .booleanProperty("activeWorkspaceRootsOnly", "Only read active-workspace-roots from Codex state; ignore saved/open roots and thread hints. Default: false.")
        .booleanProperty("requireMatchingGitHubRemote", "Require a GitHub remote whose owner matches githubOwner and whose repo name matches the local folder. Default: false.")
        .booleanProperty("includeAgentContentRoots", "Attach the current user's .codex and .agents directories as manual Workspace content roots. Default: true.")
        .booleanProperty("installCodexMcp", "Install generated Codex MCP registrations after repo sync, including manual .codex and .agents roots when attached. In dryRun mode, only returns commands. Default: false.")
        .stringProperty("githubOwner", "GitHub username used for manual repo filtering. Empty string disables owner filtering. Defaults to plugin settings.")
        .build()

    internal fun parseOptions(arguments: JsonObject): CodexWorkspaceSyncService.Options {
        return CodexWorkspaceSyncService.Options(
            dryRun = arguments["dryRun"]?.jsonPrimitive?.booleanOrNull ?: false,
            codexStatePath = optionalStringArg(arguments, "codex_state_path"),
            includeWorktrees = arguments["includeWorktrees"]?.jsonPrimitive?.booleanOrNull ?: true,
            githubOwner = optionalGithubOwnerArg(arguments),
            includeAgentContentRoots = arguments["includeAgentContentRoots"]?.jsonPrimitive?.booleanOrNull ?: true,
            codexProjectRootsOnly = arguments["codexProjectRootsOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
            activeWorkspaceRootsOnly = arguments["activeWorkspaceRootsOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
            requireMatchingGitHubRemote = arguments["requireMatchingGitHubRemote"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    internal fun buildRegistrationPlan(
        syncPlan: CodexWorkspaceSyncService.Plan,
        workspaceProjectPath: String?,
        broadStreamableHttpUrl: String = ClientConfigGenerator.getStreamableHttpUrl()
    ): CodexMcpRegistrationInstaller.Plan {
        return CodexMcpRegistrationInstaller.buildPlan(
            repoScopes = CodexWorkspaceSyncService.buildRegistrationScopes(syncPlan, workspaceProjectPath),
            broadStreamableHttpUrl = broadStreamableHttpUrl
        )
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val options = parseOptions(arguments)
        val installCodexMcp = arguments["installCodexMcp"]?.jsonPrimitive?.booleanOrNull ?: false

        val result = try {
            val prepared = CodexWorkspaceSyncService.prepare(project, options)
            val syncResult = if (options.dryRun) {
                CodexWorkspaceSyncService.buildResult(prepared, attached = emptyList(), detached = emptyList(), detachedModules = emptyList(), errors = emptyList())
            } else {
                edtAction {
                    ApplicationManager.getApplication().runWriteAction<CodexWorkspaceSyncResult> {
                        CodexWorkspaceSyncService.applyPrepared(project, prepared)
                    }
                }.also { CodexWorkspaceSyncService.persistAppliedChanges(project, it) }
            }
            if (installCodexMcp) {
                val registrationPlan = buildRegistrationPlan(prepared.plan, prepared.workspaceProjectPath)
                syncResult.copy(
                    codexMcpRegistration = CodexMcpRegistrationInstaller.install(
                        dryRun = options.dryRun,
                        plan = registrationPlan
                    )
                )
            } else {
                syncResult
            }
        } catch (e: Exception) {
            return createErrorResult(e.message ?: "Codex workspace sync failed.")
        }

        return createJsonResult(result)
    }

    private fun optionalGithubOwnerArg(arguments: JsonObject): String? {
        val raw = arguments["githubOwner"] ?: return null
        if (raw == JsonNull) return null
        return raw.jsonPrimitive.contentOrNull?.trim()
    }
}
