package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexMcpRegistrationCommandResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import junit.framework.TestCase

class CodexMcpRegistrationInstallerUnitTest : TestCase() {

    fun testBuildPlanIncludesBroadAndRepoScopedServers() {
        val plan = CodexMcpRegistrationInstaller.buildPlan(
            repoScopes = listOf(
                RepoScope("jetbrains-bridge", "C:/repo/jetbrains-bridge", "C:/Workspace"),
                RepoScope("ePC-SAFT", "C:/repo/ePC-SAFT", "C:/Workspace")
            ),
            broadStreamableHttpUrl = "http://127.0.0.1:29170/index-mcp/streamable-http",
            broadServerName = "intellij-index"
        )

        assertEquals(
            listOf("intellij-index", "intellij-index-ePC-SAFT", "intellij-index-jetbrains-bridge"),
            plan.servers.map { it.name }
        )
        assertEquals(3, plan.commands.size)
        assertTrue(plan.commands[0].contains("codex mcp add intellij-index --url"))
        assertTrue(plan.commands[1].contains("/index-mcp/repos/ePC-SAFT/streamable-http"))
        assertTrue(plan.commands[2].contains("/index-mcp/repos/jetbrains-bridge/streamable-http"))
    }

    fun testInstallDryRunDoesNotRunCommands() {
        val plan = CodexMcpRegistrationInstaller.Plan(
            servers = emptyList(),
            commands = listOf("codex mcp add test --url http://127.0.0.1")
        )
        var ranCommand = false

        val result = CodexMcpRegistrationInstaller.install(
            dryRun = true,
            plan = plan,
            commandRunner = {
                ranCommand = true
                CodexMcpRegistrationCommandResult(it, 0, "")
            }
        )

        assertTrue(result.dryRun)
        assertFalse(ranCommand)
        assertEquals(plan.commands, result.commands)
        assertTrue(result.succeeded.isEmpty())
        assertTrue(result.failures.isEmpty())
    }

    fun testInstallReportsCommandFailures() {
        val plan = CodexMcpRegistrationInstaller.Plan(
            servers = emptyList(),
            commands = listOf("success", "failure")
        )

        val result = CodexMcpRegistrationInstaller.install(
            dryRun = false,
            plan = plan,
            commandRunner = { command ->
                if (command == "success") {
                    CodexMcpRegistrationCommandResult(command, 0, "")
                } else {
                    CodexMcpRegistrationCommandResult(command, 1, "failed")
                }
            }
        )

        assertFalse(result.dryRun)
        assertEquals(1, result.succeeded.size)
        assertEquals(1, result.failures.size)
        assertEquals("failure", result.failures.single().command)
    }

    fun testGeneratedCommandsUseWindowsShellSyntaxWhenRequested() {
        val commands = ClientConfigGenerator.buildRepoScopedCodexCommands(
            broadStreamableHttpUrl = "http://127.0.0.1:29170/index-mcp/streamable-http",
            broadServerName = "intellij-index",
            repoScopes = listOf(RepoScope("repo", "C:/repo", "C:/Workspace")),
            platform = ClientConfigGenerator.CommandPlatform.WINDOWS
        )

        assertTrue(commands.all { it.contains(">NUL 2>&1 & codex mcp add") })
    }
}
