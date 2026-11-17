package com.bettercontent.betterplayertraces.network

import com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM
import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import com.bettercontent.betterplayertraces.storage.ShardRead
import com.bettercontent.betterplayertraces.storage.TraceShardState
import com.bettercontent.betterplayertraces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import java.lang.ref.WeakReference
import java.util.TreeSet
import java.util.UUID

/** Lightweight metadata only: never retain an annotation's potentially large encoded echo clip. */
internal data class MetadataNote(
    val id: UUID, val text: String, val icon: String, val color: Int, val position: BlockPos,
    val revision: Int, val owner: UUID, val hasEcho: Boolean,
) {
    companion object {
        fun from(note: TraceAnnotation) = MetadataNote(note.id, note.text, note.icon, note.color,
            note.position, note.revision, note.createdByInternal, note.hasEcho)
    }
}

/** An exact top-K selection with bounded tile and record visits and a resumable per-tile ordinal. */
internal fun metadataRankComparator(anchor: BlockPos, targets: Set<String>): Comparator<MetadataNote> =
    compareByDescending<MetadataNote> { it.id.toString() in targets }
        .thenBy { note ->
            val dx = note.position.x.toLong() - anchor.x
            val dz = note.position.z.toLong() - anchor.z
            dx * dx + dz * dz
        }.thenBy { it.id }

