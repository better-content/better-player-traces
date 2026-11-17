package com.bettercontent.betterplayertraces.network

import com.bettercontent.betterplayertraces.client.TraceTileKey
import com.bettercontent.betterplayertraces.client.TracesClientState
import com.bettercontent.betterplayertraces.domain.MovementClass
import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM
import com.bettercontent.betterplayertraces.storage.TraceShardState
import com.bettercontent.betterplayertraces.storage.ShardRead
import com.bettercontent.betterplayertraces.dto.VisibleTraceDto
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Authored source-only; execution requires separate explicit human authorization. */
class Gate3SourceTest {
    private val dimension = "minecraft:overworld"
    private fun mark(x: Int) = VisibleTraceDto("", "", MovementClass.WALK,
        x.toDouble(), 64.0, 0.5, 0f, 1f, x)

    @Test fun fiveTickAndServerBudgetsCountExaminedWorkAndActualBytes() {
        val budget = TraceStreamBudget()
        val a = UUID(1, 2); val b = UUID(3, 4)
        val limits = TraceStreamLimits(tilesPerPlayerTick = 2, tilesServerTick = 3,
            recordsPerPlayerWindow = 3, recordsServerTick = 4, bytesPerPlayerWindow = 8,
            bytesServerTick = 12, pinnedPerPlayer = 4, pinnedServer = 6)
        budget.beginTick(100, limits)
        assertTrue(budget.inspectTile(a)); assertTrue(budget.inspectTile(a)); assertFalse(budget.inspectTile(a))
        assertTrue(budget.inspectTile(b)); assertFalse(budget.inspectTile(b))
        repeat(3) { assertTrue(budget.inspectRecord(a)) }
        assertFalse(budget.inspectRecord(a)); assertTrue(budget.inspectRecord(b)); assertFalse(budget.inspectRecord(b))
        assertTrue(budget.canEncode(a, 8)); assertFalse(budget.canEncode(a, 1))
        assertTrue(budget.canEncode(b, 4)); assertFalse(budget.canEncode(b, 1))
        assertTrue(budget.reservePinned(a, 4)); assertFalse(budget.reservePinned(a, 1))
        assertFalse(budget.reservePinned(b, 3)); assertTrue(budget.reservePinned(b, 2))
        budget.beginTick(104, limits)
        assertFalse(budget.inspectRecord(a))
        assertFalse(budget.canEncode(a, 1))
        budget.beginTick(105, limits)
        assertTrue(budget.inspectRecord(a)); assertTrue(budget.canEncode(a, 1))
        assertEquals(6, budget.pinnedBytes())
        budget.releasePinned(a, 4); budget.forget(b)
        assertEquals(0, budget.pinnedBytes())
    }

    @Test fun nearestFirstOrderIncludesEntireRadiusWithoutDensityErasure() {
        val offsets = nearestTileOffsets(2)
        assertEquals(25, offsets.size)
        assertEquals(25, offsets.toSet().size)
        assertEquals(TileCoordinate(0, 0), offsets.first())
        val distances = offsets.map { it.chunkX * it.chunkX + it.chunkZ * it.chunkZ }
        assertEquals(distances.sorted(), distances)
    }

    @Test fun connectionRotationDoesNotAlwaysSpendTheGlobalBudgetOnTheSamePlayer() {
        val players = listOf("a", "b", "c", "d")
        assertEquals(players, rotatedRoundRobin(players, 0))
        assertEquals(listOf("b", "c", "d", "a"), rotatedRoundRobin(players, 1))
        assertEquals(listOf("c", "d", "a", "b"), rotatedRoundRobin(players, 2))
        assertEquals(emptyList<String>(), rotatedRoundRobin(emptyList<String>(), 0))
    }

    @Test fun boundedMetadataTopKMatchesCompleteGlobalSortRegardlessOfVisitOrder() {
        val anchor = BlockPos(0, 64, 0)
        val notes = (1..1_024).map { i -> MetadataNote(UUID(0, i.toLong()), "note-$i", "", 0xFFFFFF,
            BlockPos(i % 32 - 16, 64, i / 32 - 16), 1, UUID(7, 8), false) }
        val targets = notes.takeLast(40).mapTo(HashSet()) { it.id.toString() }
        val order = metadataRankComparator(anchor, targets)
        val expected = notes.sortedWith(order).take(64)
        fun bounded(source: List<MetadataNote>): List<MetadataNote> {
            val ranked = java.util.TreeSet(order)
            source.forEach { ranked.add(it); if (ranked.size > 64) ranked.pollLast() }
            return ranked.toList()
        }
        assertEquals(expected, bounded(notes))
        assertEquals(expected, bounded(notes.reversed()))
        assertTrue(expected.take(40).all { it.id.toString() in targets })
    }

