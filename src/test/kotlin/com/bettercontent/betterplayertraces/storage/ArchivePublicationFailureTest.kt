package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM
import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import com.bettercontent.betterplayertraces.util.Geometry
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Disposable filesystem publication tests; no Minecraft launch or production fault switch. UNRUN. */
class ArchivePublicationFailureTest {
    private fun shard(dir: Path) = dir.resolve("r.0.0.traces")
    private fun encode(state: TraceShardState) = TraceSerializer.encodeV4(state, Geometry.shardToBounds(0, 0))

    @Test fun blockedManifestCannotBeAcknowledgedAndRetryPublishesExactNoteReceipt(@TempDir dir: Path) {
        val path = shard(dir)
        val tempManifest = path.resolveSibling(".${path.fileName}.manifest.tmp")
        val id = UUID.randomUUID()
        val oldRequest = UUID.randomUUID()
        val request = UUID.randomUUID()
        val position = BlockPos(2, 70, 2)
        val oldNote = TraceAnnotation(id, "first", "pin", 0xFFFFFF, position, position,
            GLOBAL_TEAM, 1, UUID.randomUUID(), byteArrayOf(1, 2, 3))
        val committed = TraceShardState().apply { applyPublishedAnnotation(id, oldNote, oldRequest) }
        assertTrue(TraceArchive.publish(path, encode(committed), committed.archiveRevision()))
        val updated = oldNote.copy(text = "updated", revision = 2, encodedEcho = byteArrayOf(4, 5))
        val staged = committed.snapshot().first.apply { applyPublishedAnnotation(id, updated, request) }
        val payload = encode(staged)
        Files.createDirectory(tempManifest) // segment can be written; next manifest cannot be published
        assertThrows(Exception::class.java) { TraceArchive.publish(path, payload, staged.archiveRevision()) }
        assertFalse(TraceArchive.confirmDurable(path, staged.archiveRevision()),
            "an uncommitted new receipt must not be acknowledged")
        val prior = assertInstanceOf(ShardRead.Present::class.java, LiveShardReader.read(path)).value as TraceShardState
        assertEquals(oldRequest, prior.noteReceipts[id])
        assertEquals(oldNote, prior.annotationById(id), "old committed note+echo remain visible during staging")
        Files.delete(tempManifest)
        assertTrue(TraceArchive.publish(path, payload, staged.archiveRevision()))
        assertTrue(TraceArchive.confirmDurable(path, staged.archiveRevision()))
        val observed = assertInstanceOf(ShardRead.Present::class.java, LiveShardReader.read(path)).value as TraceShardState
        assertEquals(request, observed.noteReceipts[id])
        assertEquals(updated, observed.annotationById(id))
        assertArrayEquals(byteArrayOf(4, 5), observed.annotationById(id)?.encodedEcho)
    }

    @Test fun ambiguousSameRevisionRetryRejectsDifferentPayloadWithoutOverwritingCommittedData(@TempDir dir: Path) {
        val path = shard(dir)
        val old = TraceShardState().apply { markDirty() }
        val committed = encode(old)
        assertTrue(TraceArchive.publish(path, committed, old.archiveRevision()))
        assertTrue(TraceArchive.publish(path, committed, old.archiveRevision()), "exact retry is idempotent")
        val competing = TraceShardState().apply { markDirty(); putAnnotation(TraceAnnotation(
            UUID.randomUUID(), "other", "pin", 0xFFFFFF, BlockPos.ZERO, BlockPos.ZERO,
            GLOBAL_TEAM, 1, UUID.randomUUID())); setArchiveRevision(old.archiveRevision()) }
        // Same revision with different bytes must be rejected even if the caller thinks its first write was lost.
        assertEquals(old.archiveRevision(), competing.archiveRevision())
        assertFalse(TraceArchive.publish(path, encode(competing), old.archiveRevision()))
        assertArrayEquals(committed, TraceArchive.readLatest(path).payload)
    }

    @Test fun damagedPublishedSegmentFallsBackToPriorAndWritesGapMarker(@TempDir dir: Path) {
        val path = shard(dir)
        val state = TraceShardState().apply { markDirty() }
        val prior = encode(state)
        assertTrue(TraceArchive.publish(path, prior, state.archiveRevision()))
        state.markDirty()
        assertTrue(TraceArchive.publish(path, encode(state), state.archiveRevision()))
        val newer = Files.list(dir).use { stream -> stream.filter {
            it.fileName.toString().endsWith(".seg.000000000002")
        }.findFirst().orElseThrow() }
        Files.write(newer, byteArrayOf(9, 8, 7))
        val recovered = assertInstanceOf(ShardRead.Present::class.java, LiveShardReader.read(path)).value as TraceShardState
        assertEquals(1L, recovered.archiveRevision())
        assertTrue(Files.exists(path.resolveSibling("${path.fileName}.gap")))
        assertArrayEquals(prior, TraceArchive.readLatest(path).payload)
        assertArrayEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(newer),
            "recovery must not silently delete the damaged file used as evidence")
    }
}
