package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootManager
import java.io.File
import java.security.MessageDigest

object RepoScopeRegistry {
    private val LOG = logger<RepoScopeRegistry>()

    fun normalizeRepoRootPath(path: String): String =
        path.trim().trimEnd('/', '\\').replace('\\', '/')

    fun pathHash8(normalizedCanonicalRepoRootPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedCanonicalRepoRootPath.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { byte -> "%02x".format(byte) }
    }

    fun buildScopes(
        repoRootPaths: List<String>,
        workspaceProjectPath: String?
    ): List<RepoScope> {
        val normalizedRoots = repoRootPaths
            .map { normalizeRepoRootPath(it) }
            .filter { it.isNotBlank() }
            .distinct()

        val leafCounts = normalizedRoots
            .groupingBy { repoLeafName(it) }
            .eachCount()

        return normalizedRoots.map { rootPath ->
            val leaf = repoLeafName(rootPath)
            val repoId = if ((leafCounts[leaf] ?: 0) == 1) {
                leaf
            } else {
                "$leaf-${pathHash8(rootPath)}"
            }
            RepoScope(
                repoId = repoId,
                repoRootPath = rootPath,
                workspaceProjectPath = workspaceProjectPath?.let { normalizeRepoRootPath(it) }
            )
        }
    }

    fun isGitRepoRootPath(path: String): Boolean =
        File(normalizeRepoRootPath(path), ".git").exists()

    fun selectAgentRepoRootPaths(
        candidatePaths: List<String>,
        isGitRepoRoot: (String) -> Boolean = ::isGitRepoRootPath
    ): List<String> =
        candidatePaths
            .map { normalizeRepoRootPath(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { isGitRepoRoot(it) }

    fun isPathInsideScope(scope: RepoScope, path: String): Boolean {
        val normalizedPath = normalizeRepoRootPath(path)
        val normalizedRoot = normalizeRepoRootPath(scope.repoRootPath)
        return normalizedPath == normalizedRoot || normalizedPath.startsWith("$normalizedRoot/")
    }

    fun collectOpenRepoScopes(): List<RepoScope> {
        if (ApplicationManager.getApplication() == null) {
            return emptyList()
        }

        val scopes = mutableListOf<RepoScope>()
        val openProjects = ProjectManager.getInstance().openProjects
            .filter { !it.isDefault }

        for (project in openProjects) {
            val workspaceProjectPath = project.basePath?.let { normalizeRepoRootPath(it) }
            val rootPaths = collectRepoRootPaths(project)
            scopes += buildScopes(rootPaths, workspaceProjectPath)
        }

        return scopes.distinctBy { it.repoId }
    }

    fun resolveOpenRepoScope(repoId: String): RepoScope? =
        collectOpenRepoScopes().find { it.repoId == repoId }

    fun scopeForPath(repoRootPath: String): RepoScope? {
        val normalized = normalizeRepoRootPath(repoRootPath)
        return collectOpenRepoScopes().find { normalizeRepoRootPath(it.repoRootPath) == normalized }
    }

    private fun collectRepoRootPaths(project: Project): List<String> {
        val roots = mutableListOf<String>()
        project.basePath?.let { roots += it }

        try {
            val modules = ModuleManager.getInstance(project).modules
            for (module in modules) {
                for (root in ModuleRootManager.getInstance(module).contentRoots) {
                    roots += root.path
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to collect repo scope content roots for project ${project.name}", e)
        }

        return selectAgentRepoRootPaths(roots)
    }

    private fun repoLeafName(path: String): String =
        normalizeRepoRootPath(path)
            .substringAfterLast('/')
            .ifBlank { "repo" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "repo" }
}
