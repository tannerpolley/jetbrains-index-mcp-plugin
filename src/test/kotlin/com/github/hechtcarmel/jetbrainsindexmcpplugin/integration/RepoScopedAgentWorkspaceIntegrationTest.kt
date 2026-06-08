package com.github.hechtcarmel.jetbrainsindexmcpplugin.integration

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.JsonRpcHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorMcpServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorSseSessionManager
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

class RepoScopedAgentWorkspaceIntegrationTest : BasePlatformTestCase() {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val httpClient = HttpClient.newHttpClient()

    private lateinit var registry: ToolRegistry
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var server: KtorMcpServer
    private var port: Int = 0

    override fun setUp() {
        super.setUp()
        registry = ToolRegistry().also { it.registerBuiltInTools() }
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        port = ServerSocket(0).use { it.localPort }
        server = KtorMcpServer(
            port = port,
            jsonRpcHandler = JsonRpcHandler(registry),
            sseSessionManager = KtorSseSessionManager(),
            coroutineScope = coroutineScope
        )
        assertEquals(KtorMcpServer.StartResult.Success, server.start())
    }

    override fun tearDown() {
        try {
            server.stop()
            RepoScopeRegistry.getInstance().replaceOpenRoots(emptyList())
        } finally {
            coroutineScope.cancel()
            super.tearDown()
        }
    }

    fun testAttachConfigConflictAndDetachForNestedWorkspaceTopology() = runBlocking {
        val parent = Files.createTempDirectory("repo-scoped-workspace")
        val shippingRepo = parent.resolve("master-project/submodules/shipping-repo").toFile()
        assertTrue(shippingRepo.mkdirs() || shippingRepo.isDirectory)
        val repoPath = shippingRepo.path.replace('\\', '/')

        val attach = callTool(
            ToolNames.ATTACH_REPO_TO_WORKSPACE,
            buildJsonObject {
                put(ParamNames.REPO_PATH, repoPath)
            }
        )
        assertFalse("Attach should succeed", attach.isError)
        assertTrue("Attached repo should become a workspace content root", ProjectUtils.getModuleContentRoots(project).contains(repoPath))

        val config = callTool(ToolNames.GET_REPO_SCOPED_CLIENT_CONFIG, buildJsonObject { })
        val scopedServers = json.parseToJsonElement(config.text()).jsonObject["scopedServers"]!!.jsonArray.map { it.jsonObject }
        val shippingServer = scopedServers.firstOrNull { it["repoId"]?.jsonPrimitive?.content == "shipping-repo" }
        assertNotNull("Client config should publish the shipping repo scoped route", shippingServer)
        assertTrue(
            "Published scoped URL should target the repo route",
            shippingServer!!["streamableHttpUrl"]?.jsonPrimitive?.content?.endsWith("/index-mcp/repos/shipping-repo/streamable-http") == true
        )

        val scopedPing = postScoped("shipping-repo", """{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        assertEquals(200, scopedPing.statusCode())

        val conflict = postScoped(
            "shipping-repo",
            json.encodeToString(
                JsonRpcRequest.serializer(),
                JsonRpcRequest(
                    id = JsonPrimitive(2),
                    method = "tools/call",
                    params = buildJsonObject {
                        put("name", ToolNames.INDEX_STATUS)
                        put("arguments", buildJsonObject {
                            put(ParamNames.PROJECT_PATH, parent.resolve("other-repo").toString().replace('\\', '/'))
                        })
                    }
                )
            )
        )
        val conflictResponse = json.decodeFromString<JsonRpcResponse>(conflict.body())
        val conflictResult = json.decodeFromJsonElement(ToolCallResult.serializer(), conflictResponse.result!!)
        assertTrue("Conflicting project_path should be rejected on repo scoped route", conflictResult.isError)
        assertTrue(conflictResult.text().contains("repo_scope_conflict"))

        val detach = callTool(
            ToolNames.DETACH_REPO_FROM_WORKSPACE,
            buildJsonObject {
                put(ParamNames.REPO_ID, "shipping-repo")
            }
        )
        assertFalse("Detach should succeed", detach.isError)
        assertFalse("Detached repo should leave workspace content roots", ProjectUtils.getModuleContentRoots(project).contains(repoPath))

        val afterDetach = postScoped("shipping-repo", """{"jsonrpc":"2.0","id":3,"method":"ping"}""")
        assertEquals("Detached repo route should no longer be published", 404, afterDetach.statusCode())
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
        val responseJson = JsonRpcHandler(registry).handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)
        assertNull("Tool call should not return JSON-RPC error", response.error)
        return json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
    }

    private fun postScoped(repoId: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:$port${McpConstants.MCP_ENDPOINT_PATH}/repos/$repoId/streamable-http")
        )
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun ToolCallResult.text(): String =
        (content.firstOrNull() as? com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock.Text)?.text.orEmpty()
}
