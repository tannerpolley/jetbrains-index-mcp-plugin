package com.github.hechtcarmel.jetbrainsindexmcpplugin.services

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import javax.swing.Icon

class SimpleServiceDescriptor(
    private val text: String,
    private val icon: Icon = AllIcons.Nodes.Folder
) : ServiceViewDescriptor {
    override fun getPresentation(): ItemPresentation =
        object : ItemPresentation {
            override fun getPresentableText(): String = text
            override fun getIcon(unused: Boolean): Icon = icon
        }
}

class RunConfigServiceDescriptor(
    private val project: Project,
    private val leaf: RepoRunConfigLeaf
) : ServiceViewDescriptor {
    override fun getPresentation(): ItemPresentation =
        object : ItemPresentation {
            override fun getPresentableText(): String = leaf.name
            override fun getLocationString(): String = leaf.typeName
            override fun getIcon(unused: Boolean): Icon = leaf.settings?.configuration?.icon ?: AllIcons.Actions.Execute
        }

    override fun getToolbarActions(): ActionGroup =
        DefaultActionGroup().apply {
            add(RunRepoConfigAction(project, leaf))
        }

    override fun getPopupActions(): ActionGroup = toolbarActions
}

class RunRepoConfigAction(
    private val project: Project,
    private val leaf: RepoRunConfigLeaf
) : AnAction("Run") {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = leaf.settings != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        RunConfigActionBridge.run(project, leaf)
    }
}
