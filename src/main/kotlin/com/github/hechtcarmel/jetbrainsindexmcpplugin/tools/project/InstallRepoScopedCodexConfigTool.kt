package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.CodexMcpRegistrationInstaller
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

class InstallRepoScopedCodexConfigTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.INSTALL_REPO_SCOPED_CODEX_CONFIG

    override val description = """
        Install Codex MCP registrations for the broad index server and every repo-scoped server currently published by the workspace.

        Parameters: dryRun (optional, default false), project_path (optional workspace project path).
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .booleanProperty("dryRun", "Preview generated codex mcp commands without running them. Default: false.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val dryRun = arguments["dryRun"]?.jsonPrimitive?.booleanOrNull ?: false
        val result = try {
            CodexMcpRegistrationInstaller.install(dryRun = dryRun)
        } catch (e: Exception) {
            return createErrorResult(e.message ?: "Codex MCP registration install failed.")
        }
        return createJsonResult(result)
    }
}
