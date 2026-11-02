@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.client.ClientKind
import com.intellij.openapi.client.ClientProjectSession
import com.intellij.openapi.client.ClientSessionsManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.rpc.topics.ProjectRemoteTopic
import com.intellij.platform.rpc.topics.sendToClient
import io.github.danisec2601.idelink.core.ControllerCheck

private val LOG = logger<ClientNavigator>()

/**
 * Opens a backend-resolved file in the controller clients' editors. Editors are per client in split mode, so the
 * open runs under the client's ClientId; the MCP server thread is in no client's context.
 */
internal object ClientNavigator {
    // Kept on the client session, so it goes away with the session.
    private val HIGHLIGHT = Key.create<RangeHighlighter>("idelink.highlight")

    /** The JetBrains Client sessions that own [project]'s UI; REMOTE would also include Code With Me guests. */
    fun controllers(project: Project): List<ClientProjectSession> =
        ClientSessionsManager.getProjectSessions(project, ClientKind.CONTROLLER)

    /** Whether the current RPC comes from one of [project]'s controller sessions; logs a refusal. */
    fun callerIsController(project: Project, rpc: String): Boolean {
        val caller = ClientId.currentOrNull?.value
        val ok = ControllerCheck.accepts(caller, controllers(project).map { it.clientId.value }.toSet())
        if (!ok) LOG.info("idelink: $rpc refused: caller $caller is not a controller session")
        return ok
    }

    /** Sends [event] on [topic] to each controller session only (a broadcast would reach CWM guests too). */
    fun <E : Any> send(topic: ProjectRemoteTopic<E>, project: Project, event: E): Int {
        val sessions = controllers(project)
        for (s in sessions) topic.sendToClient(project, event, s.clientId)
        return sessions.size
    }

    /**
     * Opens in the calling client (a tour click) if it is one, else in every controller session; returns how
     * many sessions the open went to. Never takes keyboard focus.
     */
    fun open(project: Project, file: VirtualFile, line: Int, endLine: Int): Int {
        val all = controllers(project)
        val caller = ClientId.currentOrNull
        val matched = all.filter { it.clientId == caller }
        val sessions = matched.ifEmpty { all }
        LOG.info("idelink: open in ${sessions.size} of ${all.size} controller session(s), " +
            "matched=${matched.isNotEmpty()}, caller=$caller")
        for (s in sessions) {
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed || s.isDisposed) return@invokeLater
                ClientId.withExplicitClientId(s.clientId) { show(s, file, line, endLine) }
            }, ModalityState.nonModal())
        }
        return sessions.size
    }

    @Suppress("DEPRECATION") // the blocking open is what we want here: already on the EDT, inside the client's ClientId
    private fun show(session: ClientProjectSession, file: VirtualFile, line: Int, endLine: Int) {
        val editor = FileEditorManager.getInstance(session.project)
            .openTextEditor(OpenFileDescriptor(session.project, file, line - 1, 0), false)
            ?: return LOG.warn("idelink: no text editor opened for client ${session.clientId}")
        val doc = editor.document
        if (line > doc.lineCount) return
        val last = endLine.coerceIn(line, doc.lineCount)
        session.getUserData(HIGHLIGHT)?.dispose()
        val attrs = EditorColorsManager.getInstance().globalScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)
        session.putUserData(HIGHLIGHT, editor.markupModel.addRangeHighlighter(doc.getLineStartOffset(line - 1),
            doc.getLineEndOffset(last - 1), HighlighterLayer.SELECTION - 1, attrs, HighlighterTargetArea.LINES_IN_RANGE))
        editor.scrollingModel.scrollTo(LogicalPosition(line - 1, 0), ScrollType.CENTER)
    }
}
