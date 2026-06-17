package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import java.io.File
import java.nio.file.Files
import junit.framework.TestCase

class CodexWorkspaceSyncServiceUnitTest : TestCase() {
    private val tempDirs = mutableListOf<File>()

    override fun tearDown() {
        try {
            tempDirs.forEach { it.deleteRecursively() }
        } finally {
            super.tearDown()
        }
    }

    fun testExtractCandidatesFromKnownCodexStateSections() {
        val stateText = """
            {
              "active-workspace-roots": [
                "C:\\Users\\Tanner\\Documents\\Workspaces\\Engineering\\ePC-SAFT"
              ],
              "thread-workspace-root-hints": {
                "019e": {
                  "cwd": "C:\\Users\\Tanner\\Documents\\Workspaces\\Projects\\jetbrains-bridge"
                }
              },
              "unrelated": {
                "path": "C:\\Users\\Tanner\\Documents\\Workspaces\\Projects\\ignored"
              }
            }
        """.trimIndent()

        val candidates = CodexWorkspaceSyncService.extractCandidatesFromStateText(stateText)

        assertEquals(2, candidates.size)
        assertEquals(
            "C:\\Users\\Tanner\\Documents\\Workspaces\\Engineering\\ePC-SAFT",
            candidates[0].path
        )
        assertEquals("active-workspace-roots", candidates[0].source)
        assertEquals(
            "C:\\Users\\Tanner\\Documents\\Workspaces\\Projects\\jetbrains-bridge",
            candidates[1].path
        )
        assertEquals("thread-workspace-root-hints", candidates[1].source)
    }

    fun testBuildPlanResolvesNestedPathsAndGitWorktrees() {
        val repo = createGitRepo("repo")
        val nested = File(repo, "packages/pkg").also { it.mkdirs() }
        val worktree = createGitRepo("repo-issue-0001")

        val plan = CodexWorkspaceSyncService.buildPlan(
            candidates = listOf(CodexWorkspaceSyncService.Candidate(nested.absolutePath, "thread-workspace-root-hints")),
            existingRepoRoots = emptyList(),
            workspaceProjectPath = null,
            includeWorktrees = true,
            listWorktrees = { root ->
                assertEquals(repo.absolutePath.replace('\\', '/'), root)
                listOf(worktree.absolutePath)
            }
        )

        val rootsToAttach = plan.toAttach.map { it.repoRootPath }
        assertEquals(1, plan.discovered)
        assertTrue(rootsToAttach.contains(repo.absolutePath.replace('\\', '/')))
        assertTrue(rootsToAttach.contains(worktree.absolutePath.replace('\\', '/')))
        assertTrue(plan.alreadyAttached.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }

    fun testBuildPlanSeparatesAlreadyAttachedRoots() {
        val repo = createGitRepo("existing-repo")

        val plan = CodexWorkspaceSyncService.buildPlan(
            candidates = listOf(CodexWorkspaceSyncService.Candidate(repo.absolutePath, "active-workspace-roots")),
            existingRepoRoots = listOf(repo.absolutePath),
            workspaceProjectPath = null,
            includeWorktrees = false
        )

        assertEquals(1, plan.accepted.size)
        assertEquals(1, plan.alreadyAttached.size)
        assertTrue(plan.toAttach.isEmpty())
    }

    fun testBuildPlanSkipsMissingAndPlainDirectories() {
        val plain = Files.createTempDirectory("plain-directory").toFile().also { tempDirs += it }
        val missing = File(plain.parentFile, "missing-repo")

        val plan = CodexWorkspaceSyncService.buildPlan(
            candidates = listOf(
                CodexWorkspaceSyncService.Candidate(plain.absolutePath, "active-workspace-roots"),
                CodexWorkspaceSyncService.Candidate(missing.absolutePath, "electron-saved-workspace-roots")
            ),
            existingRepoRoots = emptyList(),
            workspaceProjectPath = null,
            includeWorktrees = false
        )

        assertTrue(plan.accepted.isEmpty())
        assertEquals(
            listOf("no_git_marker", "candidate_path_missing"),
            plan.skipped.map { it.reason }
        )
    }

    fun testBuildPlanDoesNotAttachWorkspaceProjectRootAsRepo() {
        val workspace = createGitRepo("Workspace")
        val repo = createGitRepo("child-repo")

        val plan = CodexWorkspaceSyncService.buildPlan(
            candidates = listOf(
                CodexWorkspaceSyncService.Candidate(workspace.absolutePath, "project-order"),
                CodexWorkspaceSyncService.Candidate(repo.absolutePath, "project-order")
            ),
            existingRepoRoots = emptyList(),
            workspaceProjectPath = workspace.absolutePath.replace('\\', '/'),
            includeWorktrees = false
        )

        assertEquals(listOf(repo.absolutePath.replace('\\', '/')), plan.toAttach.map { it.repoRootPath })
    }

    private fun createGitRepo(name: String): File {
        val parent = Files.createTempDirectory("codex-workspace-sync").toFile().also { tempDirs += it }
        val repo = File(parent, name).also { it.mkdirs() }
        File(repo, ".git").mkdir()
        return repo
    }
}
