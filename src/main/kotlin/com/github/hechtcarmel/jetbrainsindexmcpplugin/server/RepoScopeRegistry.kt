package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

data class RepoScopeEntry(
    val repoId: String,
    val rootPath: String
)

class RepoScopeRegistry {
    private val currentEntries = AtomicReference<List<RepoScopeEntry>>(emptyList())

    fun entries(): List<RepoScopeEntry> = currentEntries.get()

    fun replaceOpenRoots(rootPaths: List<String>): List<RepoScopeEntry> {
        val entries = buildEntries(rootPaths)
        currentEntries.set(entries)
        return entries
    }

    fun attach(rootPath: String): RepoScopeEntry {
        val normalized = normalizeRootPath(rootPath)
        val roots = (entries().map { it.rootPath } + normalized).distinct()
        val updated = replaceOpenRoots(roots)
        return updated.first { it.rootPath == normalized }
    }

    fun detach(repoId: String): Boolean {
        val before = entries()
        val after = before.filterNot { it.repoId == repoId }
        if (after.size == before.size) return false
        currentEntries.set(after)
        return true
    }

    fun find(repoId: String): RepoScopeEntry? = entries().firstOrNull { it.repoId == repoId }

    companion object {
        private val shared = RepoScopeRegistry()

        fun getInstance(): RepoScopeRegistry = shared

        fun buildEntries(rootPaths: List<String>): List<RepoScopeEntry> {
            val normalizedRoots = rootPaths
                .map(::normalizeRootPath)
                .filter { it.isNotBlank() }
                .distinct()

            val leafCounts = normalizedRoots
                .groupingBy(::leafName)
                .eachCount()

            return normalizedRoots
                .map { root ->
                    val leaf = leafName(root)
                    val repoId = if ((leafCounts[leaf] ?: 0) == 1) leaf else "$leaf-${pathHash8(root)}"
                    RepoScopeEntry(repoId = repoId, rootPath = root)
                }
                .sortedBy { it.repoId }
        }

        fun normalizeRootPath(path: String): String {
            val canonical = runCatching { File(path).canonicalPath }.getOrElse { path }
            return canonical.replace('\\', '/').trimEnd('/')
        }

        fun pathHash8(rootPath: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalizeRootPath(rootPath).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(8)
        }

        private fun leafName(rootPath: String): String =
            normalizeRootPath(rootPath).substringAfterLast('/').ifBlank { "repo" }
    }
}
