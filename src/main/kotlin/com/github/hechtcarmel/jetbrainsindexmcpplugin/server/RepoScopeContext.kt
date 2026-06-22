package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

data class RepoScope(
    val repoId: String,
    val repoRootPath: String,
    val workspaceProjectPath: String?
)

data class WorkspaceModuleScope(
    val moduleName: String,
    val moduleFilePath: String,
    val inferredRepoRootPath: String?,
    val isAttached: Boolean = true
)

object RepoScopeContext {
    private val currentScope = ThreadLocal<RepoScope?>()

    fun current(): RepoScope? = currentScope.get()

    fun asContextElement(scope: RepoScope?): ThreadContextElement<RepoScope?> =
        currentScope.asContextElement(scope)
}

object PathScopeContext {
    private val currentRootPath = ThreadLocal<String?>()

    fun currentRootPath(): String? = currentRootPath.get()

    fun asContextElement(rootPath: String?): ThreadContextElement<String?> =
        currentRootPath.asContextElement(rootPath)
}
