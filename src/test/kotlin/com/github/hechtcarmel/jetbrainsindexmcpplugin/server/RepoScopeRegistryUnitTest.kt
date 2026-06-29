package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import junit.framework.TestCase

class RepoScopeRegistryUnitTest : TestCase() {

    fun testUniqueLeafRepoIdsStayReadable() {
        val scopes = RepoScopeRegistry.buildScopes(
            repoRootPaths = listOf(
                "/workspace/projects/alpha",
                "/workspace/projects/beta"
            ),
            workspaceProjectPath = "/workspace/Workspace"
        )

        assertEquals("alpha", scopes[0].repoId)
        assertEquals("beta", scopes[1].repoId)
        assertEquals("/workspace/projects/alpha", scopes[0].repoRootPath)
        assertEquals("/workspace/Workspace", scopes[0].workspaceProjectPath)
    }

    fun testDuplicateLeafRepoIdsUseStablePathHash() {
        val first = "/workspace/projects/service"
        val second = "/workspace/archive/service"

        val scopes = RepoScopeRegistry.buildScopes(
            repoRootPaths = listOf(first, second),
            workspaceProjectPath = "/workspace/Workspace"
        )

        val firstHash = RepoScopeRegistry.pathHash8(RepoScopeRegistry.normalizeRepoRootPath(first))
        val secondHash = RepoScopeRegistry.pathHash8(RepoScopeRegistry.normalizeRepoRootPath(second))

        assertEquals("service-$firstHash", scopes[0].repoId)
        assertEquals("service-$secondHash", scopes[1].repoId)
        assertTrue("Duplicate leaves must not collide", scopes[0].repoId != scopes[1].repoId)
    }

    fun testNormalizeRepoRootPathRemovesTrailingSeparatorsAndConvertsBackslashes() {
        assertEquals(
            "C:/Users/Tanner/Documents/Workspaces/Projects/jetbrains-bridge",
            RepoScopeRegistry.normalizeRepoRootPath("C:\\Users\\Tanner\\Documents\\Workspaces\\Projects\\jetbrains-bridge\\")
        )
    }

    fun testRepoScopedConflictAllowsRepoRootAndChildrenOnly() {
        val scope = RepoScope(
            repoId = "jetbrains-bridge",
            repoRootPath = "C:/Users/Tanner/Documents/Workspaces/Projects/jetbrains-bridge",
            workspaceProjectPath = "C:/Users/Tanner/Documents/Workspaces/Workspace"
        )

        assertTrue(RepoScopeRegistry.isPathInsideScope(scope, "C:/Users/Tanner/Documents/Workspaces/Projects/jetbrains-bridge"))
        assertTrue(RepoScopeRegistry.isPathInsideScope(scope, "C:/Users/Tanner/Documents/Workspaces/Projects/jetbrains-bridge/scripts"))
        assertFalse(RepoScopeRegistry.isPathInsideScope(scope, "C:/Users/Tanner/Documents/Workspaces/Projects/other-repo"))
        assertEquals("scripts/build.ps1", RepoScopeRegistry.relativePathInScope(scope.repoRootPath, "C:/Users/Tanner/Documents/Workspaces/Projects/jetbrains-bridge/scripts/build.ps1"))
        assertNull(RepoScopeRegistry.relativePathInScope(scope.repoRootPath, "C:/Users/Tanner/Documents/Workspaces/Projects/jetbrains-bridge-cache/config.json"))
    }

    fun testAgentRepoRootSelectionSkipsWorkspaceAndNestedPackages() {
        val candidates = listOf(
            "C:/Users/Tanner/Documents/Workspaces/Workspace",
            "C:/Users/Tanner/Documents/Workspaces/Engineering/ePC-SAFT",
            "C:/Users/Tanner/Documents/Workspaces/Engineering/ePC-SAFT/packages/epcsaft",
            "C:/Users/Tanner/Documents/Workspaces/Engineering/ePC-SAFT/packages/epcsaft-regression"
        )

        val selected = RepoScopeRegistry.selectAgentRepoRootPaths(candidates) { path ->
            path == "C:/Users/Tanner/Documents/Workspaces/Engineering/ePC-SAFT"
        }

        assertEquals(
            listOf("C:/Users/Tanner/Documents/Workspaces/Engineering/ePC-SAFT"),
            selected
        )
    }

    fun testManualIndexRootSelectionOnlyAllowsCodexAndAgentsDotFolders() {
        val selected = RepoScopeRegistry.selectManualIndexRootPaths(
            listOf(
                "C:/Users/Tanner/.codex",
                "C:/Users/Tanner/.agents",
                "C:/Users/Tanner/codex",
                "C:/Users/Tanner/.ssh"
            )
        )

        assertEquals(
            listOf("C:/Users/Tanner/.codex", "C:/Users/Tanner/.agents"),
            selected
        )
    }

    fun testBuildIndexScopesIncludesManualCodexAndAgentsRoots() {
        val scopes = RepoScopeRegistry.buildIndexScopes(
            repoRootPaths = listOf("C:/Users/Tanner/Documents/Workspaces/Projects/jetbrains-bridge"),
            manualRootPaths = listOf(
                "C:/Users/Tanner/.codex",
                "C:/Users/Tanner/.agents",
                "C:/Users/Tanner/codex"
            ),
            workspaceProjectPath = "C:/Users/Tanner/Documents/Workspaces/Workspace"
        )

        assertEquals(
            listOf("agents", "codex", "jetbrains-bridge"),
            scopes.map { it.repoId }.sorted()
        )
    }

    fun testExtractImlContentRootPathsReadsWorkspaceSidecarModuleRoots() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <module type="EMPTY_MODULE" version="4">
              <component name="NewModuleRootManager" inherit-compiler-output="true">
                <content url="file://${'$'}MODULE_DIR${'$'}/../Projects/jetbrains-bridge" />
                <content url="file://${'$'}USER_HOME${'$'}/.codex" />
              </component>
            </module>
        """.trimIndent()

        val roots = RepoScopeRegistry.extractImlContentRootPaths(
            moduleFilePath = "C:/Users/Tanner/Documents/Workspaces/Workspace/.idea/jetbrains-bridge.iml",
            projectBasePath = "C:/Users/Tanner/Documents/Workspaces/Workspace",
            xml = xml,
            userHomePath = "C:/Users/Tanner"
        )

        assertEquals(
            listOf(
                "C:/Users/Tanner/Documents/Workspaces/Projects/jetbrains-bridge",
                "C:/Users/Tanner/.codex"
            ),
            roots
        )
    }
}
