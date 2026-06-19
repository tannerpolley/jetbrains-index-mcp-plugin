package com.github.hechtcarmel.jetbrainsindexmcpplugin.integration

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.JsonRpcHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeContext
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SearchTextResult
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RepoScopedAgentWorkspaceIntegrationTest : BasePlatformTestCase() {

    private lateinit var handler: JsonRpcHandler
    private lateinit var toolRegistry: ToolRegistry

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        toolRegistry = ToolRegistry()
        toolRegistry.registerBuiltInTools()
        handler = JsonRpcHandler(toolRegistry)
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testRepoScopedEndpointFindFileKeepsThreeRepoWorkspaceBoundaries() = runBlocking {
        val fixture = createAgentWorkspaceFixture()

        fixture.repos.forEach { targetRepo ->
            val result = callRepoScopedTool(
                toolName = ToolNames.FIND_FILE,
                repoScope = targetRepo.scope,
                arguments = buildJsonObject {
                    put("query", "SharedScopeProbe")
                    put("pageSize", 20)
                }
            )

            assertFalse("Repo-scoped find file should succeed for ${targetRepo.repoName}", result.isError)
            val files = json.decodeFromString<FindFileResult>((result.content.first() as ContentBlock.Text).text).files

            assertTrue(
                "Scoped file search should keep ${targetRepo.expectedRelativePath}; got ${files.map { it.path }}",
                files.any { it.path.endsWith(targetRepo.expectedRelativePath) }
            )

            fixture.repos.filter { it.repoName != targetRepo.repoName }.forEach { otherRepo ->
                assertFalse(
                    "Scoped file search for ${targetRepo.repoName} should exclude ${otherRepo.repoName}; got ${files.map { it.path }}",
                    files.any { it.path.endsWith(otherRepo.expectedRelativePath) }
                )
            }
        }
    }

    fun testRepoScopedEndpointSearchTextKeepsThreeRepoWorkspaceBoundaries() = runBlocking {
        val fixture = createAgentWorkspaceFixture()

        fixture.repos.forEach { targetRepo ->
            val result = callRepoScopedTool(
                toolName = ToolNames.SEARCH_TEXT,
                repoScope = targetRepo.scope,
                arguments = buildJsonObject {
                    put("query", "workspacescopetoken")
                    put("regex", true)
                    put("pageSize", 20)
                }
            )

            assertFalse("Repo-scoped search text should succeed for ${targetRepo.repoName}", result.isError)
            val matches = json.decodeFromString<SearchTextResult>((result.content.first() as ContentBlock.Text).text).matches

            assertTrue(
                "Scoped text search should keep ${targetRepo.expectedRelativePath}; got ${matches.map { it.file }}",
                matches.any { it.file.endsWith(targetRepo.expectedRelativePath) }
            )

            fixture.repos.filter { it.repoName != targetRepo.repoName }.forEach { otherRepo ->
                assertFalse(
                    "Scoped text search for ${targetRepo.repoName} should exclude ${otherRepo.repoName}; got ${matches.map { it.file }}",
                    matches.any { it.file.endsWith(otherRepo.expectedRelativePath) }
                )
            }
        }
    }

    fun testRepoScopedEndpointFileStructureKeepsThreeRepoWorkspaceBoundaries() = runBlocking {
        val fixture = createAgentWorkspaceFixture()

        fixture.repos.forEach { targetRepo ->
            val result = callRepoScopedTool(
                toolName = ToolNames.FILE_STRUCTURE,
                repoScope = targetRepo.scope,
                arguments = buildJsonObject {
                    put("file", targetRepo.structureRelativePath)
                }
            )

            assertFalse(
                "Repo-scoped file structure should succeed for ${targetRepo.repoName}: ${result.content}",
                result.isError
            )
            val structure = json.decodeFromString<FileStructureResult>((result.content.first() as ContentBlock.Text).text)

            assertTrue(
                "Scoped file structure should keep ${targetRepo.structureMarker}; got ${structure.structure}",
                structure.structure.contains(targetRepo.structureMarker)
            )

            fixture.repos.filter { it.repoName != targetRepo.repoName }.forEach { otherRepo ->
                assertFalse(
                    "Scoped file structure for ${targetRepo.repoName} should exclude ${otherRepo.structureMarker}; got ${structure.structure}",
                    structure.structure.contains(otherRepo.structureMarker)
                )
            }
        }
    }

    private suspend fun callRepoScopedTool(
        toolName: String,
        repoScope: RepoScopeContext,
        arguments: kotlinx.serialization.json.JsonObject
    ): ToolCallResult {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = "tools/call",
            params = buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            }
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION,
            repoScope = repoScope
        ) ?: error("Expected JSON-RPC response")

        val response = json.decodeFromString<JsonRpcResponse>(responseJson)
        assertNull("Expected tool call to avoid JSON-RPC error", response.error)
        return json.decodeFromJsonElement(ToolCallResult.serializer(), response.result ?: error("Missing tool result"))
    }

    private fun createAgentWorkspaceFixture(): AgentWorkspaceFixture {
        val inventoryRepo = createRepoRoot(
            repoName = "inventory-repo",
            probeRelativeFilePath = "src/inventory/SharedScopeProbe.kt",
            probeFileText = """
                package inventory

                class SharedScopeProbe {
                    val workspaceToken = "workspacescopetoken"
                    val repoToken = "inventoryrepouniquetoken"
                }
            """.trimIndent(),
            secondaryRelativeFilePath = "src/inventory/InventoryLedger.kt",
            secondaryFileText = """
                package inventory

                object InventoryLedger {
                    const val repoToken = "inventoryrepouniquetoken"
                }
            """.trimIndent(),
            structureRelativeFilePath = "src/shared/ScopedStructureProbe.java",
            structureFileText = """
                package shared;

                class InventoryStructureProbe {
                    String inventoryOnly() { return "inventory"; }
                }
            """.trimIndent()
        )
        val billingRepo = createRepoRoot(
            repoName = "billing-repo",
            probeRelativeFilePath = "src/billing/SharedScopeProbe.kt",
            probeFileText = """
                package billing

                class SharedScopeProbe {
                    val workspaceToken = "workspacescopetoken"
                    val repoToken = "billingrepouniquetoken"
                }
            """.trimIndent(),
            secondaryRelativeFilePath = "src/billing/BillingLedger.kt",
            secondaryFileText = """
                package billing

                object BillingLedger {
                    const val repoToken = "billingrepouniquetoken"
                }
            """.trimIndent(),
            structureRelativeFilePath = "src/shared/ScopedStructureProbe.java",
            structureFileText = """
                package shared;

                class BillingStructureProbe {
                    String billingOnly() { return "billing"; }
                }
            """.trimIndent()
        )
        val analyticsRepo = createRepoRoot(
            repoName = "analytics-repo",
            probeRelativeFilePath = "src/analytics/SharedScopeProbe.kt",
            probeFileText = """
                package analytics

                class SharedScopeProbe {
                    val workspaceToken = "workspacescopetoken"
                    val repoToken = "analyticsrepouniquetoken"
                }
            """.trimIndent(),
            secondaryRelativeFilePath = "src/analytics/AnalyticsLedger.kt",
            secondaryFileText = """
                package analytics

                object AnalyticsLedger {
                    const val repoToken = "analyticsrepouniquetoken"
                }
            """.trimIndent(),
            structureRelativeFilePath = "src/shared/ScopedStructureProbe.java",
            structureFileText = """
                package shared;

                class AnalyticsStructureProbe {
                    String analyticsOnly() { return "analytics"; }
                }
            """.trimIndent()
        )

        return AgentWorkspaceFixture(
            repos = listOf(inventoryRepo, billingRepo, analyticsRepo)
        )
    }

    private fun createRepoRoot(
        repoName: String,
        probeRelativeFilePath: String,
        probeFileText: String,
        secondaryRelativeFilePath: String,
        secondaryFileText: String,
        structureRelativeFilePath: String,
        structureFileText: String
    ): RepoFixture {
        val probeFile = myFixture.addFileToProject("$repoName/$probeRelativeFilePath", probeFileText)
        myFixture.addFileToProject("$repoName/$secondaryRelativeFilePath", secondaryFileText)
        myFixture.addFileToProject("$repoName/$structureRelativeFilePath", structureFileText)

        val repoVirtualFile = findRepoRoot(probeFile.virtualFile, repoName)

        PsiTestUtil.addContentRoot(module, repoVirtualFile)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return RepoFixture(
            repoName = repoName,
            expectedRelativePath = probeRelativeFilePath,
            structureRelativePath = structureRelativeFilePath,
            structureMarker = structureFileText.substringAfter("class ").substringBefore(" "),
            scope = RepoScopeContext(
                repoId = repoName,
                gitRootPath = repoVirtualFile.path.replace('\\', '/')
            )
        )
    }

    private fun findRepoRoot(file: VirtualFile, repoName: String): VirtualFile {
        var current: VirtualFile? = file
        while (current != null && current.name != repoName) {
            current = current.parent
        }
        return current ?: error("Failed to resolve $repoName root from fixture")
    }

    private data class AgentWorkspaceFixture(
        val repos: List<RepoFixture>
    )

    private data class RepoFixture(
        val repoName: String,
        val expectedRelativePath: String,
        val structureRelativePath: String,
        val structureMarker: String,
        val scope: RepoScopeContext
    )
}
