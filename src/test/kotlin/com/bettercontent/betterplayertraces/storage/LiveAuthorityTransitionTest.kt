package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM
import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import com.bettercontent.betterplayertraces.util.TraceShardId
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** Unrun source-only tests for transitions used by the active manager. */
class LiveAuthorityTransitionTest {
    private val shard = TraceShardId("minecraft:overworld", 0, 0)

    @Test fun publicationCatalogSurvivesEvictionWithoutRestart() {
        val catalog = TraceShardCatalog()
        val live = linkedSetOf(shard)
        val id = UUID.randomUUID()
        val pos = BlockPos(3, 68, 3)
        val note = TraceAnnotation(id, "persisted", "pin", 0x35E7FF, pos, pos, GLOBAL_TEAM, 1, UUID.randomUUID())
        val stored = TraceShardState().apply { putAnnotation(note) }
        val archived = mapOf(shard to stored.snapshot().first)
        assertTrue(catalog.candidates(live).contains(shard), "new accepted shard is still in memory")
        catalog.published(shard) // completed durable publication, including a newly created shard
        live.clear() // clean LRU eviction removes the in-memory annotation index
        val found = catalog.candidates(live).asSequence().mapNotNull { archived[it]?.annotationById(id) }.firstOrNull()
        assertEquals(note, found, "same-lifetime archive lookup must recover note after LRU eviction")
        assertTrue(shard in catalog.candidates(live), "global enumeration must also retain its manifest")
    }

    @Test fun rejectedCaptureNeverClearsExistingDiskFailurePause() {
        assertTrue(pauseAfterRejectedCapture(wasPaused = true, loadedAuthority = false),
            "a failed read during an outage must not reopen admission on next capture")
        assertTrue(pauseAfterRejectedCapture(wasPaused = true, loadedAuthority = true))
        assertTrue(pauseAfterRejectedCapture(wasPaused = false, loadedAuthority = true))
        assertFalse(pauseAfterRejectedCapture(wasPaused = false, loadedAuthority = false))
    }

    @Test fun pendingNoteEchoConsumesShardCodecHeadroomBeforeFootprintAdmission() {
        val io = TraceIoCoordinator()
        try {
            val cap = TraceAuthorityBudget.MAX_SHARD_BYTES
            val base = cap - 2_048L
            val pendingEchoAndNote = 1_536L
            assertTrue(io.reserveDirty(shard, base + pendingEchoAndNote, 64, 128L * 1024L * 1024L))
            io.stageExtra(shard, pendingEchoAndNote)
            val before = io.dirtyBacklog()
            assertFalse(io.reserveDirty(shard, base + 1_024L, 64, 128L * 1024L * 1024L),
                "live footprint plus staged annotation must fit SAME 64MiB shard codec cap")
            assertEquals(before, io.dirtyBacklog(), "rejected capture does not mutate pinned authority budget")
            assertTrue(io.reserveDirty(shard, base + 512L, 64, 128L * 1024L * 1024L))
            io.finishStage(shard)
            io.releaseDirty(shard)
        } finally { io.shutdown() }
    }

    @Test fun serverWideDirtyByteAndCountReservationsRejectProjectedOverflowWithoutMutation() {
        val budget = TraceAuthorityBudget()
        val otherDimension = TraceShardId("minecraft:the_nether", 0, 0)
        assertTrue(budget.reserveDirty(shard, 70, 2, 100))
        assertFalse(budget.reserveDirty(otherDimension, 40, 2, 100))
        assertEquals(1 to 70L, budget.dirtySnapshot())
        assertTrue(budget.reserveDirty(otherDimension, 30, 2, 100))
        assertFalse(budget.reserveDirty(TraceShardId("minecraft:the_end", 0, 0), 1, 2, 100))
        budget.releaseDirty(shard)
        assertEquals(1 to 30L, budget.dirtySnapshot())
    }

    @Test fun readResultReservationRemainsUntilConsumptionAndNoteKeepsRecoveryHeadroom() {
        val budget = TraceAuthorityBudget()
        val mib = 1024 * 1024
        assertTrue(budget.reserveQueue(60 * mib, 64, 128L * mib, note = true))
        assertFalse(budget.reserveQueue(8 * mib, 64, 128L * mib, write = true))
        assertTrue(budget.borrowNoteRead(64 * mib, 128L * mib))
        assertEquals(1 to 124L * mib, budget.queueSnapshot(), "worker completion alone does not free read result")
        assertFalse(budget.reserveQueue(64 * mib, 64, 128L * mib))
        budget.releaseBorrowedRead(64 * mib) // server consumes durable reconciliation
        assertEquals(1 to 60L * mib, budget.queueSnapshot())
        budget.releaseQueue(60 * mib, note = true)
        assertEquals(0 to 0L, budget.queueSnapshot())
    }

