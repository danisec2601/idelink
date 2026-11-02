@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink

import com.intellij.openapi.components.Service
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor
import io.github.danisec2601.idelink.shared.ArrivalApi
import io.github.danisec2601.idelink.shared.EditorStateApi
import io.github.danisec2601.idelink.shared.EditorStateDto
import io.github.danisec2601.idelink.shared.TerminalApi
import io.github.danisec2601.idelink.shared.TourApi
import java.util.concurrent.ConcurrentHashMap

/** Last editor state each project's thin client reported, with when it arrived, by [OpenProjects.key]. */
@Service(Service.Level.APP)
internal class EditorStateStore {
    data class Entry(val state: EditorStateDto, val atMillis: Long)
    private val byProject = ConcurrentHashMap<String, Entry>()

    fun put(projectKey: String, state: EditorStateDto) {
        byProject[projectKey] = Entry(state, System.currentTimeMillis())
    }

    fun get(projectKey: String): Entry? = byProject[projectKey]
}

internal class BackendEditorStateApi : EditorStateApi {
    override suspend fun report(projectId: ProjectId, state: EditorStateDto) {
        val project = projectId.findProjectOrNull() ?: return
        if (!ClientNavigator.callerIsController(project, "editor state report")) return
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(EditorStateStore::class.java).put(OpenProjects.key(project), state)
    }
}

internal class IdelinkRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<EditorStateApi>()) { BackendEditorStateApi() }
        remoteApi(remoteApiDescriptor<TourApi>()) { BackendTourApi() }
        remoteApi(remoteApiDescriptor<ArrivalApi>()) { BackendArrivalApi() }
        remoteApi(remoteApiDescriptor<TerminalApi>()) { BackendTerminalApi() }
    }
}
