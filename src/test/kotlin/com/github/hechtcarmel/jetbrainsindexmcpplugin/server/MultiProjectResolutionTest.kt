package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files

/**
 * Platform-dependent tests for multi-project resolution.
 * For schema validation tests that don't need the platform, see ToolsUnitTest.
 */
class MultiProjectResolutionTest : BasePlatformTestCase() {

    private lateinit var handler: JsonRpcHandler
    private lateinit var toolRegistry: ToolRegistry

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry()
        toolRegistry.registerBuiltInTools()
        handler = JsonRpcHandler(toolRegistry)
    }

    fun testToolCallWithSingleProject() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = "tools/call",
            params = buildJsonObject {
                put("name", ToolNames.INDEX_STATUS)
                put("arguments", buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Single project should not return JSON-RPC error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertFalse("Tool should succeed with single project", result.isError)
    }

    fun testToolCallWithExplicitProjectPath() = runBlocking {
        val projectPath = project.basePath

        val request = JsonRpcRequest(
            id = JsonPrimitive(2),
            method = "tools/call",
            params = buildJsonObject {
                put("name", ToolNames.INDEX_STATUS)
                put("arguments", buildJsonObject {
                    put("project_path", projectPath ?: "")
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Explicit project_path should not return JSON-RPC error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertFalse("Tool should succeed with explicit project_path", result.isError)
    }

    fun testToolCallWithInvalidProjectPath() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(3),
            method = "tools/call",
            params = buildJsonObject {
                put("name", ToolNames.INDEX_STATUS)
                put("arguments", buildJsonObject {
                    put("project_path", "/non/existent/project/path")
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Should not return JSON-RPC level error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertTrue("Tool should return error for invalid project_path", result.isError)

        val content = result.content.firstOrNull()
        assertNotNull("Should have error content", content)

        val errorJson = json.parseToJsonElement(
            (content as? com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock.Text)?.text ?: ""
        ).jsonObject

        assertEquals("project_not_found", errorJson["error"]?.jsonPrimitive?.content)
        assertNotNull("Should include available_projects", errorJson["available_projects"])
    }

    fun testAttachAndDetachRefreshWorkspaceContentRoots() = runBlocking {
        val repoRoot = Files.createTempDirectory("repo-lifecycle").resolve("shipping-repo").toFile()
        assertTrue("Fixture repo root should be created", repoRoot.mkdirs() || repoRoot.isDirectory)
        assertFalse("Fixture repo root should start outside content roots", ProjectUtils.getModuleContentRoots(project).contains(repoRoot.path.replace('\\', '/')))

        val attachResult = callTool(
            ToolNames.ATTACH_REPO_TO_WORKSPACE,
            buildJsonObject {
                put(ParamNames.REPO_PATH, repoRoot.path.replace('\\', '/'))
            }
        )
        assertFalse("Attach should succeed", attachResult.isError)

        val attachJson = json.parseToJsonElement(attachResult.text()).jsonObject
        val repoId = attachJson["repoId"]?.jsonPrimitive?.contentOrNull
        assertEquals("shipping-repo", repoId)
        assertTrue("Attach should add repo root to workspace content roots", ProjectUtils.getModuleContentRoots(project).contains(repoRoot.path.replace('\\', '/')))

        val detachResult = callTool(
            ToolNames.DETACH_REPO_FROM_WORKSPACE,
            buildJsonObject {
                put(ParamNames.REPO_ID, repoId!!)
            }
        )
        assertFalse("Detach should succeed", detachResult.isError)

        val detachJson = json.parseToJsonElement(detachResult.text()).jsonObject
        assertEquals("true", detachJson["detached"]?.jsonPrimitive?.content)
        assertFalse("Detach should remove repo root from workspace content roots", ProjectUtils.getModuleContentRoots(project).contains(repoRoot.path.replace('\\', '/')))
    }

    private suspend fun callTool(toolName: String, arguments: kotlinx.serialization.json.JsonObject): ToolCallResult {
        val request = JsonRpcRequest(
            id = JsonPrimitive(10),
            method = "tools/call",
            params = buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)
        assertNull("Tool call should not return JSON-RPC error", response.error)
        assertNotNull("Tool call should return result", response.result)
        return json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
    }

    private fun ToolCallResult.text(): String =
        (content.firstOrNull() as? com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock.Text)?.text ?: ""
}