    @Test fun metadataScanRejectsSameCountNoteUpdateBothMidTileAndAtFinalPinPass() {
        val state = TraceShardState()
        val owner = UUID(90, 91)
        val at = BlockPos(1, 64, 1)
        val first = TraceAnnotation(UUID(10, 11), "old", "", 0xFFFFFF, at, at, GLOBAL_TEAM, 1, owner)
        val second = TraceAnnotation(UUID(12, 13), "another", "", 0xFFFFFF, at, at, GLOBAL_TEAM, 1, owner)
        state.putAnnotation(first); state.putAnnotation(second)
        val budget = TraceStreamBudget()
        val limits = TraceStreamLimits(tilesPerPlayerTick = 1, tilesServerTick = 1,
            recordsPerPlayerWindow = 1, recordsServerTick = 1)
        val read: (Int, Int) -> ShardRead<TraceShardState> = { x, z ->
            if (x == 0 && z == 0) ShardRead.Present(state) else ShardRead.Absent
        }
        val work = TraceMetadataSelection(BlockPos.ZERO, 2, 1, emptySet(), 16, 0, 256)
        budget.beginTick(0, limits)
        assertFalse(work.advance(read, budget, owner))
        state.applyPublishedAnnotation(first.id, first.copy(text = "updated", revision = 2), UUID(14, 15))
        budget.beginTick(5, limits)
        assertFalse(work.advance(read, budget, owner))
        assertTrue(work.invalidated)
        assertFalse(work.complete)

        val secondPass = TraceMetadataSelection(BlockPos.ZERO, 2, 2, emptySet(), 16, 0, 256)
        val fast = TraceStreamLimits(tilesPerPlayerTick = 1, tilesServerTick = 1,
            recordsPerPlayerWindow = 16, recordsServerTick = 16)
        var tick = 10L
        while (!secondPass.scanned && tick < 100) {
            budget.beginTick(tick++, fast)
            assertFalse(secondPass.advance(read, budget, owner))
        }
        assertTrue(secondPass.scanned)
        assertFalse(secondPass.complete, "all visited tiles still require budgeted final validation")
        state.applyPublishedAnnotation(second.id, second.copy(text = "changed", revision = 2), UUID(16, 17))
        budget.beginTick(tick, fast)
        assertFalse(secondPass.advance(read, budget, owner))
        assertTrue(secondPass.invalidated)
        assertFalse(secondPass.complete)
    }

    @Test fun clientPublishesOnlyWholeRevisionAndKeepsLastConfirmedOnOutage() {
        TracesClientState.clearTraceTiles()
        val key = TraceTileKey(0, 0)
        assertTrue(TracesClientState.beginTraceSubscription(17, dimension, 0, nowMillis = 100))
        val old = mark(1)
        assertTrue(TracesClientState.acceptTraceTilePage(17, dimension, key, 1, 0, 1, listOf(old)))
        assertEquals(listOf(old), TracesClientState.visibleTraces())
        assertFalse(TracesClientState.acceptTraceTilePage(17, dimension, key, 2, 0, 2, listOf(mark(2))))
        assertEquals(listOf(old), TracesClientState.visibleTraces())
        assertTrue(TracesClientState.markTraceTileUnavailable(17, dimension, key))
        assertTrue(TracesClientState.traceTileStale(key))
        assertEquals(listOf(old), TracesClientState.visibleTraces())
        assertFalse(TracesClientState.acceptTraceTilePage(16, dimension, key, 4, 0, 1, emptyList()))
        assertFalse(TracesClientState.acceptTraceTilePage(17, "minecraft:the_nether", key, 4, 0, 1, emptyList()))
        assertFalse(TracesClientState.acceptTraceTilePage(17, dimension, key, 3, 0, 2, listOf(mark(3))))
        assertTrue(TracesClientState.acceptTraceTilePage(17, dimension, key, 3, 1, 2, listOf(mark(4))))
        assertEquals(listOf(mark(3), mark(4)), TracesClientState.visibleTraces())
        assertFalse(TracesClientState.traceTileStale(key))
        TracesClientState.clearTraceTiles()
    }

    @Test fun statusPacketRoundTripsAndPageEncodingIsMeasurable() {
        val status = TraceTileUnavailablePacket(5, dimension, 12, -4)
        val statusBytes = FriendlyByteBuf(Unpooled.buffer())
        try {
            status.encode(statusBytes)
            assertEquals(status, TraceTileUnavailablePacket.decode(statusBytes))
        } finally { statusBytes.release() }
        val tile = TraceTileSnapshotPacket(5, dimension, 12, -4, 7, 0, 2, List(512, ::mark))
        val bytes = FriendlyByteBuf(Unpooled.buffer())
        try {
            tile.encode(bytes)
            assertTrue(bytes.readableBytes() in 1..512 * 1024)
            assertEquals(tile, TraceTileSnapshotPacket.decode(bytes))
        } finally { bytes.release() }
    }
}
