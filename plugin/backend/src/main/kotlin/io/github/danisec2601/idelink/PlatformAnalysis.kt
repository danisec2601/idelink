package io.github.danisec2601.idelink

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.Processor
import io.github.danisec2601.idelink.core.BadArgs
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import com.intellij.openapi.projectRoots.Sdk as IdeSdk

private val SEARCH_TIMEOUT = 30.seconds
private const val MAX_LINE_READ = 2000

/** [AnalysisIde] on the IntelliJ Platform's public PSI, index and daemon APIs, plus whatever [languages] add. */
internal class PlatformAnalysis(
    private val languages: () -> List<LanguageAnalysis> = { LanguageAnalysis.EP.extensionList },
) : AnalysisIde {

    override fun isDumb(project: String) = DumbService.isDumb(project(project))

    override fun lineLengths(project: String, file: Path): IntArray? {
        val vf = vfile(file)
        return runReadActionBlocking {
            FileDocumentManager.getInstance().getDocument(vf)?.let { d ->
                IntArray(d.lineCount) { d.getLineEndOffset(it) - d.getLineStartOffset(it) }
            }
        }
    }

    override fun problems(project: String, file: Path, min: MinSeverity, limit: Int): AnalysisIde.Problems {
        val p = project(project)
        val vf = vfile(file)
        val severity = when (min) {
            MinSeverity.ERROR -> HighlightSeverity.ERROR
            MinSeverity.WARNING -> HighlightSeverity.WARNING
            MinSeverity.WEAK_WARNING -> HighlightSeverity.WEAK_WARNING
        }
        return runReadActionBlocking {
            val doc = FileDocumentManager.getInstance().getDocument(vf) ?: throw BadArgs("not a text file")
            val items = ArrayList<AnalysisIde.Problem>()
            var more = false
            DaemonCodeAnalyzerEx.processHighlights(doc, p, severity, 0, doc.textLength) { h ->
                if (items.size >= limit) { more = true; return@processHighlights false }
                val off = h.startOffset.coerceIn(0, doc.textLength)
                val line = doc.getLineNumber(off)
                items += AnalysisIde.Problem(h.severity.name, line + 1, off - doc.getLineStartOffset(line) + 1,
                    h.description, h.inspectionToolId)
                true
            }
            val psi = PsiManager.getInstance(p).findFile(vf)
            val finished = psi != null && DaemonCodeAnalyzerEx.getInstanceEx(p).isErrorAnalyzingFinished(psi)
            AnalysisIde.Problems(items, more, finished)
        }
    }

    override fun resolve(project: String, file: Path, line: Int, column: Int): AnalysisIde.Symbol? {
        val p = project(project)
        val vf = vfile(file)
        return smart(p) { targetAt(p, vf, line, column)?.let { symbol(p, it) } }
    }

    override fun usages(
        project: String, file: Path, line: Int, column: Int, includeLibraries: Boolean, limit: Int,
    ): AnalysisIde.Usages? {
        val p = project(project)
        val vf = vfile(file)
        return smart(p) {
            val target = targetAt(p, vf, line, column) ?: return@smart null
            val scope = if (includeLibraries) ProjectScope.getAllScope(p) else ProjectScope.getContentScope(p)
            val items = ArrayList<AnalysisIde.Location>()
            var more = false
            ReferencesSearch.search(target, scope, false).forEach(Processor { ref ->
                if (items.size >= limit) { more = true; return@Processor false }
                ref.element.containingFile?.let { items += location(p, it, ref.absoluteRange.startOffset) }
                true
            })
            AnalysisIde.Usages(symbol(p, target), items, more)
        }
    }

    override fun structure(project: String): AnalysisIde.Structure {
        val p = project(project)
        val langs = languages()
        return runReadActionBlocking {
            val modules = ModuleManager.getInstance(p).modules.map { m ->
                val entries = ModuleRootManager.getInstance(m).contentEntries
                val folders = entries.flatMap { e ->
                    e.sourceFolders.mapNotNull { f ->
                        f.file?.let(::nio)?.let { AnalysisIde.Folder(it, f.isTestSource) }
                    }
                }
                val platformSdk = ModuleRootManager.getInstance(m).sdk?.let(::sdk)
                AnalysisIde.Module(
                    m.name,
                    entries.mapNotNull { e -> e.file?.let(::nio) },
                    folders,
                    moduleSdk(langs.map { l -> l.moduleSdk(m)?.let(::sdk) }, platformSdk),
                )
            }
            AnalysisIde.Structure(modules, projectSdk(ProjectRootManager.getInstance(p).projectSdk?.let(::sdk)) {
                langs.map { l -> l.projectSdk(p)?.let(::sdk) }
            })
        }
    }

    private fun symbol(p: Project, element: PsiElement): AnalysisIde.Symbol {
        val e = element.navigationElement ?: element
        val file = e.containingFile
        val doc = file?.let { PsiDocumentManager.getInstance(p).getDocument(it) }
        val nameAt = (e as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: e.textOffset
        val generic = AnalysisIde.Symbol(
            kind = ElementDescriptionUtil.getElementDescription(e, UsageViewTypeLocation.INSTANCE),
            name = (e as? PsiNamedElement)?.name,
            qualifiedName = (e as? PsiQualifiedNamedElement)?.qualifiedName,
            header = doc?.let { lineText(it, nameAt) },
            doc = null,
            at = if (file != null) location(p, file, nameAt)
            else AnalysisIde.Location(null, 0, null, inContent = false, inLibrary = false, library = null),
        )
        return withExtras(generic, languages().mapNotNull { it.symbol(e, doc) })
    }

    private fun location(p: Project, file: PsiFile, offset: Int): AnalysisIde.Location {
        val vf = file.virtualFile
        val index = ProjectFileIndex.getInstance(p)
        val inContent = vf != null && index.isInContent(vf)
        val inLibrary = vf != null && index.isInLibrary(vf)
        val library = if (inLibrary) index.getOrderEntriesForFile(vf).firstNotNullOfOrNull {
            when (it) {
                is LibraryOrderEntry -> it.libraryName ?: it.presentableName
                is JdkOrderEntry -> it.jdkName ?: it.presentableName
                else -> null
            }
        } else null
        val doc = if (inContent) PsiDocumentManager.getInstance(p).getDocument(file) else null
        val off = offset.coerceIn(0, doc?.textLength ?: 0)
        return AnalysisIde.Location(
            file = vf?.let(::nio),
            line = doc?.let { it.getLineNumber(off) + 1 } ?: 0,
            lineText = doc?.let { lineText(it, off) },
            inContent = inContent, inLibrary = inLibrary, library = library,
        )
    }
}

private fun project(label: String): Project = OpenProjects.require(label)

private fun vfile(p: Path): VirtualFile =
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p) ?: throw BadArgs("IDE can't find the file")

