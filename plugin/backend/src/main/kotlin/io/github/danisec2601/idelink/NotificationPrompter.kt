@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink

import com.intellij.codeWithMe.ClientId
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import io.github.danisec2601.idelink.core.Approval

/** Shows an [Approval.Prompt] as a sticky, non-focusing balloon in each controller session of its project(s). */
internal object NotificationPrompter : Approval.Show {
    const val GROUP = "idelink"

    override fun show(prompt: Approval.Prompt, answer: (Approval.Reply) -> Unit): Approval.Shown {
        val projects = OpenProjects.labelled().filter { prompt.project == null || it.second == prompt.project }
            .map { it.first }
        val targets = projects.flatMap { p -> ClientNavigator.controllers(p).map { p to it } }
        if (targets.isEmpty()) return Approval.Shown.Nowhere("no IDE client is connected to " +
            (prompt.project?.let { "project '$it'" } ?: "any open project") + ", so there is no one to ask")
        val content = prompt.lines.joinToString("<br>") { html(it) }
        val shown = targets.map { (project, session) ->
            val n = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                .createNotification(html(prompt.title), content, NotificationType.WARNING)
            // Not the *Expiring actions: they expire before the handler runs, so a click would read as a dismissal.
            n.addAction(NotificationAction.createSimple(prompt.allow) { answer(Approval.Reply.ALLOW); n.expire() })
            n.addAction(NotificationAction.createSimple(prompt.deny) { answer(Approval.Reply.DENY); n.expire() })
            n.whenExpired { answer(Approval.Reply.DISMISSED) }
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed && !session.isDisposed) {
                    ClientId.withExplicitClientId(session.clientId) { n.notify(project) }
                }
            }, ModalityState.any())
            n
        }
        return Approval.Shown.Open { expireAll(shown) }
    }

    private fun expireAll(ns: List<Notification>) =
        ApplicationManager.getApplication().invokeLater({ ns.forEach { it.expire() } }, ModalityState.any())

    /** Notification text is HTML; lines arrive already through Plain, this also neutralises entities like &#x202E;. */
    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
