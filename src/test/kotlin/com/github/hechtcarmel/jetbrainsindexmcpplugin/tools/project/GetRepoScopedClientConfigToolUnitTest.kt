package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeContext
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GetRepoScopedClientConfigToolUnitTest : TestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testExecuteReturnsDeterministicBroadPlusScopedCodexConfig() = runBlocking {
        val settings = McpSettings()
        settings.serverHost = "127.0.0.1"
        settings.serverPort = 29170

        val tool = GetRepoScopedClientConfigTool(
            repoScopeProvider = {
                listOf(
                    RepoScopeContext(repoId = "beta", gitRootPath = "/workspace/beta"),
                    RepoScopeContext(repoId = "alpha", gitRootPath = "/workspace/alpha")
                )
            },
            settingsProvider = { settings }
        )

        val result = tool.execute(
            project = mockk<Project>(relaxed = true),
            arguments = buildJsonObject {
                put("client", "codex_cli")
                put("platform", "posix")
            }
        )

        assertFalse("Tool returned error: ${result.content}", result.isError)
        val payload = json.decodeFromString<RepoScopedClientConfigResult>(
            (result.content.single() as ContentBlock.Text).text
        )

        assertEquals("codex_cli", payload.client)
        assertEquals("posix", payload.platform)
        assertEquals("intellij-index", payload.broadServerName)
        assertEquals("http://127.0.0.1:29170/index-mcp/streamable-http", payload.broadServerUrl)
        assertEquals(listOf("alpha", "beta"), payload.repoServers.map { it.repoId })
        assertEquals(listOf("intellij-index-alpha", "intellij-index-beta"), payload.repoServers.map { it.serverName })
        assertEquals(listOf("/workspace/alpha", "/workspace/beta"), payload.repoServers.map { it.gitRootPath })
        assertTrue(payload.installCommand.startsWith("codex mcp remove intellij-index >/dev/null 2>&1"))
        assertTrue(payload.installCommand.contains("codex mcp add intellij-index-alpha --url http://127.0.0.1:29170/index-mcp/repos/alpha/streamable-http"))
    }

    fun testExecuteScopesRepoServersToResolvedProject() = runBlocking {
        val settings = McpSettings()
        settings.serverHost = "127.0.0.1"
        settings.serverPort = 29170

        val executingProject = mockk<Project>(relaxed = true) {
            every { name } returns "workspace-a"
            every { basePath } returns "/workspace/a"
        }
        val projectSeenByProvider = mutableListOf<Project>()

        val tool = GetRepoScopedClientConfigTool(
            repoScopesProvider = { project ->
                projectSeenByProvider += project
                listOf(RepoScopeContext(repoId = "workspace-a", gitRootPath = "/workspace/a"))
            },
            settingsProvider = { settings }
        )

        val result = tool.execute(
            project = executingProject,
            arguments = buildJsonObject {
                put("client", "codex_cli")
                put("platform", "posix")
            }
        )

        assertFalse("Tool returned error: ${result.content}", result.isError)
        assertSame(executingProject, projectSeenByProvider.single())
        val payload = json.decodeFromString<RepoScopedClientConfigResult>(
            (result.content.single() as ContentBlock.Text).text
        )

        assertEquals(listOf("workspace-a"), payload.repoServers.map { it.repoId })
        assertFalse(payload.installCommand.contains("unrelated-window"))
    }
}
