package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RepoWorkspaceResult
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files

class AttachRepoToWorkspaceToolUnitTest : BasePlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }
    private val tempDirs = mutableListOf<File>()

    override fun tearDown() {
        try {
            tempDirs.forEach { it.deleteRecursively() }
        } finally {
            super.tearDown()
        }
    }

    fun testAttachRepoReturnsPublishedRepoId() = runBlocking {
        val repoRoot = createGitRepoDir(module.name)

        val result = AttachRepoToWorkspaceTool().execute(
            project,
            buildJsonObject {
                put("repo_path", repoRoot.absolutePath)
            }
        )
        val payload = decodeResult(result)

        assertFalse(result.isError)
        assertEquals(module.name, payload.repoId)
        assertEquals(repoRoot.absolutePath.replace('\\', '/'), payload.repoRootPath)
        assertTrue(payload.repoScopedStreamableHttpUrl.contains("/index-mcp/repos/${payload.repoId}/streamable-http"))
    }

    fun testAttachRepoCreatesVisibleModuleForRepoRoot() = runBlocking {
        val repoRoot = createGitRepoDir(module.name)

        val result = AttachRepoToWorkspaceTool().execute(
            project,
            buildJsonObject {
                put("repo_path", repoRoot.absolutePath)
            }
        )
        val payload = decodeResult(result)
        val attachedModule = ModuleManager.getInstance(project).findModuleByName(payload.repoId)
        val contentRoots = attachedModule
            ?.let { ModuleRootManager.getInstance(it).contentRoots.map { root -> root.path.replace('\\', '/') } }
            ?: emptyList()

        assertFalse(result.isError)
        assertNotNull("Attach should create or reuse a visible module named by repo id", attachedModule)
        assertTrue(contentRoots.contains(repoRoot.absolutePath.replace('\\', '/')))
    }

    fun testAttachRejectsDirectoryThatIsNotGitRepoRoot() = runBlocking {
        val plainDirectory = Files.createTempDirectory("plain-directory").toFile().also { tempDirs += it }

        val result = AttachRepoToWorkspaceTool().execute(
            project,
            buildJsonObject {
                put("repo_path", plainDirectory.absolutePath)
            }
        )
        val text = (result.content.single() as ContentBlock.Text).text

        assertTrue(result.isError)
        assertTrue(text.contains("must be a Git repo root"))
    }

    private fun createGitRepoDir(prefix: String): File {
        val parent = Files.createTempDirectory("repo-parent").toFile().also { tempDirs += it }
        val repoRoot = File(parent, prefix)
        repoRoot.mkdir()
        File(repoRoot, ".git").mkdir()
        return repoRoot
    }

    private fun decodeResult(result: ToolCallResult): RepoWorkspaceResult {
        val text = (result.content.single() as ContentBlock.Text).text
        return json.decodeFromString(RepoWorkspaceResult.serializer(), text)
    }
}
