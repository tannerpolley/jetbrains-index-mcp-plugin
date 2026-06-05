package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.JsonRpcMethods
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcErrorCodes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.project.Project
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JsonRpcHandlerUnitTest : TestCase() {

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

    fun testInitializeRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = JsonRpcMethods.INITIALIZE,
            params = buildJsonObject {
                put("protocolVersion", McpConstants.MCP_PROTOCOL_VERSION)
                put("clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0.0")
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Initialize should not return error", response.error)
        assertNotNull("Initialize should return result", response.result)

        val result = response.result!!.jsonObject
        assertNotNull("Result should contain serverInfo", result["serverInfo"])
        assertNotNull("Result should contain capabilities", result["capabilities"])

        val serverInfo = result["serverInfo"]!!.jsonObject
        assertEquals(McpConstants.SERVER_NAME, serverInfo["name"]?.jsonPrimitive?.content)
        assertNotNull("serverInfo should contain description", serverInfo["description"])
        assertTrue(
            "description should mention code intelligence",
            serverInfo["description"]?.jsonPrimitive?.content?.contains("code intelligence", ignoreCase = true) == true
        )
    }

    fun testInitializeRequestCanOverrideProtocolVersion() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = JsonRpcMethods.INITIALIZE,
            params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0.0")
                })
            }
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = "2024-11-05"
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Initialize should not return error", response.error)
        assertEquals(
            "2024-11-05",
            response.result!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    fun testPingRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(4),
            method = JsonRpcMethods.PING
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("${JsonRpcMethods.PING} should not return error", response.error)
        assertNotNull("${JsonRpcMethods.PING} should return result", response.result)
    }

    fun testMethodNotFound() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(5),
            method = "unknown/method"
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("Unknown method should return error", response.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response.error?.code)
    }

    fun testToolCallMissingParams() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(6),
            method = JsonRpcMethods.TOOLS_CALL
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("${JsonRpcMethods.TOOLS_CALL} without params should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, response.error?.code)
    }

    fun testToolCallMissingToolName() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(7),
            method = JsonRpcMethods.TOOLS_CALL,
            params = buildJsonObject {
                put(ParamNames.ARGUMENTS, buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("${JsonRpcMethods.TOOLS_CALL} without tool name should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, response.error?.code)
    }

    fun testToolCallUnknownTool() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(8),
            method = JsonRpcMethods.TOOLS_CALL,
            params = buildJsonObject {
                put(ParamNames.NAME, "unknown_tool")
                put(ParamNames.ARGUMENTS, buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("${JsonRpcMethods.TOOLS_CALL} with unknown tool should return error", response.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response.error?.code)
    }

    fun testParseError() = runBlocking {
        val responseJson = handler.handleRequest("not valid json")
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("Invalid JSON should return error", response.error)
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, response.error?.code)
    }

    fun testInvalidJsonRpcVersion() = runBlocking {
        val requestJson = """{"jsonrpc":"1.0","id":1,"method":"ping"}"""

        val responseJson = handler.handleRequest(requestJson)
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("Invalid jsonrpc version should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, response.error?.code)
        assertTrue(
            "Error message should mention version",
            response.error?.message?.contains("2.0") == true
        )
    }

    fun testNotificationReturnsNull() = runBlocking {
        val requestJson = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

        val responseJson = handler.handleRequest(requestJson)

        assertNull("Notification should return null (no response)", responseJson)
    }

    fun testRepoScopedToolCallInjectsPinnedProjectPathWhenMissing() = runBlocking {
        val expectedRoot = "C:/repos/repo-a"
        var resolvedProjectPath: String? = null
        val handler = createHandlerWithResolver { projectPath ->
            resolvedProjectPath = projectPath
            resolverFailure("resolver_invoked", "resolver invoked")
        }

        val response = invokeRepoScopedToolCall(
            handler = handler,
            arguments = buildJsonObject { },
            repoScope = RepoScopeContext(repoId = "repo-a", gitRootPath = expectedRoot)
        )

        assertNull(response.error)
        assertEquals(expectedRoot, resolvedProjectPath)
        assertEquals("resolver_invoked", parseStructuredToolError(response)["error"]?.jsonPrimitive?.content)
    }

    fun testRepoScopedToolCallAllowsMatchingProjectPath() = runBlocking {
        var resolvedProjectPath: String? = null
        val handler = createHandlerWithResolver { projectPath ->
            resolvedProjectPath = projectPath
            resolverFailure("resolver_invoked", "resolver invoked")
        }

        val response = invokeRepoScopedToolCall(
            handler = handler,
            arguments = buildJsonObject {
                put(ParamNames.PROJECT_PATH, "C:\\repos\\repo-a\\")
            },
            repoScope = RepoScopeContext(repoId = "repo-a", gitRootPath = "C:/repos/repo-a")
        )

        assertNull(response.error)
        assertEquals("C:/repos/repo-a", resolvedProjectPath)
        assertEquals("resolver_invoked", parseStructuredToolError(response)["error"]?.jsonPrimitive?.content)
    }

    fun testRepoScopedToolCallRejectsConflictingProjectPath() = runBlocking {
        val handler = createHandlerWithResolver { projectPath ->
            fail("Resolver should not be called for conflicting project_path: $projectPath")
            resolverFailure("unexpected", "unexpected")
        }

        val response = invokeRepoScopedToolCall(
            handler = handler,
            arguments = buildJsonObject {
                put(ParamNames.PROJECT_PATH, "C:/repos/repo-b")
            },
            repoScope = RepoScopeContext(repoId = "repo-a", gitRootPath = "C:/repos/repo-a")
        )

        assertNull(response.error)

        val errorJson = parseStructuredToolError(response)
        assertEquals("repo_scope_conflict", errorJson["error"]?.jsonPrimitive?.content)
        assertEquals("repo-a", errorJson["repo_id"]?.jsonPrimitive?.content)
        assertEquals("C:/repos/repo-a", errorJson["expected_project_path"]?.jsonPrimitive?.content)
        assertEquals("C:/repos/repo-b", errorJson["provided_project_path"]?.jsonPrimitive?.content)
    }

    private suspend fun invokeRepoScopedToolCall(
        handler: JsonRpcHandler,
        arguments: JsonObject,
        repoScope: RepoScopeContext
    ): JsonRpcResponse {
        val request = JsonRpcRequest(
            id = JsonPrimitive(9),
            method = JsonRpcMethods.TOOLS_CALL,
            params = buildJsonObject {
                put(ParamNames.NAME, "test_tool")
                put(ParamNames.ARGUMENTS, arguments)
            }
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = McpConstants.MCP_PROTOCOL_VERSION,
            repoScope = repoScope
        )

        return json.decodeFromString<JsonRpcResponse>(responseJson!!)
    }

    private fun createHandlerWithResolver(resolveProject: (String?) -> ProjectResolver.Result): JsonRpcHandler {
        val localRegistry = ToolRegistry().also { it.register(fakeTool()) }
        return JsonRpcHandler(
            toolRegistry = localRegistry,
            resolveProject = resolveProject
        )
    }

    private fun fakeTool() = object : McpTool {
        override val name: String = "test_tool"
        override val description: String = "Test tool"
        override val inputSchema: JsonObject = buildJsonObject { }

        override suspend fun execute(project: Project, arguments: JsonObject) =
            error("Test tool execution should not be reached in repo scope unit tests")
    }

    private fun resolverFailure(errorCode: String, message: String): ProjectResolver.Result =
        ProjectResolver.Result(
            isError = true,
            errorResult = buildStructuredErrorResult(
                buildJsonObject {
                    put("error", errorCode)
                    put("message", message)
                }
            )
        )

    private fun parseStructuredToolError(response: JsonRpcResponse) =
        json.parseToJsonElement(
            response.result!!
                .jsonObject["content"]!!
                .jsonArray
                .first()
                .jsonObject["text"]!!
                .jsonPrimitive
                .content
        ).jsonObject
}
