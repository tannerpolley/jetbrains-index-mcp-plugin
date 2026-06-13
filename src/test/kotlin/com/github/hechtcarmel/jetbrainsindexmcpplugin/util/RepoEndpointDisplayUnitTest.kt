package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScope
import junit.framework.TestCase

class RepoEndpointDisplayUnitTest : TestCase() {

    fun testBuildRowsShowsRepoScopedUrlsAndRepoModuleNames() {
        val rows = RepoEndpointDisplay.buildRows(
            broadStreamableHttpUrl = "http://127.0.0.1:29170/index-mcp/streamable-http",
            repoScopes = listOf(
                RepoScope(
                    repoId = "superpowers-project",
                    repoRootPath = "C:/Users/Tanner/Documents/Workspaces/Projects/superpowers-project",
                    workspaceProjectPath = "C:/Users/Tanner/Documents/Workspaces/Workspace"
                ),
                RepoScope(
                    repoId = "ePC-SAFT",
                    repoRootPath = "C:/Users/Tanner/Documents/Workspaces/Engineering/ePC-SAFT",
                    workspaceProjectPath = "C:/Users/Tanner/Documents/Workspaces/Workspace"
                )
            )
        )

        assertEquals(2, rows.size)
        assertEquals("ePC-SAFT", rows[0].repoModuleName)
        assertEquals(
            "http://127.0.0.1:29170/index-mcp/repos/ePC-SAFT/streamable-http | Repo/Module: ePC-SAFT",
            rows[0].displayText
        )
        assertEquals(
            "http://127.0.0.1:29170/index-mcp/repos/superpowers-project/streamable-http | Repo/Module: superpowers-project",
            rows[1].displayText
        )
    }
}
