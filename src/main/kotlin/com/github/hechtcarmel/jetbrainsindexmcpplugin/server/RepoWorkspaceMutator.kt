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

        detachContentRootPath(project, scope.repoRootPath)
        return scope
    }

    fun detachContentRootPath(project: Project, repoRootPath: String): RepoScope {
        val normalizedRepoPath = RepoScopeRegistry.normalizeRepoRootPath(repoRootPath)
        val scope = RepoScopeRegistry.scopeForPath(normalizedRepoPath)
            ?: RepoScope(
                repoId = File(normalizedRepoPath).name,
                repoRootPath = normalizedRepoPath,
                workspaceProjectPath = project.basePath?.let { RepoScopeRegistry.normalizeRepoRootPath(it) }
            )

        val workspaceRoot = project.basePath?.let { RepoScopeRegistry.normalizeRepoRootPath(it) }
        if (normalizedRepoPath == workspaceRoot) {
            throw IllegalArgumentException("Cannot detach the workspace project root: $normalizedRepoPath")
        }

        var removed = false
        val modulesToDispose = mutableListOf<Module>()
        for (module in ModuleManager.getInstance(project).modules) {
            val model = ModuleRootManager.getInstance(module).modifiableModel
            var committed = false
            try {
                val entries = model.contentEntries.filter { entry ->
                    entry.file?.path?.let { RepoScopeRegistry.normalizeRepoRootPath(it) } == normalizedRepoPath
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
                if (ModuleRootManager.getInstance(module).contentRoots.isEmpty()) {
                    modulesToDispose += module
                }
            } finally {
                if (!committed) {
                    model.dispose()
                }
            }
        }

        if (!removed) {
            throw IllegalArgumentException("Repo root '$normalizedRepoPath' is not attached as a content root.")
        }

        disposeModules(project, modulesToDispose)
        return scope
    }

    fun detachWorkspaceModule(project: Project, requestedScope: WorkspaceModuleScope): WorkspaceModuleScope {
        val moduleManager = ModuleManager.getInstance(project)
        val requestedModuleFilePath = RepoScopeRegistry.normalizeRepoRootPath(requestedScope.moduleFilePath)
        val module = moduleManager.findModuleByName(requestedScope.moduleName)
            ?.takeIf { found ->
                requestedScope.isAttached ||
                    RepoScopeRegistry.normalizeRepoRootPath(found.moduleFile?.path ?: found.moduleFilePath) == requestedModuleFilePath
            }

        val scope = module
            ?.let { found ->
                RepoScopeRegistry.collectProjectWorkspaceModules(project)
                    .find {
                        it.moduleName == requestedScope.moduleName &&
                            RepoScopeRegistry.normalizeRepoRootPath(it.moduleFilePath) == requestedModuleFilePath
                    }
                    ?: requestedScope.copy(
                        moduleFilePath = RepoScopeRegistry.normalizeRepoRootPath(found.moduleFile?.path ?: found.moduleFilePath),
                        isAttached = true
                    )
            }
            ?: requestedScope

        if (module == null && scope.isAttached) {
            throw IllegalArgumentException("Workspace module is not attached: ${scope.moduleName}")
        }

        val workspaceRoot = project.basePath?.let { RepoScopeRegistry.normalizeRepoRootPath(it) }
        if (scope.inferredRepoRootPath != null && scope.inferredRepoRootPath == workspaceRoot) {
            throw IllegalArgumentException("Cannot detach the workspace project module: ${scope.moduleName}")
        }

        if (module != null) {
            disposeModules(project, listOf(module))
        } else {
            deleteWorkspaceOwnedModuleFile(project, scope.moduleFilePath)
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

    private fun disposeModules(project: Project, modules: List<Module>) {
        val distinctModules = modules.distinct()
        if (distinctModules.isEmpty()) {
            return
        }
        val moduleFilePaths = distinctModules
            .map { module -> RepoScopeRegistry.normalizeRepoRootPath(module.moduleFile?.path ?: module.moduleFilePath) }

        val moduleModel = ModuleManager.getInstance(project).getModifiableModel()
        var committed = false
        try {
            for (module in distinctModules) {
                if (!module.isDisposed) {
                    moduleModel.disposeModule(module)
                }
            }
            moduleModel.commit()
            committed = true
        } finally {
            if (!committed) {
                moduleModel.dispose()
            }
        }

        for (moduleFilePath in moduleFilePaths) {
            deleteWorkspaceOwnedModuleFile(project, moduleFilePath)
        }
    }

    private fun deleteWorkspaceOwnedModuleFile(project: Project, moduleFilePath: String) {
        val workspaceRoot = project.basePath?.let { RepoScopeRegistry.normalizeRepoRootPath(it) } ?: return
        val normalizedModuleFilePath = RepoScopeRegistry.normalizeRepoRootPath(moduleFilePath)
        if (!normalizedModuleFilePath.startsWith("$workspaceRoot/.idea/") || !normalizedModuleFilePath.endsWith(".iml")) {
            return
        }

        val moduleFile = File(normalizedModuleFilePath)
        if (moduleFile.exists() && !moduleFile.delete()) {
            throw IllegalStateException("Failed to delete stale Workspace module file: $normalizedModuleFilePath")
        }
        LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedModuleFilePath)
    }

    fun persistWorkspaceProject(project: Project) {
        StoreUtil.saveSettings(project, true)
    }
}
