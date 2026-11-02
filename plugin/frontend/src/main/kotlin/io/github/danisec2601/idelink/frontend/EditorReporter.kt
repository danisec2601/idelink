@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink.frontend

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import io.github.danisec2601.idelink.shared.EditorStateApi
import io.github.danisec2601.idelink.shared.EditorStateDto
import io.github.danisec2601.idelink.shared.SelectionDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reports open files, the active file, caret line and selection bounds to the backend: no file contents and no
 * selection text. The backend decides what (if anything) is inside the project root.
 */
@Service(Service.Level.PROJECT)
internal class EditorReporter(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val state = MutableStateFlow(EditorStateDto(null, null, null))

    @OptIn(FlowPreview::class)
    fun start() {
        project.messageBus.connect(scope).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = snapshot()
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = snapshot()
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) = snapshot()
            })
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                if (e.editor.project == project) snapshot()
            }
        }, this)
        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.editor.project == project) snapshot()
            }
        }, this)
        snapshot()
        scope.launch {
            state.debounce(250).collect { s ->
                runCatching { durable { EditorStateApi.getInstance().report(project.projectId(), s) } }
            }
        }
    }

    override fun dispose() {}

    private fun snapshot() {
        val fem = FileEditorManager.getInstance(project)
        val file = fem.selectedFiles.firstOrNull()
        val editor = fem.selectedTextEditor
        val line = editor?.caretModel?.logicalPosition?.line?.plus(1)
        val sel = editor?.selectionModel?.takeIf { it.hasSelection() }?.let { m ->
            val doc = editor.document
            val selFile = FileDocumentManager.getInstance().getFile(doc) ?: return@let null
            SelectionDto(selFile.path, doc.getLineNumber(m.selectionStart) + 1, doc.getLineNumber(m.selectionEnd) + 1,
                m.selectionStart, m.selectionEnd, editor.caretModel.caretCount)
        }
        state.value = EditorStateDto(file?.path, file?.url, line, fem.openFiles.map { it.path }, sel)
    }
}

internal class EditorReporterStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Editor state belongs to the EDT; ProjectActivity runs off it.
        withContext(Dispatchers.EDT) { project.getService(EditorReporter::class.java).start() }
    }
}
