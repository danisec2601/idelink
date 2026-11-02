@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink.shared

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.serialization.Serializable

/** What the user is asked about before it appears: a review tour, or a file or diff opening. */
@Serializable
enum class ArrivalKind {
    /** A review tour (Start tour). */
    TOUR,

    /** open_file or show_diff (Show). */
    OPEN,
}

/** How the client handled an arrival. */
@Serializable
enum class ArrivalOutcome {
    /** Applied without asking: the user chose "Always show" or "Always start" for this kind earlier. */
    DIRECT,

    /** Applied without asking: the backend says the user allowed it in the out-of-project prompt. */
    ALLOWED,

    /** A notification asks the user. */
    ASKED,

    /** The user chose Start tour, Show, Always start or Always show. */
    ACCEPTED,

    /** The user chose Not now, closed the notification, or a newer arrival replaced it. */
    DECLINED,
}

/** Frontend -> backend: the client's side of the arrival gate. */
@Rpc
interface ArrivalApi : RemoteApi<Unit> {
    /** Resolves the backend's implementation. */
    companion object {
        /** The [ArrivalApi] proxy for the current client. */
        suspend fun getInstance(): ArrivalApi = RemoteApiProviderService.resolve(remoteApiDescriptor<ArrivalApi>())
    }

    /** Tells the backend how arrival [id] of [kind] was handled. */
    suspend fun report(projectId: ProjectId, kind: ArrivalKind, id: Long, outcome: ArrivalOutcome)

    /**
     * Opens the file of open_file offer [offerId] in the calling client. Null when sent; otherwise why not (fixed
     * backend strings). Carries no path, like [TourApi.openStop].
     */
    suspend fun openOffer(projectId: ProjectId, offerId: Long): String?
}
