package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeContext
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class GetRepoScopedClientConfigTool internal constructor(
    private val repoScopeProvider: () -> List<RepoScopeContext> = DEFAULT_REPO_SCOPE_PROVIDER,
    private val settingsProvider: () -> McpSettings = { McpSettings.getInstance() }
) : AbstractMcpTool() {

    private var serverNameProvider: () -> String = ::defaultServerName
    private var currentPlatformProvider: () -> ClientConfigGenerator.CommandPlatform = ::currentPlatform
    private var projectRepoScopesProvider: ((Project) -> List<RepoScopeContext>)? =
        if (repoScopeProvider === DEFAULT_REPO_SCOPE_PROVIDER) {
            { project -> McpServerService.getInstance().listRepoScopes(project) }
        } else {
            null
        }

    internal constructor(
        repoScopesProvider: (Project) -> List<RepoScopeContext>,
        settingsProvider: () -> McpSettings
    ) : this(
        repoScopeProvider = DEFAULT_REPO_SCOPE_PROVIDER,
        settingsProvider = settingsProvider
    ) {
        this.projectRepoScopesProvider = repoScopesProvider
    }

    internal constructor(
        repoScopesProvider: () -> List<RepoScopeContext>,
        serverNameProvider: () -> String,
        serverHostProvider: () -> String,
        serverPortProvider: () -> Int,
        currentPlatformProvider: () -> ClientConfigGenerator.CommandPlatform
    ) : this(
        repoScopeProvider = repoScopesProvider,
        settingsProvider = {
            McpSettings().also {
                it.serverHost = serverHostProvider()
                it.serverPort = serverPortProvider()
            }
        }
    ) {
        this.serverNameProvider = serverNameProvider
        this.currentPlatformProvider = currentPlatformProvider
    }

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.GET_REPO_SCOPED_CLIENT_CONFIG

    override val description = """
        Export deterministic broad-plus-repo-scoped client configuration for coding agents after workspace repo attachment or refresh.
        Parameters: project_path (optional), client (optional, codex_cli), platform (optional, current/posix/windows).
        Use after ide_attach_repo_to_workspace or when workspace Git roots changed.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .enumProperty(ParamNames.CLIENT, "Client config format to export.", listOf(CLIENT_CODEX_CLI))
        .enumProperty(ParamNames.PLATFORM, "Command platform for shell syntax.", listOf(PLATFORM_CURRENT, PLATFORM_POSIX, PLATFORM_WINDOWS))
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val client = optionalStringArg(arguments, ParamNames.CLIENT) ?: CLIENT_CODEX_CLI
        if (client != CLIENT_CODEX_CLI) {
            return createErrorResult("Unsupported client '$client'. Supported client: $CLIENT_CODEX_CLI.")
        }

        val platformName = optionalStringArg(arguments, ParamNames.PLATFORM) ?: PLATFORM_CURRENT
        val platform = resolvePlatform(platformName)
            ?: return createErrorResult("Unsupported platform '$platformName'. Supported platforms: $PLATFORM_CURRENT, $PLATFORM_POSIX, $PLATFORM_WINDOWS.")

        val serverName = serverNameProvider()
        val settings = settingsProvider()
        val host = settings.serverHost
        val port = settings.serverPort
        val repoScopes = (projectRepoScopesProvider ?: { _: Project -> repoScopeProvider() })(project)
        val targets = ClientConfigGenerator.buildRepoScopedCodexTargets(
            repoScopes = repoScopes,
            host = host,
            port = port,
            baseServerName = serverName
        )

        val broadServerUrl = "http://$host:$port${McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH}"
        val installCommand = try {
            ClientConfigGenerator.buildBroadAndRepoScopedCodexCommand(
                broadServerName = serverName,
                broadServerUrl = broadServerUrl,
                targets = targets,
                platform = platform
            )
        } catch (e: IllegalStateException) {
            return createErrorResult(e.message ?: "Repo-scoped client configuration could not be generated.")
        }
        val scopesById = repoScopes.associateBy { it.repoId }

        return createJsonResult(
            RepoScopedClientConfigResult(
                client = client,
                platform = platform.toWireValue(),
                broadServerName = serverName,
                broadServerUrl = broadServerUrl,
                installCommand = installCommand,
                repoServers = targets.map { target ->
                    RepoScopedClientConfigTarget(
                        repoId = target.repoId,
                        serverName = target.serverName,
                        serverUrl = target.serverUrl,
                        gitRootPath = scopesById.getValue(target.repoId).gitRootPath
                    )
                },
                projectName = project.name,
                projectPath = project.basePath
            )
        )
    }

    private fun resolvePlatform(value: String): ClientConfigGenerator.CommandPlatform? =
        when (value) {
            PLATFORM_CURRENT -> currentPlatformProvider()
            PLATFORM_POSIX -> ClientConfigGenerator.CommandPlatform.POSIX
            PLATFORM_WINDOWS -> ClientConfigGenerator.CommandPlatform.WINDOWS
            else -> null
        }

    private fun ClientConfigGenerator.CommandPlatform.toWireValue(): String =
        when (this) {
            ClientConfigGenerator.CommandPlatform.POSIX -> PLATFORM_POSIX
            ClientConfigGenerator.CommandPlatform.WINDOWS -> PLATFORM_WINDOWS
        }

    private companion object {
        const val CLIENT_CODEX_CLI = "codex_cli"
        const val PLATFORM_CURRENT = "current"
        const val PLATFORM_POSIX = "posix"
        const val PLATFORM_WINDOWS = "windows"
        val DEFAULT_REPO_SCOPE_PROVIDER: () -> List<RepoScopeContext> = {
            McpServerService.getInstance().listRepoScopes()
        }
    }
}

private fun defaultServerName(): String =
    if (ApplicationManager.getApplication() == null) {
        "intellij-index"
    } else {
        ClientConfigGenerator.getDefaultServerName()
    }

private fun currentPlatform(): ClientConfigGenerator.CommandPlatform =
    if (SystemInfo.isWindows) {
        ClientConfigGenerator.CommandPlatform.WINDOWS
    } else {
        ClientConfigGenerator.CommandPlatform.POSIX
    }

@Serializable
data class RepoScopedClientConfigResult(
    val client: String,
    val platform: String,
    val broadServerName: String,
    val broadServerUrl: String,
    val installCommand: String,
    val repoServers: List<RepoScopedClientConfigTarget>,
    val projectName: String,
    val projectPath: String?
)

@Serializable
data class RepoScopedClientConfigTarget(
    val repoId: String,
    val serverName: String,
    val serverUrl: String,
    val gitRootPath: String
)
