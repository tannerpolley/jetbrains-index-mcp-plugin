package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.editor

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OpenFileToolScopeTest : BasePlatformTestCase() {

    fun testOpenFileHonorsProjectPathAsRepoRootBoundary() = runBlocking {
        val fixture = createRepoScopeFixture()
        val tool = OpenFileTool()

        val scopedResult = tool.execute(project, buildJsonObject {
            put("file", "src/shared/ScopedOpenProbe.java")
            put("project_path", fixture.repoBRoot)
            put("line", 1)
        })
        assertFalse("Repo-scoped open file should succeed: ${scopedResult.content}", scopedResult.isError)

        val selectedFile = FileEditorManager.getInstance(project).selectedFiles.singleOrNull()
        assertNotNull("Open file should select a file in the editor", selectedFile)

        val selectedPath = selectedFile!!.path.replace('\\', '/')
        assertTrue(
            "Repo-scoped open file should select repo-b path; got $selectedPath",
            selectedPath.endsWith("repo-b/src/shared/ScopedOpenProbe.java")
        )
        assertFalse(
            "Repo-scoped open file should not select repo-a path; got $selectedPath",
            selectedPath.endsWith("repo-a/src/shared/ScopedOpenProbe.java")
        )
    }

    private fun createRepoScopeFixture(): RepoScopeFixture {
        val basePath = project.basePath ?: error("Project base path is required")
        val repoARoot = Path.of(basePath).resolve("repo-a")
        val repoBRoot = Path.of(basePath).resolve("repo-b")

        Files.createDirectories(repoARoot.resolve("src/shared"))
        Files.createDirectories(repoBRoot.resolve("src/shared"))
        Files.writeString(
            repoARoot.resolve("src/shared/ScopedOpenProbe.java"),
            """
            package shared;

            class ScopedOpenProbeA {}
            """.trimIndent()
        )
        Files.writeString(
            repoBRoot.resolve("src/shared/ScopedOpenProbe.java"),
            """
            package shared;

            class ScopedOpenProbeB {}
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
