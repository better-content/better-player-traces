package com.bettercontent.betterplayertraces.client

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AnnotationDraftScopeTest {
    @Test fun definiteRejectionRetiresOldRequestBeforeSameDraftIsSubmittedAgain() {
        val draft = AnnotationDraft(BlockPos(4, 70, 8), null, text = "keep this text")
        val unrelated = AnnotationDraft(BlockPos(5, 70, 8), null, text = "unrelated")
        val rejectedId = UUID.randomUUID()
        val retryId = UUID.randomUUID()
        val unrelatedId = UUID.randomUUID()
        val entries = linkedMapOf(rejectedId to draft, unrelatedId to unrelated)
        retireDraftAliases(entries, draft) { it }
        entries[retryId] = draft
        assertFalse(rejectedId in entries, "old request identity must never accept a late reply or occupy a slot")
        assertEquals(draft, entries[retryId])
        assertEquals(unrelated, entries[unrelatedId])
        entries.remove(retryId) // same request now durably committed
        assertFalse(entries.values.any { it === draft }, "later success must not leave a stale draft alias")
    }

    @Test fun oldConnectionSameDimensionCannotAliasCurrentWorldTarget() {
        val previous = UUID.randomUUID()
        val current = UUID.randomUUID()
        assertFalse(sameNoteCommandScope(previous, "minecraft:overworld", current, "minecraft:overworld"))
        assertFalse(sameNoteCommandScope(current, "minecraft:the_nether", current, "minecraft:overworld"))
        assertTrue(sameNoteCommandScope(current, "minecraft:overworld", current, "minecraft:overworld"))
        val rejectedPrevious = AnnotationDraft(BlockPos(1, 60, 1), null,
            publication = DraftPublication.EDITING, originSession = previous, originDimension = "minecraft:overworld")
        assertFalse(sameNoteCommandScope(requireNotNull(rejectedPrevious.originSession),
            requireNotNull(rejectedPrevious.originDimension), current, "minecraft:overworld"),
            "definitely rejected previous-connection text is still inspectable, never reusable in a new world")
    }

    @Test fun oldDraftsStillCountTowardGlobalRetainedMemoryUntilExplicitDismissal() {
        assertTrue(noteDraftHasRoom(63, 8L * 1024L * 1024L))
        assertFalse(noteDraftHasRoom(64, 1_024L), "reconnect cannot make previous entries free")
        assertFalse(noteDraftHasRoom(1, 8L * 1024L * 1024L + 1L))
    }
}
