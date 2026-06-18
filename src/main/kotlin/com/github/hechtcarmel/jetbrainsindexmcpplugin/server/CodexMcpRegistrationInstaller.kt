package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexMcpRegistrationCommandResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CodexMcpRegistrationResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RepoScopedClientServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object CodexMcpRegistrationInstaller {
    private val LOG = logger<CodexMcpRegistrationInstaller>()
    private const val COMMAND_TIMEOUT_SECONDS = 30L
    private const val OUTPUT_LIMIT = 1200

    data class Plan(
        val servers: List<RepoScopedClientServer>,
        val commands: List<String>,
        val repoScopedServerNamePrefix: String
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
            repoScopedServerNamePrefix = "$broadServerName-",
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
        configuredServerNamesProvider: () -> List<String> = ::listConfiguredCodexServerNames,
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

        val pruneCommands = try {
            buildStaleRepoScopedRemoveCommands(
                plan = plan,
                configuredServerNames = configuredServerNamesProvider()
            )
        } catch (e: Exception) {
            LOG.warn("Codex MCP registration stale-server discovery failed", e)
            val result = CodexMcpRegistrationCommandResult(
                command = "codex mcp list --json",
                exitCode = null,
                output = "failed_to_list_existing_servers: ${e.message ?: e.javaClass.simpleName}"
            )
            return CodexMcpRegistrationResult(
                dryRun = false,
                servers = plan.servers,
                commands = plan.commands,
                succeeded = emptyList(),
                failures = listOf(result),
                message = "Codex MCP registration aborted before install; existing server list could not be read."
            )
        }

        val succeeded = mutableListOf<CodexMcpRegistrationCommandResult>()
        val failures = mutableListOf<CodexMcpRegistrationCommandResult>()
        val commands = pruneCommands + plan.commands
        for (command in commands) {
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
            commands = commands,
            succeeded = succeeded,
            failures = failures,
            message = message
        )
    }

    internal fun buildStaleRepoScopedRemoveCommands(
        plan: Plan,
        configuredServerNames: List<String>
    ): List<String> {
        val desiredNames = plan.servers.mapTo(mutableSetOf()) { it.name }
        return configuredServerNames
            .asSequence()
            .filter { it.startsWith(plan.repoScopedServerNamePrefix) }
            .filterNot { it in desiredNames }
            .distinct()
            .sorted()
            .map { "codex mcp remove $it" }
            .toList()
    }

    private fun listConfiguredCodexServerNames(): List<String> {
        val result = runCommand("codex mcp list --json", outputLimit = Int.MAX_VALUE)
        if (result.exitCode != 0) {
            throw IllegalStateException(result.output.ifBlank { "codex mcp list --json failed" })
        }
        return parseCodexServerNames(result.output)
    }

    internal fun parseCodexServerNames(output: String): List<String> {
        val root = Json.parseToJsonElement(output)
        require(root is JsonArray) { "codex mcp list --json did not return a JSON array" }
        return root.mapNotNull { element ->
            (element as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
        }
    }

    private fun runCommand(
        command: String,
        outputLimit: Int = OUTPUT_LIMIT
    ): CodexMcpRegistrationCommandResult {
        val shellInvocation = ClientConfigGenerator.buildShellInvocation(command)
        val process = ProcessBuilder(shellInvocation)
            .redirectErrorStream(true)
            .start()
        val outputFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }

        val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            outputFuture.cancel(true)
            return CodexMcpRegistrationCommandResult(
                command = command,
                exitCode = null,
                output = "timed_out_after_${COMMAND_TIMEOUT_SECONDS}s"
            )
        }

        val output = outputFuture.get(5, TimeUnit.SECONDS)
        return CodexMcpRegistrationCommandResult(
            command = command,
            exitCode = process.exitValue(),
            output = output.take(outputLimit)
        )
    }
}
