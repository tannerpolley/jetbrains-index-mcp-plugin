package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceRepoEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceSkippedPath
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexWorkspaceSyncResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
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

    private val codexStateRootKeys = setOf(
        "active-workspace-roots",
        "electron-saved-workspace-roots",
        "thread-workspace-root-hints",
        "project-order"
    )

    data class Options(
        val dryRun: Boolean = false,
        val codexStatePath: String? = null,
        val includeWorktrees: Boolean = true
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
        val skipped: List<CodexWorkspaceSkippedPath>
    )

    data class PreparedSync(
        val options: Options,
        val stateFile: File,
        val discoverySkipped: List<CodexWorkspaceSkippedPath>,
        val plan: Plan,
        val existingRepoRoots: List<String>,
        val workspaceProjectPath: String?
    )

    fun defaultCodexStateFile(): File =
        File(System.getProperty("user.home"), ".codex/.codex-global-state.json")

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
        val existingRepoRoots = RepoScopeRegistry.collectOpenRepoScopes().map { it.repoRootPath }
        val workspaceProjectPath = project.basePath?.let { RepoScopeRegistry.normalizeRepoRootPath(it) }
        val plan = buildPlan(
            candidates = discovery.candidates,
            existingRepoRoots = existingRepoRoots,
            workspaceProjectPath = workspaceProjectPath,
            includeWorktrees = options.includeWorktrees
        )

        return PreparedSync(
            options = options,
            stateFile = stateFile,
            discoverySkipped = discovery.skipped,
            plan = plan,
            existingRepoRoots = existingRepoRoots,
            workspaceProjectPath = workspaceProjectPath
        )
    }

    fun sync(project: Project, options: Options = Options()): CodexWorkspaceSyncResult {
        val prepared = prepare(project, options)
        return if (options.dryRun) {
            buildResult(prepared, attached = emptyList(), errors = emptyList())
        } else {
            applyPrepared(project, prepared)
        }
    }

    fun applyPrepared(project: Project, prepared: PreparedSync): CodexWorkspaceSyncResult {
        val attached = mutableListOf<ResolvedRepo>()
        val errors = mutableListOf<CodexWorkspaceSkippedPath>()

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
        if (attached.isNotEmpty()) {
            RepoWorkspaceMutator.persistWorkspaceProject(project)
            McpServerService.getInstance().notifyEndpointListChanged()
        }

        return buildResult(prepared, attached, errors)
    }

    fun buildResult(
        prepared: PreparedSync,
        attached: List<ResolvedRepo>,
        errors: List<CodexWorkspaceSkippedPath>
    ): CodexWorkspaceSyncResult {
        val plan = prepared.plan
        val finalRepoRoots = (prepared.existingRepoRoots + plan.accepted.map { it.repoRootPath }).distinct()
        val attachedEntries = buildEntries(attached, finalRepoRoots, prepared.workspaceProjectPath)
        val alreadyAttachedEntries = buildEntries(plan.alreadyAttached, finalRepoRoots, prepared.workspaceProjectPath)
        val acceptedEntries = buildEntries(plan.accepted, finalRepoRoots, prepared.workspaceProjectPath)

        val message = when {
            prepared.discoverySkipped.isNotEmpty() && plan.discovered == 0 ->
                "Codex workspace sync found no repo candidates."
            prepared.options.dryRun ->
                "Codex workspace sync dry-run found ${plan.toAttach.size} repo(s) to attach."
            errors.isNotEmpty() ->
                "Codex workspace sync attached ${attached.size} repo(s) and reported ${errors.size} error(s)."
            attached.isNotEmpty() ->
                "Codex workspace sync attached ${attached.size} repo(s)."
            else ->
                "Codex workspace sync found no missing repo roots."
        }

        return CodexWorkspaceSyncResult(
            codexStatePath = prepared.stateFile.absolutePath,
            dryRun = prepared.options.dryRun,
            discovered = plan.discovered,
            accepted = acceptedEntries,
            alreadyAttached = alreadyAttachedEntries,
            attached = attachedEntries,
            skipped = prepared.discoverySkipped + plan.skipped,
            errors = errors,
            message = message
        )
    }

    fun buildPlan(
        candidates: List<Candidate>,
        existingRepoRoots: Collection<String>,
        workspaceProjectPath: String?,
        includeWorktrees: Boolean = true,
        resolveGitRoot: (String) -> String? = ::resolveGitRootPath,
        listWorktrees: (String) -> List<String> = ::listGitWorktreePaths
    ): Plan {
        val skipped = mutableListOf<CodexWorkspaceSkippedPath>()
        val resolvedByRoot = linkedMapOf<String, MutableSet<String>>()

        fun addResolved(repoRootPath: String, source: String) {
            val normalized = RepoScopeRegistry.normalizeRepoRootPath(repoRootPath)
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

            addResolved(repoRootPath, candidate.source)

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
                    addResolved(worktreeRootPath, "git-worktree:$repoRootPath")
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

        return Plan(
            discovered = candidates.size,
            accepted = accepted,
            alreadyAttached = alreadyAttached,
            toAttach = toAttach,
            skipped = skipped
        )
    }

    fun extractCandidatesFromStateText(text: String): List<Candidate> {
        val root = parser.parseToJsonElement(text)
        val candidatesByPath = linkedMapOf<String, MutableSet<String>>()

        fun addCandidate(path: String, source: String) {
            val trimmed = path.trim()
            if (!looksLikeLocalPath(trimmed)) return
            candidatesByPath.getOrPut(trimmed) { linkedSetOf() }.add(source)
        }

        fun visit(element: JsonElement, activeSource: String?) {
            when (element) {
                is JsonObject -> {
                    for ((key, value) in element) {
                        val source = if (key in codexStateRootKeys) key else activeSource
                        visit(value, source)
                    }
                }
                is JsonArray -> element.forEach { visit(it, activeSource) }
                is JsonPrimitive -> {
                    val source = activeSource ?: return
                    val value = element.jsonPrimitive.contentOrNull ?: return
                    addCandidate(value, source)
                }
            }
        }

        visit(root, null)
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
            Discovery(extractCandidatesFromStateText(stateFile.readText()), emptyList())
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
}
