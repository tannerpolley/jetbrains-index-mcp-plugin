package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import junit.framework.TestCase

class RepoScopeRegistryUnitTest : TestCase() {

    fun testNormalizePathHandlesWindowsAndUnixSeparators() {
        assertEquals("C:/work/repo", RepoScopeRegistry.normalizePath("C:\\work\\repo\\"))
        assertEquals("/work/repo", RepoScopeRegistry.normalizePath("/work/repo/"))
    }

    fun testBuildScopesDerivesStableRepoIdsAndSuffixesCollisions() {
        val scopes = RepoScopeRegistry.buildScopes(
            listOf(
                "/work/acme/my repo",
                "C:\\src\\team\\service",
                "/work/other/service"
            )
        )

        val byRoot = scopes.associateBy { it.gitRootPath }
        assertEquals("my-repo", byRoot["/work/acme/my repo"]?.repoId)

        val serviceIds = listOfNotNull(
            byRoot["C:/src/team/service"]?.repoId,
            byRoot["/work/other/service"]?.repoId
        ).sorted()
        assertEquals(listOf("service", "service-2"), serviceIds)
    }

    fun testFindByRepoIdReturnsNullWhenUnknown() {
        val registry = RepoScopeRegistry {
            listOf("/work/acme/service")
        }

        assertNull(registry.findByRepoId("missing"))
    }
}
