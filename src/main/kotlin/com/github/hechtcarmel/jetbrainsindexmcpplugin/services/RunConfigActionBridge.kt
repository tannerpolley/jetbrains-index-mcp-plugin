package com.github.hechtcarmel.jetbrainsindexmcpplugin.services

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.openapi.project.Project

object RunConfigActionBridge {
    fun run(project: Project, leaf: RepoRunConfigLeaf) {
        val settings = leaf.settings ?: error("Run configuration '${leaf.name}' is not loaded in RunManager")
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }
}
