package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ReadFileResult
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

class ReadFileToolScopeTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testReadFileHonorsProjectPathAsRepoRootBoundary() = runBlocking {
        val fixture = createRepoScopeFixture()
        val tool = ReadFileTool()

        val scopedResult = tool.execute(project, buildJsonObject {
            put("file", "src/shared/ScopedReadProbe.java")
            put("project_path", fixture.repoBRoot)
        })
        assertFalse("Repo-scoped read file should succeed: ${scopedResult.content}", scopedResult.isError)

        val readFile = json.decodeFromString<ReadFileResult>((scopedResult.content.first() as ContentBlock.Text).text)

        assertTrue(
            "Repo-scoped read file should keep repo-b content; got ${readFile.content}",
            readFile.content.contains("repo-b")
        )
        assertFalse(
            "Repo-scoped read file should exclude repo-a content; got ${readFile.content}",
            readFile.content.contains("repo-a")
        )
    }

    private fun createRepoScopeFixture(): RepoScopeFixture {
        val basePath = project.basePath ?: error("Project base path is required")
        val repoARoot = Path.of(basePath).resolve("repo-a")
        val repoBRoot = Path.of(basePath).resolve("repo-b")

        Files.createDirectories(repoARoot.resolve("src/shared"))
        Files.createDirectories(repoBRoot.resolve("src/shared"))
        Files.writeString(
            repoARoot.resolve("src/shared/ScopedReadProbe.java"),
            """
            package shared;

            class ScopedReadProbe {
                String marker() { return "repo-a"; }
            }
            """.trimIndent()
        )
        Files.writeString(
            repoBRoot.resolve("src/shared/ScopedReadProbe.java"),
            """
            package shared;

            class ScopedReadProbe {
                String marker() { return "repo-b"; }
            }
            """.trimIndent()
        )

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
