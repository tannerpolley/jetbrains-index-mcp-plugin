package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScope

data class RepoEndpointDisplayRow(
    val url: String,
    val repoModuleName: String,
    val repoRootPath: String
) {
    val displayText: String = "$url | Repo/Module: $repoModuleName"
}

object RepoEndpointDisplay {
    fun buildRows(
        broadStreamableHttpUrl: String,
        repoScopes: List<RepoScope>
    ): List<RepoEndpointDisplayRow> =
        repoScopes
            .sortedBy { it.repoId }
            .map { scope ->
                RepoEndpointDisplayRow(
                    url = ClientConfigGenerator.buildRepoScopedStreamableHttpUrl(
                        broadStreamableHttpUrl = broadStreamableHttpUrl,
                        repoId = scope.repoId
                    ),
                    repoModuleName = scope.repoId,
                    repoRootPath = scope.repoRootPath
                )
            }
}
