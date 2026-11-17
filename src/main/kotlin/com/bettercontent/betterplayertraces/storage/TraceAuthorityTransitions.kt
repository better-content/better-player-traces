package com.bettercontent.betterplayertraces.storage

import java.util.UUID

/** Pure transitions used by the server-thread manager; no worker may apply live authority. */
internal enum class NoteRecoveryDecision { COMMITTED, RETRY_EXACT, CONFLICT, INDETERMINATE }

internal fun classifyNoteRecovery(
    observed: ShardRead<TraceShardState>, noteId: UUID, requestId: UUID,
    proposedRevision: Long, previouslyPublished: Boolean,
): NoteRecoveryDecision = when (observed) {
    is ShardRead.Present -> when {
        observed.value.noteReceipts[noteId] == requestId -> NoteRecoveryDecision.COMMITTED
        observed.value.archiveRevision() >= proposedRevision -> NoteRecoveryDecision.CONFLICT
        else -> NoteRecoveryDecision.RETRY_EXACT
    }
    ShardRead.Absent -> if (previouslyPublished) NoteRecoveryDecision.INDETERMINATE else NoteRecoveryDecision.RETRY_EXACT
    else -> NoteRecoveryDecision.INDETERMINATE
}

internal fun snapshotCompletionClearsDirty(capturedGeneration: Long, currentGeneration: Long, published: Boolean): Boolean =
    published && capturedGeneration == currentGeneration

internal fun pauseAfterRejectedCapture(wasPaused: Boolean, loadedAuthority: Boolean): Boolean =
    wasPaused || loadedAuthority

internal fun mayStartNoteRecovery(hasRead: Boolean, currentWindow: Long, nextRecoveryWindow: Long): Boolean =
    !hasRead && currentWindow >= nextRecoveryWindow

internal fun shouldSubmitLatestSnapshot(dirty: Boolean, inflight: Boolean, stagedNote: Boolean, conflict: Boolean): Boolean =
    dirty && !inflight && !stagedNote && !conflict

internal fun canStageShard(pendingShards: Collection<com.bettercontent.betterplayertraces.util.TraceShardId>,
                           shard: com.bettercontent.betterplayertraces.util.TraceShardId, inflightWrite: Boolean): Boolean =
    shard !in pendingShards && !inflightWrite

internal fun canReopenAdmission(conflicts: Int, unavailable: Int, failedWrites: Int, pendingNotes: Int,
                                pendingWrites: Int, dirty: Int, capacityAvailable: Boolean): Boolean =
    conflicts == 0 && unavailable == 0 && failedWrites == 0 && pendingNotes == 0 && pendingWrites == 0 &&
        dirty == 0 && capacityAvailable
