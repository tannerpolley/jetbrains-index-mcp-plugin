package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

data class RepoScopeContext(
    val repoId: String,
    val gitRootPath: String
) {
    val normalizedGitRootPath: String = RepoScopeRegistry.normalizePath(gitRootPath)
}
