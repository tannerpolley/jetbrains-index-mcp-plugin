package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RepoScopedClientConfigResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RepoScopedClientServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

class GetRepoScopedClientConfigTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.GET_REPO_SCOPED_CLIENT_CONFIG

    override val description = """
        Export Codex MCP install commands for the broad index server and every currently open repo-scoped index endpoint.

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

        val broadServerName = ClientConfigGenerator.getDefaultServerName()
        val broadUrl = ClientConfigGenerator.getStreamableHttpUrl()
        val broadServer = RepoScopedClientServer(
            name = broadServerName,
            url = broadUrl
        )
        val repoScopes = RepoScopeRegistry.collectOpenIndexScopes()
            .sortedBy { it.repoId }
        val repoServers = repoScopes
            .map { scope ->
                RepoScopedClientServer(
                    name = "$broadServerName-${scope.repoId}",
                    url = ClientConfigGenerator.buildRepoScopedStreamableHttpUrl(broadUrl, scope.repoId),
                    repoId = scope.repoId,
                    repoRootPath = scope.repoRootPath
                )
            }
        val servers = listOf(broadServer) + repoServers
        val commands = ClientConfigGenerator.buildRepoScopedCodexCommands(
            broadStreamableHttpUrl = broadUrl,
            broadServerName = broadServerName,
            repoScopes = repoScopes
        )

        return createJsonResult(
            RepoScopedClientConfigResult(
                client = client,
                servers = servers,
                installCommands = commands,
                message = "Exported broad plus ${repoServers.size} repo-scoped Codex MCP server registrations."
            )
        )
    }
}
