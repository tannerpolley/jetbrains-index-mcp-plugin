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

    fun testExtractCandidatesFromActiveOpenCodexStateSections() {
        val stateText = """
            {
              "active-workspace-roots": [
                "C:\\Users\\Tanner\\Documents\\Workspaces\\Projects\\jetbrains-bridge"
              ],
              "electron-saved-workspace-roots": [
                "C:\\Users\\Tanner\\Documents\\Workspaces\\Apps\\mplgallery"
              ],
              "thread-workspace-root-hints": {
                "019e93f2-8668-7800-ba40-752ad5aba592": "C:\\Users\\Tanner\\Documents\\Workspaces\\Apps\\mplgallery",
                "019e93fc-0d5e-7f41-89dc-a14c044903a1": "C:\\Users\\Tanner\\Documents\\Workspaces\\Projects\\closed-repo",
                "019e9451-7730-7dd0-86e4-5c6063b18e7b": "C:\\Users\\Tanner\\Documents\\Workspaces\\Projects\\archived-repo"
              },
              "project-order": [
                "C:\\Users\\Tanner\\Documents\\Workspaces\\Projects\\project-order-only"
              ],
              "unrelated": {
                "path": "C:\\Users\\Tanner\\Documents\\Workspaces\\Projects\\ignored"
              },
              "electron-persisted-atom-state": {
                "prompt-history": {
                  "019e93f2-8668-7800-ba40-752ad5aba592": [
                    "C:\\Users\\Tanner\\Documents\\Workspaces\\Projects\\ignored-prompt-text"
                  ]
                }
              }
            }
        """.trimIndent()

        val candidates = CodexWorkspaceSyncService.extractCandidatesFromStateText(
            stateText,
            nonArchivedThreadIds = setOf(
                "019e93f2-8668-7800-ba40-752ad5aba592",
                "019e93fc-0d5e-7f41-89dc-a14c044903a1"
            )
        )

        assertEquals(2, candidates.size)
        assertEquals(
            "C:\\Users\\Tanner\\Documents\\Workspaces\\Projects\\jetbrains-bridge",
            candidates[0].path
        )
        assertEquals("active-workspace-roots", candidates[0].source)
        assertEquals(
            "C:\\Users\\Tanner\\Documents\\Workspaces\\Apps\\mplgallery",
            candidates[1].path
        )
        assertEquals("active-thread:019e93f2-8668-7800-ba40-752ad5aba592", candidates[1].source)
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

    fun testBuildPlanRequiresMatchingGitHubOwnerRemoteWhenConfigured() {
        val owned = createGitRepo("owned-repo")
        val orgOwned = createGitRepo("org-owned-repo")
        val noRemote = createGitRepo("no-remote-repo")

        val plan = CodexWorkspaceSyncService.buildPlan(
            candidates = listOf(
                CodexWorkspaceSyncService.Candidate(owned.absolutePath, "active-workspace-roots"),
                CodexWorkspaceSyncService.Candidate(orgOwned.absolutePath, "active-workspace-roots"),
                CodexWorkspaceSyncService.Candidate(noRemote.absolutePath, "active-workspace-roots")
            ),
            existingRepoRoots = emptyList(),
            workspaceProjectPath = null,
            includeWorktrees = false,
            githubOwner = "tannerpolley",
            listRemoteUrls = { root ->
                when (root) {
                    owned.absolutePath.replace('\\', '/') -> listOf("https://github.com/tannerpolley/owned-repo.git")
                    orgOwned.absolutePath.replace('\\', '/') -> listOf("git@github.com:ePC-SAFT/ePC-SAFT.git")
                    else -> emptyList()
                }
            }
        )

        assertEquals(listOf(owned.absolutePath.replace('\\', '/')), plan.accepted.map { it.repoRootPath })
        assertEquals(
            listOf("github_owner_mismatch:ePC-SAFT", "git_remote_missing"),
            plan.skipped.map { it.reason }
        )
    }

    fun testGithubOwnerFromRemoteUrlSupportsCommonGitHubRemoteFormats() {
        assertEquals(
            "tannerpolley",
            CodexWorkspaceSyncService.githubOwnerFromRemoteUrl("https://github.com/tannerpolley/jetbrains-bridge.git")
        )
        assertEquals(
            "tannerpolley",
            CodexWorkspaceSyncService.githubOwnerFromRemoteUrl("git@github.com:tannerpolley/jetbrains-bridge.git")
        )
        assertEquals(
            "tannerpolley",
            CodexWorkspaceSyncService.githubOwnerFromRemoteUrl("ssh://git@github.com/tannerpolley/jetbrains-bridge.git")
        )
        assertNull(CodexWorkspaceSyncService.githubOwnerFromRemoteUrl("https://example.com/tannerpolley/repo.git"))
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
