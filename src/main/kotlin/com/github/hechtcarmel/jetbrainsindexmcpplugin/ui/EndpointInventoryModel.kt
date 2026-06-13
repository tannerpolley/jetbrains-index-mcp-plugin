package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator

enum class EndpointScopeKind {
    WORKSPACE,
    REPO
}

enum class EndpointInventoryState {
    INITIALIZING,
    RUNNING,
    OFFLINE,
    ERROR
}

data class EndpointInventoryRow(
    val id: String,
    val scopeKind: EndpointScopeKind,
    val scopeName: String,
    val url: String,
    val rootPath: String,
    val state: EndpointInventoryState
) {
    val copyText: String =
        if (scopeKind == EndpointScopeKind.REPO) "$url | Repo/Module: $scopeName" else url
}

object EndpointInventoryModel {
    fun buildRows(
        broadStreamableHttpUrl: String?,
        projectName: String,
        workspaceProjectPath: String?,
        repoScopes: List<RepoScope>,
        serverState: EndpointInventoryState
    ): List<EndpointInventoryRow> {
        val broadRow = EndpointInventoryRow(
            id = "workspace",
            scopeKind = EndpointScopeKind.WORKSPACE,
            scopeName = projectName,
            url = broadStreamableHttpUrl.orEmpty(),
            rootPath = workspaceProjectPath.orEmpty(),
            state = serverState
        )

        val duplicateRepoIds = repoScopes
            .groupingBy { it.repoId }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        val repoRows = repoScopes
            .sortedWith(compareBy<RepoScope> { it.repoId.lowercase() }.thenBy { it.repoRootPath.lowercase() })
            .map { scope ->
                EndpointInventoryRow(
                    id = repoRowId(scope, duplicateRepoIds),
                    scopeKind = EndpointScopeKind.REPO,
                    scopeName = scope.repoId,
                    url = broadStreamableHttpUrl?.let {
                        ClientConfigGenerator.buildRepoScopedStreamableHttpUrl(
                            broadStreamableHttpUrl = it,
                            repoId = scope.repoId
                        )
                    }.orEmpty(),
                    rootPath = scope.repoRootPath,
                    state = serverState
                )
            }

        return listOf(broadRow) + repoRows
    }

    private fun repoRowId(scope: RepoScope, duplicateRepoIds: Set<String>): String {
        if (scope.repoId !in duplicateRepoIds) {
            return "repo:${scope.repoId}"
        }
        val normalizedRoot = RepoScopeRegistry.normalizeRepoRootPath(scope.repoRootPath)
        return "repo:${scope.repoId}:${RepoScopeRegistry.pathHash8(normalizedRoot)}"
    }
}
