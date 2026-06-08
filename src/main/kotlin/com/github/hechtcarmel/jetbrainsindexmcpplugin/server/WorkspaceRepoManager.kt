package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem

object WorkspaceRepoManager {

    fun attachContentRoot(project: Project, repoPath: String): String {
        val normalized = RepoScopeRegistry.normalizeRootPath(repoPath)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized)
            ?: error("Repository path does not exist: $repoPath")

        val modules = ModuleManager.getInstance(project).modules
        val targetModule = modules.firstOrNull() ?: error("Project has no modules to attach repository root.")

        runWriteCommand(project, "Attach Repository To Workspace") {
            val alreadyAttached = modules.any { module ->
                ModuleRootManager.getInstance(module).contentRoots.any { root ->
                    RepoScopeRegistry.normalizeRootPath(root.path) == normalized
                }
            }
            if (!alreadyAttached) {
                val model = ModuleRootManager.getInstance(targetModule).modifiableModel
                try {
                    model.addContentEntry(virtualFile)
                    model.commit()
                } catch (e: Exception) {
                    model.dispose()
                    throw e
                }
            }
        }

        return normalized
    }

    fun detachContentRoot(project: Project, repoPath: String): Boolean {
        val normalized = RepoScopeRegistry.normalizeRootPath(repoPath)
        var removed = false

        runWriteCommand(project, "Detach Repository From Workspace") {
            for (module in ModuleManager.getInstance(project).modules) {
                val model = ModuleRootManager.getInstance(module).modifiableModel
                try {
                    val entriesToRemove = model.contentEntries.filter { entry ->
                        entry.file?.path?.let { RepoScopeRegistry.normalizeRootPath(it) } == normalized
                    }
                    for (entry in entriesToRemove) {
                        model.removeContentEntry(entry)
                        removed = true
                    }
                    if (entriesToRemove.isNotEmpty()) {
                        model.commit()
                    } else {
                        model.dispose()
                    }
                } catch (e: Exception) {
                    model.dispose()
                    throw e
                }
            }
        }

        return removed
    }

    private fun runWriteCommand(project: Project, name: String, action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        val command = {
            WriteCommandAction.runWriteCommandAction(project, name, null, { action() })
        }
        if (app.isDispatchThread) {
            command()
        } else {
            app.invokeAndWait { command() }
        }
    }
}
