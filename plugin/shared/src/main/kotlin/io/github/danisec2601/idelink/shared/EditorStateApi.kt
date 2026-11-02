@file:Suppress("UnstableApiUsage")

package io.github.danisec2601.idelink.shared

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.serialization.Serializable

/**
 * What the thin client reports about its editors: paths, caret line, selection bounds, never contents or selection
 * text. The backend re-checks every path against the project root and reads selections from its own document.
 */
@Serializable
data class EditorStateDto(
    /** The selected tab's file path, or null if it has no local file. */
    val path: String?,
    /** The selected tab's VFS URL, for files without a local path. */
    val url: String?,
    /** The caret's 1-based line in the selected tab. */
    val line: Int?,
    /** Every open editor tab's file path. */
    val openFiles: List<String> = emptyList(),
    /** The focused text editor's selection, if any. */
    val selection: SelectionDto? = null,
)

/**
 * The focused text editor's selection, with that editor's own file (in a split layout not always the selected
 * tab). 1-based lines; offsets into the client's document, cross-checked by the backend.
 */
@Serializable
data class SelectionDto(
    /** The focused editor's own file. */
    val path: String,
    /** First selected line. */
    val startLine: Int,
    /** Last selected line. */
    val endLine: Int,
    /** Start offset into the client's document. */
    val startOffset: Int,
    /** End offset into the client's document. */
    val endOffset: Int,
    /** Over 1 for a multi-caret or column selection, whose text the backend doesn't return. */
    val carets: Int = 1,
)

/** Frontend -> backend only. The frontend calls it; it never listens for anything. */
@Rpc
interface EditorStateApi : RemoteApi<Unit> {
    /** Resolves the backend's implementation. */
    companion object {
        /** The [EditorStateApi] proxy for the current client. */
        suspend fun getInstance(): EditorStateApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<EditorStateApi>())
    }

    /** Replaces the backend's view of [projectId]'s editors with [state]. */
    suspend fun report(projectId: ProjectId, state: EditorStateDto)
}
