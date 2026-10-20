package com.bettercontent.betterplayertraces.logic

import com.bettercontent.betterplayertraces.diagnostics.RuntimeDiagnostics
import com.bettercontent.betterplayertraces.diagnostics.PlaytestLog
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
import net.minecraft.server.level.ServerLevel

class AnnotationService(private val storage: TraceStorageManager) : AnnotationApi, SeenStateService {
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

    override fun create(level: Level, viewer: Player, text: String, icon: String, color: Int, target: BlockPos): TraceAnnotation {
        validateAnnotationFields(text, icon, color, false)
        requireTargetInReach(viewer, target)
        require(storage.queryAnnotations(target, target).none {
            it.targetBlock == target && it.createdByInternal == viewer.getUUID()
        }) { "you already have an annotation on this block" }
        val record = TraceAnnotation(
            id = UUID.randomUUID(),
            text = text,
            icon = icon,
            color = color,
            position = target,
            targetBlock = target,
            team = GLOBAL_TEAM,
            revision = 1,
            createdByInternal = viewer.getUUID()
        )
        val admitted = storage.addAnnotation(record)
        observe(level, "annotation.create", if (admitted) "admitted" else "rejected", if (admitted) "storage_accepted" else "capacity", record.id, record.revision)
        check(admitted) { "Trace storage is temporarily full; annotation was not saved" }
        (level as? ServerLevel)?.let { AnnotationUpdateIndex.get(it).touch(record.id, it.gameTime) }
        return record
    }

    override fun update(level: Level, viewer: Player, id: UUID, expectedRevision: Int, text: String?, icon: String?, color: Int?): TraceAnnotation {
        val current = storage.annotationById(id) ?: throw IllegalArgumentException("annotation not found")
        if (current.createdByInternal != viewer.getUUID() && !canModerate(viewer)) {
            observe(level, "annotation.update", "rejected", "permission", id, current.revision)
            throw IllegalStateException("not permitted")
        }
        requireTargetInReach(viewer, current.targetBlock)
        require(current.revision == expectedRevision) { "annotation changed; reopen it before saving" }
        validateAnnotationFields(text ?: current.text, icon ?: current.icon, color ?: current.color, false)
        return (storage.updateAnnotation(id, text, icon, color) ?: throw IllegalStateException("annotation update failed")).also {
            observe(level, "annotation.update", "admitted", "storage_accepted", id, it.revision)
            (level as? ServerLevel)?.let { serverLevel -> AnnotationUpdateIndex.get(serverLevel).touch(id, serverLevel.gameTime) }
        }
    }

    fun createComponents(level: Level, viewer: Player, text: String, icon: String, color: Int, target: BlockPos, hasEcho: Boolean): TraceAnnotation {
        validateAnnotationFields(text, icon, color, hasEcho)
        requireTargetInReach(viewer, target)
        require(storage.queryAnnotations(target, target).none {
            it.targetBlock == target && it.createdByInternal == viewer.uuid
        }) { "you already have an annotation on this block" }
        return TraceAnnotation(
            UUID.randomUUID(), text, icon, color, target, target, GLOBAL_TEAM, 1, viewer.uuid,
        ).also { annotation ->
            val admitted = storage.addAnnotation(annotation)
            observe(level, "annotation.create", if (admitted) "admitted" else "rejected", if (admitted) "storage_accepted" else "capacity", annotation.id, annotation.revision)
            check(admitted) { "Trace storage is temporarily full; annotation was not saved" }
        }.also { (level as? ServerLevel)?.let { serverLevel -> AnnotationUpdateIndex.get(serverLevel).touch(it.id, serverLevel.gameTime) } }
    }

    fun updateComponents(
        level: Level,
        viewer: Player,
        id: UUID,
        expectedRevision: Int,
        text: String,
        icon: String,
        color: Int,
        hasEchoAfterMutation: Boolean,
    ): TraceAnnotation {
        val current = storage.annotationById(id) ?: throw IllegalArgumentException("annotation not found")
        if (current.createdByInternal != viewer.uuid && !canModerate(viewer)) throw IllegalStateException("not permitted")
        requireTargetInReach(viewer, current.targetBlock)
        require(current.revision == expectedRevision) { "annotation changed; reopen it before saving" }
        validateAnnotationFields(text, icon, color, hasEchoAfterMutation)
        return (storage.updateAnnotation(id, text, icon, color) ?: throw IllegalStateException("annotation update failed")).also {
            observe(level, "annotation.update", "admitted", "storage_accepted", id, it.revision)
            (level as? ServerLevel)?.let { serverLevel -> AnnotationUpdateIndex.get(serverLevel).touch(id, serverLevel.gameTime) }
        }
    }

    override fun delete(level: Level, viewer: Player, id: UUID, expectedRevision: Int): Boolean {
        val current = storage.annotationById(id) ?: run { observe(level, "annotation.delete", "rejected", "missing", id, expectedRevision); return false }
        if (current.createdByInternal != viewer.getUUID() && !canModerate(viewer)) {
            observe(level, "annotation.delete", "rejected", "permission", id, current.revision)
            return false
        }
        requireTargetInReach(viewer, current.targetBlock)
        require(current.revision == expectedRevision) { "annotation changed; reopen it before deleting" }
        storage.setSeen(viewer.getUUID(), id, current.revision)
        return storage.removeAnnotation(id).also { removed ->
            observe(level, "annotation.delete", if (removed) "removed" else "rejected", if (removed) "storage_accepted" else "missing", id, expectedRevision)
            if (removed) (level as? ServerLevel)?.let { AnnotationUpdateIndex.get(it).remove(id) }
        }
    }

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

    private fun requireTargetInReach(viewer: Player, target: BlockPos) {
        require(viewer.distanceToSqr(target.x + 0.5, target.y + 0.5, target.z + 0.5) <= 64.0) {
            "annotation target is out of reach"
        }
    }

    private fun validateAnnotationFields(text: String, icon: String, color: Int, hasEcho: Boolean) =
        AnnotationComponents.validate(text, icon, color, hasEcho)
}
