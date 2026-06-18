package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceModuleEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceRepoEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceSkippedPath
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceSyncResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.services.RepoRunConfigurationSynchronizer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object CodexWorkspaceSyncService {
    private val LOG = logger<CodexWorkspaceSyncService>()
    private val parser = Json { ignoreUnknownKeys = true }
    private val sessionIdPattern =
        Regex("""([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$""")

    data class Options(
        val dryRun: Boolean = false,
        val codexStatePath: String? = null,
        val includeWorktrees: Boolean = true,
        val githubOwner: String? = null
    )

    data class Candidate(
        val path: String,
        val source: String
    )

    data class ResolvedRepo(
        val repoRootPath: String,
        val source: String
    )

    data class Plan(
        val discovered: Int,
        val accepted: List<ResolvedRepo>,
        val alreadyAttached: List<ResolvedRepo>,
        val toAttach: List<ResolvedRepo>,
        val toDetach: List<RepoScope>,
        val toDetachModules: List<WorkspaceModuleScope>,
        val skipped: List<CodexWorkspaceSkippedPath>
    )

    data class PreparedSync(
        val options: Options,
        val stateFile: File,
        val discoverySkipped: List<CodexWorkspaceSkippedPath>,
        val plan: Plan,
        val existingRepoRoots: List<String>,
        val existingModules: List<WorkspaceModuleScope>,
        val workspaceProjectPath: String?
    )

    fun defaultCodexStateFile(): File =
        File(System.getProperty("user.home"), ".codex/.codex-global-state.json")

    fun defaultCodexHome(): File =
        File(System.getProperty("user.home"), ".codex")

    fun shouldAutoSyncProject(project: Project): Boolean {
        val basePath = project.basePath ?: return false
        return File(basePath).name.equals("Workspace", ignoreCase = true)
    }

    fun prepare(project: Project, options: Options = Options()): PreparedSync {
        val stateFile = options.codexStatePath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)
            ?: defaultCodexStateFile()

        val discovery = readStateCandidates(stateFile)
        val existingRepoRoots = RepoScopeRegistry.collectProjectRepoScopes(project).map { it.repoRootPath }
        val existingModules = RepoScopeRegistry.collectProjectWorkspaceModules(project)
        val workspaceProjectPath = project.basePath?.let { RepoScopeRegistry.normalizeRepoRootPath(it) }
        val githubOwner = options.githubOwner ?: McpSettings.getInstance().codexWorkspaceGitHubOwner
        val plan = buildPlan(
            candidates = discovery.candidates,
            existingRepoRoots = existingRepoRoots,
            existingModules = existingModules,
            workspaceProjectPath = workspaceProjectPath,
            includeWorktrees = options.includeWorktrees,
            githubOwner = githubOwner,
            pruneStaleAttached = discovery.skipped.isEmpty()
        )

        return PreparedSync(
            options = options,
            stateFile = stateFile,
            discoverySkipped = discovery.skipped,
            plan = plan,
            existingRepoRoots = existingRepoRoots,
            existingModules = existingModules,
            workspaceProjectPath = workspaceProjectPath
        )
    }

    fun sync(project: Project, options: Options = Options()): CodexWorkspaceSyncResult {
        val prepared = prepare(project, options)
        return if (options.dryRun) {
            buildResult(prepared, attached = emptyList(), detached = emptyList(), detachedModules = emptyList(), errors = emptyList())
        } else {
            applyPrepared(project, prepared).also { persistAppliedChanges(project, it) }
        }
    }

    fun applyPrepared(project: Project, prepared: PreparedSync): CodexWorkspaceSyncResult {
        val attached = mutableListOf<ResolvedRepo>()
        val detached = mutableListOf<RepoScope>()
        val detachedModules = mutableListOf<WorkspaceModuleScope>()
        val errors = mutableListOf<CodexWorkspaceSkippedPath>()
        var runConfigSync = RepoRunConfigurationSynchronizer.SyncResult(imported = 0, removed = 0, errors = emptyList())

        for (scope in prepared.plan.toDetach) {
            try {
                RepoWorkspaceMutator.detachContentRootPath(project, scope.repoRootPath)
                detached.add(scope)
            } catch (e: Exception) {
                LOG.warn("Failed to detach stale Codex repo ${scope.repoRootPath}", e)
                errors.add(
                    CodexWorkspaceSkippedPath(
                        path = scope.repoRootPath,
                        source = "stale-workspace-root:${scope.repoId}",
                        reason = "detach_failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                )
            }
        }

        for (module in prepared.plan.toDetachModules) {
            try {
                detachedModules.add(RepoWorkspaceMutator.detachWorkspaceModule(project, module))
            } catch (e: Exception) {
                LOG.warn("Failed to detach stale Codex workspace module ${module.moduleName}", e)
                errors.add(
                    CodexWorkspaceSkippedPath(
                        path = module.moduleFilePath,
                        source = "stale-workspace-module:${module.moduleName}",
                        reason = "detach_module_failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                )
            }
        }

        for (repo in prepared.plan.toAttach) {
            try {
                RepoWorkspaceMutator.attachContentRoot(project, repo.repoRootPath)
                attached.add(repo)
            } catch (e: Exception) {
                LOG.warn("Failed to attach Codex repo ${repo.repoRootPath}", e)
                errors.add(
                    CodexWorkspaceSkippedPath(
                        path = repo.repoRootPath,
                        source = repo.source,
                        reason = "attach_failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                )
            }
        }

        try {
            runConfigSync = RepoRunConfigurationSynchronizer.sync(
                project = project,
                acceptedRepos = prepared.plan.accepted,
                workspaceProjectPath = prepared.workspaceProjectPath
            )
            errors += runConfigSync.errors
        } catch (e: Exception) {
            LOG.warn("Failed to sync repo run configurations for ${project.name}", e)
            errors.add(
                CodexWorkspaceSkippedPath(
                    path = project.basePath.orEmpty(),
                    source = "repo-run-config-sync",
                    reason = "run_config_sync_failed: ${e.message ?: e.javaClass.simpleName}"
                )
            )
        }

        if (
            attached.isNotEmpty() ||
            detached.isNotEmpty() ||
            detachedModules.isNotEmpty() ||
            runConfigSync.imported > 0 ||
            runConfigSync.removed > 0
        ) {
            McpServerService.getInstance().notifyEndpointListChanged()
        }

        return buildResult(prepared, attached, detached, detachedModules, errors, runConfigSync)
    }

    fun persistAppliedChanges(project: Project, result: CodexWorkspaceSyncResult) {
        if (
            result.attached.isNotEmpty() ||
            result.detached.isNotEmpty() ||
            result.detachedModules.isNotEmpty() ||
            result.runConfigurationsImported > 0 ||
            result.runConfigurationsRemoved > 0
        ) {
            RepoWorkspaceMutator.persistWorkspaceProject(project)
        }
    }

    fun buildResult(
        prepared: PreparedSync,
        attached: List<ResolvedRepo>,
        detached: List<RepoScope>,
        detachedModules: List<WorkspaceModuleScope>,
        errors: List<CodexWorkspaceSkippedPath>,
        runConfigSync: RepoRunConfigurationSynchronizer.SyncResult = RepoRunConfigurationSynchronizer.SyncResult(
            imported = 0,
            removed = 0,
            errors = emptyList()
        )
    ): CodexWorkspaceSyncResult {
        val plan = prepared.plan
        val plannedDetached = if (prepared.options.dryRun) plan.toDetach else detached
        val detachedRootSet = plannedDetached.mapTo(mutableSetOf()) { RepoScopeRegistry.normalizeRepoRootPath(it.repoRootPath) }
        val finalRepoRoots = (
            prepared.existingRepoRoots
                .filterNot { RepoScopeRegistry.normalizeRepoRootPath(it) in detachedRootSet } +
                plan.accepted.map { it.repoRootPath }
            ).distinct()
        val attachedEntries = buildEntries(attached, finalRepoRoots, prepared.workspaceProjectPath)
        val alreadyAttachedEntries = buildEntries(plan.alreadyAttached, finalRepoRoots, prepared.workspaceProjectPath)
        val acceptedEntries = buildEntries(plan.accepted, finalRepoRoots, prepared.workspaceProjectPath)
        val toDetachEntries = buildEntries(plan.toDetach) { "stale-workspace-root:${it.repoId}" }
        val detachedEntries = buildEntries(detached) { "stale-workspace-root:${it.repoId}" }
        val toDetachModuleEntries = buildModuleEntries(plan.toDetachModules)
        val detachedModuleEntries = buildModuleEntries(detachedModules)

        val message = when {
            prepared.discoverySkipped.isNotEmpty() && plan.discovered == 0 ->
                "Codex workspace sync found no repo candidates."
            prepared.options.dryRun ->
                "Codex workspace sync dry-run found ${plan.toAttach.size} repo(s) to attach, ${plan.toDetach.size} repo root(s) to detach, and ${plan.toDetachModules.size} module(s) to detach."
            errors.isNotEmpty() ->
                "Codex workspace sync attached ${attached.size} repo(s), detached ${detached.size} repo root(s), detached ${detachedModules.size} module(s), imported ${runConfigSync.imported} run config(s), removed ${runConfigSync.removed} run config(s), and reported ${errors.size} error(s)."
            attached.isNotEmpty() || detached.isNotEmpty() || detachedModules.isNotEmpty() || runConfigSync.imported > 0 || runConfigSync.removed > 0 ->
                "Codex workspace sync attached ${attached.size} repo(s), detached ${detached.size} repo root(s), detached ${detachedModules.size} module(s), imported ${runConfigSync.imported} run config(s), and removed ${runConfigSync.removed} run config(s)."
            else ->
                "Codex workspace sync found no workspace repo changes."
        }

        return CodexWorkspaceSyncResult(
            codexStatePath = prepared.stateFile.absolutePath,
            dryRun = prepared.options.dryRun,
            discovered = plan.discovered,
            accepted = acceptedEntries,
            alreadyAttached = alreadyAttachedEntries,
            attached = attachedEntries,
            toDetach = toDetachEntries,
            detached = detachedEntries,
            toDetachModules = toDetachModuleEntries,
            detachedModules = detachedModuleEntries,
            runConfigurationsImported = runConfigSync.imported,
            runConfigurationsRemoved = runConfigSync.removed,
            skipped = prepared.discoverySkipped + plan.skipped,
            errors = errors,
            message = message
        )
    }

    fun buildPlan(
        candidates: List<Candidate>,
        existingRepoRoots: Collection<String>,
        workspaceProjectPath: String?,
        existingModules: List<WorkspaceModuleScope> = emptyList(),
        includeWorktrees: Boolean = true,
        resolveGitRoot: (String) -> String? = ::resolveGitRootPath,
        listWorktrees: (String) -> List<String> = ::listGitWorktreePaths,
        githubOwner: String? = null,
        listRemoteUrls: (String) -> List<String> = ::listGitRemoteUrls,
        pruneStaleAttached: Boolean = true
    ): Plan {
        val skipped = mutableListOf<CodexWorkspaceSkippedPath>()
        val resolvedByRoot = linkedMapOf<String, MutableSet<String>>()
        val requiredOwner = githubOwner?.trim()?.takeIf { it.isNotEmpty() }

        fun addResolvedIfAllowed(repoRootPath: String, source: String) {
            val normalized = RepoScopeRegistry.normalizeRepoRootPath(repoRootPath)
            if (requiredOwner != null) {
                val remoteUrls = listRemoteUrls(normalized)
                if (remoteUrls.isEmpty()) {
                    skipped.add(CodexWorkspaceSkippedPath(normalized, source, "git_remote_missing"))
                    return
                }

                val remoteOwners = remoteUrls
                    .mapNotNull(::githubOwnerFromRemoteUrl)
                    .distinctBy { it.lowercase() }
                val hasMatchingOwner = remoteOwners.any { it.equals(requiredOwner, ignoreCase = true) }
                if (!hasMatchingOwner) {
                    val ownerText = if (remoteOwners.isEmpty()) "no_github_owner" else remoteOwners.sorted().joinToString("|")
                    skipped.add(CodexWorkspaceSkippedPath(normalized, source, "github_owner_mismatch:$ownerText"))
                    return
                }
            }

            resolvedByRoot.getOrPut(normalized) { linkedSetOf() }.add(source)
        }

        for (candidate in candidates) {
            val candidateFile = File(candidate.path)
            if (!candidateFile.exists()) {
                skipped.add(CodexWorkspaceSkippedPath(candidate.path, candidate.source, "candidate_path_missing"))
                continue
            }

            val repoRootPath = resolveGitRoot(candidateFile.absolutePath)
            if (repoRootPath == null) {
                skipped.add(CodexWorkspaceSkippedPath(candidate.path, candidate.source, "no_git_marker"))
                continue
            }

            addResolvedIfAllowed(repoRootPath, candidate.source)

            if (includeWorktrees) {
                for (worktreePath in listWorktrees(repoRootPath)) {
                    val worktreeFile = File(worktreePath)
                    if (!worktreeFile.exists()) {
                        skipped.add(CodexWorkspaceSkippedPath(worktreePath, "git-worktree:$repoRootPath", "worktree_path_missing"))
                        continue
                    }
                    val worktreeRootPath = resolveGitRoot(worktreeFile.absolutePath)
                    if (worktreeRootPath == null) {
                        skipped.add(CodexWorkspaceSkippedPath(worktreePath, "git-worktree:$repoRootPath", "no_git_marker"))
                        continue
                    }
                    addResolvedIfAllowed(worktreeRootPath, "git-worktree:$repoRootPath")
                }
            }
        }

        val accepted = resolvedByRoot.map { (repoRootPath, sources) ->
            ResolvedRepo(repoRootPath, sources.sorted().joinToString(","))
        }.filterNot { repo ->
            workspaceProjectPath != null && RepoScopeRegistry.normalizeRepoRootPath(repo.repoRootPath) == workspaceProjectPath
        }

        val existing = existingRepoRoots.map { RepoScopeRegistry.normalizeRepoRootPath(it) }.toSet()
        val alreadyAttached = accepted.filter { it.repoRootPath in existing }
        val toAttach = accepted.filterNot { it.repoRootPath in existing }
        val acceptedRoots = accepted.mapTo(mutableSetOf()) { RepoScopeRegistry.normalizeRepoRootPath(it.repoRootPath) }
        val normalizedWorkspaceProjectPath = workspaceProjectPath?.let { RepoScopeRegistry.normalizeRepoRootPath(it) }
        val toDetach = if (pruneStaleAttached) {
            RepoScopeRegistry.buildScopes(existingRepoRoots.toList(), workspaceProjectPath)
                .filterNot { scope ->
                    val root = RepoScopeRegistry.normalizeRepoRootPath(scope.repoRootPath)
                    root == normalizedWorkspaceProjectPath || root in acceptedRoots
                }
        } else {
            emptyList()
        }
        val acceptedRepoIds = RepoScopeRegistry.buildScopes(acceptedRoots.toList(), workspaceProjectPath)
            .mapTo(mutableSetOf()) { it.repoId }
        val toDetachModules = if (pruneStaleAttached) {
            existingModules
                .filter { module ->
                    shouldDetachWorkspaceModule(
                        module = module,
                        acceptedRepoIds = acceptedRepoIds,
                        acceptedRoots = acceptedRoots,
                        workspaceProjectPath = normalizedWorkspaceProjectPath
                    )
                }
                .distinctBy { RepoScopeRegistry.normalizeRepoRootPath(it.moduleFilePath).lowercase() }
        } else {
            emptyList()
        }

        return Plan(
            discovered = candidates.size,
            accepted = accepted,
            alreadyAttached = alreadyAttached,
            toAttach = toAttach,
            toDetach = toDetach,
            toDetachModules = toDetachModules,
            skipped = skipped
        )
    }

    private fun shouldDetachWorkspaceModule(
        module: WorkspaceModuleScope,
        acceptedRepoIds: Set<String>,
        acceptedRoots: Set<String>,
        workspaceProjectPath: String?
    ): Boolean {
        val workspaceRoot = workspaceProjectPath ?: return false
        if (!File(workspaceRoot).name.equals("Workspace", ignoreCase = true)) {
            return false
        }
        if (module.moduleName.equals(File(workspaceRoot).name, ignoreCase = true)) {
            return false
        }
        if (module.moduleName in acceptedRepoIds) {
            return false
        }

        val inferredRoot = module.inferredRepoRootPath?.let { RepoScopeRegistry.normalizeRepoRootPath(it) }
        if (inferredRoot != null && inferredRoot in acceptedRoots) {
            return false
        }

        val moduleFilePath = RepoScopeRegistry.normalizeRepoRootPath(module.moduleFilePath)
        val workspaceIdeaPath = "$workspaceRoot/.idea/"
        val pluginManagedWorkspaceModule = moduleFilePath.startsWith(workspaceIdeaPath) ||
            (moduleFilePath.contains("/.idea/") && moduleFilePath.endsWith("/${module.moduleName}.iml"))
        val externalRepoModule = inferredRoot != null
        return pluginManagedWorkspaceModule || externalRepoModule
    }

    fun extractCandidatesFromStateText(
        text: String,
        nonArchivedThreadIds: Set<String> = emptySet()
    ): List<Candidate> {
        val root = parser.parseToJsonElement(text)
        if (root !is JsonObject) return emptyList()
        val candidatesByPath = linkedMapOf<String, MutableSet<String>>()

        fun addCandidate(path: String, source: String) {
            val trimmed = path.trim()
            if (!looksLikeLocalPath(trimmed)) return
            candidatesByPath.getOrPut(trimmed) { linkedSetOf() }.add(source)
        }

        val activeWorkspaceRoots = extractLocalPaths(root["active-workspace-roots"])
        val savedWorkspaceRoots = extractLocalPaths(root["electron-saved-workspace-roots"])
        val openWorkspaceRootKeys = (activeWorkspaceRoots + savedWorkspaceRoots)
            .map(::localPathKey)
            .toSet()

        for (path in activeWorkspaceRoots) {
            addCandidate(path, "active-workspace-roots")
        }

        val threadHints = root["thread-workspace-root-hints"] as? JsonObject ?: JsonObject(emptyMap())
        for ((threadId, value) in threadHints) {
            if (threadId !in nonArchivedThreadIds) continue
            for (path in extractLocalPaths(value)) {
                if (localPathKey(path) in openWorkspaceRootKeys) {
                    addCandidate(path, "active-thread:$threadId")
                }
            }
        }

        return candidatesByPath.map { (path, sources) ->
            Candidate(path, sources.sorted().joinToString(","))
        }
    }

    fun resolveGitRootPath(path: String): String? {
        val startFile = File(path)
        if (!startFile.exists()) return null
        var current: File? = if (startFile.isDirectory) startFile.canonicalFile else startFile.parentFile?.canonicalFile
        while (current != null) {
            if (File(current, ".git").exists()) {
                return RepoScopeRegistry.normalizeRepoRootPath(current.absolutePath)
            }
            current = current.parentFile
        }
        return null
    }

    fun listGitWorktreePaths(repoRootPath: String): List<String> {
        return try {
            val process = ProcessBuilder("git", "-C", repoRootPath, "worktree", "list", "--porcelain")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return emptyList()
            }
            if (process.exitValue() != 0) {
                return emptyList()
            }
            process.inputStream.bufferedReader().use { reader ->
                reader.readText()
                    .lineSequence()
                    .mapNotNull { line -> line.removePrefix("worktree ").takeIf { it != line } }
                    .map { RepoScopeRegistry.normalizeRepoRootPath(it) }
                    .toList()
            }
        } catch (e: Exception) {
            LOG.debug("Failed to list Git worktrees for $repoRootPath", e)
            emptyList()
        }
    }

    fun listGitRemoteUrls(repoRootPath: String): List<String> {
        return try {
            val process = ProcessBuilder("git", "-C", repoRootPath, "remote", "-v")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return emptyList()
            }
            if (process.exitValue() != 0) {
                return emptyList()
            }
            process.inputStream.bufferedReader().use { reader ->
                reader.readText()
                    .lineSequence()
                    .mapNotNull { line -> line.trim().split(Regex("\\s+")).getOrNull(1) }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .toList()
            }
        } catch (e: Exception) {
            LOG.debug("Failed to list Git remotes for $repoRootPath", e)
            emptyList()
        }
    }

    fun githubOwnerFromRemoteUrl(remoteUrl: String): String? {
        val trimmed = remoteUrl.trim().removeSuffix("/")
        val match = Regex("""(?i)github\.com[:/]+([^/\s:]+)/[^/\s]+(?:\.git)?$""").find(trimmed)
            ?: return null
        return match.groupValues[1].takeIf { it.isNotBlank() }
    }

    fun readNonArchivedThreadIds(codexHome: File = defaultCodexHome()): Set<String> {
        val sessionsDir = File(codexHome, "sessions")
        if (!sessionsDir.exists()) return emptySet()

        return try {
            Files.walk(sessionsDir.toPath()).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl", ignoreCase = true) }
                    .map { path ->
                        sessionIdPattern.find(path.fileName.toString().removeSuffix(".jsonl"))?.groupValues?.get(1)
                    }
                    .filter { it != null }
                    .map { it!! }
                    .map { it.lowercase() }
                    .toList()
                    .toSet()
            }
        } catch (e: Exception) {
            LOG.debug("Failed to read non-archived Codex thread ids from ${sessionsDir.absolutePath}", e)
            emptySet()
        }
    }

    private data class Discovery(
        val candidates: List<Candidate>,
        val skipped: List<CodexWorkspaceSkippedPath>
    )

    private fun readStateCandidates(stateFile: File): Discovery {
        if (!stateFile.exists()) {
            return Discovery(
                candidates = emptyList(),
                skipped = listOf(CodexWorkspaceSkippedPath(stateFile.absolutePath, "codex-state", "codex_state_file_missing"))
            )
        }

        return try {
            Discovery(
                extractCandidatesFromStateText(
                    text = stateFile.readText(),
                    nonArchivedThreadIds = readNonArchivedThreadIds(stateFile.parentFile ?: defaultCodexHome())
                ),
                emptyList()
            )
        } catch (e: Exception) {
            Discovery(
                candidates = emptyList(),
                skipped = listOf(
                    CodexWorkspaceSkippedPath(
                        stateFile.absolutePath,
                        "codex-state",
                        "codex_state_json_error: ${e.message ?: e.javaClass.simpleName}"
                    )
                )
            )
        }
    }

    private fun looksLikeLocalPath(value: String): Boolean {
        if (value.length < 3 || value.contains('\n')) return false
        return value.contains(":\\") ||
            value.contains(":/") ||
            value.startsWith("/") ||
            value.startsWith("\\\\")
    }

    private fun extractLocalPaths(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        val paths = mutableListOf<String>()

        fun visit(value: JsonElement) {
            when (value) {
                is JsonArray -> value.forEach(::visit)
                is JsonObject -> value.values.forEach(::visit)
                is JsonPrimitive -> {
                    val text = value.jsonPrimitive.contentOrNull ?: return
                    if (looksLikeLocalPath(text)) {
                        paths += text
                    }
                }
            }
        }

        visit(element)
        return paths
    }

    private fun localPathKey(path: String): String =
        path.trim().replace('\\', '/').trimEnd('/').lowercase()

    private fun buildEntries(
        repos: List<ResolvedRepo>,
        scopeRoots: List<String>,
        workspaceProjectPath: String?
    ): List<CodexWorkspaceRepoEntry> {
        if (repos.isEmpty()) return emptyList()
        val scopesByRoot = RepoScopeRegistry.buildScopes(scopeRoots, workspaceProjectPath)
            .associateBy { RepoScopeRegistry.normalizeRepoRootPath(it.repoRootPath) }

        return repos.mapNotNull { repo ->
            val scope = scopesByRoot[RepoScopeRegistry.normalizeRepoRootPath(repo.repoRootPath)] ?: return@mapNotNull null
            CodexWorkspaceRepoEntry(
                repoId = scope.repoId,
                repoRootPath = scope.repoRootPath,
                source = repo.source,
                repoScopedStreamableHttpUrl = ClientConfigGenerator.buildRepoScopedStreamableHttpUrl(
                    broadStreamableHttpUrl = ClientConfigGenerator.getStreamableHttpUrl(),
                    repoId = scope.repoId
                )
            )
        }
    }

    private fun buildEntries(
        scopes: List<RepoScope>,
        source: (RepoScope) -> String
    ): List<CodexWorkspaceRepoEntry> =
        scopes.map { scope ->
            CodexWorkspaceRepoEntry(
                repoId = scope.repoId,
                repoRootPath = scope.repoRootPath,
                source = source(scope),
                repoScopedStreamableHttpUrl = ClientConfigGenerator.buildRepoScopedStreamableHttpUrl(
                    broadStreamableHttpUrl = ClientConfigGenerator.getStreamableHttpUrl(),
                    repoId = scope.repoId
                )
            )
        }

    private fun buildModuleEntries(modules: List<WorkspaceModuleScope>): List<CodexWorkspaceModuleEntry> =
        modules.map { module ->
            CodexWorkspaceModuleEntry(
                moduleName = module.moduleName,
                moduleFilePath = module.moduleFilePath,
                inferredRepoRootPath = module.inferredRepoRootPath,
                source = if (module.isAttached) {
                    "stale-workspace-module:${module.moduleName}"
                } else {
                    "stale-workspace-module-file:${module.moduleName}"
                }
            )
        }
}
