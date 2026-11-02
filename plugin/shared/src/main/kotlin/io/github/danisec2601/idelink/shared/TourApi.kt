@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink.shared

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor

/**
 * Frontend -> backend: "open stop [index] of tour [tourId]". Carries no path: the backend opens only a file it
 * resolved and root-checked when it built that tour, and re-checks it first.
 */
@Rpc
interface TourApi : RemoteApi<Unit> {
    /** Resolves the backend's implementation. */
    companion object {
        /** The [TourApi] proxy for the current client. */
        suspend fun getInstance(): TourApi = RemoteApiProviderService.resolve(remoteApiDescriptor<TourApi>())
    }

    /** Null when the open was sent to the client; otherwise why it wasn't (fixed backend strings). */
    suspend fun openStop(projectId: ProjectId, tourId: Long, index: Int): String?
}
