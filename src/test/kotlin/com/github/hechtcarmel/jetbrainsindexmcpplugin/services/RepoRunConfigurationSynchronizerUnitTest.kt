package com.github.hechtcarmel.jetbrainsindexmcpplugin.services

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.CodexWorkspaceSyncService
import com.intellij.execution.RunManager
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class RepoRunConfigurationSynchronizerUnitTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            (RunManager.getInstance(project) as RunManagerImpl).clearAll()
        } finally {
            super.tearDown()
        }
    }

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

    fun testSyncKeepsAcceptedMixedCaseRepoRunConfigurations() {
        val repo = createRepoRunConfig("ePC-SAFT", "ePC-SAFT: Validation")
        val runConfigFile = File(repo, ".run/ePC-SAFT - Validation.run.xml")
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
            acceptedRepos = listOf(
                CodexWorkspaceSyncService.ResolvedRepo(
                    repoRootPath = repo.absolutePath,
                    source = "test"
                )
            ),
            workspaceProjectPath = project.basePath
        )

        assertEquals(0, result.imported)
        assertEquals(0, result.removed)
        assertTrue(runConfigFile.isFile)
        assertTrue(RunManager.getInstance(project).allSettings.any { it.name == "ePC-SAFT: Validation" })
    }

    fun testSyncPrunesStaleImportedRepoRunConfigurations() {
        val repo = createRepoRunConfig("repo", "repo: Validation")
        val runConfigFile = File(repo, ".run/repo - Validation.run.xml")
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
        assertTrue(runConfigFile.isFile)
        assertTrue(RunManager.getInstance(project).allSettings.none { it.name == "repo: Validation" })
    }

    fun testSyncPrunesAcceptedRepoRunConfigurationsMissingFromDisk() {
        val repo = createRepoRunConfig("repo", "repo: Validation")
        val extra = writeRepoRunConfig(File(repo, ".run"), "repo - Extra.run.xml", "repo: Extra")
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

        assertTrue(extra.delete())
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

        assertEquals(0, result.imported)
        assertEquals(1, result.removed)
        assertTrue(RunManager.getInstance(project).allSettings.none { it.name == "repo: Extra" })
        assertTrue(RunManager.getInstance(project).allSettings.any { it.name == "repo: Validation" })
    }

    fun testSyncPrunesWorkspaceStoredGeneratedRunConfigurationsForRejectedRepo() {
        val staleRepo = File(project.basePath!!, "stale-repo").also { it.mkdirs() }
        RepoRunConfigurationSynchronizer.sync(
            project = project,
            acceptedRepos = listOf(CodexWorkspaceSyncService.ResolvedRepo(staleRepo.absolutePath, "test")),
            workspaceProjectPath = project.basePath
        )
        RunManager.getInstance(project).allSettings
            .single { it.name == "stale-repo: Maintenance - Git Status" }
            .storeInLocalWorkspace()

        val result = RepoRunConfigurationSynchronizer.sync(
            project = project,
            acceptedRepos = emptyList(),
            workspaceProjectPath = project.basePath
        )

        assertEquals(0, result.imported)
        assertEquals(1, result.removed)
        assertTrue(RunManager.getInstance(project).allSettings.none { it.name == "stale-repo: Maintenance - Git Status" })
    }

    fun testSyncPrunesGeneratedFallbacksWhenRepoAdoptsRunFiles() {
        val repoId = "adopts-run-files-repo"
        val repo = File(project.basePath!!, repoId).also { it.mkdirs() }
        RepoRunConfigurationSynchronizer.sync(
            project = project,
            acceptedRepos = listOf(CodexWorkspaceSyncService.ResolvedRepo(repo.absolutePath, "test")),
            workspaceProjectPath = project.basePath
        )
        RunManager.getInstance(project).allSettings
            .single { it.name == "$repoId: Maintenance - Git Status" }
            .storeInLocalWorkspace()
        val runDir = File(repo, ".run").also { it.mkdirs() }
        writeRepoRunConfig(runDir, "$repoId - Validation.run.xml", "$repoId: Validation")

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
        assertEquals(1, result.removed)
        assertTrue(RunManager.getInstance(project).allSettings.none { it.name == "$repoId: Maintenance - Git Status" })
        assertTrue(RunManager.getInstance(project).allSettings.any { it.name == "$repoId: Validation" })
    }

    fun testSyncPrunesRepoQualifiedRunConfigurationsWithStaleFolderName() {
        val repoId = "stale-folder-repo"
        val repo = File(project.basePath!!, repoId).also { it.mkdirs() }
        RepoRunConfigurationSynchronizer.sync(
            project = project,
            acceptedRepos = listOf(CodexWorkspaceSyncService.ResolvedRepo(repo.absolutePath, "test")),
            workspaceProjectPath = project.basePath
        )
        RunManager.getInstance(project).allSettings
            .single { it.name == "$repoId: Maintenance - Git Status" }
            .setFolderName("issue-0190-stale-worktree")

        val result = RepoRunConfigurationSynchronizer.sync(
            project = project,
            acceptedRepos = listOf(CodexWorkspaceSyncService.ResolvedRepo(repo.absolutePath, "test")),
            workspaceProjectPath = project.basePath
        )

        assertEquals(1, result.imported)
        assertEquals(1, result.removed)
        val settings = RunManager.getInstance(project).allSettings.single { it.name == "$repoId: Maintenance - Git Status" }
        assertEquals(repoId, settings.folderName)
    }

    fun testBuildGeneratedFallbackRunConfigurationsForRepoWithoutRunFiles() {
        val repo = File(project.basePath!!, "generated-repo").also { it.mkdirs() }
        File(repo, "pyproject.toml").writeText("[project]\nname = \"generated-repo\"\n")
        File(repo, "uv.lock").writeText("")
        File(repo, "package.json").writeText("{}\n")
        File(repo, "scripts").mkdirs()
        File(repo, "scripts/validate-plugin.ps1").writeText("Write-Output ok\n")
        File(repo, "scripts/package-plugin.ps1").writeText("Write-Output ok\n")
        File(repo, "scripts/verify-bridge-roadmap.ps1").writeText("Write-Output ok\n")
        val helper = File(project.basePath!!, "bridge-repo-task.ps1")

        val generated = RepoRunConfigurationSynchronizer.buildGeneratedRunConfigFiles(
            repoRoot = repo,
            repoId = "generated-repo",
            project = project,
            helperScript = helper
        )

        assertEquals(
            listOf(
                "generated-repo: Maintenance - Git Status",
                "generated-repo: Setup & Health - uv Environment",
                "generated-repo: Setup & Health - Node Environment",
                "generated-repo: Validation - Plugin",
                "generated-repo: Build & Package - Plugin",
                "generated-repo: Validation - Bridge Roadmap"
            ),
            generated.map { it.name }
        )
        assertTrue(generated.all { it.typeId == "PowerShellRunType" })
        assertTrue(generated.all { it.element.getAttributeValue("folderName") == "generated-repo" })
        assertTrue(generated.all { it.file.absolutePath.contains("bridge-generated-run-configs") })
    }

    private fun createRepoRunConfig(repoName: String, configName: String): File {
        val repo = File(project.basePath!!, repoName).also { it.mkdirs() }
        val runDir = File(repo, ".run").also { it.mkdirs() }
        writeRepoRunConfig(runDir, "$repoName - Validation.run.xml", configName)
        return repo
    }

    private fun writeRepoRunConfig(runDir: File, fileName: String, configName: String): File {
        val file = File(runDir, fileName)
        file.writeText(
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
        return file
    }
}
