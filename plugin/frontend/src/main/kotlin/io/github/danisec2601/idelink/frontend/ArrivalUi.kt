@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink.frontend

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.platform.project.projectId
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import fleet.rpc.client.durable
import io.github.danisec2601.idelink.shared.ArrivalApi
import io.github.danisec2601.idelink.shared.ArrivalKind
import io.github.danisec2601.idelink.shared.ArrivalOutcome
import io.github.danisec2601.idelink.shared.Plain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val GROUP = "idelink.arrival"
private const val INFO_GROUP = "idelink.info"
private const val MAX_REASON = 200

/** The arrival settings, stored on the client per user. */
@Service(Service.Level.APP)
@State(name = "IdelinkArrival", storages = [Storage("idelink.xml")])
internal class ArrivalSettings : PersistentStateComponent<ArrivalPrefs> {
    private var prefs = ArrivalPrefs()

    override fun getState(): ArrivalPrefs = prefs
    override fun loadState(state: ArrivalPrefs) { prefs = state }

    /** The gate over the current settings. */
    fun gate() = ArrivalGate(prefs)

    companion object {
        /** The application's instance. */
        fun get(): ArrivalSettings = ApplicationManager.getApplication().getService(ArrivalSettings::class.java)
    }
}

/** Shows or asks about what Claude sends to [project], one notification per kind; the newest replaces the last. */
@Service(Service.Level.PROJECT)
internal class ArrivalPrompter(private val project: Project, private val scope: CoroutineScope) {
    private val asking = ConcurrentHashMap<ArrivalKind, Notification>()

    /**
     * On the EDT: runs [apply] directly or once the user accepts a notification showing [text] (title to content).
     * [userAllowed] comes from the backend: the user already allowed this in the out-of-project prompt.
     */
    fun offer(kind: ArrivalKind, id: Long, userAllowed: Boolean, text: Pair<String, String>, go: String,
              apply: () -> Unit) {
        if (!ArrivalSettings.get().gate().asks(kind, userAllowed)) {
            report(kind, id, if (userAllowed) ArrivalOutcome.ALLOWED else ArrivalOutcome.DIRECT)
            apply()
            return
        }
        report(kind, id, ArrivalOutcome.ASKED)
        asking.remove(kind)?.expire()
        val n = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(text.first, text.second, NotificationType.INFORMATION)
        val once = OneAnswer { choice ->
            val gate = ArrivalSettings.get().gate()
            report(kind, id, gate.outcome(choice))
            if (gate.answer(kind, choice)) apply()
        }
        // Not the *Expiring actions: they expire before the handler runs, so a click would read as a dismissal.
        n.addAction(NotificationAction.createSimple(go) { once.answer(Choice.GO); n.expire() })
        n.addAction(NotificationAction.createSimple("Not now") { once.answer(Choice.NOT_NOW); n.expire() })
        n.addAction(NotificationAction.createSimple(ArrivalText.always(kind)) {
            once.answer(Choice.NEVER_ASK); n.expire()
        })
        n.whenExpired { once.answer(Choice.DISMISSED); asking.remove(kind, n) }
        asking[kind] = n
        n.notify(project)
    }

    /** Asks the backend to open offer [id] in this client; a refusal shows as a notification. */
    fun openOffer(id: Long) {
        scope.launch {
            val why = runCatching { durable { ArrivalApi.getInstance().openOffer(project.projectId(), id) } }
                .getOrElse { "the backend didn't answer" } ?: return@launch
            withContext(Dispatchers.EDT) {
                NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(
                    "idelink couldn't open the file", ArrivalText.html(Plain.text(why, MAX_REASON)),
                    NotificationType.WARNING,
                ).notify(project)
            }
        }
    }

    private fun report(kind: ArrivalKind, id: Long, outcome: ArrivalOutcome) {
        scope.launch {
            runCatching { durable { ArrivalApi.getInstance().report(project.projectId(), kind, id, outcome) } }
        }
    }
}

/** Settings | Tools | idelink. */
internal class ArrivalConfigurable : BoundConfigurable("idelink") {
    override fun createPanel(): DialogPanel = panel {
        val s = ArrivalSettings.get()
        group("When Claude sends something to show") {
            row {
                checkBox("Ask before starting a review tour")
                    .bindSelected({ s.state.askTour }, { s.state.askTour = it })
            }
            row {
                checkBox("Ask before opening a file or diff")
                    .bindSelected({ s.state.askOpen }, { s.state.askOpen = it })
                    .comment("When off, files and diffs open directly and the visible tab switches (typing goes " +
                        "to the newly opened tab).")
            }
            row { comment("\"Always start\" and \"Always show\" on a notification turn these off.") }
        }
    }
}

/** Find Action: "idelink: Ask again before showing tours, files and diffs". */
internal class ResetArrivalAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        ArrivalSettings.get().gate().reset()
        NotificationGroupManager.getInstance().getNotificationGroup(INFO_GROUP).createNotification(
            "idelink will ask again before showing a review tour, file or diff", NotificationType.INFORMATION,
        ).notify(e.project)
    }
}
