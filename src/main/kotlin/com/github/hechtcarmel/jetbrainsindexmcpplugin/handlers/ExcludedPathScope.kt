package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PathScopeContext
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeContext
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope

/**
 * A [GlobalSearchScope] that delegates to [baseScope] but rejects files in excluded
 * directories (venv, node_modules, build output, worktrees).
 *
 * Applying the filter at the scope level means IntelliJ's search infrastructure
 * never resolves PSI or allocates buffer slots for excluded files — unlike the
 * post-filter approach which requires over-fetching to compensate.
 */
class ExcludedPathScope(
    baseScope: GlobalSearchScope,
    basePath: String,
    private val enforceBasePath: Boolean = false,
) : DelegatingGlobalSearchScope(baseScope) {
    private val normalizedBasePath = RepoScopeRegistry.normalizeRepoRootPath(basePath)

    override fun contains(file: VirtualFile): Boolean {
        if (!super.contains(file)) return false
        if (enforceBasePath && normalizedBasePath.isNotBlank() && !RepoScopeRegistry.isPathInsideScope(normalizedBasePath, file.path)) {
            return false
        }
        val relativePath = RepoScopeRegistry.normalizeRepoRootPath(file.path)
            .removePrefix(normalizedBasePath)
            .removePrefix("/")
        return !isExcludedPath(relativePath)
    }
}

/**
 * Wraps [GlobalSearchScope.projectScope] or [GlobalSearchScope.allScope] with
 * excluded-path filtering so that venv, node_modules, and worktree files are
 * never processed by IntelliJ's search APIs.
 */
fun createFilteredScope(project: Project, includeLibraries: Boolean = false): GlobalSearchScope {
    val explicitScopeRoot = RepoScopeContext.current()?.repoRootPath
        ?: PathScopeContext.currentRootPath()
    val basePath = explicitScopeRoot ?: project.basePath ?: ""
    val baseScope = if (includeLibraries) {
        GlobalSearchScope.allScope(project)
    } else {
        GlobalSearchScope.projectScope(project)
    }
    return ExcludedPathScope(baseScope, basePath, enforceBasePath = explicitScopeRoot != null)
}
