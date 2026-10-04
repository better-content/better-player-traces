package com.bettercontent.betterplayertraces

import com.mojang.authlib.GameProfile
import com.bettercontent.betterplayertraces.config.TracesConfig
import com.bettercontent.betterplayertraces.domain.FootTrace
import com.bettercontent.betterplayertraces.domain.MovementClass
import com.bettercontent.betterplayertraces.domain.AnnotationComponents
import com.bettercontent.betterplayertraces.domain.AnnotationEchoRecord
import com.bettercontent.betterplayertraces.domain.TraceSupport
import com.bettercontent.betterplayertraces.echo.EchoClip
import com.bettercontent.betterplayertraces.echo.EchoClipCodec
import com.bettercontent.betterplayertraces.echo.EchoEncoding
import com.bettercontent.betterplayertraces.echo.EchoFrame
import com.bettercontent.betterplayertraces.echo.EchoRoot
import com.bettercontent.betterplayertraces.logic.AnnotationService
import com.bettercontent.betterplayertraces.logic.ErosionService
import com.bettercontent.betterplayertraces.api.event.TraceEpisodeEvent
import com.bettercontent.betterplayertraces.storage.AnnotationEchoSavedData
import com.bettercontent.betterplayertraces.storage.TraceStorageManager
import com.bettercontent.betterplayertraces.trace.TraceEpisodes
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID

@GameTestHolder("better_player_traces")
object TraceGametestProbe {

    private class TraceEventRecorder {
        val events = mutableListOf<TraceEpisodeEvent>()

        @SubscribeEvent
        fun onTraceEpisode(event: TraceEpisodeEvent) {
            events += event
        }
    }

