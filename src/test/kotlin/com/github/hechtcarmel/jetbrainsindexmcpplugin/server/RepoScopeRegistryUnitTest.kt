package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import junit.framework.TestCase

class RepoScopeRegistryUnitTest : TestCase() {

    fun testDuplicateLeafNamesUseStablePathHashSuffixes() {
        val first = testPath("workspace/service")
        val second = testPath("submodules/service")

        val entries = RepoScopeRegistry.buildEntries(listOf(first, second))

        val idsByPath = entries.associate { it.rootPath to it.repoId }
        assertEquals("service-${RepoScopeRegistry.pathHash8(first)}", idsByPath[RepoScopeRegistry.normalizeRootPath(first)])
        assertEquals("service-${RepoScopeRegistry.pathHash8(second)}", idsByPath[RepoScopeRegistry.normalizeRootPath(second)])
        assertFalse("Duplicate repo ids must not use ordinal suffixes", entries.any { it.repoId.endsWith("-2") })
    }

    fun testUniqueLeafNamesStayReadable() {
        val entries = RepoScopeRegistry.buildEntries(listOf(testPath("workspace/api"), testPath("workspace/web")))

        assertEquals(listOf("api", "web"), entries.map { it.repoId })
    }

    fun testRepoIdsAreStableAcrossInputOrder() {
        val first = testPath("workspace/service")
        val second = testPath("submodules/service")

        val forward = RepoScopeRegistry.buildEntries(listOf(first, second)).associate { it.rootPath to it.repoId }
        val reversed = RepoScopeRegistry.buildEntries(listOf(second, first)).associate { it.rootPath to it.repoId }

        assertEquals(forward, reversed)
    }

    fun testDetachRemovesOneRepoWithoutRenamingRemainingRepos() {
        val registry = RepoScopeRegistry()
        val first = testPath("workspace/service")
        val second = testPath("submodules/service")
        val web = testPath("workspace/web")

        registry.replaceOpenRoots(listOf(first, second, web))
        val removedId = registry.entries().first { it.rootPath == RepoScopeRegistry.normalizeRootPath(first) }.repoId
        val remainingBefore = registry.entries().filterNot { it.repoId == removedId }.associate { it.rootPath to it.repoId }

        assertTrue(registry.detach(removedId))

        assertEquals(remainingBefore, registry.entries().associate { it.rootPath to it.repoId })
        assertFalse(registry.entries().any { it.repoId == removedId })
    }

    private fun testPath(relative: String): String =
        "C:/Users/Tanner/Documents/Workspaces/Projects/$relative"
}
