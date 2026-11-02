@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink.frontend

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import io.github.danisec2601.idelink.shared.Plain
import io.github.danisec2601.idelink.shared.TourEvent
import io.github.danisec2601.idelink.shared.TourApi
import io.github.danisec2601.idelink.shared.TourItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

private const val TOOL_WINDOW_ID = "idelink Tour"

/**
 * The review tour: stops (picking one asks the backend to open it) and the picked note in a plain JTextArea. Every
 * Claude-supplied string goes through Plain again here; no component gets HTML or a tooltip.
 */
internal class TourPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val items = DefaultListModel<TourItem>()
    private var tourId = 0L
    private val list = JBList(items).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<TourItem>() {
            override fun customizeCellRenderer(l: JList<out TourItem>, v: TourItem, i: Int, sel: Boolean, focus: Boolean) {
                append("${i + 1}. ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(Plain.text("${v.path}:${v.line}", 300))
                append("  " + Plain.text(v.note, 160), SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
        }
    }
    private val note = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val title = JLabel()

    init {
        val bar = JPanel().apply {
            add(JButton("Previous").apply { addActionListener { step(-1) } })
            add(JButton("Next").apply { addActionListener { step(1) } })
            add(title)
        }
        add(bar, BorderLayout.NORTH)
        add(JBSplitter(false, 0.5f).apply {
            firstComponent = JBScrollPane(list); secondComponent = JBScrollPane(note)
        }, BorderLayout.CENTER)
        list.addListSelectionListener { e -> if (!e.valueIsAdjusting) go(list.selectedIndex) }
    }

    private fun step(d: Int) {
        if (items.isEmpty) return
        list.selectedIndex = (list.selectedIndex + d).coerceIn(0, items.size() - 1)
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    private fun go(index: Int) {
        val item = items.getOrNull(index) ?: return
        showNote(item)
        val id = tourId
        project.getService(Model::class.java).openStop(id, index) { why ->
            if (tourId == id && list.selectedIndex == index) note.text = "(couldn't open: ${Plain.text(why, 200)})\n\n" + note.text
        }
    }

    private fun showNote(item: TourItem) {
        note.text = Plain.text(item.note, 2000, multiline = true)
        note.caretPosition = 0
    }

    private fun DefaultListModel<TourItem>.getOrNull(i: Int) = if (i in 0 until size()) get(i) else null

    /** Selects stop 1, which opens it and shows its note. */
    fun start() {
        if (items.isEmpty) return
        if (list.selectedIndex == 0) go(0) else list.selectedIndex = 0
        list.ensureIndexIsVisible(0)
    }

    fun load(event: TourEvent) {
        title.text = Plain.text(event.title, 200) + "  (" + event.items.size + " stops)"
        tourId = event.tourId
        items.clear()
        event.items.forEach { items.addElement(it) }
        items.getOrNull(0)?.let(::showNote) ?: run { note.text = "" }
    }

    @Service(Service.Level.PROJECT)
    class Model(private val project: Project, private val scope: CoroutineScope) {
        var panel: TourPanel? = null
        private var pending: TourEvent? = null
        private var startPending = false
        private var latest = 0L

        /** Puts [event] in the panel without showing the tool window or opening anything. */
        fun receive(event: TourEvent) {
            latest = event.tourId
            startPending = false
            panel?.load(event) ?: run { pending = event }
        }

        /** Shows the tool window without focusing it and starts tour [tourId] at stop 1, if it is still the latest. */
        fun start(tourId: Long) {
            if (tourId != latest) return
            val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            startPending = true
            tw.show { flush() }
            flush()
        }

        fun attach(p: TourPanel) { panel = p; flush() }

        private fun flush() {
            val p = panel ?: return
            pending?.let(p::load)
            pending = null
            if (startPending) { startPending = false; p.start() }
        }

        /** [onFailure] runs on the EDT with the backend's reason. */
        fun openStop(tourId: Long, index: Int, onFailure: (String) -> Unit) {
            scope.launch {
                val why = runCatching { durable { TourApi.getInstance().openStop(project.projectId(), tourId, index) } }
                    .getOrElse { "the backend didn't answer" } ?: return@launch
                withContext(Dispatchers.EDT) { onFailure(why) }
            }
        }
    }
}

internal class TourToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TourPanel(project)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false))
        project.getService(TourPanel.Model::class.java).attach(panel)
    }
}