    private fun validEchoClip(): ByteArray = EchoClipCodec.encodeQuantized(
        EchoClip(
            EchoEncoding.BONE,
            EchoClip.SAMPLE_RATE,
            intArrayOf(),
            listOf(
                EchoFrame(EchoRoot(0f, 0f, 0f, 0f, 0f), FloatArray(EchoClip.BONE_CHANNEL_COUNT)),
                EchoFrame(EchoRoot(0.25f, 0f, 0f, 0.1f, 0.1f), FloatArray(EchoClip.BONE_CHANNEL_COUNT) { 0.05f }),
            ),
        ),
    )

    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 200)
    @JvmStatic
    fun persistenceSurvivesRestart(helper: GameTestHelper) {
        val level = helper.level
        val config = TracesConfig.common
        val traceId = UUID.randomUUID()
        val source = UUID.randomUUID()
        val trace = FootTrace(
            id = traceId,
            levelKey = level.dimension().location().toString(),
            blockPos = BlockPos(300, 64, 300),
            movementClass = MovementClass.WALK,
            strength = 1.0f,
            sequenceId = UUID.randomUUID(),
            sequenceIndex = 0,
            createdAt = 1,
            sequenceEpoch = 1,
            surviving = true,
            sourcePlayerInternal = source,
            support = TraceSupport(BlockPos(300, 63, 300), ResourceLocation("minecraft", "stone")),
        )

        val writer = TraceStorageManager(level, config)
        val boundsMin = trace.blockPos.offset(-1, 0, -1)
        val boundsMax = trace.blockPos.offset(1, 0, 1)

        try {
            writer.addFootTrace(trace)
            writer.tickFlush()
            writer.close()

            val reader = TraceStorageManager(level, config)
            try {
                val loaded = reader.queryTraces(boundsMin, boundsMax)
                helper.assertTrue(loaded.isNotEmpty(), "loaded trace batch size=${loaded.size}, ids=${loaded.map { it.id }}")
                helper.assertTrue(loaded.any { it.id == traceId }, "trace should persist across storage manager reload")
                helper.succeed()
            } finally {
                reader.close()
            }
        } finally {
            // no-op
        }
    }

    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 200)
    @JvmStatic
    fun waterErasesTraces(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val trace = FootTrace(
            id = UUID.randomUUID(),
            levelKey = level.dimension().location().toString(),
            blockPos = BlockPos(600, 64, 600),
            movementClass = MovementClass.WALK,
            strength = 1.0f,
            sequenceId = UUID.randomUUID(),
            sequenceIndex = 1,
            createdAt = 1,
            sequenceEpoch = 1,
            surviving = true,
            sourcePlayerInternal = UUID.randomUUID(),
            support = TraceSupport(BlockPos(600, 63, 600), ResourceLocation("minecraft", "stone")),
        )

        try {
            storage.addFootTrace(trace)
            storage.tickFlush()
            helper.assertTrue(storage.queryTraces(trace.blockPos, trace.blockPos).isNotEmpty(), "precondition: trace written")

            ErosionService(storage, TracesConfig.common).onFluidTick(trace.blockPos)
            helper.assertTrue(storage.queryTraces(trace.blockPos, trace.blockPos).isEmpty(), "water should remove nearby traces")
            helper.succeed()
        } finally {
            storage.close()
        }
    }

    private fun rainFixturePosition(helper: GameTestHelper, local: BlockPos): BlockPos {
        val level = helper.level
        val column = helper.absolutePos(BlockPos(local.x, 0, local.z))
        val position = BlockPos(column.x,
            level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, column.x, column.z) + 1,
            column.z)
        level.server.commands.performPrefixedCommand(level.server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
            "fillbiome ${position.x} ${position.y} ${position.z} ${position.x} ${position.y} ${position.z} minecraft:plains")
        return position
    }

    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 260)
    @JvmStatic
    fun rainExposureErodesOnlyUncoveredTrace(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val erosion = ErosionService(storage, TracesConfig.common)
        val exposedPosition = rainFixturePosition(helper, BlockPos(5, 0, 5))
        val shelteredPosition = rainFixturePosition(helper, BlockPos(8, 0, 8))
        level.setBlockAndUpdate(exposedPosition.below(), Blocks.STONE.defaultBlockState())
        level.setBlockAndUpdate(shelteredPosition.below(), Blocks.STONE.defaultBlockState())
        // A second exposed point shares the sheltered trace's chunk. This catches the old
        // chunk-centre sampling regression while the sheltered point verifies canopy handling.
        val comparisonPosition = shelteredPosition.offset(5, 0, 5).atY(
            level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                shelteredPosition.x + 5, shelteredPosition.z + 5) + 1)
        level.server.commands.performPrefixedCommand(level.server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
            "fillbiome ${comparisonPosition.x} ${comparisonPosition.y} ${comparisonPosition.z} ${comparisonPosition.x} ${comparisonPosition.y} ${comparisonPosition.z} minecraft:plains")
        level.setBlockAndUpdate(comparisonPosition.below(), Blocks.STONE.defaultBlockState())
        val coverPos = shelteredPosition.above(30)
        level.setBlockAndUpdate(coverPos, Blocks.STONE.defaultBlockState())
        fun traceAt(position: BlockPos) = FootTrace(
            id = UUID.randomUUID(),
            levelKey = level.dimension().location().toString(),
            blockPos = position,
            movementClass = MovementClass.WALK,
            strength = 1.0f,
            sequenceId = UUID.randomUUID(),
            sequenceIndex = 0,
            createdAt = 1,
            sequenceEpoch = 1,
            surviving = true,
            sourcePlayerInternal = UUID.randomUUID(),
            support = TraceSupport(position.below(), ResourceLocation("minecraft", "stone")),
        )
        val exposed = traceAt(exposedPosition)
        val sheltered = traceAt(shelteredPosition)
        val comparison = traceAt(comparisonPosition)
        try {
            level.setWeatherParameters(0, 240, true, false)
            level.rainLevel = 1f
            level.oRainLevel = 1f
            storage.addFootTrace(exposed)
            storage.addFootTrace(sheltered)
            storage.addFootTrace(comparison)
            storage.tickFlush()
            // Wait for skylight propagation, then validate and erode within the same rain episode.
            helper.runAfterDelay(10L) {
                try {
                    level.setWeatherParameters(0, 240, true, false)
                    level.rainLevel = 1f
                    level.oRainLevel = 1f
                    helper.assertTrue(level.hasChunkAt(exposedPosition) && level.isRainingAt(exposedPosition),
                        "exposed fixture must remain loaded and receive rain: loaded=${level.hasChunkAt(exposedPosition)} rainAt=${level.isRainingAt(exposedPosition)} rain=${level.getRainLevel(1f)} sky=${level.canSeeSky(exposedPosition)} biome=${level.getBiome(exposedPosition).value().hasPrecipitation()}")
                    helper.assertTrue(level.hasChunkAt(shelteredPosition) && !level.canSeeSky(shelteredPosition),
                        "sheltered fixture must remain loaded with its canopy: cover=${level.getBlockState(coverPos)}")
                    helper.assertTrue(level.isRainingAt(comparisonPosition), "same-chunk comparison fixture must receive rain")
                    val before = listOf(exposed, sheltered, comparison).associate { trace ->
                        trace.id to storage.queryTraces(trace.blockPos, trace.blockPos).single { it.id == trace.id }
                    }
                    erosion.tick(level, 80)
                    val after = listOf(exposed, sheltered, comparison).associate { trace ->
                        trace.id to storage.queryTraces(trace.blockPos, trace.blockPos).single { it.id == trace.id }
                    }
                    helper.assertTrue(after.getValue(exposed.id).strength < before.getValue(exposed.id).strength || !after.getValue(exposed.id).surviving,
                        "rain must weaken an exposed trace")
                    helper.assertTrue(after.getValue(comparison.id).strength < before.getValue(comparison.id).strength || !after.getValue(comparison.id).surviving,
                        "rain must weaken the exposed trace sharing the sheltered trace's chunk")
                    helper.assertTrue(after.getValue(sheltered.id).strength >= before.getValue(sheltered.id).strength * 0.99f,
                        "rain must preserve the sheltered trace")
                    helper.succeed()
                } finally {
                    level.setBlockAndUpdate(coverPos, Blocks.AIR.defaultBlockState())
                    level.setWeatherParameters(0, 0, false, false)
                    storage.close()
                }
            }
        } catch (failure: Throwable) {
            storage.close()
            throw failure
        }
    }

    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun annotationPersistsAfterTargetBlockReplaced(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val player = helper.makeMockSurvivalPlayer()
        val target = player.blockPosition().offset(2, 0, 0)

        try {
            val annotation = service.create(level, player, "probe", "pin", 0x55D66B, target)
            level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState())

            val boundsMin = target.offset(-1, 0, -1)
            val boundsMax = target.offset(1, 0, 1)
            val visible = service.annotationsWithin(level, boundsMin, boundsMax, player)

            helper.assertTrue(visible.annotations.any { it.id == annotation.id }, "annotation should persist when block is replaced")
            helper.succeed()
        } finally {
            storage.close()
        }
    }

    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun globalTeamVisibleToSecondPlayer(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val author = helper.makeMockSurvivalPlayer()
        val viewer = helper.makeMockSurvivalPlayer()
        val position = author.blockPosition().offset(2, 0, 0)

        try {
            val created = service.create(level, author, "team check", "pin", 0x579DFF, position)

            val visible = service.annotationsWithin(
                level,
                position.offset(-4, 0, -4),
                position.offset(4, 64, 4),
                viewer,
            )

            helper.assertTrue(
                visible.annotations.any { it.id == created.id },
                "global-team annotations should be visible to all players"
            )
            helper.succeed()
        } finally {
            storage.close()
        }
    }


    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun annotationOwnershipAndRevisionAreEnforced(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val author = helper.makeMockSurvivalPlayer()
        val viewer = helper.makeMockSurvivalPlayer()
        val target = author.blockPosition().offset(1, 0, 0)
        try {
            val created = service.create(level, author, "first", "pin", 0x579DFF, target)
            helper.assertTrue(runCatching { service.create(level, author, "duplicate", "pin", 0x579DFF, target) }.isFailure, "one creator may only have one note per block")
            helper.assertTrue(runCatching { service.update(level, viewer, created.id, 1, "stolen", null, null) }.isFailure, "other players must not update notes")
            helper.assertTrue(!service.delete(level, viewer, created.id, 1), "other players must not delete notes")
            val updated = service.update(level, author, created.id, 1, "second", null, null)
            helper.assertTrue(updated.revision == 2, "owner update should increment revision")
            helper.assertTrue(runCatching { service.update(level, author, created.id, 1, "stale", null, null) }.isFailure, "stale revisions must be rejected")
            helper.assertTrue(!service.acknowledgeViewed(viewer, created.id, 1), "stale viewed revisions must be rejected")
            helper.assertTrue(service.acknowledgeViewed(viewer, created.id, 2), "nearby current revision should be acknowledged")
            helper.assertTrue(service.seenRevision(viewer.uuid, created.id) == 2, "viewed revision should persist")
            helper.assertTrue(service.delete(level, author, created.id, 2), "owner should delete current revision")
            helper.succeed()
        } finally {
            storage.close()
        }
    }

    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun annotationEchoPersistsRevisesAndDeletesAtomically(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val owner = helper.makeMockSurvivalPlayer()
        val target = owner.blockPosition().offset(2, 0, 0)
        val echoes = AnnotationEchoSavedData()

        try {
            val created = service.createComponents(level, owner, "", "", 0, target, hasEcho = true)
            echoes.replace(AnnotationEchoRecord(created.id, created.revision, owner.uuid, validEchoClip()))
            val restored = AnnotationEchoSavedData.load(echoes.save(CompoundTag()))
            helper.assertTrue(restored.get(created.id)?.annotationRevision == 1, "echo should survive saved-data reload")

            val updated = service.updateComponents(level, owner, created.id, 1, "gesture", "", 0, hasEchoAfterMutation = true)
            val prior = restored.get(created.id)!!
            restored.replace(AnnotationEchoRecord(created.id, updated.revision, prior.ownerId, prior.encodedClip))
            helper.assertTrue(updated.revision == 2 && restored.get(created.id)?.annotationRevision == 2, "note and kept echo should revise together")

            helper.assertTrue(service.delete(level, owner, created.id, 2), "owner should delete the revised note")
            helper.assertTrue(restored.remove(created.id), "deleting a note should delete its echo")
            helper.assertTrue(restored.get(created.id) == null, "deleted echo must not remain queryable")
            helper.succeed()
        } finally {
            storage.close()
        }
    }

    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun annotationEchoCapacityRejectsWithoutEviction(helper: GameTestHelper) {
        val echoes = AnnotationEchoSavedData()
        val owner = UUID.randomUUID()
        val clip = validEchoClip()
        repeat(AnnotationEchoRecord.MAX_PER_PLAYER) {
            echoes.replace(AnnotationEchoRecord(UUID.randomUUID(), 1, owner, clip))
        }
        val existingIds = echoes.all().map { it.annotationId }.toSet()
        val rejected = runCatching { echoes.requireCapacity(UUID.randomUUID(), owner) }.isFailure
        helper.assertTrue(rejected, "the 65th player-owned echo should be rejected")
        helper.assertTrue(echoes.count() == AnnotationEchoRecord.MAX_PER_PLAYER, "capacity rejection must not change the echo count")
        helper.assertTrue(echoes.all().map { it.annotationId }.toSet() == existingIds, "capacity rejection must not evict an existing gesture")
        helper.succeed()
    }

    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun deniedEchoEditLeavesNoteAndClipUnchanged(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val owner = helper.makeMockSurvivalPlayer()
        val stranger = helper.makeMockSurvivalPlayer()
        val target = owner.blockPosition().offset(2, 0, 0)
        val echoes = AnnotationEchoSavedData()

        try {
            val created = service.createComponents(level, owner, "", "pin", AnnotationComponents.colors.getValue("cyan"), target, hasEcho = true)
            val encoded = validEchoClip()
            echoes.replace(AnnotationEchoRecord(created.id, 1, owner.uuid, encoded))
            val denied = runCatching {
                service.updateComponents(level, stranger, created.id, 1, "stolen", "", 0, hasEchoAfterMutation = false)
            }.isFailure
            helper.assertTrue(denied, "another player must not replace or remove an echo")
            helper.assertTrue(storage.annotationById(created.id)?.revision == 1, "denied edit must not increment the note revision")
            helper.assertTrue(echoes.get(created.id)?.encodedClip?.contentEquals(encoded) == true, "denied edit must preserve the original clip")
            helper.succeed()
        } finally {
            storage.close()
        }
    }

    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun operatorCanReplaceAnnotationEcho(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val owner = helper.makeMockSurvivalPlayer()
        val target = owner.blockPosition().offset(2, 0, 0)
        val operator = object : ServerPlayer(level.server, level, GameProfile(UUID.randomUUID(), "TracesGameTestOperator")) {
            override fun hasPermissions(permissionLevel: Int): Boolean = true
        }.also {
            it.setPos(owner.x, owner.y, owner.z)
        }
        val echoes = AnnotationEchoSavedData()

        try {
            val created = service.createComponents(level, owner, "original", "", 0, target, hasEcho = true)
            echoes.replace(AnnotationEchoRecord(created.id, 1, owner.uuid, validEchoClip()))
            val updated = service.updateComponents(level, operator, created.id, 1, "moderated", "memorial", AnnotationComponents.colors.getValue("white"), hasEchoAfterMutation = true)
            echoes.replace(AnnotationEchoRecord(created.id, updated.revision, owner.uuid, validEchoClip()))
            helper.assertTrue(updated.revision == 2, "operator replacement should increment the note revision")
            helper.assertTrue(echoes.get(created.id)?.annotationRevision == 2, "operator replacement should invalidate the old echo revision")
            helper.succeed()
        } finally {
            storage.close()
        }
    }

    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun traceJourneyEventsAreCorrelatedAndPublishedOnce(helper: GameTestHelper) {
        val level = helper.level
        val player = ServerPlayer(
            level.server,
            level,
            GameProfile(UUID.randomUUID(), "trace-episode"),
        )
        val origin = player.blockPosition()
        val storage = TraceStorageManager(level, TracesConfig.common)
        val recorder = TraceEventRecorder()
        MinecraftForge.EVENT_BUS.register(recorder)

        try {
            TraceEpisodes.forget(player)
            TraceEpisodes.traceCommitted(player)
            TraceEpisodes.traceCommitted(player)
            helper.assertTrue(recorder.events.size == 1, "commit must publish once per journey")

            player.tickCount = 20
            player.setPos(origin.x + 20.0, origin.y.toDouble(), origin.z.toDouble())
            TraceEpisodes.checkReturn(player, storage)

            storage.addFootTrace(FootTrace(
                id = UUID.randomUUID(),
                levelKey = level.dimension().location().toString(),
                blockPos = origin,
                movementClass = MovementClass.WALK,
                strength = 1.0f,
                sequenceId = UUID.randomUUID(),
                sequenceIndex = 0,
                createdAt = level.gameTime - 6000,
                sequenceEpoch = 1,
                surviving = true,
                sourcePlayerInternal = player.uuid,
                support = TraceSupport(origin.below(), ResourceLocation("minecraft", "stone")),
            ))
            player.tickCount = 40
            player.setPos(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())
            TraceEpisodes.checkReturn(player, storage)
            TraceEpisodes.checkReturn(player, storage)

            helper.assertTrue(recorder.events.size == 2, "return must publish once and close the journey")
            val committed = recorder.events[0]
            val returned = recorder.events[1]
            helper.assertTrue(committed.kind == TraceEpisodeEvent.Kind.COMMITTED, "first boundary must be COMMITTED")
            helper.assertTrue(returned.kind == TraceEpisodeEvent.Kind.RETURNED, "second boundary must be RETURNED")
            helper.assertTrue(committed.episodeId == returned.episodeId, "journey boundaries must share an episode ID")
            helper.assertTrue(committed.player === player && returned.player === player, "events must expose the authoritative player")
            helper.assertTrue(committed.originDimension == level.dimension() && committed.origin == origin,
                "event must retain the journey origin")
            helper.succeed()
        } finally {
            MinecraftForge.EVENT_BUS.unregister(recorder)
            TraceEpisodes.forget(player)
            storage.close()
        }
    }
}
