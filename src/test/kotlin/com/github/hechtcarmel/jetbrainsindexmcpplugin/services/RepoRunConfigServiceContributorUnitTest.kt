package com.github.hechtcarmel.jetbrainsindexmcpplugin.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RepoRunConfigServiceContributorUnitTest : BasePlatformTestCase() {

    fun testContributorReturnsRepoNodesBeforeTypeNodes() {
        val contributor = RepoRunConfigServiceContributor {
            RepoRunConfigTree(
                repos = listOf(
                    RepoRunConfigRepo(
                        repoName = "ePC-SAFT",
                        types = listOf(
                            RepoRunConfigTypeGroup(
                                typeId = "UvRunConfigurationType",
                                typeName = "uv run",
                                configs = listOf(
                                    RepoRunConfigLeaf(
                                        name = "ePC-SAFT: Generate Equilibrium Activation",
                                        typeId = "UvRunConfigurationType",
                                        typeName = "uv run",
                                        repoName = "ePC-SAFT",
                                        settings = null
                                    )
                                )
                            )
                        )
                    )
                ),
                diagnostics = emptyList()
            )
        }

        val repoNodes = contributor.getServices(project)
        assertEquals(listOf("ePC-SAFT"), repoNodes.map { it.repo.repoName })

        val typeNodes = repoNodes.single().getServices(project)
        assertEquals(listOf("uv run"), typeNodes.map { it.typeGroup.typeName })

        val configNodes = typeNodes.single().getServices(project)
        assertEquals("ePC-SAFT: Generate Equilibrium Activation", configNodes.single().leaf.name)
    }
}
