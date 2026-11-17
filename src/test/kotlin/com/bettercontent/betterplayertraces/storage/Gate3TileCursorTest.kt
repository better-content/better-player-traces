package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.domain.FootTrace
import com.bettercontent.betterplayertraces.domain.MovementClass
import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Source-only cursor regression; this test has not been run. */
class Gate3TileCursorTest {
    @Test fun noteUpdateWithUnchangedTileCountInvalidatesMetadataRevisionPin() {
        val state = TraceShardState()
        val id = UUID(71, 72)
        val owner = UUID(73, 74)
        val position = BlockPos(1, 64, 2)
        val first = TraceAnnotation(id, "old", "", 0xFFFFFF, position, position,
            GLOBAL_TEAM, 1, owner)
        state.putAnnotation(first)
        val ordinal = requireNotNull(state.nextAnnotationInTile(0, 0, 0L)).first
        val initialCount = state.annotationTileCount(0, 0)
        val footprintTileRevision = state.tileRevision(0, 0)
        val notePin = state.guidanceRevision()
        state.applyPublishedAnnotation(id, first.copy(text = "new", revision = 2), UUID(75, 76))
        assertEquals(initialCount, state.annotationTileCount(0, 0))
        assertEquals(footprintTileRevision, state.tileRevision(0, 0), "footprint tile revisions alone cannot pin note metadata")
        assertNotEquals(notePin, state.guidanceRevision(), "resumable and final metadata pin checks must reject this update")
        assertEquals(ordinal, requireNotNull(state.nextAnnotationInTile(0, 0, 0L)).first)
    }

    @Test fun denseTilePagesCanResumeAcrossPhysicalErasureWithoutIndexRenumbering() {
        val state = TraceShardState()
        val owner = UUID(10, 20)
        val sequence = UUID(30, 40)
        val marks = (0 until 1_100).map { i ->
            FootTrace(UUID(0, i.toLong() + 1), "minecraft:overworld", (i % 16) + 0.5,
                64.0, 0.5, 0f, MovementClass.WALK, 1f, sequence, i,
                i.toLong(), 1, true, owner)
        }
        marks.forEach(state::addFootTrace)
        assertEquals(1_100, state.livingTraceTileCount(0, 0))
        var after = 0L
        val firstPage = buildList {
            repeat(512) {
                val entry = requireNotNull(state.nextTraceInTile(0, 0, after))
                after = entry.first
                add(entry.second.id)
            }
        }
        assertEquals(marks.take(512).map { it.id }, firstPage)
        assertEquals(1, state.removeTraceById(marks[700].id))
        assertEquals(1_099, state.livingTraceTileCount(0, 0))
        // A transfer pinned to the old tile revision must restart, not append to its earlier page.
        assertTrue(state.tileRevision(0, 0) > 0L)
        val replay = buildList {
            var cursor = 0L
            while (true) {
                val entry = state.nextTraceInTile(0, 0, cursor) ?: break
                cursor = entry.first
                add(entry.second.id)
            }
        }
        assertEquals(marks.map { it.id }.filterNot { it == marks[700].id }, replay)
    }
}
