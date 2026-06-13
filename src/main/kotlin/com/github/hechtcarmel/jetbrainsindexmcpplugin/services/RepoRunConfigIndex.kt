package com.github.hechtcarmel.jetbrainsindexmcpplugin.services

import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project

object RepoRunConfigIndex {
    fun fromProject(project: Project): RepoRunConfigTree {
        val runManager = RunManager.getInstance(project)
        val temporarySettings = runManager.tempConfigurationsList.toSet()
        val candidates = runManager.allSettings.map { settings ->
            RepoRunConfigCandidate(
                name = settings.name,
                folderName = settings.folderName.orEmpty(),
                typeId = settings.type.id,
                typeName = settings.type.displayName,
                isTemporary = settings in temporarySettings,
                settings = settings
            )
        }
        return buildTree(candidates)
    }

    fun buildTree(candidates: List<RepoRunConfigCandidate>): RepoRunConfigTree {
        val diagnostics = mutableListOf<RepoRunConfigDiagnostic>()
        val accepted = candidates.filter { candidate ->
            when {
                candidate.isTemporary -> {
                    diagnostics += RepoRunConfigDiagnostic(candidate.name, "temporary config rejected")
                    false
                }
                candidate.folderName.isBlank() -> {
                    diagnostics += RepoRunConfigDiagnostic(candidate.name, "missing repo folder")
                    false
                }
                else -> true
            }
        }

        val duplicateKeys = accepted
            .groupBy { RepoTypeConfigKey(it.folderName, it.typeId, it.name) }
            .filterValues { it.size > 1 }
            .keys

        duplicateKeys.forEach { key ->
            diagnostics += RepoRunConfigDiagnostic(
                configName = key.configName,
                reason = "duplicate config name in ${key.repoName}/${key.typeId}"
            )
        }

        val repos = accepted
            .filterNot { RepoTypeConfigKey(it.folderName, it.typeId, it.name) in duplicateKeys }
            .groupBy { it.folderName }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (repoName, repoCandidates) ->
                RepoRunConfigRepo(
                    repoName = repoName,
                    types = repoCandidates
                        .groupBy { it.typeId }
                        .map { (_, typeCandidates) ->
                            val first = typeCandidates.first()
                            RepoRunConfigTypeGroup(
                                typeId = first.typeId,
                                typeName = first.typeName,
                                configs = typeCandidates
                                    .sortedBy { it.name.lowercase() }
                                    .map {
                                        RepoRunConfigLeaf(
                                            name = it.name,
                                            typeId = it.typeId,
                                            typeName = it.typeName,
                                            repoName = it.folderName,
                                            settings = it.settings
                                        )
                                    }
                            )
                        }
                        .sortedBy { it.typeName.lowercase() }
                )
            }

        return RepoRunConfigTree(repos = repos, diagnostics = diagnostics)
    }

    private data class RepoTypeConfigKey(
        val repoName: String,
        val typeId: String,
        val configName: String
    )
}
