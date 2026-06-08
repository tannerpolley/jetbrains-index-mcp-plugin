package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.GetRepoScopedClientConfigResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

class GetRepoScopedClientConfigTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false
    override val name = ToolNames.GET_REPO_SCOPED_CLIENT_CONFIG
    override val description = """
        Export broad-plus-repo-scoped Codex MCP registration commands for all published workspace repo ids.
        Parameters: project_path (optional workspace project).
    """.trimIndent()
    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val broadUrl = ClientConfigGenerator.getStreamableHttpUrl()
        val baseName = ClientConfigGenerator.getDefaultServerName()
        val commands = RepoScopeRegistry.getInstance().entries().map { entry ->
            ClientConfigGenerator.buildRepoScopedCodexCommand(
                broadStreamableHttpUrl = broadUrl,
                baseServerName = baseName,
                repoId = entry.repoId
            )
        }
        return createJsonResult(
            GetRepoScopedClientConfigResult(
                broadServerName = baseName,
                broadStreamableHttpUrl = broadUrl,
                codexCommands = commands
            )
        )
    }
}
