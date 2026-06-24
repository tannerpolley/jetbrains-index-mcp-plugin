package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.CodexMcpRegistrationInstaller
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.CodexWorkspaceSyncService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RepoScopedClientConfigResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

class GetRepoScopedClientConfigTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.GET_REPO_SCOPED_CLIENT_CONFIG

    override val description = """
        Export Codex MCP install commands for the broad index server and the Codex-active GitHub-owner-approved repo-scoped servers in a master Workspace project.

        Parameters: client (optional, currently "codex"), project_path (optional workspace project path).
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .enumProperty(ParamNames.CLIENT, "Client config format to export.", listOf("codex"))
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val client = optionalStringArg(arguments, ParamNames.CLIENT) ?: "codex"
        if (client != "codex") {
            return createErrorResult("Unsupported client '$client'. Supported client: codex.")
        }

        val plan = if (CodexWorkspaceSyncService.shouldAutoSyncProject(project)) {
            val prepared = CodexWorkspaceSyncService.prepare(project, CodexWorkspaceSyncService.Options(dryRun = true))
            CodexMcpRegistrationInstaller.buildPlan(
                repoScopes = CodexWorkspaceSyncService.buildRegistrationScopes(
                    prepared.plan,
                    prepared.workspaceProjectPath
                )
            )
        } else {
            CodexMcpRegistrationInstaller.buildPlan()
        }

        return createJsonResult(
            RepoScopedClientConfigResult(
                client = client,
                servers = plan.servers,
                installCommands = plan.commands,
                message = "Exported broad plus ${plan.servers.count { it.repoId != null }} repo-scoped Codex MCP server registrations."
            )
        )
    }
}
