package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.domain.FootTrace
import com.bettercontent.betterplayertraces.domain.MovementClass
import com.bettercontent.betterplayertraces.domain.TraceSupport
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Source-only regressions. No test execution was authorized for this implementation. */
class LiveAuthoritySourceTest {
    @Test fun acceptedSnapshotDoesNotObserveLaterFootprintMutation() {
        val state = TraceShardState()
        val first = foot("first", 0)
        val next = foot("next", 1)
        state.addFootTrace(first)
        val (immutable, watermark) = state.snapshot()
        state.addFootTrace(next)
        assertEquals(listOf(first.id), immutable.footTracesSnapshot().map { it.id })
        state.clearDirtyIfUnchanged(watermark)
        assertTrue(state.dirty, "an older completion must not clear newer dirty authority")
        assertEquals(listOf(first.id, next.id), state.footTracesSnapshot().map { it.id })
    }

    @Test fun erasurePhysicallyRemovesFootprintWithoutRenumberingSurvivors() {
        val state = TraceShardState()
        val first = foot("first", 0)
        val next = foot("next", 2)
        state.addFootTrace(first)
        state.addFootTrace(next)
        assertTrue(state.updateTraceWeakness(first.id, 0.0))
        assertEquals(listOf(next.id), state.footTracesSnapshot().map { it.id })
        assertEquals(listOf(2), state.queryTraceTile(0, 0).map { it.sequenceIndex })
        assertFalse(state.updateTraceWeakness(first.id, 0.0))
    }

    private fun foot(seed: String, index: Int): FootTrace = FootTrace(
        UUID.nameUUIDFromBytes(seed.toByteArray()), "minecraft:overworld", 1.5 + index, 64.0, 1.5,
        0f, MovementClass.WALK, 1f, UUID.nameUUIDFromBytes("route".toByteArray()), index,
        1L, 1L, true, UUID.nameUUIDFromBytes("actor".toByteArray()),
        support = TraceSupport(BlockPos(1 + index, 63, 1), ResourceLocation("minecraft", "stone")),
    )
}
