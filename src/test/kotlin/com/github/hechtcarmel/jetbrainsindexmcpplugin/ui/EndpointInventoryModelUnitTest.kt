package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScope
import junit.framework.TestCase

class EndpointInventoryModelUnitTest : TestCase() {

    fun testBuildRowsIncludesWorkspaceAndRepoEndpoints() {
        val rows = EndpointInventoryModel.buildRows(
            broadStreamableHttpUrl = "http://127.0.0.1:29170/index-mcp/streamable-http",
            projectName = "Workspace",
            workspaceProjectPath = "C:/Users/Tanner/Documents/Workspaces/Workspace",
            repoScopes = listOf(repo("superpowers-project"), repo("ePC-SAFT")),
            serverState = EndpointInventoryState.RUNNING
        )

        assertEquals(3, rows.size)
        assertEquals(EndpointScopeKind.WORKSPACE, rows[0].scopeKind)
        assertEquals("Workspace", rows[0].scopeName)
        assertEquals("http://127.0.0.1:29170/index-mcp/streamable-http", rows[0].url)
        assertEquals("ePC-SAFT", rows[1].scopeName)
        assertEquals("http://127.0.0.1:29170/index-mcp/repos/ePC-SAFT/streamable-http", rows[1].url)
        assertEquals(
            "http://127.0.0.1:29170/index-mcp/repos/ePC-SAFT/streamable-http | Repo/Module: ePC-SAFT",
            rows[1].copyText
        )
    }

    fun testOfflineStateKeepsRowsVisible() {
        val rows = EndpointInventoryModel.buildRows(
            broadStreamableHttpUrl = null,
            projectName = "Workspace",
            workspaceProjectPath = "C:/Users/Tanner/Documents/Workspaces/Workspace",
            repoScopes = listOf(repo("ePC-SAFT")),
            serverState = EndpointInventoryState.OFFLINE
        )

        assertEquals(2, rows.size)
        assertEquals(EndpointInventoryState.OFFLINE, rows[0].state)
        assertEquals(EndpointInventoryState.OFFLINE, rows[1].state)
        assertEquals("ePC-SAFT", rows[1].scopeName)
        assertEquals("", rows[1].url)
    }

    fun testDuplicateRepoIdsUseStableDistinctRowIds() {
        val rows = EndpointInventoryModel.buildRows(
            broadStreamableHttpUrl = "http://127.0.0.1:29170/index-mcp/streamable-http",
            projectName = "Workspace",
            workspaceProjectPath = "C:/Users/Tanner/Documents/Workspaces/Workspace",
            repoScopes = listOf(
                repo(
                    id = "CMake",
                    root = "C:/Users/Tanner/Documents/Workspaces/Projects/CMake"
                ),
                repo(
                    id = "CMake",
                    root = "C:/Users/Tanner/Documents/Workspaces/Projects/CMake-issue-0241"
                )
            ),
            serverState = EndpointInventoryState.RUNNING
        )

        assertEquals(3, rows.size)
        assertEquals("repo:CMake:f080a223", rows[1].id)
        assertEquals("repo:CMake:654f7688", rows[2].id)
        assertEquals("CMake", rows[1].scopeName)
        assertEquals("CMake", rows[2].scopeName)
    }

    private fun repo(
        id: String,
        root: String = "C:/Users/Tanner/Documents/Workspaces/Projects/$id"
    ): RepoScope =
        RepoScope(
            repoId = id,
            repoRootPath = root,
            workspaceProjectPath = "C:/Users/Tanner/Documents/Workspaces/Workspace"
        )
}