private fun sdk(s: IdeSdk) = AnalysisIde.Sdk(s.name, s.versionString, s.sdkType.name)

private fun nio(vf: VirtualFile): Path? =
    if (vf.isInLocalFileSystem) runCatching { vf.toNioPath() }.getOrNull() else null

/** Waits for no index: a dumb project fails at once, and a search that outlives the timeout fails too. */
private fun <T> smart(p: Project, body: () -> T): T {
    check(!DumbService.isDumb(p)) { INDEXING }
    return try {
        runBlocking { withTimeout(SEARCH_TIMEOUT) { smartReadAction(p) { body() } } }
    } catch (e: IndexNotReadyException) {
        throw IllegalStateException(INDEXING, e)
    } catch (e: TimeoutCancellationException) {
        throw IllegalStateException("timed out after $SEARCH_TIMEOUT (IDE busy or indexing); retry shortly", e)
    }
}

private fun textOf(p: Project, vf: VirtualFile): Pair<PsiFile, Document> {
    val psi = PsiManager.getInstance(p).findFile(vf) ?: throw BadArgs("the IDE has no syntax tree for this file")
    val doc = PsiDocumentManager.getInstance(p).getDocument(psi) ?: throw BadArgs("not a text file")
    return psi to doc
}

private fun targetAt(p: Project, vf: VirtualFile, line: Int, column: Int): PsiElement? {
    val (psi, doc) = textOf(p, vf)
    if (line > doc.lineCount.coerceAtLeast(1)) throw BadArgs("the file changed during the call")
    val start = if (doc.lineCount == 0) 0 else doc.getLineStartOffset(line - 1)
    val offset = TargetElementUtil.adjustOffset(psi, doc, (start + column - 1).coerceAtMost(doc.textLength))
    return referenceTarget(psi, offset) ?: declarationAt(psi, offset)
}

private fun referenceTarget(psi: PsiFile, offset: Int): PsiElement? {
    val ref = psi.findReferenceAt(offset) ?: return null
    return ref.resolve()
        ?: (ref as? PsiPolyVariantReference)?.multiResolve(false)?.firstOrNull { it.isValidResult }?.element
}

/** The declaration whose name is at [offset], when the caret is on a name rather than a reference. */
private fun declarationAt(psi: PsiFile, offset: Int): PsiElement? {
    val leaf = psi.findElementAt(offset) ?: return null
    val owner = PsiTreeUtil.getParentOfType(leaf, PsiNameIdentifierOwner::class.java, false)
    return owner?.takeIf { it.nameIdentifier?.textRange?.containsOffset(offset) == true }
}

private fun lineText(doc: Document, offset: Int): String {
    val line = doc.getLineNumber(offset.coerceIn(0, doc.textLength))
    val s = doc.getLineStartOffset(line)
    return doc.getText(TextRange(s, minOf(doc.getLineEndOffset(line), s + MAX_LINE_READ)))
}
