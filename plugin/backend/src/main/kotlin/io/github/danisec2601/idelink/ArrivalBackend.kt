@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import io.github.danisec2601.idelink.core.Arrivals
import io.github.danisec2601.idelink.core.PathScope
import io.github.danisec2601.idelink.core.Policy
import io.github.danisec2601.idelink.shared.ArrivalApi
import io.github.danisec2601.idelink.shared.ArrivalKind
import io.github.danisec2601.idelink.shared.ArrivalOutcome

private val LOG = logger<ArrivalService>()

/** An open_file the user hasn't answered yet: lines [line]..[endLine] of a file PathScope resolved. */
internal data class OpenTarget(val file: PathScope.Resolved, val line: Int, val endLine: Int)

/** Holds the backend's [Arrivals] for every project. */
@Service(Service.Level.APP)
internal class ArrivalService {
    val arrivals = Arrivals<OpenTarget>(log = { LOG.info(it) })
}

/** What a Code With Me guest's RPC gets back. */
internal const val NOT_CONTROLLER = "only the project's own IDE client can do this, not a Code With Me guest"

private fun arrivals() = ApplicationManager.getApplication().getService(ArrivalService::class.java).arrivals

internal class BackendArrivalApi : ArrivalApi {
    override suspend fun report(projectId: ProjectId, kind: ArrivalKind, id: Long, outcome: ArrivalOutcome) {
        val project = projectId.findProjectOrNull() ?: return
        if (!ClientNavigator.callerIsController(project, "arrival report")) return
        arrivals().report(OpenProjects.key(project), kind, id, outcome)
            ?.let { LOG.info("idelink: arrival report ignored: $it") }
    }

    @Suppress("ReturnCount") // a guest's call is refused before anything is looked up
    override suspend fun openOffer(projectId: ProjectId, offerId: Long): String? {
        val project = projectId.findProjectOrNull() ?: return "project is closed on the backend"
        if (!ClientNavigator.callerIsController(project, "open offer")) return NOT_CONTROLLER
        val t = arrivals().take(OpenProjects.key(project), offerId)
            ?: return "Claude sent a newer file since, or it was already shown"
        Policy.refuseDeferred(t.file, schemaTyped(t.file.real), "Claude sent it")?.let { return it }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(t.file.real)
        val n = vf?.let { readAction { FileDocumentManager.getInstance().getDocument(it)?.lineCount } }
        return when {
            vf == null -> "the file is gone"
            n == null -> "not a text file"
            else -> {
                val last = n.coerceAtLeast(1)
                val line = t.line.coerceIn(1, last)
                "no IDE client session found on the backend"
                    .takeIf { ClientNavigator.open(project, vf, line, t.endLine.coerceIn(line, last)) == 0 }
            }
        }
    }
}
