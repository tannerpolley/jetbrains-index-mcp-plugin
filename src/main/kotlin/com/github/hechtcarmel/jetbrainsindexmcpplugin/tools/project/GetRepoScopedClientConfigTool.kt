package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.GetRepoScopedClientConfigResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RepoScopedClientConfig
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
        val scopedServers = RepoScopeRegistry.getInstance().entries().map { entry ->
            val serverName = ClientConfigGenerator.buildRepoScopedServerName(baseName, entry.repoId)
            val streamableHttpUrl = ClientConfigGenerator.buildRepoScopedStreamableHttpUrl(broadUrl, entry.repoId)
            RepoScopedClientConfig(
                repoId = entry.repoId,
                repoPath = entry.rootPath,
                serverName = serverName,
                streamableHttpUrl = streamableHttpUrl,
                codexCommand = ClientConfigGenerator.buildCodexCommand(
                    serverUrl = streamableHttpUrl,
                    serverName = serverName
                )
            )
        }
        return createJsonResult(
            GetRepoScopedClientConfigResult(
                broadServerName = baseName,
                broadStreamableHttpUrl = broadUrl,
                scopedServers = scopedServers,
                codexCommands = scopedServers.map { it.codexCommand }
            )
        )
    }
}
