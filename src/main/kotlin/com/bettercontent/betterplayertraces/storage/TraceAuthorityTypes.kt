package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.domain.TraceSupport
import com.bettercontent.betterplayertraces.util.TraceShardId
import net.minecraft.core.BlockPos
import java.util.UUID

internal data class SeenStateRecord(val annotationId: UUID, val playerId: UUID, val highestRevision: Int)

internal fun shouldPruneInvalidSupport(
    support: TraceSupport,
    isChunkLoaded: (BlockPos) -> Boolean,
    isValid: (TraceSupport) -> Boolean,
): Boolean = isChunkLoaded(support.position) && !isValid(support)

internal data class StorageOutageFrontier(
    val rejectedCaptures: Long,
    val rejectedAnnotations: Long,
    val affectedShards: Set<TraceShardId>,
    val deferredByKind: Map<Kind, Long> = emptyMap(),
) {
    enum class Kind { CAPTURE, ANNOTATION, EVENT_REMOVAL, EROSION, SEEN_STATE, READ }
}

internal object StorageOutageStatus {
    fun format(frontier: StorageOutageFrontier): String =
        "storage outage: rejected captures=${frontier.rejectedCaptures} annotations=${frontier.rejectedAnnotations} " +
            "affected regions=${frontier.affectedShards.size}; unsaved footprints may be lost on crash; " +
            "unavailable archive data must not be treated as empty"
}
