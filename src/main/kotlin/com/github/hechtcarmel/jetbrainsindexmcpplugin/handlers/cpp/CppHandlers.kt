package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.cpp

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.StructureHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.IdeStructureViewExtractor
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement

private const val CPP_LANGUAGE_ID = "C++"

private val CPP_LANGUAGE_IDS = setOf(
    "C",
    "C++",
    "C/C++",
    "CPP",
    "ObjectiveC",
    "ObjectiveC++"
)

private val CPP_FILE_EXTENSIONS = setOf(
    "c",
    "cc",
    "cpp",
    "cxx",
    "h",
    "hh",
    "hpp",
    "hxx",
    "ipp",
    "ixx",
    "inl",
    "tpp",
    "m",
    "mm",
    "cu",
    "cuh"
)

/**
 * Registration entry point for C/C++ handlers.
 *
 * CLion's C/C++ PSI lives in the optional CIDR module. This file intentionally uses only
 * platform APIs and class-name inspection so the plugin still loads in IDEs without CLion.
 */
object CppHandlers {

    private val LOG = logger<CppHandlers>()

    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!PluginDetectors.cpp.isAvailable) {
            LOG.info("C/C++ support not available, skipping C/C++ handler registration")
            return
        }

        registry.registerStructureHandler(CppStructureHandler())

        LOG.info("Registered C/C++ handlers")
    }
}

abstract class BaseCppHandler<T> : LanguageHandler<T> {

    override val languageId = CPP_LANGUAGE_ID

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isCppLanguage(element)

    override fun isAvailable(): Boolean = PluginDetectors.cpp.isAvailable

    protected fun isCppLanguage(element: PsiElement): Boolean {
        val file = (element as? PsiFile) ?: element.containingFile
        val languageIds = buildSet {
            add(element.language.id)
            element.language.baseLanguage?.let { add(it.id) }
            file?.viewProvider?.languages?.forEach { language ->
                add(language.id)
                language.baseLanguage?.let { add(it.id) }
            }
        }

        if (languageIds.any { it in CPP_LANGUAGE_IDS || it.contains("C++", ignoreCase = true) }) {
            return true
        }

        val extension = file?.name
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?: return false

        return extension in CPP_FILE_EXTENSIONS
    }

    protected fun getName(element: PsiElement): String? {
        (element as? PsiNamedElement)?.name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        return invokeString(element, "getName")?.trim()?.takeIf { it.isNotEmpty() }
    }

    protected fun displayName(element: PsiElement, presentation: ItemPresentation): String? {
        val psiName = getName(element)
        if (!psiName.isNullOrBlank()) return psiName

        return presentation.presentableText
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { presentationText ->
                presentationText
                    .removePrefix("#define ")
                    .removePrefix("#include ")
                    .substringBefore('(')
                    .substringBefore(':')
                    .trim()
                    .takeIf { it.isNotEmpty() }
            }
    }

    protected fun signatureFromPresentation(name: String, presentation: ItemPresentation): String? {
        val text = presentation.presentableText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val location = presentation.locationString?.trim()?.takeIf { it.isNotEmpty() }

        val signature = when {
            text == name -> null
            text.startsWith(name) -> text.removePrefix(name).trim()
            text.contains(name) -> text
            else -> null
        }?.ifBlank { null }

        return listOfNotNull(signature, location).joinToString(" ").ifBlank { null }
    }

    protected fun modifiersFor(element: PsiElement): List<String> {
        val declarationText = element.text
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(300)
            .orEmpty()

        val modifiers = listOf(
            "public",
            "protected",
            "private",
            "static",
            "virtual",
            "override",
            "final",
            "inline",
            "constexpr",
            "consteval",
            "constinit",
            "extern",
            "mutable",
            "friend",
            "explicit"
        )

        return modifiers.filter { modifier ->
            declarationText.contains(Regex("""(^|\W)${Regex.escape(modifier)}($|\W)"""))
        }
    }

    protected fun invokeString(element: PsiElement, methodName: String): String? =
        try {
            element.javaClass.getMethod(methodName).invoke(element)?.toString()
        } catch (e: Exception) {
            null
        }
}

class CppStructureHandler : BaseCppHandler<List<StructureNode>>(), StructureHandler {

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        if (!canHandle(file)) return emptyList()

        val structureViewNodes = IdeStructureViewExtractor.extract(
            file = file,
            project = project,
            classifier = CppStructureClassifier()
        )
        if (structureViewNodes.isNotEmpty()) return structureViewNodes

