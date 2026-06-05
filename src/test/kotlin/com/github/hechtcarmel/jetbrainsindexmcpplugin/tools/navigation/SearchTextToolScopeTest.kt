package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SearchTextResult
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SearchTextToolScopeTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testSearchTextHonorsProjectPathAsRepoRootBoundary() = runBlocking {
        val fixture = createRepoScopeFixture()
        val tool = SearchTextTool()

        val scopedResult = tool.execute(project, buildJsonObject {
            put("query", "reposcopeneedle")
            put("project_path", fixture.repoARoot)
            put("pageSize", 20)
        })
        assertFalse("Repo-scoped text search should succeed: ${scopedResult.content}", scopedResult.isError)

        val scopedMatches = json.decodeFromString<SearchTextResult>((scopedResult.content.first() as ContentBlock.Text).text).matches
        assertTrue(
            "Repo-scoped text search should keep repo-a file; got ${scopedMatches.map { it.file }}",
            scopedMatches.any { it.file.endsWith("src/repoa/NeedleA.kt") }
        )
        assertFalse(
            "Repo-scoped text search should exclude repo-b file; got ${scopedMatches.map { it.file }}",
            scopedMatches.any { it.file.endsWith("src/repob/NeedleB.kt") }
        )
    }

    private fun createRepoScopeFixture(): RepoScopeFixture {
        val repoAFile = myFixture.addFileToProject(
            "repo-a/src/repoa/NeedleA.kt",
            "class NeedleA { val token = \"reposcopeneedle\" }"
        )
        val repoBFile = myFixture.addFileToProject(
            "repo-b/src/repob/NeedleB.kt",
            "class NeedleB { val token = \"reposcopeneedle\" }"
        )

        val repoAVirtualFile = repoAFile.virtualFile.parent?.parent?.parent
            ?: error("Failed to resolve repo-a root from fixture")
        val repoBVirtualFile = repoBFile.virtualFile.parent?.parent?.parent
            ?: error("Failed to resolve repo-b root from fixture")

        PsiTestUtil.addContentRoot(module, repoAVirtualFile)
        PsiTestUtil.addContentRoot(module, repoBVirtualFile)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return RepoScopeFixture(
            repoARoot = repoAVirtualFile.path.replace('\\', '/'),
            repoBRoot = repoBVirtualFile.path.replace('\\', '/')
        )
    }

    private data class RepoScopeFixture(
        val repoARoot: String,
        val repoBRoot: String
    )
}
