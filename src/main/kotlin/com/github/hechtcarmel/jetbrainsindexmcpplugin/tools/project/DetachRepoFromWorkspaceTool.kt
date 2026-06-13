package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoWorkspaceMutator
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RepoWorkspaceResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

class DetachRepoFromWorkspaceTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.DETACH_REPO_FROM_WORKSPACE

    override val description = """
        Detach a repo content root from the current IntelliJ workspace by repo id.

        Parameters: repo_id (required, the id returned by ide_attach_repo_to_workspace), project_path (optional workspace project path).
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.REPO_ID, "Repo id to detach from the workspace.", required = true)
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val repoId = requiredStringArg(arguments, ParamNames.REPO_ID).getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: ${ParamNames.REPO_ID}")
        }

        val scope = edtAction {
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction<com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScope> {
                RepoWorkspaceMutator.detachContentRoot(project, repoId)
            }
        }
        RepoWorkspaceMutator.persistWorkspaceProject(project)
        McpServerService.getInstance().notifyEndpointListChanged()
        val scopedUrl = ClientConfigGenerator.buildRepoScopedStreamableHttpUrl(
            broadStreamableHttpUrl = ClientConfigGenerator.getStreamableHttpUrl(),
            repoId = scope.repoId
        )

        return createJsonResult(
            RepoWorkspaceResult(
                repoId = scope.repoId,
                repoRootPath = scope.repoRootPath,
                workspaceProjectPath = scope.workspaceProjectPath,
                repoScopedStreamableHttpUrl = scopedUrl,
                message = "Detached repo '${scope.repoRootPath}' from '$repoId'."
            )
        )
    }
}
