package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
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
    private val basePath: String,
) : DelegatingGlobalSearchScope(baseScope) {

    override fun contains(file: VirtualFile): Boolean {
        if (!super.contains(file)) return false
        val relativePath = file.path.removePrefix(basePath).removePrefix("/")
        return !isExcludedPath(relativePath)
    }
}

class RepoRootScope(
    private val project: Project,
    baseScope: GlobalSearchScope,
    repoRootPath: String,
    private val includeLibraries: Boolean = false
) : DelegatingGlobalSearchScope(baseScope) {

    private val normalizedRepoRootPath = ProjectResolver.normalizePath(repoRootPath)

    override fun contains(file: VirtualFile): Boolean {
        if (!super.contains(file)) return false
        if (includeLibraries && ProjectUtils.isDependencyFile(project, file)) return true

        val normalizedFilePath = ProjectResolver.normalizePath(file.path)
        return normalizedFilePath == normalizedRepoRootPath || normalizedFilePath.startsWith("$normalizedRepoRootPath/")
    }
}

/**
 * Wraps [GlobalSearchScope.projectScope] or [GlobalSearchScope.allScope] with
 * excluded-path filtering so that venv, node_modules, and worktree files are
 * never processed by IntelliJ's search APIs.
 */
fun createFilteredScope(
    project: Project,
    includeLibraries: Boolean = false,
    repoRootPath: String? = null
): GlobalSearchScope {
    val basePath = project.basePath ?: ""
    val baseScope = if (includeLibraries) {
        GlobalSearchScope.allScope(project)
    } else {
        GlobalSearchScope.projectScope(project)
    }
    val filteredScope = ExcludedPathScope(baseScope, basePath)
    return if (repoRootPath.isNullOrBlank()) {
        filteredScope
    } else {
        RepoRootScope(project, filteredScope, repoRootPath, includeLibraries)
    }
}

fun applyRepoRootScope(
    project: Project,
    baseScope: GlobalSearchScope,
    repoRootPath: String,
    includeLibraries: Boolean = false
): GlobalSearchScope = RepoRootScope(project, baseScope, repoRootPath, includeLibraries)