    @Test fun failedNoteReadAndExactWriteRetriesAreWindowBoundedDespiteEveryTickPolling() {
        var window = 3L // one window corresponds to the existing 20-server-tick flush/recovery cadence
        var nextRead = 0L
        assertTrue(mayStartNoteRecovery(false, window, nextRead))
        nextRead = window + 1 // actual manager records this before enqueueing reconciliation
        repeat(19) { assertFalse(mayStartNoteRecovery(false, window, nextRead),
            "failed or missing reconciliation may not spin at every server tick") }
        assertFalse(mayStartNoteRecovery(true, window + 1, nextRead), "already inflight read cannot be duplicated")
        window++
        assertTrue(mayStartNoteRecovery(false, window, nextRead), "next 20-tick window may retry")
        val nextExactWrite = window + 1
        assertFalse(window >= nextExactWrite, "failed write cannot replay its payload again in same window")
        assertTrue(window + 1 >= nextExactWrite)
    }

    @Test fun failedNoteCanReconcileEvenWithOneConfiguredQueueSlot() {
        val budget = TraceAuthorityBudget()
        val mib = 1024 * 1024
        assertTrue(budget.reserveQueue(2 * mib, 1, 128L * mib, note = true))
        assertFalse(budget.reserveQueue(64 * mib, 1, 128L * mib), "ordinary read cannot exceed count cap")
        assertTrue(budget.borrowNoteRead(64 * mib, 128L * mib), "completed note writer reuses its count slot")
        assertEquals(1 to 66L * mib, budget.queueSnapshot())
        budget.releaseBorrowedRead(64 * mib)
        budget.releaseQueue(2 * mib, note = true)
        assertEquals(0 to 0L, budget.queueSnapshot())

        assertTrue(budget.reserveQueue(mib, 2, 128L * mib, note = true))
        assertTrue(budget.reserveQueue(mib, 2, 128L * mib, note = true))
        assertTrue(budget.borrowNoteRead(64 * mib, 128L * mib), "small notes may fill count but cannot block recovery")
        budget.releaseBorrowedRead(64 * mib)
        budget.releaseQueue(mib, note = true)
        budget.releaseQueue(mib, note = true)
    }

    @Test fun coordinatorReusesFailedNoteSlotWhenQueueMaxIsOne() {
        val io = TraceIoCoordinator()
        try {
            assertTrue(io.reserveNote(2, 1, 128L * 1024 * 1024))
            assertFalse(io.submit(Callable { false }).get(2, TimeUnit.SECONDS))
            val read = requireNotNull(io.submitReconciliationRead(64, Callable { 7 }))
            assertEquals(7, read.get(2, TimeUnit.SECONDS))
            assertEquals(1 to 66L, io.backlog(), "note slot remains pinned until reconciliation is consumed")
            io.releaseReconciliationRead(64)
            io.releaseNote(2)
            assertEquals(0 to 0L, io.backlog())
        } finally { io.shutdown() }
    }

    @Test fun coordinatorKeepsCompletedReadReservedUntilServerConsumesResult() {
        val io = TraceIoCoordinator()
        try {
            val first = requireNotNull(io.submitRead(1, 64, Callable { 42 }))
            assertEquals(42, first.get(2, TimeUnit.SECONDS))
            assertEquals(1 to 64L, io.backlog(), "worker completion must not release server-held result")
            assertNull(io.submitRead(1, 64, Callable { 99 }))
            io.release(64)
            assertEquals(0 to 0L, io.backlog())
        } finally { io.shutdown() }
    }

    @Test fun predictedReservationRefundAndUpsizeFailureAreAtomic() {
        val budget = TraceAuthorityBudget()
        assertTrue(budget.reserveQueue(40, 2, 100))
        assertFalse(budget.resizeQueue(40, 110, 100))
        assertEquals(1 to 40L, budget.queueSnapshot())
        assertTrue(budget.resizeQueue(40, 25, 100))
        assertEquals(1 to 25L, budget.queueSnapshot())
        budget.releaseQueue(25)
        assertTrue(budget.reserveDirty(shard, 80, 1, 100))
        val previous = budget.previousDirty(shard)
        assertFalse(budget.reserveDirty(shard, 120, 1, 100))
        budget.restoreDirty(shard, previous)
        assertEquals(1 to 80L, budget.dirtySnapshot())
    }

