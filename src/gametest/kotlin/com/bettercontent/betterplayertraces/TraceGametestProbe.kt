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
import com.bettercontent.betterplayertraces.storage.NoteMutationResult
import com.bettercontent.betterplayertraces.storage.NoteRejectReason
import com.bettercontent.betterplayertraces.storage.TraceStorageManager
import com.bettercontent.betterplayertraces.storage.TraceReadUnavailableException
import com.bettercontent.betterplayertraces.trace.TraceEpisodes
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import com.bettercontent.betterplayertraces.domain.EchoMutation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.FlatLevelSource
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

    /** Native note fixtures advance server ticks; never join archive futures on the game thread. */
    private fun awaitNote(
        helper: GameTestHelper, storage: TraceStorageManager,
        submit: ((NoteMutationResult) -> Unit) -> NoteMutationResult,
        committed: (com.bettercontent.betterplayertraces.domain.TraceAnnotation?) -> Unit,
    ) {
        var outcome: NoteMutationResult? = null
        fun poll(remaining: Int) {
            try {
                storage.tickFlush()
                storage.tickSummaries()
                when (val result = outcome) {
                    is NoteMutationResult.Committed -> committed(result.annotation)
                    is NoteMutationResult.Rejected -> helper.assertTrue(false, "note publication rejected: ${result.reason}")
                    else -> {
                        helper.assertTrue(remaining > 0, "note publication did not complete: $result")
                        helper.runAfterDelay(1L) { poll(remaining - 1) }
                    }
                }
            } catch (failure: Throwable) { storage.close(); throw failure }
        }
        fun attempt(remaining: Int) {
            try {
                storage.tickFlush()
                storage.tickSummaries()
                val initial = submit { outcome = it }
                if (initial == NoteMutationResult.Rejected(NoteRejectReason.STORAGE_UNAVAILABLE)
                    || initial == NoteMutationResult.Rejected(NoteRejectReason.STORAGE_CAPACITY)
                    || initial == NoteMutationResult.Rejected(NoteRejectReason.BUSY)) {
                    helper.assertTrue(remaining > 0, "archive did not become readable")
                    helper.runAfterDelay(1L) { attempt(remaining - 1) }
                } else {
                    helper.assertTrue(initial is NoteMutationResult.Pending, "note admission failed: $initial")
                    poll(remaining)
                }
            } catch (failure: Throwable) { storage.close(); throw failure }
        }
        attempt(180)
    }

    private fun awaitReopenedEcho(
        helper: GameTestHelper, writer: TraceStorageManager, id: UUID, revision: Int, bytes: ByteArray, done: () -> Unit,
    ) {
        val reader = TraceStorageManager(helper.level, TracesConfig.common)
        fun check(remaining: Int) {
            try {
                reader.tickSummaries()
                val reopened = reader.annotationEcho(id)
                helper.assertTrue(reopened?.annotationRevision == revision && reopened?.encodedClip?.contentEquals(bytes) == true,
                    "fresh v4 shard reader must recover the durably published note+echo")
                reader.close()
                done()
            } catch (_: TraceReadUnavailableException) {
                if (remaining <= 0) {
                    reader.close(); writer.close()
                    helper.assertTrue(false, "fresh v4 reader did not become available")
                } else helper.runAfterDelay(1L) { check(remaining - 1) }
            } catch (failure: Throwable) { reader.close(); writer.close(); throw failure }
        }
        check(120)
    }

    /** Wait for the actual asynchronous archive discovery/read; never block the server thread. */
    private fun awaitReadable(helper: GameTestHelper, storage: TraceStorageManager, position: BlockPos, done: () -> Unit) {
        fun poll(remaining: Int) {
            val readable = try {
                storage.queryTraces(position, position)
                true
            } catch (_: TraceReadUnavailableException) { false }
            if (readable) done()
            else {
                helper.assertTrue(remaining > 0, "native trace archive did not become readable at $position")
                helper.runAfterDelay(1L) { poll(remaining - 1) }
            }
        }
        poll(180)
    }

    @GameTest(batch = "better_player_traces", template = "trace_arena", timeoutTicks = 200)
    @JvmStatic
    fun footTrafficProbeIsReadOnly(helper: GameTestHelper) {
        val level = helper.level
        val storage = TracesMod.getRuntime(level.server).storage(level)
        val query = com.bettercontent.betterplayertraces.logic.TraceQueryService()
        // An isolated shard keeps local/regional totals exact; server total includes any other fixtures.
        val origin = BlockPos(100_096, 64, 100_096)
        val baseline = try { query.trafficPotential(level, origin) }
        catch (_: TraceReadUnavailableException) {
            helper.runAfterDelay(1L) { footTrafficProbeIsReadOnly(helper) }
            return
        }
        if (!baseline.globalReady) {
            // Archive-wide summaries advance on server ticks; a pending result is never zero.
            helper.runAfterDelay(1L) { footTrafficProbeIsReadOnly(helper) }
            return
        }
        helper.assertTrue(baseline.traceCount == 0 && baseline.regionalSurvivingStrength == 0f, "fixture shard must be empty")
        val positions = listOf(origin, origin.offset(100, 0, 0), origin.offset(512, 0, 0))
        val traces = positions.mapIndexed { i, pos ->
            FootTrace(UUID.randomUUID(), level.dimension().location().toString(), pos, MovementClass.WALK,
                (i + 1).toFloat(), UUID.randomUUID(), 0, 1, 1, true, UUID.randomUUID(),
                support = TraceSupport(pos.below(), ResourceLocation("minecraft", "stone")))
        }
        traces.forEach { helper.assertTrue(storage.addFootTrace(it), "fixture trace accepted") }
        val before = positions.map { storage.traceTileSnapshot(it.x shr 4, it.z shr 4) }
        fun verifyWhenReady(remaining: Int) {
        val potential = try { query.trafficPotential(level, origin) }
        catch (_: TraceReadUnavailableException) {
            helper.assertTrue(remaining > 0, "probe local archive did not become ready")
            helper.runAfterDelay(1L) { verifyWhenReady(remaining - 1) }
            return
        }
        if (!potential.globalReady) {
            helper.assertTrue(remaining > 0, "archive-wide probe summary did not become ready")
            helper.runAfterDelay(1L) { verifyWhenReady(remaining - 1) }
            return
        }
        helper.assertTrue(potential.traceCount == 1 && potential.aliveCount == 1 && potential.sequenceCount == 1, "exact local counts")
        helper.assertTrue(potential.localSurvivingStrength == 1f && potential.regionalSurvivingStrength == 3f, "local and regional strength")
        helper.assertTrue(potential.serverSurvivingStrength == baseline.serverSurvivingStrength + 6f, "server strength includes remote shard")
        helper.assertTrue(potential.serverShare == 1f / potential.serverSurvivingStrength && potential.regionalShare == 3f / potential.serverSurvivingStrength, "shares use server denominator")
        val reports = mutableListOf<String>()
        val player = object : ServerPlayer(level.server, level, GameProfile(UUID.randomUUID(), "probe-reader")) {
            override fun displayClientMessage(component: net.minecraft.network.chat.Component, actionBar: Boolean) {
                reports += component.string
            }
        }
        player.setPos(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())
        val stack = net.minecraft.world.item.ItemStack(com.bettercontent.betterplayertraces.item.TracesItems.PROBE_ITEM.get())
        stack.orCreateTag.putString("sentinel", "unchanged")
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack)
        val savedStack = stack.copy()
        val result = stack.item.use(level, player, net.minecraft.world.InteractionHand.MAIN_HAND)
        helper.assertTrue(result.result.consumesAction() && result.`object` === stack, "probe returns the same held stack")
        helper.assertTrue(net.minecraft.world.item.ItemStack.matches(stack, savedStack), "probe must not consume or alter its stack")
        helper.assertTrue(reports.size == 1 && reports.single().contains("local=1.0, region=3.0, server=${potential.serverSurvivingStrength}"), "actual item reports query totals")
        helper.assertTrue(query.trafficPotential(level, origin) == potential, "query and probe must not alter totals")
        helper.assertTrue(positions.map { storage.traceTileSnapshot(it.x shr 4, it.z shr 4) } == before, "query and probe must not mutate shard records/revisions")
        helper.succeed()
        }
        verifyWhenReady(120)
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

    @GameTest(batch = "better_player_traces_persistence", template = "trace_arena", timeoutTicks = 200)
    @JvmStatic
    fun persistenceSurvivesRestart(helper: GameTestHelper) {
        val level = helper.level
        helper.assertTrue(level.chunkSource.generator is FlatLevelSource,
            "native trace fixtures must use flat terrain, not clear naturally generated block entities")
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

        awaitReadable(helper, writer, trace.blockPos) {
            try {
                helper.assertTrue(writer.addFootTrace(trace), "ready archive accepted native footprint")
                writer.tickFlush()
            } finally { writer.close() }
            val reader = TraceStorageManager(level, config)
            awaitReadable(helper, reader, trace.blockPos) {
                try {
                    val loaded = reader.queryTraces(boundsMin, boundsMax)
                    helper.assertTrue(loaded.isNotEmpty(), "loaded trace batch size=${loaded.size}, ids=${loaded.map { it.id }}")
                    helper.assertTrue(loaded.any { it.id == traceId }, "trace should persist across storage manager reload")
                    helper.succeed()
                } finally { reader.close() }
            }
        }
    }

    @GameTest(batch = "better_player_traces_water", template = "trace_arena", timeoutTicks = 200)
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

        awaitReadable(helper, storage, trace.blockPos) {
            try {
                helper.assertTrue(storage.addFootTrace(trace), "ready archive accepted native footprint")
                storage.tickFlush()
                helper.assertTrue(storage.queryTraces(trace.blockPos, trace.blockPos).isNotEmpty(), "precondition: trace written")

                ErosionService(storage, TracesConfig.common).onFluidTick(trace.blockPos)
                helper.assertTrue(storage.queryTraces(trace.blockPos, trace.blockPos).isEmpty(), "water should remove nearby traces")
                helper.succeed()
            } finally { storage.close() }
        }
    }

    @GameTest(batch = "better_player_traces_support", template = "trace_arena", timeoutTicks = 200)
    @JvmStatic
    fun loadedSupportLossPhysicallyErasesWithoutRenumbering(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val erosion = ErosionService(storage, TracesConfig.common)
        val firstPos = helper.absolutePos(BlockPos(3, 2, 3))
        val secondPos = firstPos.east()
        val sequence = UUID.randomUUID()
        val supportBlock = ResourceLocation("minecraft", "stone")
        fun footprint(position: BlockPos, index: Int) = FootTrace(
            UUID.randomUUID(), level.dimension().location().toString(), position, MovementClass.WALK,
            1f, sequence, index, 1, 1, true, UUID.randomUUID(),
            support = TraceSupport(position.below(), supportBlock),
        )
        val removed = footprint(firstPos, 0)
        val survivor = footprint(secondPos, 2)
        try {
            level.setBlockAndUpdate(firstPos.below(), Blocks.STONE.defaultBlockState())
            level.setBlockAndUpdate(secondPos.below(), Blocks.STONE.defaultBlockState())
            helper.assertTrue(level.chunkSource.getChunkNow(firstPos.x shr 4, firstPos.z shr 4) != null,
                "maintenance fixture must be in an already-loaded chunk")
            awaitReadable(helper, storage, firstPos) {
                try {
                    helper.assertTrue(storage.addFootTrace(removed) && storage.addFootTrace(survivor), "fixture accepted")
                    erosion.onChunkLoaded(firstPos.x shr 4, firstPos.z shr 4)
                    level.setBlockAndUpdate(firstPos.below(), Blocks.AIR.defaultBlockState())
                    erosion.tick(level, 1)
                    val remaining = storage.queryTraces(firstPos, secondPos)
                    helper.assertTrue(remaining.map { it.id } == listOf(survivor.id),
                        "loaded support loss must remove the first record from all live indexes")
                    helper.assertTrue(remaining.single().sequenceIndex == 2, "surviving sequence gap must remain")
                    helper.succeed()
                } finally {
                    erosion.onChunkUnloaded(firstPos.x shr 4, firstPos.z shr 4)
                    storage.close()
                }
            }
        } catch (failure: Throwable) { storage.close(); throw failure }
    }

    private fun rainFixturePosition(helper: GameTestHelper, local: BlockPos): BlockPos {
        return rainFixtureColumn(helper, helper.absolutePos(BlockPos(local.x, 0, local.z)))
    }

    private fun rainFixtureColumn(helper: GameTestHelper, column: BlockPos): BlockPos {
        val level = helper.level
        // A rain fixture must not inherit terrain height, transparent obstruction,
        // flowing water or a cold high-altitude biome from a previous native world.
        // Own only this test's three columns, at a fixed temperate altitude.
        val position = BlockPos(column.x, 128, column.z)
        for (y in position.y until level.maxBuildHeight) {
            level.setBlockAndUpdate(BlockPos(position.x, y, position.z), Blocks.AIR.defaultBlockState())
        }
        // BiomeManager uses seed-jittered neighboring quart samples, not the
        // single quart containing this block. Fill the full sampling envelope.
        val changed = level.server.commands.performPrefixedCommand(level.server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
            "fillbiome ${position.x - 8} ${position.y - 8} ${position.z - 8} ${position.x + 8} ${position.y + 8} ${position.z + 8} minecraft:plains")
        helper.assertTrue(changed > 0, "native plains fixture biome command must succeed")
        helper.assertTrue(level.getBiome(position).value().getPrecipitationAt(position) == net.minecraft.world.level.biome.Biome.Precipitation.RAIN,
            "native fixture biome must provide rain: pos=$position biome=${level.getBiome(position).unwrapKey()} precipitation=${level.getBiome(position).value().getPrecipitationAt(position)}")
        return position
    }

    @GameTest(batch = "better_player_traces_rain", template = "trace_arena", timeoutTicks = 260)
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
        // Stay inside the same native chunk even when the GameTest arena is
        // translated across a chunk edge; a fixed +5 offset did not guarantee it.
        val comparisonColumn = BlockPos(
            shelteredPosition.x + if ((shelteredPosition.x and 15) <= 10) 5 else -5, 0,
            shelteredPosition.z + if ((shelteredPosition.z and 15) <= 10) 5 else -5)
        val comparisonPosition = rainFixtureColumn(helper, comparisonColumn)
        helper.assertTrue(net.minecraft.world.level.ChunkPos(comparisonPosition) == net.minecraft.world.level.ChunkPos(shelteredPosition),
            "comparison and sheltered traces must share the same native chunk")
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
        awaitReadable(helper, storage, exposedPosition) {
        try {
            level.setWeatherParameters(0, 240, true, false)
            level.rainLevel = 1f
            level.oRainLevel = 1f
            helper.assertTrue(storage.addFootTrace(exposed) && storage.addFootTrace(sheltered)
                && storage.addFootTrace(comparison), "ready archive accepted the three rain fixtures")
            storage.tickFlush()
            for (position in listOf(exposedPosition, shelteredPosition, comparisonPosition))
                erosion.onChunkLoaded(position.x shr 4, position.z shr 4)
            // Wait for skylight propagation, then validate and erode within the same rain episode.
            helper.runAfterDelay(10L) {
                try {
                    level.setWeatherParameters(0, 240, true, false)
                    level.rainLevel = 1f
                    level.oRainLevel = 1f
                    helper.assertTrue(level.hasChunkAt(exposedPosition) && level.isRainingAt(exposedPosition),
                        "exposed fixture must remain loaded and receive rain: loaded=${level.hasChunkAt(exposedPosition)} rainAt=${level.isRainingAt(exposedPosition)} rain=${level.getRainLevel(1f)} sky=${level.canSeeSky(exposedPosition)} biome=${level.getBiome(exposedPosition).unwrapKey()} precipitation=${level.getBiome(exposedPosition).value().getPrecipitationAt(exposedPosition)} pos=$exposedPosition height=${level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, exposedPosition.x, exposedPosition.z)}")
                    helper.assertTrue(level.hasChunkAt(shelteredPosition) && !level.canSeeSky(shelteredPosition),
                        "sheltered fixture must remain loaded with its canopy: cover=${level.getBlockState(coverPos)}")
                    helper.assertTrue(level.isRainingAt(comparisonPosition), "same-chunk comparison fixture must receive rain: rain=${level.getRainLevel(1f)} sky=${level.canSeeSky(comparisonPosition)} biome=${level.getBiome(comparisonPosition).unwrapKey()} precipitation=${level.getBiome(comparisonPosition).value().getPrecipitationAt(comparisonPosition)} pos=$comparisonPosition height=${level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, comparisonPosition.x, comparisonPosition.z)}")
                    val before = listOf(exposed, sheltered, comparison).associate { trace ->
                        trace.id to storage.queryTraces(trace.blockPos, trace.blockPos).single { it.id == trace.id }
                    }
                    erosion.tick(level, 0) // Establish the per-chunk native rain interval.
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
                    for (position in listOf(exposedPosition, shelteredPosition, comparisonPosition))
                        erosion.onChunkUnloaded(position.x shr 4, position.z shr 4)
                    storage.close()
                }
            }
        } catch (failure: Throwable) {
            storage.close()
            throw failure
        }
        }
    }

    @GameTest(batch = "better_player_traces_note_01", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun annotationPersistsAfterTargetBlockReplaced(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val player = helper.makeMockSurvivalPlayer()
        val target = player.blockPosition().offset(2, 0, 0)

        awaitNote(helper, storage,
            { done -> service.submitCreate(level, player, UUID.randomUUID(), target, "probe", "pin", 0x55D66B, null, done) },
        ) { annotation ->
            try {
                helper.assertTrue(annotation != null, "committed creation must return the note")
                level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState())
                val boundsMin = target.offset(-1, 0, -1)
                val boundsMax = target.offset(1, 0, 1)
                val visible = service.annotationsWithin(level, boundsMin, boundsMax, player)
                helper.assertTrue(visible.annotations.any { it.id == annotation?.id }, "annotation should persist when block is replaced")
                helper.succeed()
            } finally { storage.close() }
        }
    }

    @GameTest(batch = "better_player_traces_note_02", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun globalTeamVisibleToSecondPlayer(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val author = helper.makeMockSurvivalPlayer()
        val viewer = helper.makeMockSurvivalPlayer()
        val position = author.blockPosition().offset(2, 0, 0)

        awaitNote(helper, storage,
            { done -> service.submitCreate(level, author, UUID.randomUUID(), position, "team check", "pin", 0x579DFF, null, done) },
        ) { created ->
            try {
                val visible = service.annotationsWithin(level, position.offset(-4, 0, -4), position.offset(4, 64, 4), viewer)
                helper.assertTrue(visible.annotations.any { it.id == created?.id }, "global-team annotations should be visible to all players")
                helper.succeed()
            } finally { storage.close() }
        }
    }


    @GameTest(batch = "better_player_traces_note_03", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun annotationOwnershipAndRevisionAreEnforced(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val author = helper.makeMockSurvivalPlayer()
        val viewer = helper.makeMockSurvivalPlayer()
        val target = author.blockPosition().offset(1, 0, 0)
        awaitNote(helper, storage,
            { done -> service.submitCreate(level, author, UUID.randomUUID(), target, "first", "pin", 0x579DFF, null, done) },
        ) { created ->
            val id = requireNotNull(created).id
            fun checkDuplicate(remaining: Int) {
                storage.tickFlush()
                val duplicate = service.submitCreate(level, author, UUID.randomUUID(), target,
                    "duplicate", "pin", 0x579DFF, null) {}
                if (duplicate == NoteMutationResult.Rejected(NoteRejectReason.BUSY)
                    || duplicate == NoteMutationResult.Rejected(NoteRejectReason.STORAGE_UNAVAILABLE)) {
                    helper.assertTrue(remaining > 0, "archive did not settle before duplicate check: $duplicate")
                    helper.runAfterDelay(1L) { checkDuplicate(remaining - 1) }
                    return
                }
                helper.assertTrue(duplicate == NoteMutationResult.Rejected(NoteRejectReason.TARGET_BUSY),
                    "one creator may only have one note per block: $duplicate")
            helper.assertTrue(service.submitUpdate(level, viewer, UUID.randomUUID(), id, 1, "stolen", "pin", 0x579DFF, EchoMutation.KEEP, null) {} ==
                NoteMutationResult.Rejected(NoteRejectReason.PERMISSION), "other players must not update notes")
            helper.assertTrue(service.submitDelete(level, viewer, UUID.randomUUID(), id, 1) {} ==
                NoteMutationResult.Rejected(NoteRejectReason.PERMISSION), "other players must not delete notes")
            awaitNote(helper, storage,
                { done -> service.submitUpdate(level, author, UUID.randomUUID(), id, 1, "second", "pin", 0x579DFF, EchoMutation.KEEP, null, done) },
            ) { updated ->
                helper.assertTrue(updated?.revision == 2, "owner update should increment revision")
                helper.assertTrue(service.submitUpdate(level, author, UUID.randomUUID(), id, 1, "stale", "pin", 0x579DFF, EchoMutation.KEEP, null) {} ==
                    NoteMutationResult.Rejected(NoteRejectReason.REVISION_CONFLICT), "stale revisions must be rejected")
                helper.assertTrue(!service.acknowledgeViewed(viewer, id, 1), "stale viewed revisions must be rejected")
                helper.assertTrue(service.acknowledgeViewed(viewer, id, 2), "nearby current revision should be acknowledged")
                helper.assertTrue(service.seenRevision(viewer.uuid, id) == 2, "viewed revision should persist")
                awaitNote(helper, storage,
                    { done -> service.submitDelete(level, author, UUID.randomUUID(), id, 2, done) },
                ) { deleted ->
                    try {
                        helper.assertTrue(deleted == null && storage.annotationById(id) == null, "owner delete must publish absent note")
                        helper.succeed()
                    } finally { storage.close() }
                }
            }
            }
            checkDuplicate(180)
        }
    }

    @GameTest(batch = "better_player_traces_note_04", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun annotationEchoPersistsRevisesAndDeletesAtomically(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val owner = helper.makeMockSurvivalPlayer()
        val target = owner.blockPosition().offset(2, 0, 0)
        val clip = validEchoClip()
        awaitNote(helper, storage,
            { done -> service.submitCreate(level, owner, UUID.randomUUID(), target, "", "", 0, clip, done) },
        ) { created ->
            val id = requireNotNull(created).id
            helper.assertTrue(storage.annotationEcho(id)?.encodedClip?.contentEquals(clip) == true,
                "durably published note and echo must be visible from same shard authority")
            awaitNote(helper, storage,
                { done -> service.submitUpdate(level, owner, UUID.randomUUID(), id, 1, "gesture", "", 0, EchoMutation.KEEP, null, done) },
            ) { updated ->
                helper.assertTrue(updated?.revision == 2 && storage.annotationEcho(id)?.annotationRevision == 2 &&
                    storage.annotationEcho(id)?.encodedClip?.contentEquals(clip) == true, "note and kept echo must revise atomically")
                awaitReopenedEcho(helper, storage, id, 2, clip) {
                    awaitNote(helper, storage,
                        { done -> service.submitDelete(level, owner, UUID.randomUUID(), id, 2, done) },
                    ) { deleted ->
                        try {
                            helper.assertTrue(deleted == null && storage.annotationById(id) == null && storage.annotationEcho(id) == null,
                                "deleting a note must durably remove its echo")
                            helper.succeed()
                        } finally { storage.close() }
                    }
                }
            }
        }
    }

    @GameTest(batch = "better_player_traces_note_99", template = "trace_arena", timeoutTicks = 60000)
    @JvmStatic
    fun annotationEchoCapacityRejectsWithoutEviction(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val owner = helper.makeMockSurvivalPlayer()
        val clip = validEchoClip()
        val positions = (0 until AnnotationEchoRecord.MAX_PER_PLAYER).map { index ->
            owner.blockPosition().offset(index % 8 - 3, 0, index / 8 - 3)
        }
        val ids = mutableSetOf<UUID>()
        fun next(index: Int) {
            if (index == positions.size) {
                try {
                    val rejected = service.submitCreate(level, owner, UUID.randomUUID(), owner.blockPosition().offset(5, 0, 0),
                        "extra", "pin", 0x579DFF, clip) {}
                    helper.assertTrue(rejected == NoteMutationResult.Rejected(NoteRejectReason.STORAGE_CAPACITY),
                        "the 65th player-owned echo must be rejected before publication")
                    helper.assertTrue(ids.size == AnnotationEchoRecord.MAX_PER_PLAYER &&
                        ids.all { storage.annotationEcho(it)?.encodedClip?.contentEquals(clip) == true },
                        "capacity rejection must neither change nor evict existing shard echoes")
                    helper.succeed()
                } finally { storage.close() }
                return
            }
            awaitNote(helper, storage,
                { done -> service.submitCreate(level, owner, UUID.randomUUID(), positions[index], "note", "pin", 0x579DFF, clip, done) },
            ) { created ->
                ids += requireNotNull(created).id
                next(index + 1)
            }
        }
        next(0)
    }

    @GameTest(batch = "better_player_traces_note_06", template = "trace_arena", timeoutTicks = 220)
    @JvmStatic
    fun deniedEchoEditLeavesNoteAndClipUnchanged(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val owner = helper.makeMockSurvivalPlayer()
        val stranger = helper.makeMockSurvivalPlayer()
        val target = owner.blockPosition().offset(2, 0, 0)
        val encoded = validEchoClip()
        awaitNote(helper, storage,
            { done -> service.submitCreate(level, owner, UUID.randomUUID(), target, "", "pin",
                AnnotationComponents.colors.getValue("cyan"), encoded, done) },
        ) { created ->
            try {
                val id = requireNotNull(created).id
                val denied = service.submitUpdate(level, stranger, UUID.randomUUID(), id, 1, "stolen", "", 0,
                    EchoMutation.REMOVE, null) {}
                helper.assertTrue(denied == NoteMutationResult.Rejected(NoteRejectReason.PERMISSION),
                    "another player must not replace or remove an echo")
                helper.assertTrue(storage.annotationById(id)?.revision == 1, "denied edit must not increment note revision")
                helper.assertTrue(storage.annotationEcho(id)?.encodedClip?.contentEquals(encoded) == true,
                    "denied edit must preserve the original shard clip")
                helper.succeed()
            } finally { storage.close() }
        }
    }

    @GameTest(batch = "better_player_traces_note_07", template = "trace_arena", timeoutTicks = 220)
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
        val priorClip = validEchoClip()
        val replacementClip = validEchoClip().copyOf()
        awaitNote(helper, storage,
            { done -> service.submitCreate(level, owner, UUID.randomUUID(), target, "original", "", 0, priorClip, done) },
        ) { created ->
            val id = requireNotNull(created).id
            awaitNote(helper, storage,
                { done -> service.submitUpdate(level, operator, UUID.randomUUID(), id, 1, "moderated", "memorial",
                    AnnotationComponents.colors.getValue("white"), EchoMutation.REPLACE, replacementClip, done) },
            ) { updated ->
                try {
                    helper.assertTrue(updated?.revision == 2, "operator replacement should increment note revision")
                    helper.assertTrue(storage.annotationEcho(id)?.annotationRevision == 2 &&
                        storage.annotationEcho(id)?.encodedClip?.contentEquals(replacementClip) == true,
                        "operator replacement must invalidate old echo in same shard publication")
                    helper.succeed()
                } finally { storage.close() }
            }
        }
    }

    @GameTest(batch = "better_player_traces_journey", template = "trace_arena", timeoutTicks = 220)
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
        awaitReadable(helper, storage, origin) {
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
}
