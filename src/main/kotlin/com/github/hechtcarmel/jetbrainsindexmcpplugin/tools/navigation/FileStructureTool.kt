package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TreeFormatter
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for analyzing the hierarchical structure of source files.
 *
 * Provides a tree-formatted view of file structure similar to IDE's Structure view,
 * showing classes, methods, fields, Markdown headings, and their nesting relationships.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Markdown
 */
class FileStructureTool : AbstractMcpTool() {

    override val name = "ide_file_structure"

    override val description = """
        Get the hierarchical structure of a source file (similar to IDE's Structure view).

        Shows classes, methods, fields, functions, PHP namespaces, constants, enum cases, Markdown headings, and their nesting relationships in a tree format.

        Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Markdown

        Returns: Formatted tree string with element types, modifiers, signatures, and line numbers.

        Parameters: file (required) - Path relative to project root

        Example: {"file": "src/main/java/com/example/MyClass.java"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). REQUIRED.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = requiredStringArg(arguments, "file").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }

        return suspendingReadAction {
            val psiFile = resolveScopedPsiFile(project, arguments, file)
                ?: return@suspendingReadAction createErrorResult("File not found: $file")

            // Get structure handler for this file's language
            val handler = LanguageHandlerRegistry.getStructureHandler(psiFile)
                ?: return@suspendingReadAction createErrorResult(
                    "Language not supported for file structure. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForStructure().joinToString(", ")}"
                )

            // Extract structure
            val nodes = handler.getFileStructure(psiFile, project)

            if (nodes.isEmpty()) {
                return@suspendingReadAction createSuccessResult(
                    "File is empty or has no parseable structure.\n\n" +
                    "File: ${psiFile.name}\n" +
                    "Language: ${psiFile.language.id}"
                )
            }

            // Format as tree
            val treeString = TreeFormatter.format(nodes, psiFile.name, psiFile.language.id)

            createJsonResult(FileStructureResult(
                file = file,
                language = psiFile.language.id,
                structure = treeString
            ))
        }
    }

    private fun resolveScopedPsiFile(project: Project, arguments: JsonObject, file: String): PsiFile? {
        val requestedProjectPath = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (requestedProjectPath != null) {
            return resolvePsiFileUnderRoot(project, requestedProjectPath, file)
        }

        return getPsiFile(project, file)
    }

    private fun resolvePsiFileUnderRoot(project: Project, rootPath: String, file: String): PsiFile? {
        val rootCanonical = File(rootPath).canonicalFile
        val normalizedRelativePath = file.replace('\\', '/')
        val rootVirtualFile = resolveRootVirtualFile(project, rootCanonical)

        if (!File(file).isAbsolute) {
            rootVirtualFile?.findFileByRelativePath(normalizedRelativePath)?.let { virtualFile ->
                return PsiManager.getInstance(project).findFile(virtualFile)
            }
        }

        val candidateCanonical = if (File(file).isAbsolute) File(file).canonicalFile else File(rootCanonical, file).canonicalFile

        val rootPrefix = rootCanonical.path + File.separator
        val candidatePath = candidateCanonical.path
        if (candidatePath != rootCanonical.path && !candidatePath.startsWith(rootPrefix)) {
            return null
        }

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(candidateCanonical.toPath()) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile)
    }

    private fun resolveRootVirtualFile(project: Project, rootCanonical: File): VirtualFile? =
        ProjectRootManager.getInstance(project).contentRoots
            .firstOrNull { contentRoot ->
                File(contentRoot.path).canonicalPath == rootCanonical.path
            }
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootCanonical.toPath())
}
