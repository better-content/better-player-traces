package com.bettercontent.betterplayertraces

import com.bettercontent.betterplayertraces.client.TraceAimCandidate
import com.bettercontent.betterplayertraces.client.TraceAimSelection
import com.bettercontent.betterplayertraces.client.TraceEncounter
import com.bettercontent.betterplayertraces.dto.TraceOwner
import com.bettercontent.betterplayertraces.dto.VisibleTraceDto
import com.bettercontent.betterplayertraces.domain.MovementClass
import com.bettercontent.betterplayertraces.network.TraceTileSnapshotPacket
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TraceObservationTest {
    private val alice = TraceOwner(UUID(1, 1), "Alice")
    private val bob = TraceOwner(UUID(2, 2), "Bob")
    private fun mark(owner: TraceOwner? = alice) = VisibleTraceDto("", "", MovementClass.entries.first(), 1.0, 64.0, 1.0, 0f, 1f, 0, owner = owner)
    private fun packet(marks: List<VisibleTraceDto>) = TraceTileSnapshotPacket(1, "minecraft:overworld", 0, 0, 1, 0, 1, marks)

    @Test fun dictionaryRoundTripCompressionAndUnknownNames() {
        val original = packet(List(512) { mark() } + mark(TraceOwner(bob.id)) + mark(null))
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            original.encode(buffer)
            assertTrue(buffer.readableBytes() < 50_000)
            val decoded = TraceTileSnapshotPacket.decode(buffer)
            assertEquals(original, decoded)
            assertEquals(0, buffer.readableBytes())
        } finally { buffer.release() }
    }

    @Test fun ownershipValidation() {
        assertThrows(IllegalArgumentException::class.java) { TraceOwner(UUID(0, 0), "Alice") }
        listOf("", "a name", "§Alice", "a".repeat(17)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { TraceOwner(alice.id, name) }
        }
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            assertThrows(IllegalArgumentException::class.java) {
                packet(listOf(mark(), mark(TraceOwner(alice.id, "NotAlice")))).encode(buffer)
            }
        } finally { buffer.release() }
    }

    @Test fun malformedOwnerReferencesAndDictionariesAreRejected() {
        fun header(buffer: FriendlyByteBuf, count: Int) {
            buffer.writeVarLong(1); buffer.writeUtf("minecraft:overworld"); buffer.writeInt(0); buffer.writeInt(0)
            buffer.writeVarLong(1); buffer.writeVarInt(0); buffer.writeVarInt(1); buffer.writeVarInt(count)
        }
        listOf(-1, 1025).forEach { count ->
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            try { header(buffer, count); assertThrows(IllegalArgumentException::class.java) { TraceTileSnapshotPacket.decode(buffer) } }
            finally { buffer.release() }
        }
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            header(buffer, 1); buffer.writeUUID(alice.id); buffer.writeBoolean(false)
            buffer.writeVarInt(1); buffer.writeVarInt(2)
            assertThrows(IllegalArgumentException::class.java) { TraceTileSnapshotPacket.decode(buffer) }
        } finally { buffer.release() }
    }

    @Test fun deterministicAimAndHysteresis() {
        val selection = TraceAimSelection()
        val a = TraceAimCandidate(alice, 0.4, 4.0, "a")
        val b = TraceAimCandidate(bob, 0.3, 6.0, "b")
        assertEquals(alice, selection.update(listOf(a), 0)?.owner)
        assertEquals(alice, selection.update(listOf(b, a), 50)?.owner)
        assertEquals(alice, selection.update(listOf(a, b), 249)?.owner)
        assertEquals(bob, selection.update(listOf(a, b), 250)?.owner)
        assertEquals(bob, selection.update(emptyList(), 499)?.owner)
        assertNull(selection.update(emptyList(), 500))
        selection.clear()
        assertEquals(alice, selection.update(listOf(b.copy(perpendicular = 0.4, along = 4.0), a), 1000)?.owner)
    }

    @Test fun alternatingChallengersCannotKeepLostOwnerBeyond250ms() {
        val selection = TraceAimSelection()
        val a = TraceAimCandidate(alice, 0.4, 4.0, "a")
        val b = TraceAimCandidate(bob, 0.3, 4.0, "b")
        val unknown = TraceAimCandidate(null, 0.2, 4.0, "c")
        selection.update(listOf(a), 0)
        assertEquals(alice, selection.update(listOf(b), 50)?.owner)
        assertEquals(alice, selection.update(listOf(unknown), 150)?.owner)
        assertEquals(bob, selection.update(listOf(b), 250)?.owner)
    }

    @Test fun insufficientImprovementNeverSwitchesAndChallengerMustRemainContinuous() {
        val selection = TraceAimSelection()
        val a = TraceAimCandidate(alice, 0.4, 4.0, "a")
        val b = TraceAimCandidate(bob, 0.35, 1.0, "b")
        selection.update(listOf(a), 0)
        selection.update(listOf(a, b), 100)
        assertEquals(alice, selection.update(listOf(a, b), 1000)?.owner)
        selection.update(listOf(a, b.copy(perpendicular = 0.2)), 1100)
        selection.update(listOf(a), 1200)
        selection.update(listOf(a, b.copy(perpendicular = 0.2)), 1250)
        assertEquals(alice, selection.update(listOf(a, b.copy(perpendicular = 0.2)), 1400)?.owner)
    }

    @Test fun encounterDebounceOneNoticeAndCooldownFromShown() {
        val encounter = TraceEncounter()
        fun update(t: Long, near: Boolean = true, outer: Boolean = near) = encounter.update(t, true, near, outer, false, true)
        assertFalse(update(0)); assertFalse(update(499)); assertTrue(update(500))
        assertFalse(update(1000))
        encounter.shown(2000)
        update(3000, false); update(13_000, false)
        assertFalse(update(14_000)); assertFalse(update(14_500))
        assertFalse(update(61_999)); assertTrue(update(62_000))
    }

    @Test fun sightEntryConsumesEncounterAndSuppressedFramesDoNotDebounce() {
        val encounter = TraceEncounter()
        assertFalse(encounter.update(0, true, true, true, true, true))
        assertFalse(encounter.update(1000, true, true, true, false, true))
        encounter.reset()
        assertFalse(encounter.update(0, true, true, true, false, false))
        assertFalse(encounter.update(500, true, true, true, false, true))
        assertFalse(encounter.update(999, true, true, true, false, true))
        assertTrue(encounter.update(1000, true, true, true, false, true))
    }

    @Test fun lookRayRangeAndOcclusionBoundaries() {
        val origin = net.minecraft.world.phys.Vec3.ZERO
        val direction = net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0)
        fun aim(x: Double, z: Double, clear: Boolean = true) =
            com.bettercontent.betterplayertraces.client.TraceObservationGeometry.aim(
                net.minecraft.world.phys.Vec3(x, 0.0, z), origin, direction) { clear }
        assertNotNull(aim(0.6, 4.0)); assertNull(aim(0.601, 4.0))
        assertNotNull(aim(0.0, 12.0)); assertNull(aim(0.0, 12.001))
        assertNull(aim(0.0, -1.0)); assertNull(aim(0.0, 0.0)); assertNull(aim(0.0, 4.0, false))
        assertTrue(com.bettercontent.betterplayertraces.client.TraceObservationGeometry.inside(
            net.minecraft.world.phys.Vec3(8.0, 3.0, 0.0), origin, 8.0, 3.0))
        assertFalse(com.bettercontent.betterplayertraces.client.TraceObservationGeometry.inside(
            net.minecraft.world.phys.Vec3(8.0, 3.001, 0.0), origin, 8.0, 3.0))
    }

    @Test fun unchangedRevisionConfirmationRefreshesFreshnessAndEvictionInvalidatesObservation() {
        com.bettercontent.betterplayertraces.config.TracesConfig.clientSpec.setConfig(
            com.electronwill.nightconfig.core.CommentedConfig.inMemory())
        val state = com.bettercontent.betterplayertraces.client.TracesClientState
        state.clearTraceTiles()
        try {
            assertTrue(state.beginTraceSubscription(10, "minecraft:overworld", 0, 100))
            val key = com.bettercontent.betterplayertraces.client.TraceTileKey(0, 0)
            assertTrue(state.acceptTraceTilePage(10, "minecraft:overworld", key, 1, 0, 1, listOf(mark())))
            assertTrue(state.subscriptionFresh("minecraft:overworld", 10_100))
            assertFalse(state.subscriptionFresh("minecraft:overworld", 10_101))
            assertTrue(state.beginTraceSubscription(10, "minecraft:overworld", 0, 10_102))
            assertTrue(state.subscriptionFresh("minecraft:overworld", 10_103))
            assertEquals(1, state.nearbyTraces(0.0, 0.0, 8.0).size)
            val epoch = state.observationEpoch
            state.evictTraceTiles(10, "minecraft:overworld", listOf(key))
            assertTrue(state.observationEpoch > epoch)
            assertTrue(state.nearbyTraces(0.0, 0.0, 8.0).isEmpty())
            assertFalse(state.beginTraceSubscription(9, "minecraft:overworld", 0, 10_103))
            assertFalse(state.beginTraceSubscription(10, "minecraft:the_nether", 0, 10_103))
            assertFalse(state.subscriptionFresh("minecraft:the_nether", 10_103))
        } finally { state.clearTraceTiles() }
    }

    @Test fun asynchronousSkinFallbackCacheAndObsoleteCallbacks() {
        val cache = com.bettercontent.betterplayertraces.client.TraceSkinCache<String>(2)
        var old: ((String) -> Unit)? = null
        var fresh: ((String) -> Unit)? = null
        assertEquals("default-a", cache.getOrRequest(alice.id, "default-a") { old = it })
        assertEquals("default-a", cache.getOrRequest(alice.id, "unused") { fail("duplicate lookup") })
        old!!("resolved-a")
        assertEquals("resolved-a", cache.getOrRequest(alice.id, "unused") { fail("duplicate lookup") })
        cache.clear()
        assertEquals("default-new", cache.getOrRequest(alice.id, "default-new") { fresh = it })
        old!!("obsolete-world")
        assertEquals("default-new", cache.getOrRequest(alice.id, "unused") { fail("duplicate lookup") })
        fresh!!("resolved-new")
        assertEquals("resolved-new", cache.getOrRequest(alice.id, "unused") { fail("duplicate lookup") })
    }

    @Test fun dimensionTransitionPreservesSessionCooldownButLogoutResetsIt() {
        val encounter = TraceEncounter()
        encounter.shown(100)
        encounter.reset(preserveCooldown = true)
        assertFalse(encounter.update(1000, true, true, true, false, true))
        assertFalse(encounter.update(1500, true, true, true, false, true))
        assertFalse(encounter.update(60_099, true, true, true, false, true))
        assertTrue(encounter.update(60_100, true, true, true, false, true))
        encounter.shown(60_100)
        encounter.reset()
        assertFalse(encounter.update(60_200, true, true, true, false, true))
        assertTrue(encounter.update(60_700, true, true, true, false, true))
    }

    @Test fun staleDataCannotRearmAnEncounter() {
        val encounter = TraceEncounter()
        encounter.update(0, true, true, true, true, true)
        encounter.update(1000, true, false, false, false, true)
        encounter.update(12_000, false, false, false, false, true)
        assertFalse(encounter.update(20_000, true, true, true, false, true))
        assertFalse(encounter.update(21_000, true, true, true, false, true))
    }
}
