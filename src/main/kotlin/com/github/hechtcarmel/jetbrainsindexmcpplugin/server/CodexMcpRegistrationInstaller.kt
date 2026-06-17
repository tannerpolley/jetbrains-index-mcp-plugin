package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexMcpRegistrationCommandResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexMcpRegistrationResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RepoScopedClientServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.TimeUnit

object CodexMcpRegistrationInstaller {
    private val LOG = logger<CodexMcpRegistrationInstaller>()
    private const val COMMAND_TIMEOUT_SECONDS = 30L
    private const val OUTPUT_LIMIT = 1200

    data class Plan(
        val servers: List<RepoScopedClientServer>,
        val commands: List<String>
    )

    fun buildPlan(
        repoScopes: List<RepoScope> = RepoScopeRegistry.collectOpenRepoScopes(),
        broadStreamableHttpUrl: String = ClientConfigGenerator.getStreamableHttpUrl(),
        broadServerName: String = ClientConfigGenerator.getDefaultServerName()
    ): Plan {
        val servers = mutableListOf(
            RepoScopedClientServer(
                name = broadServerName,
                url = broadStreamableHttpUrl
            )
        )
        for (scope in repoScopes.sortedBy { it.repoId }) {
            servers += RepoScopedClientServer(
                name = "$broadServerName-${scope.repoId}",
                url = ClientConfigGenerator.buildRepoScopedStreamableHttpUrl(broadStreamableHttpUrl, scope.repoId),
                repoId = scope.repoId,
                repoRootPath = scope.repoRootPath
            )
        }

        return Plan(
            servers = servers,
            commands = ClientConfigGenerator.buildRepoScopedCodexCommands(
                broadStreamableHttpUrl = broadStreamableHttpUrl,
                broadServerName = broadServerName,
                repoScopes = repoScopes
            )
        )
    }

    fun install(
        dryRun: Boolean,
        plan: Plan = buildPlan(),
        commandRunner: (String) -> CodexMcpRegistrationCommandResult = ::runCommand
    ): CodexMcpRegistrationResult {
        if (dryRun) {
            return CodexMcpRegistrationResult(
                dryRun = true,
                servers = plan.servers,
                commands = plan.commands,
                succeeded = emptyList(),
                failures = emptyList(),
                message = "Codex MCP registration dry-run generated ${plan.commands.size} command(s)."
            )
        }

        val succeeded = mutableListOf<CodexMcpRegistrationCommandResult>()
        val failures = mutableListOf<CodexMcpRegistrationCommandResult>()
        for (command in plan.commands) {
            val result = try {
                commandRunner(command)
            } catch (e: Exception) {
                LOG.warn("Codex MCP registration command failed to start", e)
                CodexMcpRegistrationCommandResult(
                    command = command,
                    exitCode = null,
                    output = "failed_to_start: ${e.message ?: e.javaClass.simpleName}"
                )
            }

            if (result.exitCode == 0) {
                succeeded += result
            } else {
                failures += result
            }
        }

        val message = if (failures.isEmpty()) {
            "Installed ${succeeded.size} Codex MCP server registration command(s)."
        } else {
            "Installed ${succeeded.size} Codex MCP server registration command(s); ${failures.size} failed."
        }

        return CodexMcpRegistrationResult(
            dryRun = false,
            servers = plan.servers,
            commands = plan.commands,
            succeeded = succeeded,
            failures = failures,
            message = message
        )
    }

    private fun runCommand(command: String): CodexMcpRegistrationCommandResult {
        val shellInvocation = ClientConfigGenerator.buildShellInvocation(command)
        val process = ProcessBuilder(shellInvocation)
            .redirectErrorStream(true)
            .start()

        val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return CodexMcpRegistrationCommandResult(
                command = command,
                exitCode = null,
                output = "timed_out_after_${COMMAND_TIMEOUT_SECONDS}s"
            )
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }
        return CodexMcpRegistrationCommandResult(
            command = command,
            exitCode = process.exitValue(),
            output = output.take(OUTPUT_LIMIT)
        )
    }
}
