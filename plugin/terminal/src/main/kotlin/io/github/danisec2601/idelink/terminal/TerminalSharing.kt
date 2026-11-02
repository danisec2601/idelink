@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink.terminal

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowContextMenuActionBase
import com.intellij.platform.project.projectId
import com.intellij.platform.rpc.topics.ProjectRemoteTopic
import com.intellij.platform.rpc.topics.ProjectRemoteTopicListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.ui.content.Content
import io.github.danisec2601.idelink.shared.Plain
import io.github.danisec2601.idelink.shared.TerminalAnswerDto
import io.github.danisec2601.idelink.shared.TerminalApi
import io.github.danisec2601.idelink.shared.TerminalRequestEvent
import io.github.danisec2601.idelink.shared.TerminalText
import io.github.danisec2601.idelink.shared.Topics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.beans.PropertyChangeListener

private val LOG = logger<TerminalShares>()
private val MARKER = Key.create<PropertyChangeListener>("idelink.terminal.sharedMarker")
private const val SUFFIX = TerminalText.SHARED_SUFFIX

// Blank screen rows below the cursor are read too, then trimmed; this many extra rows covers a typical screen.
private const val BLANK_ROWS = 200L

/** This client's opt-in per terminal tab. Lives only in memory, so it ends with the tab or the client. */
@Service(Service.Level.PROJECT)
internal class TerminalShares(private val project: Project, val scope: CoroutineScope) {
    val registry = ShareRegistry<TerminalToolWindowTab>()

    fun openTabs(): List<TerminalToolWindowTab> = TerminalToolWindowTabsManager.getInstance(project).tabs

    fun tabOf(content: Content): TerminalToolWindowTab? = openTabs().firstOrNull { it.content === content }

    fun toggle(tab: TerminalToolWindowTab) {
        if (registry.unshare(tab)) unmark(tab.content) else { registry.share(tab); mark(tab.content) }
        LOG.info("idelink: terminal tab ${if (registry.idOf(tab) != null) "shared" else "unshared"}")
    }

    fun answer(e: TerminalRequestEvent): TerminalAnswerDto = runReadActionBlocking {
        val id = e.terminalId
        if (id == null) TerminalAnswers.list(registry, openTabs(), ::title, ::name)
        else TerminalAnswers.read(registry, ::openTabs, id, e.lines, ::readTab)
    }

    private fun title(tab: TerminalToolWindowTab) = Plain.text(tab.view.title.buildTitle(), max = 200)

    private fun name(tab: TerminalToolWindowTab) = Plain.text(tabName(tab.content.displayName), max = 200)

    private fun readTab(tab: TerminalToolWindowTab, lines: Int): TabRead {
        val sets = tab.view.outputModels
        val m = sets.active.value
        val last = m.lastLineIndex
        val wanted = lines + BLANK_ROWS
        val first = if (last.minus(m.firstLineIndex) > wanted) last.minus(wanted) else m.firstLineIndex
        val from = if (m.endOffset.minus(m.startOffset) > 2L * TerminalText.MAX_CHARS)
            maxOf(m.getStartOfLine(first), m.endOffset.minus(2L * TerminalText.MAX_CHARS)) else m.getStartOfLine(first)
        val text = m.getText(from, m.endOffset)
        return TabRead(text.toString(), title(tab), m === sets.alternative, name(tab))
    }

    private fun mark(c: Content) {
        if (c.getUserData(MARKER) != null) return
        // The terminal rewrites the tab name on every title change, so the suffix is re-applied after each.
        val l = PropertyChangeListener { ev -> if (ev.propertyName == Content.PROP_DISPLAY_NAME) addSuffix(c) }
        c.putUserData(MARKER, l)
        c.addPropertyChangeListener(l)
        addSuffix(c)
    }

    private fun addSuffix(c: Content) {
        val name = c.displayName ?: ""
        if (!name.endsWith(SUFFIX)) c.displayName = name + SUFFIX
    }

    private fun unmark(c: Content) {
        c.getUserData(MARKER)?.let(c::removePropertyChangeListener)
        c.putUserData(MARKER, null)
        c.displayName = c.displayName?.removeSuffix(SUFFIX)
    }
}

/** Tab context menu toggle. Shown only on reworked-terminal tabs of this client. */
internal class ShareTerminalAction : ToolWindowContextMenuActionBase(), DumbAware {
    override fun update(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        val shares = e.project?.service<TerminalShares>()
        val tab = content?.let { shares?.tabOf(it) }
        e.presentation.isEnabledAndVisible = tab != null
        if (tab != null) e.presentation.text =
            if (shares!!.registry.idOf(tab) != null) "Stop Sharing with Claude" else "Share with Claude"
    }

    override fun actionPerformed(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        val shares = e.project?.service<TerminalShares>() ?: return
        content?.let(shares::tabOf)?.let(shares::toggle)
    }
}

/** Answers a backend request from this client's shared tabs; the opt-in is re-checked as the answer is built. */
internal class TerminalRequestListener : ProjectRemoteTopicListener<TerminalRequestEvent> {
    override val topic: ProjectRemoteTopic<TerminalRequestEvent> = Topics.TERMINAL_REQUEST

    override fun handleEvent(project: Project, event: TerminalRequestEvent) {
        val shares = project.service<TerminalShares>()
        shares.scope.launch {
            val answer = withContext(Dispatchers.EDT) { shares.answer(event) }
            runCatching { TerminalApi.getInstance().answer(project.projectId(), event.requestId, answer) }
                .onFailure { LOG.warn("idelink: terminal answer not delivered: ${it.javaClass.simpleName}") }
        }
    }
}
