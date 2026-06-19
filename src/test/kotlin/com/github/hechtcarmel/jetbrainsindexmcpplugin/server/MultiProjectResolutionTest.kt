package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
        val response = invokeIndexStatus(projectPath ?: "", JsonPrimitive(2))

        assertNull("Explicit project_path should not return JSON-RPC error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertFalse("Tool should succeed with explicit project_path", result.isError)
    }

    fun testToolCallWithModuleContentRootPath() = runBlocking {
        val contentRoot = addWorkspaceSubProjectContentRoot("workspace-subproject")
        val response = invokeIndexStatus(contentRoot.path, JsonPrimitive(20))

        assertNull("Module content root path should not return JSON-RPC error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertFalse("Tool should succeed with module content root path", result.isError)
    }

    fun testToolCallWithSubdirectoryOfModuleContentRoot() = runBlocking {
        addWorkspaceSubProjectContentRoot("workspace-subproject")
        val nestedDir = myFixture.tempDirFixture.findOrCreateDir("workspace-subproject/nested/repo")
        val response = invokeIndexStatus(nestedDir.path, JsonPrimitive(21))

        assertNull("Subdirectory of module content root should not return JSON-RPC error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertFalse("Tool should succeed with subdirectory of module content root", result.isError)
    }

    fun testToolCallWithSiblingAndNestedSubmoduleStyleContentRoots() = runBlocking {
        val inventoryRoot = addWorkspaceSubProjectContentRoot("inventory-repo")
        val billingRoot = addWorkspaceSubProjectContentRoot("billing-repo")
        val shippingRoot = addWorkspaceSubProjectContentRoot("submodules/shipping-repo")

        val inventoryResponse = invokeIndexStatus(inventoryRoot.path, JsonPrimitive(22))
        val billingResponse = invokeIndexStatus(billingRoot.path, JsonPrimitive(23))
        val shippingResponse = invokeIndexStatus(shippingRoot.path, JsonPrimitive(24))

        assertNull("Sibling inventory root should not return JSON-RPC error", inventoryResponse.error)
        assertNull("Sibling billing root should not return JSON-RPC error", billingResponse.error)
        assertNull("Nested shipping root should not return JSON-RPC error", shippingResponse.error)

        assertFalse(
            "Tool should succeed with sibling inventory root",
            json.decodeFromJsonElement(ToolCallResult.serializer(), inventoryResponse.result!!).isError
        )
        assertFalse(
            "Tool should succeed with sibling billing root",
            json.decodeFromJsonElement(ToolCallResult.serializer(), billingResponse.result!!).isError
        )
        assertFalse(
            "Tool should succeed with nested submodule-style shipping root",
            json.decodeFromJsonElement(ToolCallResult.serializer(), shippingResponse.result!!).isError
        )
    }

    fun testToolCallWithInvalidProjectPath() = runBlocking {
        val response = invokeIndexStatus("/non/existent/project/path", JsonPrimitive(3))

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

    private suspend fun invokeIndexStatus(projectPath: String, requestId: JsonPrimitive): JsonRpcResponse {
        val request = JsonRpcRequest(
            id = requestId,
            method = "tools/call",
            params = buildJsonObject {
                put("name", ToolNames.INDEX_STATUS)
                put("arguments", buildJsonObject {
                    put("project_path", projectPath)
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        return json.decodeFromString<JsonRpcResponse>(responseJson!!)
    }

    private fun addWorkspaceSubProjectContentRoot(relativePath: String): VirtualFile {
        val contentRoot = myFixture.tempDirFixture.findOrCreateDir(relativePath)
        PsiTestUtil.addContentRoot(module, contentRoot)
        return contentRoot
    }
}
