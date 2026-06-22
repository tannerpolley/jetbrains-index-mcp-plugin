package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootManager
import java.io.File
import java.security.MessageDigest

object RepoScopeRegistry {
    private val LOG = logger<RepoScopeRegistry>()

    fun normalizeRepoRootPath(path: String): String =
        path.trim().trimEnd('/', '\\').replace('\\', '/')

    fun pathHash8(normalizedCanonicalRepoRootPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedCanonicalRepoRootPath.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { byte -> "%02x".format(byte) }
    }

    fun buildScopes(
        repoRootPaths: List<String>,
        workspaceProjectPath: String?
    ): List<RepoScope> {
        val normalizedRoots = repoRootPaths
            .map { normalizeRepoRootPath(it) }
            .filter { it.isNotBlank() }
            .distinct()

        val leafCounts = normalizedRoots
            .groupingBy { repoLeafName(it) }
            .eachCount()

        return normalizedRoots.map { rootPath ->
            val leaf = repoLeafName(rootPath)
            val repoId = if ((leafCounts[leaf] ?: 0) == 1) {
                leaf
            } else {
                "$leaf-${pathHash8(rootPath)}"
            }
            RepoScope(
                repoId = repoId,
                repoRootPath = rootPath,
                workspaceProjectPath = workspaceProjectPath?.let { normalizeRepoRootPath(it) }
            )
        }
    }

    fun isGitRepoRootPath(path: String): Boolean =
        File(normalizeRepoRootPath(path), ".git").exists()

    fun selectAgentRepoRootPaths(
        candidatePaths: List<String>,
        isGitRepoRoot: (String) -> Boolean = ::isGitRepoRootPath
    ): List<String> =
        candidatePaths
            .map { normalizeRepoRootPath(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { isGitRepoRoot(it) }

    fun isPathInsideScope(scope: RepoScope, path: String): Boolean {
        return isPathInsideScope(scope.repoRootPath, path)
    }

    fun isPathInsideScope(scopeRootPath: String, path: String): Boolean {
        val normalizedPath = normalizeRepoRootPath(path)
        val normalizedRoot = normalizeRepoRootPath(scopeRootPath)
        if (isWindows()) {
            return normalizedPath.equals(normalizedRoot, ignoreCase = true) ||
                normalizedPath.startsWith("$normalizedRoot/", ignoreCase = true)
        }

        return normalizedPath == normalizedRoot || normalizedPath.startsWith("$normalizedRoot/")
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").contains("Windows", ignoreCase = true)

    fun collectOpenRepoScopes(): List<RepoScope> {
        if (ApplicationManager.getApplication() == null) {
            return emptyList()
        }

        val scopes = mutableListOf<RepoScope>()
        val openProjects = ProjectManager.getInstance().openProjects
            .filter { !it.isDefault }

        for (project in openProjects) {
            scopes += collectProjectRepoScopes(project)
        }

        return scopes.distinctBy { it.repoId }
    }

    fun collectProjectRepoScopes(project: Project): List<RepoScope> {
        val workspaceProjectPath = project.basePath?.let { normalizeRepoRootPath(it) }
        return buildScopes(collectRepoRootPaths(project), workspaceProjectPath)
    }

    fun collectProjectContentRootPaths(project: Project): List<String> {
        if (ApplicationManager.getApplication() == null) {
            return emptyList()
        }

        val roots = mutableListOf<String>()
        try {
            val modules = ModuleManager.getInstance(project).modules
            for (module in modules) {
                for (root in ModuleRootManager.getInstance(module).contentRoots) {
                    roots += root.path
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to collect content roots for project ${project.name}", e)
        }

        return roots
            .map { normalizeRepoRootPath(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun collectProjectWorkspaceModules(project: Project): List<WorkspaceModuleScope> {
        if (ApplicationManager.getApplication() == null) {
            return emptyList()
        }

        val modules = try {
            ModuleManager.getInstance(project).modules.toList()
        } catch (e: Exception) {
            LOG.debug("Failed to collect workspace modules for project ${project.name}", e)
            return emptyList()
        }

        val attachedModules = modules.map { module ->
            val moduleFilePath = normalizeRepoRootPath(module.moduleFile?.path ?: module.moduleFilePath)
            val contentGitRoot = ModuleRootManager.getInstance(module).contentRoots
                .map { normalizeRepoRootPath(it.path) }
                .firstOrNull { isGitRepoRootPath(it) }
            WorkspaceModuleScope(
                moduleName = module.name,
                moduleFilePath = moduleFilePath,
                inferredRepoRootPath = contentGitRoot ?: inferRepoRootFromModuleFilePath(moduleFilePath)
            )
        }

        val attachedModuleFiles = attachedModules
            .mapTo(mutableSetOf()) { normalizeRepoRootPath(it.moduleFilePath).lowercase() }
        val workspaceRoot = project.basePath?.let { normalizeRepoRootPath(it) }
        val orphanWorkspaceModuleFiles = workspaceRoot
            ?.let { File(it, ".idea") }
            ?.listFiles { file -> file.isFile && file.extension.equals("iml", ignoreCase = true) }
            ?.map { file -> normalizeRepoRootPath(file.absolutePath) }
            ?.filterNot { path -> path.lowercase() in attachedModuleFiles }
            ?.map { moduleFilePath ->
                WorkspaceModuleScope(
                    moduleName = File(moduleFilePath).nameWithoutExtension,
                    moduleFilePath = moduleFilePath,
                    inferredRepoRootPath = inferRepoRootFromModuleFilePath(moduleFilePath),
                    isAttached = false
                )
            }
            ?: emptyList()

        return (attachedModules + orphanWorkspaceModuleFiles)
            .distinctBy { normalizeRepoRootPath(it.moduleFilePath).lowercase() }
    }

    fun resolveOpenRepoScope(repoId: String): RepoScope? =
        collectOpenRepoScopes().find { it.repoId == repoId }

    fun scopeForPath(repoRootPath: String): RepoScope? {
        val normalized = normalizeRepoRootPath(repoRootPath)
        return collectOpenRepoScopes().find { normalizeRepoRootPath(it.repoRootPath) == normalized }
    }

    private fun collectRepoRootPaths(project: Project): List<String> {
        val roots = mutableListOf<String>()
        project.basePath?.let { roots += it }

        try {
            val modules = ModuleManager.getInstance(project).modules
            for (module in modules) {
                for (root in ModuleRootManager.getInstance(module).contentRoots) {
                    roots += root.path
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to collect repo scope content roots for project ${project.name}", e)
        }

        return selectAgentRepoRootPaths(roots)
    }

    private fun repoLeafName(path: String): String =
        normalizeRepoRootPath(path)
            .substringAfterLast('/')
            .ifBlank { "repo" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "repo" }

    private fun inferRepoRootFromModuleFilePath(moduleFilePath: String): String? {
        val moduleFile = File(normalizeRepoRootPath(moduleFilePath))
        val ideaDir = moduleFile.parentFile ?: return null
        if (ideaDir.name != ".idea") return null

        val repoRoot = ideaDir.parentFile ?: return null
        val normalizedRepoRoot = normalizeRepoRootPath(repoRoot.path)
        return normalizedRepoRoot.takeIf { isGitRepoRootPath(it) }
    }
}