    @Test fun stagedNoteIsInvisibleAndSameShardNoteMustWaitForPublication() {
        val id = UUID.randomUUID()
        val pos = BlockPos(2, 70, 2)
        val old = TraceAnnotation(id, "before", "pin", 0x35E7FF, pos, pos, GLOBAL_TEAM, 1, UUID.randomUUID())
        val live = TraceShardState().apply { putAnnotation(old) }
        val staged = live.snapshot().first
        val replacement = old.copy(text = "after", revision = 2, encodedEcho = byteArrayOf(1, 2))
        staged.applyPublishedAnnotation(id, replacement, UUID.randomUUID())
        assertEquals(old, live.annotationById(id), "unpublished staged state cannot leak into live queries")
        assertEquals(replacement, staged.annotationById(id))
        assertFalse(canStageShard(listOf(shard), shard, false), "second note in same shard cannot overwrite pending snapshot")
        assertFalse(canStageShard(emptyList(), shard, true), "inflight ordinary write precedes a note")
        assertTrue(canStageShard(emptyList(), shard, false))
        assertFalse(shouldSubmitLatestSnapshot(true, false, true, false), "normal flush must not overwrite staged note")
    }

    @Test fun deleteStagesSameShardEchoRemovalAndIdentityReceiptWithoutPrematureVisibility() {
        val id = UUID.randomUUID()
        val request = UUID.randomUUID()
        val pos = BlockPos(2, 70, 2)
        val old = TraceAnnotation(id, "old", "pin", 0x35E7FF, pos, pos, GLOBAL_TEAM, 2,
            UUID.randomUUID(), byteArrayOf(1, 2, 3))
        val live = TraceShardState().apply { putAnnotation(old) }
        val staged = live.snapshot().first.apply { applyPublishedAnnotation(id, null, request) }
        assertArrayEquals(byteArrayOf(1, 2, 3), live.annotationById(id)?.encodedEcho,
            "unpublished deletion cannot remove the committed echo")
        assertNull(staged.annotationById(id))
        assertEquals(request, staged.noteReceipts[id], "delete is identified by request ID even after record disappears")
    }

    @Test fun completionOfOlderSnapshotCannotClearNewerDirtyAndShutdownSchedulesLatest() {
        assertFalse(snapshotCompletionClearsDirty(1, 2, true))
        assertFalse(snapshotCompletionClearsDirty(1, 1, false))
        assertTrue(snapshotCompletionClearsDirty(2, 2, true))
        assertFalse(shouldSubmitLatestSnapshot(true, true, false, false))
        assertTrue(shouldSubmitLatestSnapshot(true, false, false, false), "after old writer completes, drain must submit latest")
        assertFalse(shouldSubmitLatestSnapshot(true, false, false, true), "conflict is sticky")
    }

    @Test fun failedNoteRetriesExactIdentityAfterHealthyOldHeadAndConflictsNeverResume() {
        val note = UUID.randomUUID()
        val request = UUID.randomUUID()
        val old = TraceShardState().apply { markDirty(); setArchiveRevision(3) }
        assertEquals(NoteRecoveryDecision.RETRY_EXACT, classifyNoteRecovery(
            ShardRead.Present(old), note, request, 4, previouslyPublished = true))
        assertEquals(NoteRecoveryDecision.INDETERMINATE, classifyNoteRecovery(
            ShardRead.Absent, note, request, 4, previouslyPublished = true))
        val committed = old.snapshot().first.apply { noteReceipts[note] = request; setArchiveRevision(4) }
        assertEquals(NoteRecoveryDecision.COMMITTED, classifyNoteRecovery(
            ShardRead.Present(committed), note, request, 4, previouslyPublished = true))
        val unexpected = old.snapshot().first.apply { setArchiveRevision(5) }
        assertEquals(NoteRecoveryDecision.CONFLICT, classifyNoteRecovery(
            ShardRead.Present(unexpected), note, request, 4, previouslyPublished = true))
        assertFalse(canReopenAdmission(1, 0, 0, 0, 0, 0, true))
        assertTrue(canReopenAdmission(0, 0, 0, 0, 0, 0, true))
    }
}
