package com.github.hechtcarmel.jetbrainsindexmcpplugin.services

import com.intellij.execution.RunnerAndConfigurationSettings

data class RepoRunConfigCandidate(
    val name: String,
    val folderName: String,
    val typeId: String,
    val typeName: String,
    val isTemporary: Boolean,
    val settings: RunnerAndConfigurationSettings?
)

data class RepoRunConfigTree(
    val repos: List<RepoRunConfigRepo>,
    val diagnostics: List<RepoRunConfigDiagnostic>
)

data class RepoRunConfigRepo(
    val repoName: String,
    val types: List<RepoRunConfigTypeGroup>
)

data class RepoRunConfigTypeGroup(
    val typeId: String,
    val typeName: String,
    val configs: List<RepoRunConfigLeaf>
)

data class RepoRunConfigLeaf(
    val name: String,
    val typeId: String,
    val typeName: String,
    val repoName: String,
    val settings: RunnerAndConfigurationSettings?
)

data class RepoRunConfigDiagnostic(
    val configName: String,
    val reason: String
)
