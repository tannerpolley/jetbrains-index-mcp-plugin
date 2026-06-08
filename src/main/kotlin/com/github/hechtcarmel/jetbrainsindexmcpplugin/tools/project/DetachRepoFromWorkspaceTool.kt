package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.WorkspaceRepoManager
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DetachRepoFromWorkspaceResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

class DetachRepoFromWorkspaceTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false
    override val name = ToolNames.DETACH_REPO_FROM_WORKSPACE
    override val description = """
        Detach a repository from the active workspace by its published repo id and remove its repo-scoped MCP route.
        Parameters: repo_id (required), project_path (optional workspace project).
    """.trimIndent()
    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.REPO_ID, "Published repo id to detach from the workspace.", required = true)
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val repoId = requiredStringArg(arguments, ParamNames.REPO_ID)
            .getOrElse { return createErrorResult(it.message ?: "Missing required parameter: ${ParamNames.REPO_ID}") }
        val entry = RepoScopeRegistry.getInstance().find(repoId)
        val workspaceDetached = entry?.let { WorkspaceRepoManager.detachContentRoot(project, it.rootPath) } ?: false
        val registryDetached = RepoScopeRegistry.getInstance().detach(repoId)
        return createJsonResult(
            DetachRepoFromWorkspaceResult(
                repoId = repoId,
                detached = workspaceDetached || registryDetached
            )
        )
    }
}
