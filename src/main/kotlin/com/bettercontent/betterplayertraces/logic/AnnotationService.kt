package com.bettercontent.betterplayertraces.logic

import com.bettercontent.betterplayertraces.diagnostics.RuntimeDiagnostics
import com.bettercontent.betterplayertraces.api.AnnotationApi
import com.bettercontent.betterplayertraces.api.SeenStateService
import com.bettercontent.betterplayertraces.domain.*
import com.bettercontent.betterplayertraces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import com.bettercontent.betterplayertraces.storage.AnnotationUpdateIndex
import com.bettercontent.betterplayertraces.storage.NoteMutationResult
import com.bettercontent.betterplayertraces.storage.NoteRejectReason
import com.bettercontent.betterplayertraces.storage.TraceReadUnavailableException
import net.minecraft.server.level.ServerLevel

class AnnotationService(private val storage: TraceStorageManager) : AnnotationApi, SeenStateService {
    private val pendingEchoSlots = mutableMapOf<UUID, Int>()
    private fun observe(level: Level, event: String, result: String, reason: String, id: UUID, revision: Int) {
        if (!RuntimeDiagnostics.LOG.enabled("annotation")) return
        com.bettercontent.betterplayertraces.diagnostics.FeatureObservation.annotation(
            RuntimeDiagnostics.LOG, { RuntimeDiagnostics.context(level) }, event, result, reason, id, revision)
    }
    override fun annotationsWithin(level: Level, boundsMin: BlockPos, boundsMax: BlockPos, viewer: Player?): AnnotationViewResult {
        val annotations = storage.queryAnnotations(boundsMin, boundsMax)
        val viewerUuid = viewer?.getUUID() ?: return AnnotationViewResult(annotations, 0, null)
        val unseen = annotations.count { annotation -> annotation.revision > storage.getSeen(viewerUuid, annotation.id) }
        return AnnotationViewResult(annotations, unseen, viewerUuid)
    }

    /** Server-thread admission only; success is reported exclusively by the publication callback. */
    override fun submitCreate(
        level: ServerLevel, viewer: Player, requestId: UUID, target: BlockPos,
        text: String, icon: String, color: Int, echo: ByteArray?, completion: (NoteMutationResult) -> Unit,
    ): NoteMutationResult {
        val valid = try { validateInput(text, icon, color, echo) } catch (_: IllegalArgumentException) {
            return NoteMutationResult.Rejected(NoteRejectReason.INVALID_INPUT)
        }
        if (!targetInReach(viewer, target)) return NoteMutationResult.Rejected(NoteRejectReason.OUT_OF_REACH)
        if (valid != null) echoCapacity(viewer.uuid)?.let { return NoteMutationResult.Rejected(it) }
        val id = UUID.randomUUID()
        val record = TraceAnnotation(id, text, icon, color, target, target, GLOBAL_TEAM, 1, viewer.uuid, valid)
        return stage(level, requestId, id, null, record, completion, if (valid != null) viewer.uuid else null)
    }

