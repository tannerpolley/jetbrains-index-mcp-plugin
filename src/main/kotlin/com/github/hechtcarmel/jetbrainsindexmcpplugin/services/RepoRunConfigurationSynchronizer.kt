package com.github.hechtcarmel.jetbrainsindexmcpplugin.services

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.CodexWorkspaceSyncService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceSkippedPath
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import java.io.File
import org.jdom.Element

object RepoRunConfigurationSynchronizer {
    private val LOG = logger<RepoRunConfigurationSynchronizer>()

    data class SyncResult(
        val imported: Int,
        val removed: Int,
        val errors: List<CodexWorkspaceSkippedPath>
    )

    data class RunConfigFile(
        val file: File,
        val repoId: String,
        val name: String,
        val typeId: String,
        val element: Element
    )

    fun sync(
        project: Project,
        acceptedRepos: List<CodexWorkspaceSyncService.ResolvedRepo>,
        workspaceProjectPath: String?
    ): SyncResult {
        if (acceptedRepos.isEmpty()) {
            return SyncResult(imported = 0, removed = pruneStaleImportedConfigs(project, emptySet()), errors = emptyList())
        }

        val runManager = RunManager.getInstance(project)
        val runManagerImpl = runManager as? RunManagerImpl
            ?: throw IllegalStateException("Workspace RunManager does not support repo .run import.")
        val repoIdsByRoot = RepoScopeRegistry.buildScopes(
            acceptedRepos.map { it.repoRootPath }.distinct(),
            workspaceProjectPath
        ).associateBy { RepoScopeRegistry.normalizeRepoRootPath(it.repoRootPath) }
        val acceptedRepoIds = repoIdsByRoot.values.mapTo(mutableSetOf()) { it.repoId.lowercase() }
        val errors = mutableListOf<CodexWorkspaceSkippedPath>()
        var imported = 0

        val removed = pruneStaleImportedConfigs(project, acceptedRepoIds)

        for (repo in acceptedRepos) {
            val normalizedRoot = RepoScopeRegistry.normalizeRepoRootPath(repo.repoRootPath)
            val repoId = repoIdsByRoot[normalizedRoot]?.repoId ?: continue
            for (runConfigFile in collectRunConfigFiles(File(normalizedRoot))) {
                try {
                    val parsed = parseRunConfigFile(runConfigFile, repoId)
                    if (ensureImported(runManager, runManagerImpl, parsed, project)) {
                        imported += 1
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to import repo run configuration ${runConfigFile.absolutePath}", e)
                    errors += CodexWorkspaceSkippedPath(
                        path = runConfigFile.absolutePath,
                        source = "repo-run-config:$repoId",
                        reason = "run_config_import_failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
        }

        return SyncResult(imported = imported, removed = removed, errors = errors)
    }

    fun collectRunConfigFiles(repoRoot: File): List<File> {
        val runDir = File(repoRoot, ".run")
        if (!runDir.isDirectory) {
            return emptyList()
        }

        return runDir
            .listFiles { file -> file.isFile && file.name.endsWith(".run.xml", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun parseRunConfigFile(file: File, repoId: String): RunConfigFile {
        val root = JDOMUtil.load(file)
        val configuration = when (root.name) {
            "component" -> root.getChild("configuration")
            "configuration" -> root
            else -> null
        }?.clone() as? Element
            ?: throw IllegalArgumentException("Run config XML has no configuration element.")

        if (configuration.getAttributeValue("default").equals("true", ignoreCase = true)) {
            throw IllegalArgumentException("Default run configuration templates cannot be imported from repo .run files.")
        }

        val name = configuration.getAttributeValue("name")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Run config XML is missing a name.")
        val typeId = configuration.getAttributeValue("type")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Run config XML is missing a type.")

        configuration.setAttribute("folderName", repoId)
        return RunConfigFile(
            file = file,
            repoId = repoId,
            name = name,
            typeId = typeId,
            element = configuration
        )
    }

    private fun ensureImported(
        runManager: RunManager,
        runManagerImpl: RunManagerImpl,
        runConfigFile: RunConfigFile,
        project: Project
    ): Boolean {
        val expectedPath = normalizeStoredPath(project, runConfigFile.file.absolutePath)
        val matching = runManager.allSettings.filter { settings ->
            settings.name == runConfigFile.name &&
                settings.type.id == runConfigFile.typeId &&
                settings.folderName.equals(runConfigFile.repoId, ignoreCase = true)
        }

        val alreadyImported = matching.any { settings ->
            normalizeStoredPath(project, settings.pathIfStoredInArbitraryFileInProject) == expectedPath
        }
        if (alreadyImported) {
            return false
        }

        for (settings in matching) {
            runManager.removeConfiguration(settings)
        }

        val imported = runManagerImpl.loadConfiguration(runConfigFile.element, false)
        imported.setFolderName(runConfigFile.repoId)
        imported.setTemporary(false)
        imported.storeInArbitraryFileInProject(runConfigFile.file.absolutePath)
        return true
    }

    private fun pruneStaleImportedConfigs(project: Project, acceptedRepoIds: Set<String>): Int {
        val runManager = RunManager.getInstance(project)
        val staleSettings = runManager.allSettings.filter { settings ->
            val folderName = settings.folderName?.takeIf { it.isNotBlank() } ?: return@filter false
            if (folderName.lowercase() in acceptedRepoIds) return@filter false
            val storedPath = normalizeStoredPath(project, settings.pathIfStoredInArbitraryFileInProject) ?: return@filter false
            storedPath.contains("/.run/") && storedPath.endsWith(".run.xml")
        }

        for (settings in staleSettings) {
            runManager.removeConfiguration(settings)
        }
        return staleSettings.size
    }

    private fun normalizeStoredPath(project: Project, path: String?): String? {
        val raw = path?.takeIf { it.isNotBlank() } ?: return null
        val file = File(raw)
        val absolute = if (file.isAbsolute) {
            file.absolutePath
        } else {
            val basePath = project.basePath ?: return RepoScopeRegistry.normalizeRepoRootPath(raw)
            File(basePath, raw).absolutePath
        }
        return RepoScopeRegistry.normalizeRepoRootPath(absolute)
    }
}
