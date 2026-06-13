package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

data class RepoScope(
    val repoId: String,
    val repoRootPath: String,
    val workspaceProjectPath: String?
)

object RepoScopeContext {
    private val currentScope = ThreadLocal<RepoScope?>()

    fun current(): RepoScope? = currentScope.get()

    fun asContextElement(scope: RepoScope?): ThreadContextElement<RepoScope?> =
        currentScope.asContextElement(scope)
}