    override fun submitUpdate(
        level: ServerLevel, viewer: Player, requestId: UUID, id: UUID, expectedRevision: Int,
        text: String, icon: String, color: Int, mutation: EchoMutation, echo: ByteArray?,
        completion: (NoteMutationResult) -> Unit,
    ): NoteMutationResult {
        val current = try { storage.annotationById(id) } catch (_: TraceReadUnavailableException) {
            return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_UNAVAILABLE)
        } ?: return NoteMutationResult.Rejected(NoteRejectReason.NOT_FOUND)
        if (current.createdByInternal != viewer.uuid && !canModerate(viewer)) return NoteMutationResult.Rejected(NoteRejectReason.PERMISSION)
        if (!targetInReach(viewer, current.targetBlock)) return NoteMutationResult.Rejected(NoteRejectReason.OUT_OF_REACH)
        if (current.revision != expectedRevision) return NoteMutationResult.Rejected(NoteRejectReason.REVISION_CONFLICT)
        if (current.revision == Int.MAX_VALUE) return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_CAPACITY)
        if ((mutation == EchoMutation.REPLACE) != (echo != null)) return NoteMutationResult.Rejected(NoteRejectReason.INVALID_INPUT)
        val nextEcho = when (mutation) {
            EchoMutation.KEEP -> current.encodedEcho
            EchoMutation.REPLACE -> echo
            EchoMutation.REMOVE -> null
        }
        val valid = try { validateInput(text, icon, color, nextEcho) } catch (_: IllegalArgumentException) {
            return NoteMutationResult.Rejected(NoteRejectReason.INVALID_INPUT)
        }
        if (valid != null && !current.hasEcho)
            echoCapacity(current.createdByInternal)?.let { return NoteMutationResult.Rejected(it) }
        return stage(level, requestId, id, expectedRevision,
            current.copy(text = text, icon = icon, color = color, revision = current.revision + 1, encodedEcho = valid), completion,
            if (valid != null && !current.hasEcho) current.createdByInternal else null)
    }

    override fun submitDelete(
        level: ServerLevel, viewer: Player, requestId: UUID, id: UUID, expectedRevision: Int,
        completion: (NoteMutationResult) -> Unit,
    ): NoteMutationResult {
        val current = try { storage.annotationById(id) } catch (_: TraceReadUnavailableException) {
            return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_UNAVAILABLE)
        } ?: return NoteMutationResult.Rejected(NoteRejectReason.NOT_FOUND)
        if (current.createdByInternal != viewer.uuid && !canModerate(viewer)) return NoteMutationResult.Rejected(NoteRejectReason.PERMISSION)
        if (!targetInReach(viewer, current.targetBlock)) return NoteMutationResult.Rejected(NoteRejectReason.OUT_OF_REACH)
        if (current.revision != expectedRevision) return NoteMutationResult.Rejected(NoteRejectReason.REVISION_CONFLICT)
        return stage(level, requestId, id, expectedRevision, null, completion)
    }

    private fun stage(
        level: ServerLevel, requestId: UUID, id: UUID, expectedRevision: Int?, record: TraceAnnotation?,
        completion: (NoteMutationResult) -> Unit, reservedEchoOwner: UUID? = null,
    ): NoteMutationResult {
        reservedEchoOwner?.let { pendingEchoSlots[it] = (pendingEchoSlots[it] ?: 0) + 1 }
        fun releaseEchoSlot() {
            reservedEchoOwner?.let { owner ->
                val remaining = (pendingEchoSlots[owner] ?: 1) - 1
                if (remaining <= 0) pendingEchoSlots.remove(owner) else pendingEchoSlots[owner] = remaining
            }
        }
        val result = try { storage.stageNote(requestId, id, expectedRevision, record) { outcome ->
            if (outcome is NoteMutationResult.Committed || outcome is NoteMutationResult.Rejected ||
                outcome is NoteMutationResult.Indeterminate && outcome.terminal) releaseEchoSlot()
            if (outcome is NoteMutationResult.Committed) {
                // Derived metadata is updated AFTER publication, not part of note authority.
                runCatching {
                    if (record == null) AnnotationUpdateIndex.get(level).remove(id)
                    else AnnotationUpdateIndex.get(level).touch(id, level.gameTime)
                }.onFailure { com.mojang.logging.LogUtils.getLogger().warn("Derived note return metadata unavailable for {}", id, it) }
                runCatching { observe(level, "annotation.publish", "committed", "durable", id, record?.revision ?: expectedRevision ?: 0) }
            }
            completion(outcome)
        } } catch (error: Exception) { releaseEchoSlot(); throw error }
        if (result is NoteMutationResult.Rejected) releaseEchoSlot()
        if (result is NoteMutationResult.Rejected)
            runCatching { observe(level, "annotation.stage", "rejected", result.reason.name.lowercase(), id, expectedRevision ?: 0) }
        return result
    }

    private fun echoCapacity(owner: UUID): NoteRejectReason? {
        val count = storage.echoOwnerCount(owner) ?: return NoteRejectReason.STORAGE_UNAVAILABLE
        // Updates call this only when the existing note has no echo, so exclusion is already zero.
        return if (count + (pendingEchoSlots[owner] ?: 0) >= com.bettercontent.betterplayertraces.domain.AnnotationEchoRecord.MAX_PER_PLAYER)
            NoteRejectReason.STORAGE_CAPACITY else null
    }

    private fun validateInput(text: String, icon: String, color: Int, echo: ByteArray?): ByteArray? {
        // A null result is also a valid no-echo note; exceptions are handled by each caller.
        AnnotationComponents.validate(text, icon, color, echo != null)
        echo?.let { com.bettercontent.betterplayertraces.logic.AnnotationEchoValidation.decode(it) }
        return echo?.copyOf()
    }

    private fun targetInReach(viewer: Player, target: BlockPos): Boolean =
        viewer.distanceToSqr(target.x + 0.5, target.y + 0.5, target.z + 0.5) <= 64.0

    override fun seenRevision(player: UUID, annotationId: UUID): Int = storage.getSeen(player, annotationId)

    override fun setSeen(player: UUID, annotationId: UUID, revision: Int) {
        storage.setSeen(player, annotationId, revision)
    }

    fun acknowledgeViewed(viewer: Player, annotationId: UUID, revision: Int): Boolean {
        val annotation = storage.annotationById(annotationId) ?: return false
        if (annotation.revision != revision) return false
        if (viewer.distanceToSqr(
                annotation.position.x + 0.5,
                annotation.position.y + 0.5,
                annotation.position.z + 0.5,
            ) > 100.0
        ) return false
        storage.setSeen(viewer.uuid, annotationId, revision)
        return true
    }

    private fun canModerate(viewer: Player): Boolean = (viewer as? ServerPlayer)?.hasPermissions(2) == true

}
