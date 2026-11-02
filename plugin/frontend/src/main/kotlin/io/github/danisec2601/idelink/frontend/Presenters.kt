@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink.frontend

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.platform.rpc.topics.ProjectRemoteTopic
import com.intellij.platform.rpc.topics.ProjectRemoteTopicListener
import io.github.danisec2601.idelink.shared.ArrivalKind
import io.github.danisec2601.idelink.shared.OpenOfferEvent
import io.github.danisec2601.idelink.shared.Plain
import io.github.danisec2601.idelink.shared.ShowDiffEvent
import io.github.danisec2601.idelink.shared.Topics
import io.github.danisec2601.idelink.shared.TourEvent

private fun onEdt(project: Project, f: () -> Unit) =
    ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) f() }, ModalityState.nonModal())

/**
 * Text-only, read-only diff: both sides are in-memory documents (no VirtualFile), so there is no file the
 * viewer could write to, and FORCE_READ_ONLY removes its edit/apply actions.
 */
internal class ShowDiffListener : ProjectRemoteTopicListener<ShowDiffEvent> {
    override val topic: ProjectRemoteTopic<ShowDiffEvent> = Topics.SHOW_DIFF
    override fun handleEvent(project: Project, event: ShowDiffEvent) = onEdt(project) {
        project.getService(ArrivalPrompter::class.java)
            .offer(ArrivalKind.OPEN, event.offerId, event.userAllowed, ArrivalText.diff(event.path), "Show") {
                show(project, event)
            }
    }

    private fun show(project: Project, event: ShowDiffEvent) {
        val type = FileTypeManager.getInstance().getFileTypeByFileName(event.fileName)
        val f = DiffContentFactory.getInstance()
        val req = SimpleDiffRequest(Plain.text(event.title, 300),
            f.create(project, event.leftText, type), f.create(project, event.rightText, type),
            Plain.text(event.leftTitle, 400), Plain.text(event.rightTitle, 400))
        req.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        DiffManager.getInstance().showDiff(project, req, DiffDialogHints.FRAME)
    }
}

internal class TourListener : ProjectRemoteTopicListener<TourEvent> {
    override val topic: ProjectRemoteTopic<TourEvent> = Topics.TOUR
    override fun handleEvent(project: Project, event: TourEvent) = onEdt(project) {
        val model = project.getService(TourPanel.Model::class.java)
        model.receive(event)
        project.getService(ArrivalPrompter::class.java).offer(ArrivalKind.TOUR, event.tourId, event.userAllowed,
            ArrivalText.tour(event.title, event.items.size), "Start tour") { model.start(event.tourId) }
    }
}

/** Opens nothing itself: Show asks the backend, which opens only the file it resolved for this offer. */
internal class OpenOfferListener : ProjectRemoteTopicListener<OpenOfferEvent> {
    override val topic: ProjectRemoteTopic<OpenOfferEvent> = Topics.OPEN_OFFER
    override fun handleEvent(project: Project, event: OpenOfferEvent) = onEdt(project) {
        val prompter = project.getService(ArrivalPrompter::class.java)
        prompter.offer(ArrivalKind.OPEN, event.offerId, event.userAllowed, ArrivalText.open(event.path, event.line),
            "Show") {
            prompter.openOffer(event.offerId)
        }
    }
}
