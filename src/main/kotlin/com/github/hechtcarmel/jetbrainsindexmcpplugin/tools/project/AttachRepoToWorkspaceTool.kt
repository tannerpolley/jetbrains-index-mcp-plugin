package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeContext
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.projectImport.ProjectAttachProcessor
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class AttachRepoToWorkspaceTool internal constructor(
    private val contentRootsProvider: (Project) -> List<String> = { ProjectUtils.getModuleContentRoots(it) },
    private val attachToProjectOverride: (suspend (Project, Path) -> Boolean)? = null,
    private val ensureGitMappingOverride: (suspend (Project, String) -> Unit)? = null,
    private val repoScopesProvider: (Project) -> List<RepoScopeContext> = { McpServerService.getInstance().listRepoScopes(it) },
    private val repoScopePollAttempts: Int = DEFAULT_REPO_SCOPE_POLL_ATTEMPTS,
    private val repoScopePollDelayMillis: Long = DEFAULT_REPO_SCOPE_POLL_DELAY_MILLIS
) : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.ATTACH_REPO_TO_WORKSPACE

    override val description = """
        Attach an existing repository directory to the current IDE workspace so repo-scoped MCP endpoints can target it. Use when a coding agent opens or creates a sibling repository and needs the IDE to index it without closing the current workspace.
        Parameters: repo_path (required absolute directory path), project_path (optional workspace project path).
        Returns: attached status, repo path, project name, and current workspace content roots.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(
            ParamNames.REPO_PATH,
            "Absolute path to an existing repository directory to attach to the current IDE workspace.",
            required = true
        )
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val repoPathText = arguments[ParamNames.REPO_PATH]?.jsonPrimitive?.contentOrNull
            ?: return createErrorResult("Missing required parameter: ${ParamNames.REPO_PATH}")

        val repoPath = Path.of(repoPathText).toAbsolutePath().normalize()
        if (!Files.isDirectory(repoPath)) {
            return createErrorResult("Repository path does not exist or is not a directory: $repoPath")
        }

        val normalizedRepoPath = ProjectResolver.normalizePath(repoPath.toString())
        val alreadyAttached = contentRootsProvider(project)
            .map(ProjectResolver::normalizePath)
            .any { it == normalizedRepoPath || it.startsWith("$normalizedRepoPath/") }

        val attachAction = attachToProjectOverride ?: ::attachToProject
        if (!alreadyAttached && !attachAction(project, repoPath)) {
            return createErrorResult("No IDE project attach processor accepted repository path: $normalizedRepoPath")
        }

        val ensureGitMapping = ensureGitMappingOverride ?: ::ensureGitMapping
        ensureGitMapping(project, normalizedRepoPath)

        if (!waitForRepoScope(project, normalizedRepoPath)) {
            return createErrorResult("Attached repository is not discoverable as a repo-scoped Git root: $normalizedRepoPath")
        }

        return createJsonResult(
            AttachRepoToWorkspaceResult(
                attached = !alreadyAttached,
                alreadyAttached = alreadyAttached,
                repoPath = normalizedRepoPath,
                projectName = project.name,
                projectPath = project.basePath,
                contentRoots = contentRootsProvider(project).map(ProjectResolver::normalizePath)
            )
        )
    }

    private suspend fun attachToProject(project: Project, repoPath: Path): Boolean = edtAction {
        val processor = ProjectAttachProcessor.getProcessor(project, repoPath, null)
            ?: return@edtAction false
        processor.attachToProject(project, repoPath, null)
    }

    private suspend fun ensureGitMapping(project: Project, normalizedRepoPath: String) {
        edtAction {
            val vcsManager = ProjectLevelVcsManager.getInstance(project)
            val mappings = vcsManager.getDirectoryMappings()
            val hasGitMapping = mappings.any { mapping ->
                ProjectResolver.normalizePath(mapping.directory) == normalizedRepoPath &&
                    mapping.vcs.equals(GIT_VCS_NAME, ignoreCase = true)
            }

            if (!hasGitMapping) {
                vcsManager.setDirectoryMappings(mappings + VcsDirectoryMapping(normalizedRepoPath, GIT_VCS_NAME))
            }
        }
    }

    private suspend fun waitForRepoScope(project: Project, normalizedRepoPath: String): Boolean {
        repeat(repoScopePollAttempts.coerceAtLeast(1)) { attempt ->
            if (isRepoScopeVisible(project, normalizedRepoPath)) {
                return true
            }
            if (attempt < repoScopePollAttempts - 1) {
                delay(repoScopePollDelayMillis)
            }
        }

        return false
    }

    private fun isRepoScopeVisible(project: Project, normalizedRepoPath: String): Boolean =
        repoScopesProvider(project).any { it.normalizedGitRootPath == normalizedRepoPath }

    private companion object {
        const val GIT_VCS_NAME = "Git"
        const val DEFAULT_REPO_SCOPE_POLL_ATTEMPTS = 5
        const val DEFAULT_REPO_SCOPE_POLL_DELAY_MILLIS = 100L
    }
}

@Serializable
data class AttachRepoToWorkspaceResult(
    val attached: Boolean,
    val alreadyAttached: Boolean,
    val repoPath: String,
    val projectName: String,
    val projectPath: String?,
    val contentRoots: List<String>
)
