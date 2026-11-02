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
import io.github.danisec2601.idelink.core.PathScope
import io.github.danisec2601.idelink.core.Policy
import io.github.danisec2601.idelink.shared.TourApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val LOG = logger<TourStore>()

/** The current tour per project ([OpenProjects.key]), as the backend resolved it. A new tour replaces the old one. */
@Service(Service.Level.APP)
internal class TourStore {
    data class Stop(val file: PathScope.Resolved, val line: Int)
    private data class Tour(val id: Long, val stops: List<Stop>)

    // Seeded from the clock so a client still showing a tour from before a backend restart can't match a new id.
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val byProject = ConcurrentHashMap<String, Tour>()

    fun put(projectKey: String, stops: List<Stop>): Long =
        nextId.incrementAndGet().also { byProject[projectKey] = Tour(it, stops) }

    fun stop(projectKey: String, tourId: Long, index: Int): Stop? =
        byProject[projectKey]?.takeIf { it.id == tourId }?.stops?.getOrNull(index)
}

internal class BackendTourApi : TourApi {
    override suspend fun openStop(projectId: ProjectId, tourId: Long, index: Int): String? {
        val project = projectId.findProjectOrNull() ?: return "project is closed on the backend"
        if (!ClientNavigator.callerIsController(project, "tour stop")) return NOT_CONTROLLER
        val stop = ApplicationManager.getApplication().getService(TourStore::class.java)
            .stop(OpenProjects.key(project), tourId, index)
            ?: return "this tour was replaced by a newer one"
        Policy.refuseDeferred(stop.file, schemaTyped(stop.file.real), "the tour was sent")?.let { return it }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(stop.file.real) ?: return "the file is gone"
        val n = readAction { FileDocumentManager.getInstance().getDocument(vf)?.lineCount } ?: return "not a text file"
        val line = stop.line.coerceIn(1, n.coerceAtLeast(1))
        if (ClientNavigator.open(project, vf, line, line) == 0) return "no IDE client session found on the backend"
        LOG.debug("idelink: tour stop $index opened")
        return null
    }
}
