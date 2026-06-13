package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.configurationStore.StoreUtil
import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

object RepoWorkspaceMutator {

    fun attachContentRoot(project: Project, repoPath: String): RepoScope {
        val normalizedRepoPath = RepoScopeRegistry.normalizeRepoRootPath(repoPath)
        val repoRoot = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedRepoPath)
            ?: throw IllegalArgumentException("Repo path does not exist: $normalizedRepoPath")
        if (!repoRoot.isDirectory) {
            throw IllegalArgumentException("Repo path must be a directory: $normalizedRepoPath")
        }
        if (!RepoScopeRegistry.isGitRepoRootPath(normalizedRepoPath)) {
            throw IllegalArgumentException("Repo path must be a Git repo root with a .git marker: $normalizedRepoPath")
        }

        val plannedScope = plannedScopeForPath(project, normalizedRepoPath)
        val targetModule = findOrCreateRepoModule(project, plannedScope.repoId)

        val alreadyAttachedToTargetModule = ModuleRootManager.getInstance(targetModule).contentEntries.any { entry ->
            entry.file?.path?.let { RepoScopeRegistry.normalizeRepoRootPath(it) } == normalizedRepoPath
        }

        if (!alreadyAttachedToTargetModule) {
            val rootModel = ModuleRootManager.getInstance(targetModule).modifiableModel
            var committed = false
            try {
                rootModel.addContentEntry(repoRoot)
                rootModel.commit()
                committed = true
            } finally {
                if (!committed) {
                    rootModel.dispose()
                }
            }
        }
        pruneRepoSiblingModules(project, plannedScope)

        return RepoScopeRegistry.scopeForPath(normalizedRepoPath)
            ?: plannedScope
    }

    fun detachContentRoot(project: Project, repoId: String): RepoScope {
        val scope = RepoScopeRegistry.resolveOpenRepoScope(repoId)
            ?: throw IllegalArgumentException("Repo id is not attached to the open workspace: $repoId")

        val workspaceRoot = project.basePath?.let { RepoScopeRegistry.normalizeRepoRootPath(it) }
        if (scope.repoRootPath == workspaceRoot) {
            throw IllegalArgumentException("Cannot detach the workspace project root: ${scope.repoRootPath}")
        }

        var removed = false
        for (module in ModuleManager.getInstance(project).modules) {
            val model = ModuleRootManager.getInstance(module).modifiableModel
            var committed = false
            try {
                val entries = model.contentEntries.filter { entry ->
                    entry.file?.path?.let { RepoScopeRegistry.normalizeRepoRootPath(it) } == scope.repoRootPath
                }
                if (entries.isEmpty()) {
                    model.dispose()
                    committed = true
                    continue
                }

                for (entry in entries) {
                    model.removeContentEntry(entry)
                }
                model.commit()
                committed = true
                removed = true
            } finally {
                if (!committed) {
                    model.dispose()
                }
            }
        }

        if (!removed) {
            throw IllegalArgumentException("Repo id '$repoId' resolved to '${scope.repoRootPath}', but no matching content root was attached.")
        }

        return scope
    }

    private fun plannedScopeForPath(project: Project, normalizedRepoPath: String): RepoScope {
        val workspaceProjectPath = project.basePath?.let { RepoScopeRegistry.normalizeRepoRootPath(it) }
        val existingRootPaths = RepoScopeRegistry.collectOpenRepoScopes().map { it.repoRootPath }
        return RepoScopeRegistry.buildScopes(
            repoRootPaths = existingRootPaths + normalizedRepoPath,
            workspaceProjectPath = workspaceProjectPath
        ).first {
            RepoScopeRegistry.normalizeRepoRootPath(it.repoRootPath) == normalizedRepoPath
        }
    }

    private fun findOrCreateRepoModule(project: Project, repoId: String): Module {
        val moduleManager = ModuleManager.getInstance(project)
        moduleManager.findModuleByName(repoId)?.let { return it }

        val workspacePath = project.basePath
            ?: throw IllegalStateException("Workspace project '${project.name}' has no base path for repo module '$repoId'.")
        val moduleFile = File(File(workspacePath, ".idea"), "$repoId.iml")
        moduleFile.parentFile?.mkdirs()

        val moduleModel = moduleManager.getModifiableModel()
        var committed = false
        try {
            val module = moduleModel.newModule(moduleFile.absolutePath, EmptyModuleType.EMPTY_MODULE)
            moduleModel.commit()
            committed = true
            return module
        } finally {
            if (!committed) {
                moduleModel.dispose()
            }
        }
    }

    private fun pruneRepoSiblingModules(project: Project, scope: RepoScope) {
        val moduleManager = ModuleManager.getInstance(project)
        val staleModules = moduleManager.modules.filter { module ->
            module.name.startsWith("${scope.repoId}.") &&
                (module.moduleFile?.path ?: module.moduleFilePath)
                    .let { RepoScopeRegistry.normalizeRepoRootPath(it) }
                    .startsWith("${scope.repoRootPath}/.idea/")
        }
        if (staleModules.isEmpty()) {
            return
        }

        val moduleModel = moduleManager.getModifiableModel()
        var committed = false
        try {
            for (module in staleModules) {
                moduleModel.disposeModule(module)
            }
            moduleModel.commit()
            committed = true
        } finally {
            if (!committed) {
                moduleModel.dispose()
            }
        }
    }

    fun persistWorkspaceProject(project: Project) {
        StoreUtil.saveSettings(project, true)
    }
}