internal class TraceMetadataSelection(
    val anchor: BlockPos,
    radius: Int,
    val epoch: Long,
    val targets: Set<String>,
    val capacity: Int,
    val minY: Int,
    val maxY: Int,
    val startedTick: Long = 0L,
) {
    private val minX = anchor.x - radius * 16
    private val maxX = anchor.x + radius * 16
    private val minZ = anchor.z - radius * 16
    private val maxZ = anchor.z + radius * 16
    private val tiles = buildList {
        for (x in Math.floorDiv(minX, 16)..Math.floorDiv(maxX, 16))
            for (z in Math.floorDiv(minZ, 16)..Math.floorDiv(maxZ, 16)) add(TileCoordinate(x, z))
    }.sortedWith(compareBy<TileCoordinate> {
        val x = it.chunkX.toLong() - Math.floorDiv(anchor.x, 16)
        val z = it.chunkZ.toLong() - Math.floorDiv(anchor.z, 16)
        x * x + z * z
    }.thenBy { it.chunkX }.thenBy { it.chunkZ })
    private val ranked = TreeSet<MetadataNote>(metadataRankComparator(anchor, targets))
    private data class Pin(val archiveRevision: Long, val guidanceRevision: Long,
                           val dirty: Boolean, val incarnation: WeakReference<TraceShardState>?)
    private val pins = ArrayList<Pin>(tiles.size)
    var pendingSinceTick: Long = startedTick
        private set
    private var validationIndex = 0
    private var tileIndex = 0
    private var inspectedInTile = 0
    private var ordinal = 0L
    private var identity: WeakReference<TraceShardState>? = null
    private var expectedRevision = 0L
    private var expectedGuidanceRevision = 0L
    private var expectedArchiveRevision = 0L
    private var expectedDirty = false
    private var expectedCount = 0
    var unavailable = false; private set
    var invalidated = false; private set
    internal val scanned: Boolean get() = tileIndex == tiles.size
    val complete: Boolean get() = scanned && validationIndex == pins.size
    val reservedBytes: Long = capacity.toLong() * 1024L + tiles.size.toLong() * 64L

    fun selected(): List<MetadataNote> { check(complete); return ranked.toList() }
    fun beginValidation(tick: Long) { check(complete); validationIndex = 0; pendingSinceTick = tick }

    private fun offerForSelection(note: MetadataNote) {
        ranked.add(note)
        if (ranked.size > capacity) ranked.pollLast()
    }

    /** Returns false on a budget boundary or typed archive unavailability; NEVER treats it as empty. */
    fun advance(storage: TraceStorageManager, budget: TraceStreamBudget, player: UUID): Boolean =
        advance(storage::maintenanceTile, budget, player)

    internal fun advance(readTile: (Int, Int) -> ShardRead<TraceShardState>,
                         budget: TraceStreamBudget, player: UUID): Boolean {
        unavailable = false
        while (tileIndex < tiles.size) {
            if (!budget.inspectTile(player)) return false
            val tile = tiles[tileIndex]
            when (val read = readTile(tile.chunkX, tile.chunkZ)) {
                ShardRead.Absent -> {
                    if (identity != null) { invalidated = true; return false }
                    pins.add(Pin(0L, 0L, false, null))
                    finishTile()
                }
                is ShardRead.Present -> {
                    val state = read.value
                    if (identity == null) {
                        identity = WeakReference(state)
                        expectedRevision = state.tileRevision(tile.chunkX, tile.chunkZ)
                        expectedGuidanceRevision = state.guidanceRevision()
                        expectedArchiveRevision = state.archiveRevision()
                        expectedDirty = state.dirty
                        expectedCount = state.annotationTileCount(tile.chunkX, tile.chunkZ)
                    } else if (identity?.get() !== state && inspectedInTile == 0 && !expectedDirty && !state.dirty &&
                        state.archiveRevision() == expectedArchiveRevision &&
                        state.tileRevision(tile.chunkX, tile.chunkZ) == expectedRevision &&
                        state.annotationTileCount(tile.chunkX, tile.chunkZ) == expectedCount) {
                        // No ordinal has been consumed yet; a reloaded identical immutable archive is safe.
                        identity = WeakReference(state)
                        expectedGuidanceRevision = state.guidanceRevision()
                    } else if (identity?.get() !== state || state.tileRevision(tile.chunkX, tile.chunkZ) != expectedRevision ||
                        state.guidanceRevision() != expectedGuidanceRevision ||
                        state.annotationTileCount(tile.chunkX, tile.chunkZ) != expectedCount) {
                        invalidated = true
                        return false // caller restarts; no mixed shard incarnation or tile revision
                    }
                    while (inspectedInTile < expectedCount) {
                        if (!budget.inspectRecord(player)) return false
                        val next = state.nextAnnotationInTile(tile.chunkX, tile.chunkZ, ordinal)
                            ?: run { invalidated = true; return false }
                        inspectedInTile++
                        ordinal = next.first
                        val note = next.second
                        if (note.team != GLOBAL_TEAM || note.position.x !in minX..maxX ||
                            note.position.y !in minY..maxY || note.position.z !in minZ..maxZ) continue
                        offerForSelection(MetadataNote.from(note))
                    }
                    pins.add(Pin(state.archiveRevision(), state.guidanceRevision(), state.dirty, WeakReference(state)))
                    finishTile()
                }
                else -> { unavailable = true; return false }
            }
        }
        // Final validation is itself resumable and charged; no snapshot is sent before ALL pins pass.
        while (validationIndex < pins.size) {
            if (!budget.inspectTile(player)) return false
            val tile = tiles[validationIndex]
            val pin = pins[validationIndex]
            when (val read = readTile(tile.chunkX, tile.chunkZ)) {
                ShardRead.Absent -> if (pin.incarnation != null) { invalidated = true; return false }
                is ShardRead.Present -> {
                    val state = read.value
                    if (pin.incarnation == null ||
                        (pin.incarnation.get() === state && state.guidanceRevision() != pin.guidanceRevision) ||
                        (pin.incarnation.get() !== state &&
                            (state.archiveRevision() != pin.archiveRevision || state.dirty || pin.dirty))) {
                        invalidated = true; return false
                    }
                }
                else -> { unavailable = true; return false }
            }
            validationIndex++
        }
        return true
    }

    private fun finishTile() {
        tileIndex++
        inspectedInTile = 0
        ordinal = 0L
        identity = null
        expectedRevision = 0L
        expectedGuidanceRevision = 0L
        expectedArchiveRevision = 0L
        expectedDirty = false
        expectedCount = 0
    }
}
