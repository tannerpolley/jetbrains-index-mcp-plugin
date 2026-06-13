package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RepoWorkspaceResult
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files

class DetachRepoFromWorkspaceToolUnitTest : BasePlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }
    private val tempDirs = mutableListOf<File>()

    override fun tearDown() {
        try {
            tempDirs.forEach { it.deleteRecursively() }
        } finally {
            super.tearDown()
        }
    }

    fun testDetachRepoRemovesAttachedRepoId() = runBlocking {
        val repoRoot = createGitRepoDir(module.name)
        val attached = AttachRepoToWorkspaceTool().execute(
            project,
            buildJsonObject {
                put("repo_path", repoRoot.absolutePath)
            }
        )
        val attachedPayload = decodeResult(attached)
        assertNotNull(RepoScopeRegistry.scopeForPath(repoRoot.absolutePath))

        val detached = DetachRepoFromWorkspaceTool().execute(
            project,
            buildJsonObject {
                put("repo_id", attachedPayload.repoId)
            }
        )
        val detachedPayload = decodeResult(detached)

        assertFalse(detached.isError)
        assertEquals(attachedPayload.repoId, detachedPayload.repoId)
        assertNull(RepoScopeRegistry.scopeForPath(repoRoot.absolutePath))
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
