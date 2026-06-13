package com.github.hechtcarmel.jetbrainsindexmcpplugin.services

import junit.framework.TestCase

class RepoRunConfigIndexUnitTest : TestCase() {

    fun testGroupsByRepoThenTypeThenConfig() {
        val tree = RepoRunConfigIndex.buildTree(
            candidates = listOf(
                candidate("ePC-SAFT: Generate Equilibrium Activation", "ePC-SAFT", "UvRunConfigurationType", "uv run"),
                candidate("ePC-SAFT: Validation - Smoke", "ePC-SAFT", "PythonConfigurationType", "Python"),
                candidate("jetbrains-bridge: Validation - Plugin", "jetbrains-bridge", "PowerShellRunType", "PowerShell")
            )
        )

        assertEquals(listOf("ePC-SAFT", "jetbrains-bridge"), tree.repos.map { it.repoName })
        assertEquals(listOf("Python", "uv run"), tree.repos[0].types.map { it.typeName })
        assertEquals("ePC-SAFT: Generate Equilibrium Activation", tree.repos[0].types[1].configs.single().name)
        assertEquals("jetbrains-bridge: Validation - Plugin", tree.repos[1].types.single().configs.single().name)
        assertTrue(tree.diagnostics.isEmpty())
    }

    fun testRejectsMissingFolderAndDuplicateRepoTypeName() {
        val tree = RepoRunConfigIndex.buildTree(
            candidates = listOf(
                candidate("Validation", "", "UvRunConfigurationType", "uv run"),
                candidate("ePC-SAFT: Validation - Smoke", "ePC-SAFT", "UvRunConfigurationType", "uv run"),
                candidate("ePC-SAFT: Validation - Smoke", "ePC-SAFT", "UvRunConfigurationType", "uv run")
            )
        )

        assertEquals(0, tree.repos.size)
        assertEquals(2, tree.diagnostics.size)
        assertTrue(tree.diagnostics.any { it.reason.contains("missing repo folder") })
        assertTrue(tree.diagnostics.any { it.reason.contains("duplicate config name") })
    }

    fun testRejectsTemporaryConfigs() {
        val tree = RepoRunConfigIndex.buildTree(
            candidates = listOf(
                candidate(
                    name = "ePC-SAFT: Temporary Scratch",
                    folderName = "ePC-SAFT",
                    typeId = "PythonConfigurationType",
                    typeName = "Python",
                    isTemporary = true
                )
            )
        )

        assertTrue(tree.repos.isEmpty())
        assertEquals("temporary config rejected", tree.diagnostics.single().reason)
    }

    private fun candidate(
        name: String,
        folderName: String,
        typeId: String,
        typeName: String,
        isTemporary: Boolean = false
    ): RepoRunConfigCandidate =
        RepoRunConfigCandidate(
            name = name,
            folderName = folderName,
            typeId = typeId,
            typeName = typeName,
            isTemporary = isTemporary,
            settings = null
        )
}