        return CppSourceStructureParser.parse(file.text)
    }

    private inner class CppStructureClassifier : IdeStructureViewExtractor.Classifier {
        override fun describe(
            value: Any?,
            presentation: ItemPresentation
        ): IdeStructureViewExtractor.StructureElementInfo? {
            val element = value as? PsiElement ?: return null
            val kind = classify(element, presentation) ?: return null
            val name = displayName(element, presentation) ?: return null

            return IdeStructureViewExtractor.StructureElementInfo(
                name = name,
                kind = kind,
                modifiers = modifiersFor(element),
                signature = signatureFromPresentation(name, presentation)
            )
        }
    }

    private fun classify(element: PsiElement, presentation: ItemPresentation): StructureKind? {
        val className = "${element.javaClass.simpleName} ${element.javaClass.name}".lowercase()
        val text = presentation.presentableText?.trim()?.lowercase().orEmpty()
        val elementText = element.text?.lineSequence()?.firstOrNull()?.trim()?.lowercase().orEmpty()
        val signal = "$className $text $elementText"

        return when {
            signal.contains("namespace") -> StructureKind.NAMESPACE
            signal.contains("#include") || signal.contains("include") -> StructureKind.INCLUDE
            signal.contains("typedef") || signal.contains("typealias") || text.startsWith("using ") -> StructureKind.TYPE_ALIAS
            signal.contains("enum") -> StructureKind.ENUM
            signal.contains("constructor") -> StructureKind.CONSTRUCTOR
            signal.contains("destructor") -> StructureKind.METHOD
            signal.contains("method") -> StructureKind.METHOD
            signal.contains("function") -> StructureKind.FUNCTION
            signal.contains("class") || signal.contains("struct") || signal.contains("union") -> StructureKind.CLASS
            signal.contains("interface") -> StructureKind.INTERFACE
            signal.contains("macro") || signal.contains("#define") || signal.contains("define") -> StructureKind.CONSTANT
            signal.contains("field") -> StructureKind.FIELD
            signal.contains("variable") || signal.contains("var") -> StructureKind.VARIABLE
            signal.contains("property") -> StructureKind.PROPERTY
            else -> null
        }
    }
}

internal object CppSourceStructureParser {

    private val namespaceRegex = Regex("""^\s*namespace\s+([A-Za-z_]\w*(?:::\w+)*)?\s*\{""")
    private val typeRegex = Regex("""^\s*(?:template\s*<[^>]+>\s*)?(class|struct|union)\s+([A-Za-z_]\w*)\b.*\{?""")
    private val enumRegex = Regex("""^\s*enum(?:\s+(?:class|struct))?\s+([A-Za-z_]\w*)?\b.*\{?""")
    private val usingRegex = Regex("""^\s*using\s+([A-Za-z_]\w*)\s*=""")
    private val typedefRegex = Regex("""^\s*typedef\b.*\b([A-Za-z_]\w*)\s*;""")
    private val functionRegex = Regex(
        """^\s*(?:template\s*<[^>]+>\s*)?(.+?)\b(~?[A-Za-z_]\w*)\s*\(([^;()]*(?:\([^)]*\)[^;()]*)*)\)\s*(?:const\b)?\s*(?:noexcept\b)?\s*(?:override\b)?\s*(?:final\b)?\s*(?:[:{;].*)?$"""
    )
    private val fieldRegex = Regex("""^\s*(.+?)\b([A-Za-z_]\w*)\s*(?:\[[^]]*])?\s*(?:=.*)?;""")

    private val controlNames = setOf(
        "if",
        "for",
        "while",
        "switch",
        "catch",
        "return",
        "sizeof",
        "alignof",
        "static_cast",
        "dynamic_cast",
        "reinterpret_cast",
        "const_cast"
    )

    private val declarationModifiers = listOf(
        "public",
        "protected",
        "private",
        "static",
        "virtual",
        "override",
        "final",
        "inline",
        "constexpr",
        "consteval",
        "constinit",
        "extern",
        "mutable",
        "friend",
        "explicit"
    )

