package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindFileResult
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FindFileToolScopeTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testFindFileHonorsProjectPathAsRepoRootBoundary() = runBlocking {
        val fixture = createRepoScopeFixture()
        val tool = FindFileTool()

        val scopedResult = tool.execute(project, buildJsonObject {
            put("query", "ScopeProbe")
            put("project_path", fixture.repoARoot)
            put("pageSize", 20)
        })
        assertFalse("Repo-scoped search should succeed: ${scopedResult.content}", scopedResult.isError)

        val scopedFiles = json.decodeFromString<FindFileResult>((scopedResult.content.first() as ContentBlock.Text).text).files
        assertTrue(
            "Repo-scoped search should keep repo-a file",
            scopedFiles.any { it.path.endsWith("src/repoa/ScopeProbeA.kt") }
        )
        assertFalse(
            "Repo-scoped search should exclude repo-b file",
            scopedFiles.any { it.path.endsWith("src/repob/ScopeProbeB.kt") }
        )
    }

    private fun createRepoScopeFixture(): RepoScopeFixture {
        val basePath = project.basePath ?: error("Project base path is required")
        val repoARoot = Path.of(basePath).resolve("repo-a")
        val repoBRoot = Path.of(basePath).resolve("repo-b")

        Files.createDirectories(repoARoot.resolve("src/repoa"))
        Files.createDirectories(repoBRoot.resolve("src/repob"))
        Files.writeString(repoARoot.resolve("src/repoa/ScopeProbeA.kt"), "class ScopeProbeA")
        Files.writeString(repoBRoot.resolve("src/repob/ScopeProbeB.kt"), "class ScopeProbeB")

        val repoAVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repoARoot)
            ?: error("Failed to refresh repo-a root")
        val repoBVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repoBRoot)
            ?: error("Failed to refresh repo-b root")

        PsiTestUtil.addContentRoot(module, repoAVirtualFile)
        PsiTestUtil.addContentRoot(module, repoBVirtualFile)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return RepoScopeFixture(
            repoARoot = repoARoot.toString().replace('\\', '/'),
            repoBRoot = repoBRoot.toString().replace('\\', '/')
        )
    }

    private data class RepoScopeFixture(
        val repoARoot: String,
        val repoBRoot: String
    )
}
