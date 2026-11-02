package io.github.danisec2601.idelink

import com.intellij.openapi.editor.Document
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.psi.PsiElement

/** Language-specific additions to the analysis tools, contributed by optional modules such as idelink.python. */
interface LanguageAnalysis {
    /** What this language adds for [element] (its declaration, with [document] as its text); null if nothing. */
    fun symbol(element: PsiElement, document: Document?): SymbolExtras?

    /** The language's own SDK for [module] (e.g. its Python interpreter), or null to use the module's SDK. */
    fun moduleSdk(module: Module): Sdk?

    /** The language's SDK for [project], used only when the project has no SDK of its own. */
    fun projectSdk(project: Project): Sdk?

    /** Holds [EP]. */
    companion object {
        /** The extension point optional modules register their [LanguageAnalysis] with. */
        val EP: ExtensionPointName<LanguageAnalysis> =
            ExtensionPointName.create("io.github.danisec2601.idelink.languageAnalysis")
    }
}

/**
 * Fields a language fills in over the generic symbol; a null field leaves the generic value.
 *
 * @property qualifiedName the language's fully qualified name for the symbol
 * @property header the declaration's signature, in place of its single line
 * @property doc the symbol's documentation text, e.g. a Python docstring
 */
data class SymbolExtras(val qualifiedName: String? = null, val header: String? = null, val doc: String? = null)

/** [generic] with each field taken from the first of [extras] that provides it. */
internal fun withExtras(generic: AnalysisIde.Symbol, extras: List<SymbolExtras>): AnalysisIde.Symbol = generic.copy(
    qualifiedName = extras.firstNotNullOfOrNull { it.qualifiedName } ?: generic.qualifiedName,
    header = extras.firstNotNullOfOrNull { it.header } ?: generic.header,
    doc = extras.firstNotNullOfOrNull { it.doc } ?: generic.doc,
)

/** A module's SDK: the first of [language] that names one, else [platform]. */
internal fun moduleSdk(language: List<AnalysisIde.Sdk?>, platform: AnalysisIde.Sdk?): AnalysisIde.Sdk? =
    language.firstNotNullOfOrNull { it } ?: platform

/** A project's SDK: [platform], else the first of [language] that names one. */
internal fun projectSdk(platform: AnalysisIde.Sdk?, language: () -> List<AnalysisIde.Sdk?>): AnalysisIde.Sdk? =
    platform ?: language().firstNotNullOfOrNull { it }
