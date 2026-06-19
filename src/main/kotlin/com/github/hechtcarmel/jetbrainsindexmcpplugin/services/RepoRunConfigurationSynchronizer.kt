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
    private const val GENERATED_CONFIG_DIR = ".idea/bridge-generated-run-configs"
    private const val GENERATED_HELPER_NAME = "bridge-repo-task.ps1"

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
        val runConfigFilesByRepoId = linkedMapOf<String, List<RunConfigFile>>()

        val generatedHelperScript = ensureGeneratedHelperScript(project)
        for (repo in acceptedRepos) {
            val normalizedRoot = RepoScopeRegistry.normalizeRepoRootPath(repo.repoRootPath)
            val repoId = repoIdsByRoot[normalizedRoot]?.repoId ?: continue
            val repoRoot = File(normalizedRoot)
            val repoRunConfigFiles = collectRunConfigFiles(repoRoot)

            val parsedRepoRunConfigFiles = mutableListOf<RunConfigFile>()
            for (repoRunConfigFile in repoRunConfigFiles) {
                try {
                    parsedRepoRunConfigFiles += parseRunConfigFile(repoRunConfigFile, repoId)
                } catch (e: Exception) {
                    LOG.warn("Failed to parse repo run configuration ${repoRunConfigFile.absolutePath}", e)
                    errors += CodexWorkspaceSkippedPath(
                        path = repoRunConfigFile.absolutePath,
                        source = "repo-run-config:$repoId",
                        reason = "run_config_parse_failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
            runConfigFilesByRepoId[repoId] = parsedRepoRunConfigFiles.ifEmpty {
                if (repoRunConfigFiles.isNotEmpty()) {
                    return@ifEmpty emptyList()
                }
                buildGeneratedRunConfigFiles(
                    repoRoot = repoRoot,
                    repoId = repoId,
                    project = project,
                    helperScript = generatedHelperScript
                )
            }
        }

        val expectedStoredPathsByRepoId = runConfigFilesByRepoId.mapValues { (_, runConfigFiles) ->
            runConfigFiles
                .mapNotNull { normalizeStoredPath(project, it.file.absolutePath) }
                .mapTo(mutableSetOf()) { it.lowercase() }
        }
        val expectedNamesByRepoId = runConfigFilesByRepoId.mapValues { (_, runConfigFiles) ->
            runConfigFiles.mapTo(mutableSetOf()) { it.name.lowercase() }
        }
        val removed = pruneStaleImportedConfigs(
            project = project,
            acceptedRepoIds = acceptedRepoIds,
            expectedStoredPathsByRepoId = expectedStoredPathsByRepoId,
            expectedNamesByRepoId = expectedNamesByRepoId
        )

        for ((repoId, runConfigFiles) in runConfigFilesByRepoId) {
            for (parsed in runConfigFiles) {
                try {
                    if (ensureImported(runManager, runManagerImpl, parsed, project)) {
                        imported += 1
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to import repo run configuration ${parsed.file.absolutePath}", e)
                    errors += CodexWorkspaceSkippedPath(
                        path = parsed.file.absolutePath,
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

    fun buildGeneratedRunConfigFiles(
        repoRoot: File,
        repoId: String,
        project: Project,
        helperScript: File
    ): List<RunConfigFile> {
        val specs = buildGeneratedRunConfigSpecs(repoRoot, repoId)
        if (specs.isEmpty()) {
            return emptyList()
        }

        val generatedDir = File(project.basePath ?: repoRoot.absolutePath, GENERATED_CONFIG_DIR)
        val repoDir = File(generatedDir, safeFileSegment(repoId)).also { it.mkdirs() }
        return specs.map { spec ->
            val file = File(repoDir, "${safeFileSegment(spec.name)}.run.xml")
            RunConfigFile(
                file = file,
                repoId = repoId,
                name = spec.name,
                typeId = "PowerShellRunType",
                element = powerShellConfigurationElement(
                    name = spec.name,
                    repoId = repoId,
                    repoRoot = repoRoot,
                    helperScript = helperScript,
                    task = spec.task
                )
            )
        }
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

    private fun pruneStaleImportedConfigs(
        project: Project,
        acceptedRepoIds: Set<String>,
        expectedStoredPathsByRepoId: Map<String, Set<String>> = emptyMap(),
        expectedNamesByRepoId: Map<String, Set<String>> = emptyMap()
    ): Int {
        val runManager = RunManager.getInstance(project)
        val staleSettings = runManager.allSettings.filter { settings ->
            val nameRepoId = repoIdFromQualifiedName(settings.name)
            val folderName = settings.folderName?.takeIf { it.isNotBlank() }
            val repoId = nameRepoId ?: folderName ?: return@filter false
            val repoKey = repoId.lowercase()
            val storedPath = normalizeStoredPath(project, settings.pathIfStoredInArbitraryFileInProject)
            val isRepoOwned = storedPath?.let { it.contains("/.run/") && it.endsWith(".run.xml") } ?: false
            val isGenerated = isGeneratedRunConfiguration(project, settings, storedPath)
            val isFolderForDifferentRepo = folderName != null && !folderName.equals(repoId, ignoreCase = true)
            val isFolderForRejectedRepo = folderName != null && folderName.lowercase() !in acceptedRepoIds
            val isManaged = isRepoOwned || isGenerated || nameRepoId != null || isFolderForRejectedRepo
            when {
                repoKey !in acceptedRepoIds -> isManaged
                isFolderForDifferentRepo -> true
                isManaged -> {
                    val storedPathIsExpected = storedPath?.lowercase() in expectedStoredPathsByRepoId.orEmpty(repoKey)
                    val nameIsExpected = settings.name.lowercase() in expectedNamesByRepoId.orEmpty(repoKey)
                    !storedPathIsExpected && !nameIsExpected
                }
                else -> false
            }
        }

        for (settings in staleSettings) {
            runManager.removeConfiguration(settings)
        }
        return staleSettings.size
    }

    private fun repoIdFromQualifiedName(name: String): String? {
        val separatorIndex = name.indexOf(':')
        if (separatorIndex <= 0) {
            return null
        }
        return name.substring(0, separatorIndex).takeIf { it.isNotBlank() }
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

    private fun isGeneratedRunConfiguration(
        project: Project,
        settings: RunnerAndConfigurationSettings,
        storedPath: String?
    ): Boolean {
        if (storedPath?.contains("/$GENERATED_CONFIG_DIR/") == true && storedPath.endsWith(".run.xml")) {
            return true
        }

        val scriptUrl = configurationAttribute(settings, "scriptUrl") ?: return false
        val expandedScriptUrl = project.basePath?.let { scriptUrl.replace("\$PROJECT_DIR$", it) } ?: scriptUrl
        return RepoScopeRegistry.normalizeRepoRootPath(expandedScriptUrl)
            .contains("/$GENERATED_CONFIG_DIR/$GENERATED_HELPER_NAME")
    }

    private fun configurationAttribute(settings: RunnerAndConfigurationSettings, name: String): String? {
        val element = Element("configuration")
        return try {
            settings.configuration.writeExternal(element)
            element.getAttributeValue(name)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private data class GeneratedRunConfigSpec(
        val name: String,
        val task: String
    )

    private fun buildGeneratedRunConfigSpecs(repoRoot: File, repoId: String): List<GeneratedRunConfigSpec> {
        val specs = mutableListOf<GeneratedRunConfigSpec>()
        specs += GeneratedRunConfigSpec("$repoId: Maintenance - Git Status", "GitStatus")
        if (File(repoRoot, "pyproject.toml").isFile && File(repoRoot, "uv.lock").isFile) {
            specs += GeneratedRunConfigSpec("$repoId: Setup & Health - uv Environment", "UvPythonVersion")
        }
        if (File(repoRoot, "package.json").isFile) {
            specs += GeneratedRunConfigSpec("$repoId: Setup & Health - Node Environment", "NodeVersion")
        }
        if (File(repoRoot, "scripts/validate-plugin.ps1").isFile) {
            specs += GeneratedRunConfigSpec("$repoId: Validation - Plugin", "ValidatePlugin")
        }
        if (File(repoRoot, "scripts/package-plugin.ps1").isFile) {
            specs += GeneratedRunConfigSpec("$repoId: Build & Package - Plugin", "PackagePlugin")
        }
        if (File(repoRoot, "scripts/verify-bridge-roadmap.ps1").isFile) {
            specs += GeneratedRunConfigSpec("$repoId: Validation - Bridge Roadmap", "VerifyBridgeRoadmap")
        }
        return specs
    }

    private fun powerShellConfigurationElement(
        name: String,
        repoId: String,
        repoRoot: File,
        helperScript: File,
        task: String
    ): Element {
        return Element("configuration")
            .setAttribute("default", "false")
            .setAttribute("name", name)
            .setAttribute("type", "PowerShellRunType")
            .setAttribute("factoryName", "PowerShell")
            .setAttribute("folderName", repoId)
            .setAttribute("scriptUrl", RepoScopeRegistry.normalizeRepoRootPath(helperScript.absolutePath))
            .setAttribute("workingDirectory", RepoScopeRegistry.normalizeRepoRootPath(repoRoot.absolutePath))
            .setAttribute("commandOptions", "-NoProfile -ExecutionPolicy Bypass")
            .setAttribute(
                "scriptParameters",
                "-RepoRoot '${RepoScopeRegistry.normalizeRepoRootPath(repoRoot.absolutePath)}' -Task $task"
            )
            .addContent(Element("method").setAttribute("v", "2"))
    }

    private fun ensureGeneratedHelperScript(project: Project): File {
        val basePath = project.basePath
            ?: throw IllegalStateException("Workspace project has no base path for generated Services helper.")
        val helperFile = File(File(basePath, GENERATED_CONFIG_DIR).also { it.mkdirs() }, GENERATED_HELPER_NAME)
        val script = generatedHelperScriptText()
        if (!helperFile.isFile || helperFile.readText() != script) {
            helperFile.writeText(script)
        }
        return helperFile
    }

    private fun generatedHelperScriptText(): String =
        """
        [CmdletBinding()]
        param(
            [Parameter(Mandatory = ${'$'}true)]
            [string] ${'$'}RepoRoot,
            [Parameter(Mandatory = ${'$'}true)]
            [ValidateSet('GitStatus', 'UvPythonVersion', 'NodeVersion', 'ValidatePlugin', 'PackagePlugin', 'VerifyBridgeRoadmap')]
            [string] ${'$'}Task
        )

        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}repoRootValue = ${'$'}RepoRoot.Trim([char]39, [char]34)
        ${'$'}repo = Resolve-Path -LiteralPath ${'$'}repoRootValue

        switch (${'$'}Task) {
            'GitStatus' {
                git -C ${'$'}repo.Path status --short --branch
                break
            }
            'UvPythonVersion' {
                uv run --project ${'$'}repo.Path --no-sync python --version
                break
            }
            'NodeVersion' {
                node --version
                npm --prefix ${'$'}repo.Path --version
                break
            }
            'ValidatePlugin' {
                & (Join-Path ${'$'}repo.Path 'scripts/validate-plugin.ps1')
                break
            }
            'PackagePlugin' {
                & (Join-Path ${'$'}repo.Path 'scripts/package-plugin.ps1')
                break
            }
            'VerifyBridgeRoadmap' {
                & (Join-Path ${'$'}repo.Path 'scripts/verify-bridge-roadmap.ps1')
                break
            }
        }
        """.trimIndent() + "\n"

    private fun safeFileSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "run-config" }

    private fun Map<String, Set<String>>.orEmpty(key: String): Set<String> =
        this[key] ?: emptySet()
}
