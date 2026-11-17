package com.bettercontent.betterplayertraces.api

import com.bettercontent.betterplayertraces.domain.AnnotationViewResult
import com.bettercontent.betterplayertraces.domain.EchoMutation
import com.bettercontent.betterplayertraces.storage.NoteMutationResult
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import java.util.UUID

/** A note command only reports durable success through its later server-thread completion. */
interface AnnotationApi {
    fun annotationsWithin(level: Level, boundsMin: BlockPos, boundsMax: BlockPos, viewer: Player?): AnnotationViewResult

    fun submitCreate(level: ServerLevel, viewer: Player, requestId: UUID, target: BlockPos,
                     text: String, icon: String, color: Int, echo: ByteArray?,
                     completion: (NoteMutationResult) -> Unit): NoteMutationResult

    fun submitUpdate(level: ServerLevel, viewer: Player, requestId: UUID, id: UUID, expectedRevision: Int,
                     text: String, icon: String, color: Int, mutation: EchoMutation, echo: ByteArray?,
                     completion: (NoteMutationResult) -> Unit): NoteMutationResult

    fun submitDelete(level: ServerLevel, viewer: Player, requestId: UUID, id: UUID, expectedRevision: Int,
                     completion: (NoteMutationResult) -> Unit): NoteMutationResult
}
