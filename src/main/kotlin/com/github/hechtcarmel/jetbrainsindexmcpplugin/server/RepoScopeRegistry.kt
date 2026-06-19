package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager

class RepoScopeRegistry internal constructor(
    private val gitRootPathProvider: () -> List<String>,
    private val projectGitRootPathProvider: (Project) -> List<String>
) {

    constructor() : this(::discoverGitRootPaths, ::discoverGitRootPaths)

    constructor(gitRootPathProvider: () -> List<String>) : this(
        gitRootPathProvider = gitRootPathProvider,
        projectGitRootPathProvider = ::discoverGitRootPaths
    )

    fun listScopes(): List<RepoScopeContext> = buildScopes(gitRootPathProvider())

    fun listScopes(project: Project): List<RepoScopeContext> = buildScopes(projectGitRootPathProvider(project))

    fun findByRepoId(repoId: String): RepoScopeContext? =
        listScopes().firstOrNull { it.repoId == repoId }

    companion object {
        internal fun normalizePath(path: String): String = ProjectResolver.normalizePath(path)

        internal fun buildScopes(gitRootPaths: List<String>): List<RepoScopeContext> {
            val uniquePaths = gitRootPaths
                .map(::normalizePath)
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            val repoIdCounts = mutableMapOf<String, Int>()
            return uniquePaths.map { gitRootPath ->
                val baseRepoId = deriveRepoId(gitRootPath)
                val seenCount = (repoIdCounts[baseRepoId] ?: 0) + 1
                repoIdCounts[baseRepoId] = seenCount

                RepoScopeContext(
                    repoId = if (seenCount == 1) baseRepoId else "$baseRepoId-$seenCount",
                    gitRootPath = gitRootPath
                )
            }
        }

        internal fun deriveRepoId(path: String): String {
            val leafName = normalizePath(path).substringAfterLast('/').ifBlank { "repo" }
            val slug = leafName
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')

            return slug.ifBlank { "repo" }
        }

        private fun discoverGitRootPaths(): List<String> {
            val openProjects = ProjectManager.getInstance().openProjects
                .filter { !it.isDefault }

            return openProjects.flatMap(::discoverGitRootPaths)
        }

        private fun discoverGitRootPaths(project: Project): List<String> {
            if (project.isDefault) {
                return emptyList()
            }

            return ProjectLevelVcsManager.getInstance(project).getAllVcsRoots()
                .asSequence()
                .filter { it.vcs?.name.equals("Git", ignoreCase = true) }
                .mapNotNull { it.path?.path }
                .toList()
        }
    }
}
