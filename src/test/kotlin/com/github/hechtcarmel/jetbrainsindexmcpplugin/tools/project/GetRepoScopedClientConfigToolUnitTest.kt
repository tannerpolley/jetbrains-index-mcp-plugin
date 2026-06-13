package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RepoScopedClientConfigResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RepoWorkspaceResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files

class GetRepoScopedClientConfigToolUnitTest : BasePlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }
    private val tempDirs = mutableListOf<File>()

    override fun tearDown() {
        try {
            tempDirs.forEach { it.deleteRecursively() }
        } finally {
            super.tearDown()
        }
    }

    fun testClientConfigIncludesAttachedRepoServer() = runBlocking {
        val repoRoot = createGitRepoDir(module.name)
        val attached = AttachRepoToWorkspaceTool().execute(
            project,
            buildJsonObject {
                put("repo_path", repoRoot.absolutePath)
            }
        )
        val attachedPayload = decodeWorkspaceResult(attached)

        val result = GetRepoScopedClientConfigTool().execute(project, buildJsonObject { })
        val payload = decodeConfigResult(result)
        val broadServerName = ClientConfigGenerator.getDefaultServerName()

        assertFalse(result.isError)
        assertTrue(payload.servers.any { it.name == broadServerName })
        assertTrue(payload.servers.any { it.name == "$broadServerName-${attachedPayload.repoId}" })
        assertTrue(payload.installCommands.any { it.contains("$broadServerName-${attachedPayload.repoId}") })
    }

    private fun createGitRepoDir(prefix: String): File {
        val parent = Files.createTempDirectory("repo-parent").toFile().also { tempDirs += it }
        val repoRoot = File(parent, prefix)
        repoRoot.mkdir()
        File(repoRoot, ".git").mkdir()
        return repoRoot
    }

    private fun decodeWorkspaceResult(result: ToolCallResult): RepoWorkspaceResult {
        val text = (result.content.single() as ContentBlock.Text).text
        return json.decodeFromString(RepoWorkspaceResult.serializer(), text)
    }

    private fun decodeConfigResult(result: ToolCallResult): RepoScopedClientConfigResult {
        val text = (result.content.single() as ContentBlock.Text).text
        return json.decodeFromString(RepoScopedClientConfigResult.serializer(), text)
    }
}
