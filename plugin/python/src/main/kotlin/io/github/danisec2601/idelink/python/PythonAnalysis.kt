package io.github.danisec2601.idelink.python

import com.intellij.openapi.editor.Document
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.jetbrains.python.psi.PyDocStringOwner
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyQualifiedNameOwner
import com.jetbrains.python.psi.PyStatementListContainer
import com.jetbrains.python.psi.resolve.QualifiedNameFinder
import com.jetbrains.python.sdk.PythonSdkUtil
import com.jetbrains.python.sdk.pythonSdk
import io.github.danisec2601.idelink.LanguageAnalysis
import io.github.danisec2601.idelink.SymbolExtras

private const val MAX_HEADER = 2000

/** Python's additions to the analysis tools: def/class signatures, docstrings, qualified names and interpreters. */
internal class PythonAnalysis : LanguageAnalysis {
    override fun symbol(element: PsiElement, document: Document?): SymbolExtras? {
        val extras = SymbolExtras(
            qualifiedName = (element as? PyQualifiedNameOwner)?.qualifiedName ?: moduleName(element),
            header = document?.let { signature(element, it) },
            doc = (element as? PyDocStringOwner)?.docStringExpression?.text?.let(::docstringText),
        )
        return extras.takeIf { it != SymbolExtras() }
    }

    override fun moduleSdk(module: Module): Sdk? = PythonSdkUtil.findPythonSdk(module)

    override fun projectSdk(project: Project): Sdk? = project.pythonSdk

    /** The importable dotted name of a module or package, e.g. "os" for an `import os` target. */
    private fun moduleName(element: PsiElement): String? =
        (element as? PsiFileSystemItem)?.takeIf { it is PyFile || it is PsiDirectory }
            ?.let { QualifiedNameFinder.findShortestImportableQName(it)?.toString() }

    /** A def or class from its first line up to its body, or null if [element] has no body. */
    private fun signature(element: PsiElement, document: Document): String? {
        val body = (element as? PyStatementListContainer)?.statementList?.textRange?.startOffset
        val start = element.textRange.startOffset
        if (body == null || body <= start) return null
        return document.getText(TextRange(start, minOf(body, start + MAX_HEADER))).trimEnd()
    }
}

private val stringPrefix = Regex("""^[rRuUbBfF]{0,2}""")

/** The text inside a Python string literal's quotes, without unescaping; [literal] is the literal's source text. */
internal fun docstringText(literal: String): String {
    val s = literal.trim().replace(stringPrefix, "")
    val q = listOf("\"\"\"", "'''", "\"", "'").firstOrNull { s.startsWith(it) } ?: return s
    val inner = s.removePrefix(q)
    val body = if (inner.endsWith(q)) inner.dropLast(q.length) else inner
    val lines = body.lines()
    val rest = lines.drop(1)
    val indent = rest.filter { it.isNotBlank() }.minOfOrNull { l -> l.indexOfFirst { !it.isWhitespace() } } ?: 0
    return (listOf(lines[0].trim()) + rest.map { it.drop(indent).trimEnd() }).joinToString("\n").trim()
}
