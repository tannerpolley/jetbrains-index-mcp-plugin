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

    fun testBuildScopesAssignsCollisionSuffixesDeterministicallyAcrossInputOrder() {
        val first = RepoScopeRegistry.buildScopes(
            listOf(
                "/work/zeta/service",
                "/work/alpha/service"
            )
        )
        val second = RepoScopeRegistry.buildScopes(
            listOf(
                "/work/alpha/service",
                "/work/zeta/service"
            )
        )

        assertEquals(
            first.map { it.repoId to it.gitRootPath },
            second.map { it.repoId to it.gitRootPath }
        )
    }

    fun testBuildScopesIncludesNestedGitRootsForSubmoduleStyleWorkspaces() {
        val scopes = RepoScopeRegistry.buildScopes(
            listOf(
                "/work/master/modules/service",
                "/work/master",
                "/work/master/modules/docs"
            )
        )

        assertEquals(
            listOf(
                "master" to "/work/master",
                "docs" to "/work/master/modules/docs",
                "service" to "/work/master/modules/service"
            ),
            scopes.map { it.repoId to it.gitRootPath }
        )
    }

    fun testBuildScopesDeduplicatesEquivalentRootsAfterNormalization() {
        val scopes = RepoScopeRegistry.buildScopes(
            listOf(
                "C:\\work\\master\\module\\service\\",
                "C:/work/master/module/service"
            )
        )

        assertEquals(1, scopes.size)
        assertEquals("service", scopes.single().repoId)
        assertEquals("C:/work/master/module/service", scopes.single().gitRootPath)
    }

    fun testListScopesRefreshesWhenSiblingAndNestedRootsAreAttachedAfterOpen() {
        val roots = mutableListOf("/work/master/inventory-repo")
        val registry = RepoScopeRegistry { roots.toList() }

        assertEquals(listOf("inventory-repo"), registry.listScopes().map { it.repoId })

        roots += "/work/master/billing-repo"
        roots += "/work/master/submodules/shipping-repo"

        val refreshedScopes = registry.listScopes()
        assertEquals(
            listOf("billing-repo", "inventory-repo", "shipping-repo"),
            refreshedScopes.map { it.repoId }
        )
        assertEquals(
            "/work/master/submodules/shipping-repo",
            registry.findByRepoId("shipping-repo")?.gitRootPath
        )
    }

    fun testFindByRepoIdReturnsNullWhenUnknown() {
        val registry = RepoScopeRegistry {
            listOf("/work/acme/service")
        }

        assertNull(registry.findByRepoId("missing"))
    }
}