    fun parse(source: String): List<StructureNode> {
        val roots = mutableListOf<NodeBuilder>()
        val scopes = mutableListOf<Scope>()
        var braceDepth = 0
        var inBlockComment = false

        source.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val lineWithoutComments = stripComments(rawLine, inBlockComment)
            inBlockComment = lineWithoutComments.inBlockComment
            val line = lineWithoutComments.text.trim()

            if (line.startsWith("}")) {
                popClosedScopes(scopes, braceDepth)
            }

            if (line.isNotEmpty() && !isInsideFunction(scopes)) {
                parseLine(line, lineNumber, braceDepth, roots, scopes)
            }

            braceDepth = (braceDepth + countChar(line, '{') - countChar(line, '}')).coerceAtLeast(0)
        }

        return roots.map { it.toNode() }
    }

    private fun parseLine(
        line: String,
        lineNumber: Int,
        braceDepth: Int,
        roots: MutableList<NodeBuilder>,
        scopes: MutableList<Scope>
    ) {
        parsePreprocessor(line, lineNumber, roots, scopes)?.let { return }
        parseNamespace(line, lineNumber, braceDepth, roots, scopes)?.let { return }
        parseType(line, lineNumber, braceDepth, roots, scopes)?.let { return }
        parseEnum(line, lineNumber, braceDepth, roots, scopes)?.let { return }
        parseTypeAlias(line, lineNumber, roots, scopes)?.let { return }
        parseFunction(line, lineNumber, braceDepth, roots, scopes)?.let { return }
        parseFieldOrVariable(line, lineNumber, roots, scopes)
    }

    private fun parsePreprocessor(
        line: String,
        lineNumber: Int,
        roots: MutableList<NodeBuilder>,
        scopes: MutableList<Scope>
    ): Boolean? {
        if (line.startsWith("#include")) {
            val name = line.removePrefix("#include").trim().takeIf { it.isNotEmpty() } ?: return true
            addNode(roots, scopes, NodeBuilder(name, StructureKind.INCLUDE, emptyList(), null, lineNumber))
            return true
        }

        if (line.startsWith("#define")) {
            val name = line.removePrefix("#define").trim().substringBefore(' ').takeIf { it.isNotEmpty() } ?: return true
            addNode(roots, scopes, NodeBuilder(name, StructureKind.CONSTANT, emptyList(), null, lineNumber))
            return true
        }

        return null
    }

    private fun parseNamespace(
        line: String,
        lineNumber: Int,
        braceDepth: Int,
        roots: MutableList<NodeBuilder>,
        scopes: MutableList<Scope>
    ): Boolean? {
        val match = namespaceRegex.find(line) ?: return null
        val name = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "(anonymous namespace)"
        val node = NodeBuilder(name, StructureKind.NAMESPACE, emptyList(), null, lineNumber)
        addNode(roots, scopes, node)
        pushContainer(scopes, node, braceDepth, line)
        return true
    }

    private fun parseType(
        line: String,
        lineNumber: Int,
        braceDepth: Int,
        roots: MutableList<NodeBuilder>,
        scopes: MutableList<Scope>
    ): Boolean? {
        val match = typeRegex.find(line) ?: return null
        val modifiers = modifiersFrom(line)
        val node = NodeBuilder(match.groupValues[2], StructureKind.CLASS, modifiers, null, lineNumber)
        addNode(roots, scopes, node)
        pushContainer(scopes, node, braceDepth, line)
        return true
    }

    private fun parseEnum(
        line: String,
        lineNumber: Int,
        braceDepth: Int,
        roots: MutableList<NodeBuilder>,
        scopes: MutableList<Scope>
    ): Boolean? {
        val match = enumRegex.find(line) ?: return null
        val name = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "(anonymous enum)"
        val node = NodeBuilder(name, StructureKind.ENUM, modifiersFrom(line), null, lineNumber)
        addNode(roots, scopes, node)
        pushContainer(scopes, node, braceDepth, line)
        return true
    }

    private fun parseTypeAlias(
        line: String,
        lineNumber: Int,
        roots: MutableList<NodeBuilder>,
        scopes: MutableList<Scope>
    ): Boolean? {
        val usingName = usingRegex.find(line)?.groupValues?.getOrNull(1)
        val typedefName = typedefRegex.find(line)?.groupValues?.getOrNull(1)
        val name = usingName ?: typedefName ?: return null
        addNode(roots, scopes, NodeBuilder(name, StructureKind.TYPE_ALIAS, modifiersFrom(line), null, lineNumber))
        return true
    }

    private fun parseFunction(
        line: String,
        lineNumber: Int,
        braceDepth: Int,
        roots: MutableList<NodeBuilder>,
        scopes: MutableList<Scope>
    ): Boolean? {
        val match = functionRegex.find(line) ?: return null
        val prefix = match.groupValues[1].trim()
        val name = match.groupValues[2].trim()
        if (name in controlNames || prefix.endsWith("=")) return null

        val classScope = scopes.asReversed().firstOrNull { it.node?.kind == StructureKind.CLASS }
        val className = classScope?.node?.name
        val kind = when {
            className != null && (name == className || name == "~$className") -> StructureKind.CONSTRUCTOR
            className != null -> StructureKind.METHOD
            else -> StructureKind.FUNCTION
        }
        val params = match.groupValues[3].trim()
        val signature = "($params)"
        val node = NodeBuilder(name, kind, modifiersFrom(line), signature, lineNumber)
        addNode(roots, scopes, node)

        if (line.contains("{") && countChar(line, '{') > countChar(line, '}')) {
            scopes += Scope(node = null, bodyDepth = braceDepth + countChar(line, '{') - countChar(line, '}'))
        }

        return true
    }

    private fun parseFieldOrVariable(
        line: String,
        lineNumber: Int,
        roots: MutableList<NodeBuilder>,
        scopes: MutableList<Scope>
    ): Boolean? {
        if (!line.endsWith(";") || line.contains("(") || line.startsWith("return ")) return null
        val match = fieldRegex.find(line) ?: return null
        val name = match.groupValues[2].takeIf { it.isNotBlank() } ?: return null
        val classScope = scopes.asReversed().firstOrNull { it.node?.kind == StructureKind.CLASS }
        val kind = if (classScope == null) StructureKind.VARIABLE else StructureKind.FIELD
        addNode(roots, scopes, NodeBuilder(name, kind, modifiersFrom(line), null, lineNumber))
        return true
    }

    private fun addNode(
        roots: MutableList<NodeBuilder>,
        scopes: MutableList<Scope>,
        node: NodeBuilder
    ) {
        val parent = scopes.asReversed().firstOrNull { it.node != null }?.node
        if (parent == null) {
            roots += node
        } else {
            parent.children += node
        }
    }

    private fun pushContainer(
        scopes: MutableList<Scope>,
        node: NodeBuilder,
        braceDepth: Int,
        line: String
    ) {
        val depthChange = countChar(line, '{') - countChar(line, '}')
        if (depthChange > 0) {
            scopes += Scope(node = node, bodyDepth = braceDepth + depthChange)
        }
    }

    private fun popClosedScopes(scopes: MutableList<Scope>, braceDepth: Int) {
        while (scopes.isNotEmpty() && scopes.last().bodyDepth >= braceDepth) {
            scopes.removeAt(scopes.lastIndex)
        }
    }

    private fun isInsideFunction(scopes: List<Scope>): Boolean =
        scopes.isNotEmpty() && scopes.last().node == null

    private fun modifiersFrom(line: String): List<String> =
        declarationModifiers.filter { modifier ->
            line.contains(Regex("""(^|\W)${Regex.escape(modifier)}($|\W)"""))
        }

    private fun countChar(line: String, char: Char): Int =
        line.count { it == char }

    private fun stripComments(line: String, wasInBlockComment: Boolean): CommentStripResult {
        val output = StringBuilder()
        var index = 0
        var inBlockComment = wasInBlockComment

        while (index < line.length) {
            when {
                inBlockComment && index + 1 < line.length && line[index] == '*' && line[index + 1] == '/' -> {
                    inBlockComment = false
                    index += 2
                }
                inBlockComment -> index++
                index + 1 < line.length && line[index] == '/' && line[index + 1] == '*' -> {
                    inBlockComment = true
                    index += 2
                }
                index + 1 < line.length && line[index] == '/' && line[index + 1] == '/' -> {
                    index = line.length
                }
                else -> {
                    output.append(line[index])
                    index++
                }
            }
        }

        return CommentStripResult(output.toString(), inBlockComment)
    }

    private data class CommentStripResult(
        val text: String,
        val inBlockComment: Boolean
    )

    private data class Scope(
        val node: NodeBuilder?,
        val bodyDepth: Int
    )

    private data class NodeBuilder(
        val name: String,
        val kind: StructureKind,
        val modifiers: List<String>,
        val signature: String?,
        val line: Int,
        val children: MutableList<NodeBuilder> = mutableListOf()
    ) {
        fun toNode(): StructureNode =
            StructureNode(
                name = name,
                kind = kind,
                modifiers = modifiers,
                signature = signature,
                line = line,
                children = children.map { it.toNode() }
            )
    }
}
