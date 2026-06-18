package com.github.hechtcarmel.jetbrainsindexmcpplugin.services

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.CodexWorkspaceSyncService
import com.intellij.execution.RunManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class RepoRunConfigurationSynchronizerUnitTest : BasePlatformTestCase() {

    fun testParseNormalizesRepoFolder() {
        val repo = createRepoRunConfig("repo", "repo: Validation")
        val parsed = RepoRunConfigurationSynchronizer.parseRunConfigFile(
            file = File(repo, ".run/repo - Validation.run.xml"),
            repoId = "repo"
        )

        assertEquals("repo: Validation", parsed.name)
        assertEquals("Application", parsed.typeId)
        assertEquals("repo", parsed.element.getAttributeValue("folderName"))
    }

    fun testSyncImportsRepoRunConfigurationsIntoRunManager() {
        val repo = createRepoRunConfig("repo", "repo: Validation")

        val result = RepoRunConfigurationSynchronizer.sync(
            project = project,
            acceptedRepos = listOf(
                CodexWorkspaceSyncService.ResolvedRepo(
                    repoRootPath = repo.absolutePath,
                    source = "test"
                )
            ),
            workspaceProjectPath = project.basePath
        )

        assertEquals(1, result.imported)
        assertEquals(0, result.removed)
        assertTrue(result.errors.isEmpty())

        val settings = RunManager.getInstance(project).allSettings.single {
            it.name == "repo: Validation" && it.folderName == "repo"
        }
        assertEquals("Application", settings.type.id)
        assertTrue(settings.isStoredInArbitraryFileInProject)
        assertTrue(settings.pathIfStoredInArbitraryFileInProject?.contains(".run") == true)
    }

    fun testSyncPrunesStaleImportedRepoRunConfigurations() {
        val repo = createRepoRunConfig("repo", "repo: Validation")
        RepoRunConfigurationSynchronizer.sync(
            project = project,
            acceptedRepos = listOf(
                CodexWorkspaceSyncService.ResolvedRepo(
                    repoRootPath = repo.absolutePath,
                    source = "test"
                )
            ),
            workspaceProjectPath = project.basePath
        )

        val result = RepoRunConfigurationSynchronizer.sync(
            project = project,
            acceptedRepos = emptyList(),
            workspaceProjectPath = project.basePath
        )

        assertEquals(0, result.imported)
        assertEquals(1, result.removed)
        assertTrue(RunManager.getInstance(project).allSettings.none { it.name == "repo: Validation" })
    }

    private fun createRepoRunConfig(repoName: String, configName: String): File {
        val repo = File(project.basePath!!, repoName).also { it.mkdirs() }
        val runDir = File(repo, ".run").also { it.mkdirs() }
        File(runDir, "$repoName - Validation.run.xml").writeText(
            """
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="$configName" type="Application" factoryName="Application" folderName="wrong-folder">
                <option name="MAIN_CLASS_NAME" value="com.example.Main" />
                <module name="${project.name}" />
                <method v="2" />
              </configuration>
            </component>
            """.trimIndent()
        )
        return repo
    }
}
