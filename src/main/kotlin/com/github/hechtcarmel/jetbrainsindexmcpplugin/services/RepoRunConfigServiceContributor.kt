package com.github.hechtcarmel.jetbrainsindexmcpplugin.services

import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.openapi.project.Project

class RepoRunConfigServiceContributor(
    private val treeProvider: (Project) -> RepoRunConfigTree = { RepoRunConfigIndex.fromProject(it) }
) : ServiceViewContributor<RepoRunConfigRepoNode> {
    override fun getViewDescriptor(project: Project): ServiceViewDescriptor =
        SimpleServiceDescriptor("Bridge Repos")

    override fun getServices(project: Project): List<RepoRunConfigRepoNode> =
        treeProvider(project).repos.map { RepoRunConfigRepoNode(it) }

    override fun getServiceDescriptor(project: Project, service: RepoRunConfigRepoNode): ServiceViewDescriptor =
        service.getViewDescriptor(project)
}

data class RepoRunConfigRepoNode(val repo: RepoRunConfigRepo) : ServiceViewContributor<RepoRunConfigTypeNode> {
    override fun getViewDescriptor(project: Project): ServiceViewDescriptor =
        SimpleServiceDescriptor(repo.repoName)

    override fun getServices(project: Project): List<RepoRunConfigTypeNode> =
        repo.types.map { RepoRunConfigTypeNode(repo.repoName, it) }

    override fun getServiceDescriptor(project: Project, service: RepoRunConfigTypeNode): ServiceViewDescriptor =
        service.getViewDescriptor(project)
}

data class RepoRunConfigTypeNode(
    val repoName: String,
    val typeGroup: RepoRunConfigTypeGroup
) : ServiceViewContributor<RepoRunConfigLeafNode> {
    override fun getViewDescriptor(project: Project): ServiceViewDescriptor =
        SimpleServiceDescriptor(typeGroup.typeName)

    override fun getServices(project: Project): List<RepoRunConfigLeafNode> =
        typeGroup.configs.map { RepoRunConfigLeafNode(it) }

    override fun getServiceDescriptor(project: Project, service: RepoRunConfigLeafNode): ServiceViewDescriptor =
        service.getViewDescriptor(project)
}

data class RepoRunConfigLeafNode(val leaf: RepoRunConfigLeaf) {
    fun getViewDescriptor(project: Project): ServiceViewDescriptor =
        RunConfigServiceDescriptor(project, leaf)
}
