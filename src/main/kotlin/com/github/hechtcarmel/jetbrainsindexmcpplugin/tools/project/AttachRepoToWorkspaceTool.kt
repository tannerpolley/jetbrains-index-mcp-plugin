package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.AttachRepoToWorkspaceResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

class AttachRepoToWorkspaceTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false
    override val name = ToolNames.ATTACH_REPO_TO_WORKSPACE
    override val description = """
        Attach an existing repository root to the active workspace and publish its deterministic repo-scoped MCP route.
        Parameters: repo_path (required absolute repository root), project_path (optional workspace project).
    """.trimIndent()
    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.REPO_PATH, "Absolute path to the repository root to attach.", required = true)
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val repoPath = requiredStringArg(arguments, ParamNames.REPO_PATH)
            .getOrElse { return createErrorResult(it.message ?: "Missing required parameter: ${ParamNames.REPO_PATH}") }
        val entry = RepoScopeRegistry.getInstance().attach(repoPath)
        return createJsonResult(
            AttachRepoToWorkspaceResult(
                repoId = entry.repoId,
                repoPath = entry.rootPath
            )
        )
    }
}
