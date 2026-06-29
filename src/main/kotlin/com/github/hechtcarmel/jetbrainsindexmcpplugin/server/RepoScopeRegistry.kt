package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootManager
import java.io.File
import java.io.StringReader
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

object RepoScopeRegistry {
    private val LOG = logger<RepoScopeRegistry>()
    private val MANUAL_INDEX_ROOT_NAMES = setOf(".agents", ".codex")

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

    fun selectManualIndexRootPaths(candidatePaths: List<String>): List<String> =
        candidatePaths
            .map { normalizeRepoRootPath(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { rawLeafName(it) in MANUAL_INDEX_ROOT_NAMES }

    fun buildIndexScopes(
        repoRootPaths: List<String>,
        manualRootPaths: List<String>,
        workspaceProjectPath: String?
    ): List<RepoScope> {
        val scopeRoots = (
            repoRootPaths.map { normalizeRepoRootPath(it) } +
                selectManualIndexRootPaths(manualRootPaths)
            )
            .filter { it.isNotBlank() }
            .distinct()
        return buildScopes(scopeRoots, workspaceProjectPath)
    }

    fun isPathInsideScope(scope: RepoScope, path: String): Boolean {
        return isPathInsideScope(scope.repoRootPath, path)
    }

    fun isPathInsideScope(scopeRootPath: String, path: String): Boolean {
        return relativePathInScope(scopeRootPath, path) != null
    }

    fun relativePathInScope(scopeRootPath: String, path: String): String? {
        val normalizedPath = normalizeRepoRootPath(path)
        val normalizedRoot = normalizeRepoRootPath(scopeRootPath)
        val prefix = "$normalizedRoot/"
        if (isWindows()) {
            if (normalizedPath.equals(normalizedRoot, ignoreCase = true)) return ""
            if (normalizedPath.startsWith(prefix, ignoreCase = true)) {
                return normalizedPath.substring(prefix.length)
            }
            return null
        }

        if (normalizedPath == normalizedRoot) return ""
        if (normalizedPath.startsWith(prefix)) return normalizedPath.substring(prefix.length)
        return null
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

    fun collectOpenIndexScopes(): List<RepoScope> {
        if (ApplicationManager.getApplication() == null) {
            return emptyList()
        }

        val scopes = mutableListOf<RepoScope>()
        val openProjects = ProjectManager.getInstance().openProjects
            .filter { !it.isDefault }

        for (project in openProjects) {
            scopes += collectProjectIndexScopes(project)
        }

        return scopes.distinctBy { it.repoId }
    }

    fun collectProjectRepoScopes(project: Project): List<RepoScope> {
        val workspaceProjectPath = project.basePath?.let { normalizeRepoRootPath(it) }
        return buildScopes(collectRepoRootPaths(project), workspaceProjectPath)
    }

    fun collectProjectIndexScopes(project: Project): List<RepoScope> {
        val workspaceProjectPath = project.basePath?.let { normalizeRepoRootPath(it) }
        val configuredContentRootPaths = collectProjectConfiguredContentRootPaths(project)
        return buildIndexScopes(
            repoRootPaths = selectAgentRepoRootPaths(
                collectRepoRootPaths(project) + configuredContentRootPaths
            ),
            manualRootPaths = collectProjectContentRootPaths(project) + configuredContentRootPaths,
            workspaceProjectPath = workspaceProjectPath
        )
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

    fun extractImlContentRootPaths(
        moduleFilePath: String,
        projectBasePath: String?,
        xml: String,
        userHomePath: String = System.getProperty("user.home")
    ): List<String> {
        val moduleFile = File(normalizeRepoRootPath(moduleFilePath))
        val moduleDir = if (moduleFile.parentFile?.name == ".idea" && projectBasePath != null) {
            projectBasePath
        } else {
            moduleFile.parent
        } ?: return emptyList()

        val document = try {
            val factory = DocumentBuilderFactory.newInstance()
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            } catch (_: Exception) {
                // Some JDK XML providers do not support every hardening feature.
            }
            factory.isExpandEntityReferences = false
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            LOG.debug("Failed to parse module file $moduleFilePath", e)
            return emptyList()
        }

        val contentNodes = document.getElementsByTagName("content")
        val roots = mutableListOf<String>()
        for (i in 0 until contentNodes.length) {
            val element = contentNodes.item(i) as? Element ?: continue
            val url = element.getAttribute("url")
            resolveImlFileUrl(
                url = url,
                moduleDir = moduleDir,
                projectBasePath = projectBasePath,
                userHomePath = userHomePath
            )?.let { roots += it }
        }

        return roots.distinct()
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

    fun resolveOpenIndexScope(repoId: String): RepoScope? =
        collectOpenIndexScopes().find { it.repoId == repoId }

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

    private fun collectProjectConfiguredContentRootPaths(project: Project): List<String> {
        val basePath = project.basePath?.let { normalizeRepoRootPath(it) } ?: return emptyList()
        val ideaDir = File(basePath, ".idea")
        if (!ideaDir.isDirectory) return emptyList()

        val moduleFiles = ideaDir.listFiles { file -> file.isFile && file.extension.equals("iml", ignoreCase = true) }
            ?: return emptyList()

        return moduleFiles
            .flatMap { moduleFile ->
                try {
                    extractImlContentRootPaths(
                        moduleFilePath = moduleFile.path,
                        projectBasePath = basePath,
                        xml = moduleFile.readText()
                    )
                } catch (e: Exception) {
                    LOG.debug("Failed to read module file ${moduleFile.path}", e)
                    emptyList()
                }
            }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun resolveImlFileUrl(
        url: String,
        moduleDir: String,
        projectBasePath: String?,
        userHomePath: String
    ): String? {
        if (!url.startsWith("file://")) return null

        var path = url.removePrefix("file://")
            .replace("\$MODULE_DIR\$", normalizeRepoRootPath(moduleDir))
            .replace("\$PROJECT_DIR\$", projectBasePath?.let { normalizeRepoRootPath(it) }.orEmpty())
            .replace("\$USER_HOME\$", normalizeRepoRootPath(userHomePath))

        if (Regex("^/[A-Za-z]:/").containsMatchIn(path)) {
            path = path.removePrefix("/")
        }

        return try {
            normalizeRepoRootPath(Path.of(path).normalize().toString())
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun repoLeafName(path: String): String =
        rawLeafName(path)
            .ifBlank { "repo" }
            .replace(Regex("[^A-Za-z0-9_-]+"), "-")
            .trim('-', '_')
            .ifBlank { "repo" }

    private fun rawLeafName(path: String): String =
        normalizeRepoRootPath(path)
            .substringAfterLast('/')

    private fun inferRepoRootFromModuleFilePath(moduleFilePath: String): String? {
        val moduleFile = File(normalizeRepoRootPath(moduleFilePath))
        val ideaDir = moduleFile.parentFile ?: return null
        if (ideaDir.name != ".idea") return null

        val repoRoot = ideaDir.parentFile ?: return null
        val normalizedRepoRoot = normalizeRepoRootPath(repoRoot.path)
        return normalizedRepoRoot.takeIf { isGitRepoRootPath(it) }
    }
}
