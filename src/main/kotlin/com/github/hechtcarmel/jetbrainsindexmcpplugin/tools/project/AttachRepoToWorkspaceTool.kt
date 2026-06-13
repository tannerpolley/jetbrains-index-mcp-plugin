package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
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

class AttachRepoToWorkspaceTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.ATTACH_REPO_TO_WORKSPACE

    override val description = """
        Attach a local repo directory as a content root in the current IntelliJ workspace and return its repo-scoped MCP identity.

        Parameters: repo_path (required, absolute local repo directory), project_path (optional workspace project path).
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.REPO_PATH, "Absolute path to the repo directory to attach.", required = true)
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val repoPath = requiredStringArg(arguments, ParamNames.REPO_PATH).getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: ${ParamNames.REPO_PATH}")
        }

        val scope = try {
            edtAction {
                com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction<com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScope> {
                    RepoWorkspaceMutator.attachContentRoot(project, repoPath)
                }
            }
        } catch (e: IllegalArgumentException) {
            return createErrorResult(e.message ?: "Could not attach repo root.")
        } catch (e: IllegalStateException) {
            return createErrorResult(e.message ?: "Could not attach repo root.")
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
                message = "Attached repo '${scope.repoRootPath}' as '${McpConstants.getServerName()}-${scope.repoId}'."
            )
        )
    }
}
