package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeContext
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

class AttachRepoToWorkspaceToolUnitTest : TestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testExecuteDoesNotReportSuccessUntilAttachedRepoIsDiscoverableAsRepoScope() = runBlocking {
        withTempDirectory { repoPath ->
            val normalizedRepoPath = ProjectResolver.normalizePath(repoPath.toString())
            val project = workspaceProject()
            val tool = AttachRepoToWorkspaceTool(
                contentRootsProvider = { emptyList() },
                attachToProjectOverride = { _, _ -> true },
                ensureGitMappingOverride = { _, _ -> },
                repoScopesProvider = { emptyList() }
            )

            val result = tool.execute(project, attachArgs(normalizedRepoPath))

            assertTrue("Attach should fail until repo-scope discovery can see the repo", result.isError)
            assertTrue(
                (result.content.single() as ContentBlock.Text).text.contains("not discoverable as a repo-scoped Git root")
            )
        }
    }

    fun testExecuteReportsSuccessAfterAttachWhenRepoScopeDiscoveryCanSeeRepo() = runBlocking {
        withTempDirectory { repoPath ->
            val normalizedRepoPath = ProjectResolver.normalizePath(repoPath.toString())
            val project = workspaceProject()
            var gitMappingEnsured = false
            val tool = AttachRepoToWorkspaceTool(
                contentRootsProvider = { emptyList() },
                attachToProjectOverride = { _, attachedPath ->
                    assertEquals(normalizedRepoPath, ProjectResolver.normalizePath(attachedPath.toString()))
                    true
                },
                ensureGitMappingOverride = { _, mappedPath ->
                    assertEquals(normalizedRepoPath, mappedPath)
                    gitMappingEnsured = true
                },
                repoScopesProvider = {
                    if (gitMappingEnsured) {
                        listOf(RepoScopeContext(repoId = "attached-repo", gitRootPath = normalizedRepoPath))
                    } else {
                        emptyList()
                    }
                }
            )

            val result = tool.execute(project, attachArgs(normalizedRepoPath))

            assertFalse("Tool returned error: ${result.content}", result.isError)
            val payload = json.decodeFromString<AttachRepoToWorkspaceResult>(
                (result.content.single() as ContentBlock.Text).text
            )
            assertTrue(payload.attached)
            assertFalse(payload.alreadyAttached)
            assertEquals(normalizedRepoPath, payload.repoPath)
        }
    }

    private fun workspaceProject(): Project =
        mockk<Project>(relaxed = true) {
            every { name } returns "workspace"
            every { basePath } returns "/workspace/master"
        }

    private fun attachArgs(repoPath: String) = buildJsonObject {
        put("repo_path", repoPath)
    }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("attach-repo-tool-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
