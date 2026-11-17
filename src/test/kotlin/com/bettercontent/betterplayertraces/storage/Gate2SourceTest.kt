package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.domain.FootTrace
import com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM
import com.bettercontent.betterplayertraces.domain.MovementClass
import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import com.bettercontent.betterplayertraces.domain.TraceSupport
import com.bettercontent.betterplayertraces.logic.CaptureService
import com.bettercontent.betterplayertraces.logic.GuidanceEngine
import com.bettercontent.betterplayertraces.logic.GuidanceRouteWork
import com.bettercontent.betterplayertraces.logic.GuidanceProgressTracker
import com.bettercontent.betterplayertraces.dto.GuidancePointDto
import com.bettercontent.betterplayertraces.dto.GuidanceRouteDto
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Source-only regressions; these tests must not be executed without the user's separate approval. */
class Gate2SourceTest {
    private val owner = UUID.nameUUIDFromBytes("owner".toByteArray())
    private val viewer = UUID.nameUUIDFromBytes("viewer".toByteArray())
    private val sequence = UUID.nameUUIDFromBytes("sequence".toByteArray())
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray())
    private fun trace(index: Int, strength: Float = 1f, support: BlockPos = BlockPos(index, 63, 0)) = FootTrace(
        id("trace-$index"), "minecraft:overworld", index + 0.5, 64.0, 0.5, 0f,
        MovementClass.WALK, strength, sequence, index, index.toLong(), 1, true, owner,
        support = TraceSupport(support, ResourceLocation("minecraft", "stone")),
    )
    private fun note(index: Int, echo: Boolean = false) = TraceAnnotation(
        id("note-$index"), "target", "", 0xFFFFFF, BlockPos(index, 64, 0), BlockPos(index, 64, 0),
        GLOBAL_TEAM, 1, owner, if (echo) byteArrayOf(1, 2) else null,
    )

    @Test fun captureBoundsEvenAnExtremeSegmentAndSignalsOmittedTail() {
        val sampled = CaptureService.sampleSegment(Vec3(0.0, 64.0, 0.0), Vec3(1_000_000.0, 64.0, 0.0), 0.0)
        assertTrue(sampled.truncated)
        assertEquals(CaptureService.MAX_SEGMENT_POINTS, sampled.points.size)
        assertEquals(0.0, sampled.distanceSinceEmission)
    }

    @Test fun physicalErasureUpdatesIdSpatialTemporalAndCursorIndexesWithoutClosingGap() {
        val state = TraceShardState()
        val first = trace(0)
        val middle = trace(1)
        val last = trace(2)
        listOf(first, middle, last).forEach(state::addFootTrace)
        val firstOrdinal = requireNotNull(state.nextTraceInTile(0, 0, 0)).first
        assertEquals(1, state.removeBySupport(middle.support!!.position))
        assertFalse(state.containsFootTrace(middle.id))
        assertEquals(listOf(first.id, last.id), state.queryTraceTile(0, 0).map { it.id })
        assertEquals(listOf(last.id), state.nearbyFootTracesAfter(BlockPos(-1, 0, -1), BlockPos(8, 255, 8), 0L)
            .filter { it.createdAt > 1L }.map { it.id })
        assertEquals(last.id, state.nextTraceInTile(0, 0, firstOrdinal)?.second?.id)
        assertEquals(listOf(0, 2), state.footTracesSnapshot().map { it.sequenceIndex })
    }

    @Test fun denseTileErasureUsesIdKeyedBucketsWithoutShiftingSurvivors() {
        val state = TraceShardState()
        val records = (0 until 4_096).map { index ->
            trace(index).copy(x = (index % 16) + 0.5, z = 0.5,
                support = TraceSupport(BlockPos(index % 16, 63, 0), ResourceLocation("minecraft", "stone")))
        }
        records.forEach(state::addFootTrace)
        val bucketField = TraceShardState::class.java.getDeclaredField("tracesByTile").also { it.isAccessible = true }
        val bucket = (bucketField.get(state) as Map<*, *>).values.single()
        assertTrue(bucket is LinkedHashMap<*, *>, "per-tile erasure must use stable ID-keyed removal, not list shifting")
        records.subList(512, 1_536).forEach { assertEquals(1, state.removeTraceById(it.id)) }
        val surviving = state.queryTraceTile(0, 0)
        assertEquals(3_072, surviving.size)
        assertEquals(records.take(512).map { it.id } + records.drop(1_536).map { it.id }, surviving.map { it.id })
        val firstOrdinal = requireNotNull(state.nextTraceInTile(0, 0, 0L)).first
        assertEquals(records[1].id, state.nextTraceInTile(0, 0, firstOrdinal)?.second?.id)
    }

    @Test fun brokenSequenceDoesNotConnectSurvivingRunsAcrossMissingIds() {
        val traces = listOf(trace(0), trace(1), trace(10), trace(11))
        val target = note(11)
        val direct = GuidanceEngine.buildRoutes(traces, listOf(target), viewer, Vec3(0.5, 64.0, 0.5), { 0 })
        assertTrue(direct.routes.isEmpty())
        val staged = GuidanceRouteWork(Vec3(0.5, 64.0, 0.5))
        traces.reversed().forEach(staged::addTrace)
        staged.addAnnotation(target)
        repeat(500) { if (staged.result == null) assertTrue(staged.step(2) <= 2) }
        assertTrue(requireNotNull(staged.result).routes.isEmpty())
    }

    @Test fun boundedCanonicalRouteWorkResumesAcrossTinyBudgets() {
        val traces = (0..8).map(::trace)
        val target = note(8)
        val builder = GuidanceRouteWork(Vec3(0.5, 64.0, 0.5))
        traces.reversed().forEach(builder::addTrace)
        builder.addAnnotation(target)
        repeat(2000) { if (builder.result == null) assertTrue(builder.step(2) <= 2) }
        val route = requireNotNull(builder.result).routes.single()
        assertEquals((0..8).map { it + 0.5 }, route.path.map { it.x })
        assertEquals(8.0, builder.routeDistance(target.id.toString()) ?: -1.0, 0.00001)
    }

    @Test fun summaryStaysNotReadyOnUnknownArchiveAndRebuildsWithBoundedVisits() {
        val index = TraceSummaryIndex()
        val shardA = com.bettercontent.betterplayertraces.util.TraceShardId("minecraft:overworld", 0, 0)
        val shardB = com.bettercontent.betterplayertraces.util.TraceShardId("minecraft:overworld", 1, 0)
        val a = TraceShardState().apply { addFootTrace(trace(0)); putAnnotation(note(0, echo = true)) }
        val b = TraceShardState().apply { addFootTrace(trace(1, 2f)); putAnnotation(note(1, echo = true)) }
        index.schedule(shardA); index.schedule(shardB)
        repeat(4) { index.tick({ id -> if (id == shardA) ShardRead.Present(a) else ShardRead.Loading }, 2) }
        assertFalse(index.ready())
        assertNull(index.echoCount(owner))
        repeat(200) { if (!index.ready()) index.tick({ id -> ShardRead.Present(if (id == shardA) a else b) }, 2) }
        assertTrue(index.ready())
        assertEquals(2, index.echoCount(owner))
        assertEquals(shardB, index.noteShard(note(1).id))
        assertEquals(3f, index.serverStrength())
        assertEquals(0.5f, index.percentile(1f))
        val before = a.currentGeneration()
        val removed = requireNotNull(a.traceById(trace(0).id))
        a.removeTraceById(removed.id)
        index.traceChanged(shardA, before, a, removed, null)
        assertTrue(index.ready())
        assertEquals(2f, index.serverStrength())
    }

    @Test fun completedRouteSeedNeverAwardsExperienceUntilAnExplicitTickObservation() {
        val progress = GuidanceProgressTracker()
        val route = GuidanceRouteDto("target", 1, listOf(
            GuidancePointDto(0.0, 64.0, 0.0), GuidancePointDto(3.0, 64.0, 0.0), GuidancePointDto(6.0, 64.0, 0.0),
        ))
        progress.seed(viewer, Vec3(0.0, 64.0, 0.0), route, 6.0)
        val shorter = route.copy(path = route.path.drop(1))
        assertEquals(1, progress.observeRemaining(viewer, Vec3(3.0, 64.0, 0.0), shorter, 3.0).experience)
        assertEquals(0, progress.observeRemaining(viewer, Vec3(3.0, 64.0, 0.0), shorter, 3.0).experience)
    }

    @Test fun exactPercentileIndexUpdatesWithoutScanningFootprintHistory() {
        val ranks = StrengthRankTree()
        ranks.adjust(0.25f, 2L)
        ranks.adjust(0.75f, 1L)
        assertEquals(2L, ranks.countAtMost(0.25f))
        assertEquals(3L, ranks.countAtMost(0.75f))
        ranks.adjust(0.25f, -1L)
        assertEquals(1L, ranks.countAtMost(0.25f))
        ranks.adjust(0.75f, -1L)
        assertEquals(1L, ranks.countAtMost(1f))
    }

    @Test fun seenAndNoteIndexesSurviveSnapshotWithoutReturningErasedState() {
        val state = TraceShardState()
        val note = note(3, echo = true)
        state.putAnnotation(note)
        val seen = SeenStateRecord(note.id, viewer, 2)
        state.seenStates += seen
        state.indexSeen(seen)
        val frozen = state.snapshot().first
        assertEquals(2, frozen.seenRevision(viewer, note.id))
        assertEquals(note.id, frozen.nextAnnotationInTile(0, 0, 0)?.second?.id)
        assertTrue(state.removeAnnotation(note.id))
        assertNull(state.nextAnnotationInTile(0, 0, 0))
        assertEquals(0, state.seenRevision(viewer, note.id))
        assertEquals(2, frozen.seenRevision(viewer, note.id))
    }
}
