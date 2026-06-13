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
        Export Codex MCP install commands for the broad index server and every repo-scoped server currently published by the workspace.

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
        val scopes = RepoScopeRegistry.collectOpenRepoScopes()
        val servers = mutableListOf(
            RepoScopedClientServer(
                name = broadServerName,
                url = broadUrl
            )
        )

        for (scope in scopes.sortedBy { it.repoId }) {
            servers += RepoScopedClientServer(
                name = "$broadServerName-${scope.repoId}",
                url = ClientConfigGenerator.buildRepoScopedStreamableHttpUrl(broadUrl, scope.repoId),
                repoId = scope.repoId,
                repoRootPath = scope.repoRootPath
            )
        }

        return createJsonResult(
            RepoScopedClientConfigResult(
                client = client,
                servers = servers,
                installCommands = ClientConfigGenerator.buildRepoScopedCodexCommands(
                    broadStreamableHttpUrl = broadUrl,
                    broadServerName = broadServerName,
                    repoScopes = scopes
                ),
                message = "Exported broad plus ${scopes.size} repo-scoped Codex MCP server registrations."
            )
        )
    }
}
