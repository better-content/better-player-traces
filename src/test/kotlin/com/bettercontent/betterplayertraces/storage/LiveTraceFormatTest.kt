package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM
import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import com.bettercontent.betterplayertraces.util.Geometry
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Authored only; execution requires separate user approval. */
class LiveTraceFormatTest {
    @Test fun rootDiscoveryIncludesManifestAndBackupOnly(@TempDir world: Path) {
        val root = world.resolve("data/better_player_traces")
        val dimension = ResourceLocation("minecraft", "overworld")
        assertTrue(LiveTraceRoot.open(root, dimension).isEmpty())
        val folder = root.resolve("minecraft/overworld")
        Files.createDirectories(folder)
        Files.write(folder.resolve("r.1.-2.traces.manifest.bak"), byteArrayOf(1))
        Files.write(folder.resolve("r.-4.7.traces.manifest"), byteArrayOf(1))
        assertEquals(setOf(1 to -2, -4 to 7), LiveTraceRoot.open(root, dimension))
    }

    @Test fun populatedUnversionedAndLegacyRootsRejectWithoutDeletion(@TempDir world: Path) {
        val dimension = ResourceLocation("minecraft", "overworld")
        val root = world.resolve("data/better_player_traces")
        Files.createDirectories(root)
        val protected = root.resolve("unknown")
        Files.writeString(protected, "user data")
        assertThrows(UnsupportedRootException::class.java) { LiveTraceRoot.open(root, dimension) }
        assertEquals("user data", Files.readString(protected))
        Files.delete(protected)
        val legacy = world.resolve("data/traces/minecraft/overworld")
        Files.createDirectories(legacy)
        val oldShard = legacy.resolve("r.0.0.traces")
        Files.write(oldShard, byteArrayOf(4))
        assertThrows(UnsupportedRootException::class.java) { LiveTraceRoot.open(root, dimension) }
        assertArrayEquals(byteArrayOf(4), Files.readAllBytes(oldShard))
    }

    @Test fun v4AnnotationEchoAndDeleteReceiptRemainAtomic(@TempDir dir: Path) {
        val id = UUID.randomUUID()
        val request = UUID.randomUUID()
        val input = byteArrayOf(2, 3, 4)
        val pos = BlockPos(3, 75, 9)
        val note = TraceAnnotation(id, "Here", "pin", 0x35E7FF, pos, pos, GLOBAL_TEAM, 1, UUID.randomUUID(), input)
        input[0] = 77
        assertEquals(2, note.encodedEcho!![0].toInt())
        note.encodedEcho!![0] = 99
        assertEquals(2, note.encodedEcho!![0].toInt())
        assertEquals(note, note.copy(encodedEcho = byteArrayOf(2, 3, 4)))

        val state = TraceShardState()
        state.applyPublishedAnnotation(id, note, UUID.randomUUID())
        val path = dir.resolve("r.0.0.traces")
        val bytes = TraceSerializer.encodeV4(state, Geometry.shardToBounds(0, 0))
        assertTrue(TraceArchive.publish(path, bytes, state.archiveRevision()))
        val restored = assertInstanceOf(ShardRead.Present::class.java, LiveShardReader.read(path)).value as TraceShardState
        assertEquals(note, restored.annotationById(id))
        restored.applyPublishedAnnotation(id, null, request)
        assertTrue(TraceArchive.publish(path, TraceSerializer.encodeV4(restored, Geometry.shardToBounds(0, 0)), restored.archiveRevision()))
        val deleted = assertInstanceOf(ShardRead.Present::class.java, LiveShardReader.read(path)).value as TraceShardState
        assertNull(deleted.annotationById(id))
        assertEquals(request, deleted.noteReceipts[id], "absence alone cannot reconcile a deletion")
    }

    @Test fun backupOnlyRecoversAndBadOrUnsupportedDataAreNeverAbsent(@TempDir dir: Path) {
        val path = dir.resolve("r.0.0.traces")
        assertEquals(ShardRead.Absent, LiveShardReader.read(path))
        val state = TraceShardState().apply { markDirty() }
        assertTrue(TraceArchive.publish(path, TraceSerializer.encodeV4(state, Geometry.shardToBounds(0, 0)), state.archiveRevision()))
        state.markDirty()
        assertTrue(TraceArchive.publish(path, TraceSerializer.encodeV4(state, Geometry.shardToBounds(0, 0)), state.archiveRevision()))
        Files.delete(path.resolveSibling("${path.fileName}.manifest"))
        assertTrue(LiveShardReader.read(path) is ShardRead.Present)
        assertTrue(Files.exists(path.resolveSibling("${path.fileName}.manifest")))

        val legacyPath = dir.resolve("r.1.0.traces")
        assertTrue(TraceArchive.publish(legacyPath, TraceSerializer.encodeV3(state, Geometry.shardToBounds(1, 0)), state.archiveRevision()))
        assertTrue(LiveShardReader.read(legacyPath) is ShardRead.Unsupported)
        val corruptPath = dir.resolve("r.2.0.traces")
        Files.write(corruptPath.resolveSibling("${corruptPath.fileName}.manifest"), byteArrayOf(1, 2, 3))
        assertTrue(LiveShardReader.read(corruptPath) is ShardRead.Corrupt)
    }
}
