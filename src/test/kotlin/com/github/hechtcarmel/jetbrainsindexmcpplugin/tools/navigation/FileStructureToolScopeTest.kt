package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
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

class FileStructureToolScopeTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testFileStructureHonorsProjectPathAsRepoRootBoundary() = runBlocking {
        val fixture = createRepoScopeFixture()
        val tool = FileStructureTool()

        val scopedResult = tool.execute(project, buildJsonObject {
            put("file", "src/shared/SharedScopeProbe.java")
            put("project_path", fixture.repoARoot)
        })
        assertFalse("Repo-scoped file structure should succeed: ${scopedResult.content}", scopedResult.isError)

        val scopedStructure = json.decodeFromString<FileStructureResult>((scopedResult.content.first() as ContentBlock.Text).text)

        assertTrue(
            "Repo-scoped file structure should keep repo-a class; got ${scopedStructure.structure}",
            scopedStructure.structure.contains("ScopeProbeA")
        )
        assertFalse(
            "Repo-scoped file structure should exclude repo-b class; got ${scopedStructure.structure}",
            scopedStructure.structure.contains("ScopeProbeB")
        )
    }

    private fun createRepoScopeFixture(): RepoScopeFixture {
        val basePath = project.basePath ?: error("Project base path is required")
        val repoARoot = Path.of(basePath).resolve("repo-a")
        val repoBRoot = Path.of(basePath).resolve("repo-b")

        Files.createDirectories(repoARoot.resolve("src/shared"))
        Files.createDirectories(repoBRoot.resolve("src/shared"))
        Files.writeString(
            repoARoot.resolve("src/shared/SharedScopeProbe.java"),
            """
            package shared;

            class ScopeProbeA {
                String alpha() { return "repo-a"; }
            }
            """.trimIndent()
        )
        Files.writeString(
            repoBRoot.resolve("src/shared/SharedScopeProbe.java"),
            """
            package shared;

            class ScopeProbeB {
                String beta() { return "repo-b"; }
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
