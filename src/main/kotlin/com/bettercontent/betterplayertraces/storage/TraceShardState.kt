package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import com.bettercontent.betterplayertraces.domain.FootTrace
import com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM
import com.bettercontent.betterplayertraces.domain.TraceKind
import net.minecraft.core.BlockPos
import java.util.TreeMap
import java.util.UUID

/** DataOutput.writeUTF's modified UTF-8 length, including its two-byte prefix. */
private fun modifiedUtfSize(text: String): Long = 2L + text.sumOf { char ->
    when { char.code == 0 -> 2L; char.code <= 0x7f -> 1L; char.code <= 0x7ff -> 2L; else -> 3L }
}
internal fun traceStorageBytes(trace: FootTrace): Long =
    88L + modifiedUtfSize(trace.id.toString()) + modifiedUtfSize(trace.levelKey) +
        (trace.support?.let { 12L + modifiedUtfSize(it.blockId.toString()) } ?: 0L)

internal fun annotationStorageBytes(note: TraceAnnotation): Long =
    76L + modifiedUtfSize(note.id.toString()) + modifiedUtfSize(note.text) + modifiedUtfSize(note.icon) +
        modifiedUtfSize(note.team.id) + (note.encodedEcho?.size ?: 0).toLong()

class TraceShardState {
    internal val footTraces: TraceRecordSet = TraceRecordSet()
    /** Incrementally maintained encoded-record admission estimate; never walk history per capture. */
    internal var estimatedFootBytes: Long = 0
        private set
    internal var estimatedNoteBytes: Long = 0
        private set
    internal val annotations: AnnotationRecordSet = AnnotationRecordSet()
    internal val seenStates: SeenRecordSet = SeenRecordSet()
    /** Last published mutation identity per note, including deleted notes. */
    internal val noteReceipts: MutableMap<UUID, UUID> = linkedMapOf()
    private val tileRevisions: MutableMap<TraceTileId, Long> = mutableMapOf()
    private val tracesByTile: MutableMap<TraceTileId, LinkedHashMap<UUID, FootTrace>> = mutableMapOf()
    private val livingTraceCounts: MutableMap<TraceTileId, Int> = mutableMapOf()
    private val traceOrdinalById: MutableMap<UUID, Long> = mutableMapOf()
    private val traceIdsByTileOrdinal: MutableMap<TraceTileId, TreeMap<Long, UUID>> = mutableMapOf()
    private var nextTraceOrdinal = 1L
    /** Rebuildable temporal candidates; spatial bounds remain an exact final filter. */
    private val tracesByCreatedAt: NavigableTraceIndex = NavigableTraceIndex()
    private val tracesById: MutableMap<UUID, FootTrace> = mutableMapOf()
    private val tracesBySupport: MutableMap<BlockPos, MutableSet<UUID>> = mutableMapOf()
    private val tracesByPosition: MutableMap<BlockPos, MutableSet<UUID>> = mutableMapOf()
    private val annotationsById: MutableMap<UUID, TraceAnnotation> = mutableMapOf()
    private val annotationsByTile: MutableMap<TraceTileId, LinkedHashMap<UUID, TraceAnnotation>> = mutableMapOf()
    private val noteOrdinalById: MutableMap<UUID, Long> = mutableMapOf()
    private val noteIdsByTileOrdinal: MutableMap<TraceTileId, TreeMap<Long, UUID>> = mutableMapOf()
    private var nextNoteOrdinal = 1L
    private val seenByAnnotation: MutableMap<UUID, MutableSet<Pair<UUID, UUID>>> = mutableMapOf()
    @Volatile
    var dirty: Boolean = false
        private set
    private var generation: Long = 0
    private var nextTileRevision: Long = 1
    private var archiveRevision: Long = 0
    /** Path-invalidating revision. Additive captures and non-erasing rain retain valid routes. */
    private var guidanceRevision: Long = 0

    @Synchronized
    fun markDirty() {
        generation++
        archiveRevision++
        dirty = true
    }

    @Synchronized
    fun snapshot(): Pair<TraceShardState, Long> {
        val copy = TraceShardState()
        copy.footTraces.addAll(footTraces)
        copy.estimatedFootBytes = estimatedFootBytes
        copy.estimatedNoteBytes = estimatedNoteBytes
        copy.annotations.addAll(annotations.map { it.copy() })
        copy.seenStates.addAll(seenStates)
        copy.noteReceipts.putAll(noteReceipts)
        copy.footTraces.forEach(copy::indexTrace)
        copy.annotations.forEach(copy::indexAnnotation)
        copy.seenStates.forEach(copy::indexSeen)
        copy.tileRevisions.putAll(tileRevisions)
        copy.nextTileRevision = nextTileRevision
        copy.archiveRevision = archiveRevision
        copy.guidanceRevision = guidanceRevision
        return copy to generation
    }

    @Synchronized
    internal fun archiveRevision(): Long = archiveRevision

    @Synchronized
    internal fun setArchiveRevision(revision: Long) {
        require(revision >= 0) { "negative archive revision" }
        archiveRevision = revision
    }

    @Synchronized
    fun clearDirtyIfUnchanged(snapshotGeneration: Long) {
        if (generation == snapshotGeneration) dirty = false
    }

    @Synchronized
    internal fun currentGeneration(): Long = generation

    @Synchronized
    internal fun guidanceRevision(): Long = guidanceRevision

    @Synchronized
    internal fun invalidateGuidance() { guidanceRevision++ }

    @Synchronized
    fun footTracesSnapshot(): List<FootTrace> = footTraces.toList()

    @Synchronized
    internal fun containsFootTrace(id: UUID): Boolean = footTraces.byId(id) != null

    @Synchronized
    internal fun traceById(id: UUID): FootTrace? = tracesById[id]

    @Synchronized
    internal fun addLoadedFootTrace(trace: FootTrace) {
        require(!containsFootTrace(trace.id)) { "duplicate trace ID in archive" }
        footTraces += trace
        estimatedFootBytes += traceStorageBytes(trace)
        indexTrace(trace)
        tileRevisions.putIfAbsent(TraceTileId.containing(trace.blockPos), nextTileRevision++)
    }

    @Synchronized
    internal fun addLoadedAnnotation(annotation: TraceAnnotation) {
        require(annotation.id !in annotationsById) { "duplicate note ID in archive" }
        annotations += annotation
        estimatedNoteBytes += annotationStorageBytes(annotation)
        indexAnnotation(annotation)
    }

    @Synchronized
    internal fun replaceLoadedTileRevisions(revisions: Map<TraceTileId, Long>) {
        tileRevisions.clear()
        tileRevisions.putAll(revisions)
        nextTileRevision = (revisions.values.maxOrNull() ?: 0L) + 1L
    }

    @Synchronized
    internal fun tileRevisionsSnapshot(): Map<TraceTileId, Long> = tileRevisions.toMap()

    @Synchronized
    internal fun tileRevisionCount(): Int = tileRevisions.size

    @Synchronized
    fun addFootTrace(trace: FootTrace) {
        require(trace.kind != TraceKind.FOOTPRINT || trace.support != null) { "footprint trace has no supporting block" }
        require(!containsFootTrace(trace.id)) { "duplicate trace ID" }
        footTraces += trace
        estimatedFootBytes += traceStorageBytes(trace)
        indexTrace(trace)
        markTraceTilesDirty(setOf(TraceTileId.containing(trace.blockPos)))
    }

    /** Atomically inserts a capture unless its stable ID is already present. */
    @Synchronized
    internal fun addFootTraceIfAbsent(trace: FootTrace): Boolean {
        if (tracesById.containsKey(trace.id)) return false
        addFootTrace(trace)
        return true
    }

    @Synchronized
    fun tileRevision(chunkX: Int, chunkZ: Int): Long = tileRevisions[TraceTileId(chunkX, chunkZ)] ?: 0L

    @Synchronized
    internal fun livingTraceTileCount(chunkX: Int, chunkZ: Int): Int = livingTraceCounts[TraceTileId(chunkX, chunkZ)] ?: 0

    @Synchronized
    fun queryTraceTile(chunkX: Int, chunkZ: Int): List<FootTrace> {
        val tile = TraceTileId(chunkX, chunkZ)
        return tracesByTile[tile]?.values.orEmpty().filter { it.surviving }
    }

    @Synchronized
    fun traceTileSnapshot(chunkX: Int, chunkZ: Int): TraceTileSnapshot = TraceTileSnapshot(
        TraceTileId(chunkX, chunkZ),
        tileRevision(chunkX, chunkZ),
        queryTraceTile(chunkX, chunkZ),
    )

    @Synchronized
    fun annotationsSnapshot(): List<TraceAnnotation> = annotations.toList()

    @Synchronized
    internal fun seenStatesSnapshot(): List<SeenStateRecord> = seenStates.toList()

    @Synchronized
    internal fun seenRevision(player: UUID, annotation: UUID): Int = seenStates.byKey(annotation, player)?.highestRevision ?: 0

    @Synchronized
    internal fun indexSeen(record: SeenStateRecord) {
        seenByAnnotation.getOrPut(record.annotationId) { linkedSetOf() }.add(record.annotationId to record.playerId)
    }

    @Synchronized
    internal fun removeSeenForAnnotation(id: UUID) {
        seenByAnnotation.remove(id)?.forEach { (_, player) ->
            seenStates.byKey(id, player)?.let(seenStates::remove)
        }
    }

    @Synchronized
    internal fun footTraceCursor(): Iterator<FootTrace> = footTraces.iterator()

    @Synchronized
    internal fun annotationCursor(): Iterator<TraceAnnotation> = annotations.iterator()

    /** Stable resumable tile cursor: O(log(tile records)) even after a physical erasure. */
    @Synchronized
    internal fun nextTraceInTile(chunkX: Int, chunkZ: Int, afterOrdinal: Long): Pair<Long, FootTrace>? {
        val entry = traceIdsByTileOrdinal[TraceTileId(chunkX, chunkZ)]?.higherEntry(afterOrdinal) ?: return null
        return entry.key to requireNotNull(tracesById[entry.value])
    }

    @Synchronized
    internal fun annotationTileCount(chunkX: Int, chunkZ: Int): Int = annotationsByTile[TraceTileId(chunkX, chunkZ)]?.size ?: 0

    @Synchronized
    internal fun nextAnnotationInTile(chunkX: Int, chunkZ: Int, afterOrdinal: Long): Pair<Long, TraceAnnotation>? {
        val entry = noteIdsByTileOrdinal[TraceTileId(chunkX, chunkZ)]?.higherEntry(afterOrdinal) ?: return null
        return entry.key to requireNotNull(annotationsById[entry.value])
    }

    @Synchronized
    fun counts(): Triple<Int, Int, Int> = Triple(footTraces.size, annotations.size, seenStates.size)

    @Synchronized
    fun nearbyFootTraces(boundsMin: BlockPos, boundsMax: BlockPos): List<FootTrace> {
        return tracesInTiles(boundsMin, boundsMax).filter { t ->
            val p = t.blockPos
            p.x in boundsMin.x..boundsMax.x && p.y in boundsMin.y..boundsMax.y && p.z in boundsMin.z..boundsMax.z && t.surviving
        }
    }

    /**
     * Returns traces newer than [createdAfter] using the per-shard temporal index.
     * The tile index is deliberately not used here because the temporal candidate
     * set is smaller for return summaries; exact spatial and surviving checks stay
     * identical to [nearbyFootTraces].
     */
    @Synchronized
    fun nearbyFootTracesAfter(boundsMin: BlockPos, boundsMax: BlockPos, createdAfter: Long): List<FootTrace> {
        return tracesByCreatedAt.after(createdAfter).filter { t ->
            val p = t.blockPos
            p.x in boundsMin.x..boundsMax.x && p.y in boundsMin.y..boundsMax.y &&
                p.z in boundsMin.z..boundsMax.z && t.surviving
        }
    }

    @Synchronized
    fun nearbyAnnotations(boundsMin: BlockPos, boundsMax: BlockPos): List<TraceAnnotation> {
        return annotationsInTiles(boundsMin, boundsMax).filter { a ->
            val p = a.position
            p.x in boundsMin.x..boundsMax.x && p.y in boundsMin.y..boundsMax.y && p.z in boundsMin.z..boundsMax.z && a.team == GLOBAL_TEAM
        }
    }

    @Synchronized
    fun removeAtPosition(position: BlockPos): Int = eraseRecords(tracesByPosition[position].orEmpty().mapNotNull(tracesById::get))

    @Synchronized
    fun removeBySupport(position: BlockPos): Int = eraseRecords(tracesBySupport[position].orEmpty().mapNotNull(tracesById::get))

    @Synchronized
    internal fun removeTraceById(id: UUID): Int = eraseRecords(listOfNotNull(tracesById[id]))

    @Synchronized
    fun pruneInvalidSupports(chunkX: Int, chunkZ: Int, isValid: (com.bettercontent.betterplayertraces.domain.TraceSupport) -> Boolean): Int =
        eraseRecords(tracesByTile[TraceTileId(chunkX, chunkZ)]?.values.orEmpty().filter { trace ->
            trace.support?.let { !isValid(it) } == true
        })

    @Synchronized
    fun removeFootTraces(boundsMin: BlockPos, boundsMax: BlockPos): Int =
        eraseRecords(tracesInTiles(boundsMin, boundsMax).filter { trace ->
            val pos = trace.blockPos
            pos.x in boundsMin.x..boundsMax.x && pos.y in boundsMin.y..boundsMax.y && pos.z in boundsMin.z..boundsMax.z
        })

    @Synchronized
    fun updateWeakness(position: BlockPos, factor: Double) {
        require(factor.isFinite() && factor >= 0.0) { "erosion factor must be finite and non-negative" }
        val candidates = tracesInTiles(position.offset(-9, 0, -9), position.offset(9, 0, 9))
        val changedTiles = mutableSetOf<TraceTileId>()
        var erased = false
        for (trace in candidates) {
            val p = trace.blockPos
            val dx = p.x - position.x
            val dz = p.z - position.z
            if (dx * dx + dz * dz > 80) continue
            val next = trace.strength * factor.toFloat()
            if (next <= 0.04f) {
                if (footTraces.remove(trace)) {
                    removeIndexedTrace(trace)
                    estimatedFootBytes -= traceStorageBytes(trace)
                    changedTiles += TraceTileId.containing(trace.blockPos)
                    erased = true
                }
            } else if (next != trace.strength) {
                val updated = trace.copy(strength = next)
                footTraces.replace(updated)
                replaceIndexedTrace(trace, updated)
                changedTiles += TraceTileId.containing(trace.blockPos)
            }
        }
        if (erased) invalidateGuidance()
        if (changedTiles.isNotEmpty()) markTraceTilesDirty(changedTiles)
    }

    @Synchronized
    fun updateTraceWeakness(traceId: java.util.UUID, factor: Double): Boolean {
        require(factor.isFinite() && factor >= 0.0) { "erosion factor must be finite and non-negative" }
        val trace = footTraces.byId(traceId)?.takeIf { it.surviving } ?: return false
        val next = trace.strength * factor.toFloat()
        if (next <= 0.04f) {
            footTraces.remove(trace)
            removeIndexedTrace(trace)
            estimatedFootBytes -= traceStorageBytes(trace)
            invalidateGuidance()
        } else {
            if (next == trace.strength) return false
            val updated = trace.copy(strength = next)
            footTraces.replace(updated)
            replaceIndexedTrace(trace, updated)
        }
        markTraceTilesDirty(setOf(TraceTileId.containing(trace.blockPos)))
        return true
    }

    @Synchronized
    fun annotationById(id: java.util.UUID): TraceAnnotation? = annotationsById[id]

    /** Called only after a note publication completed; never replaces intervening footprint changes. */
    @Synchronized
    internal fun applyPublishedAnnotation(id: UUID, replacement: TraceAnnotation?, requestId: UUID) {
        val old = annotationsById[id]
        val ordinal = noteOrdinalById[id]
        if (old != null) {
            estimatedNoteBytes -= annotationStorageBytes(old)
            annotations.remove(old)
            removeIndexedAnnotation(old)
        }
        if (replacement != null) {
            val inserted = replacement.copy()
            annotations.add(inserted)
            estimatedNoteBytes += annotationStorageBytes(inserted)
            if (ordinal != null) noteOrdinalById[id] = ordinal
            indexAnnotation(inserted)
        } else {
            removeSeenForAnnotation(id)
        }
        noteReceipts[id] = requestId
        invalidateGuidance()
        markDirty()
    }

    @Synchronized
    fun putAnnotation(annotation: TraceAnnotation) {
        require(annotation.id !in annotationsById) { "duplicate note ID" }
        annotations.add(annotation)
        estimatedNoteBytes += annotationStorageBytes(annotation)
        indexAnnotation(annotation)
        invalidateGuidance()
        markDirty()
    }

    @Synchronized
    fun putAnnotationIfAbsent(annotation: TraceAnnotation): Boolean {
        val existing = annotationsById[annotation.id]
        if (existing != null) return existing == annotation
        annotations.add(annotation)
        estimatedNoteBytes += annotationStorageBytes(annotation)
        indexAnnotation(annotation)
        invalidateGuidance()
        markDirty()
        return true
    }

    @Synchronized
    fun removeAnnotation(id: java.util.UUID): Boolean {
        val removed = annotationsById[id] ?: return false
        annotations.remove(removed)
        estimatedNoteBytes -= annotationStorageBytes(removed)
        removeIndexedAnnotation(removed)
        removeSeenForAnnotation(id)
        invalidateGuidance()
        markDirty()
        return true
    }

    @Synchronized
    fun updateAnnotation(id: java.util.UUID, text: String?, icon: String?, color: Int?): TraceAnnotation? {
        val current = annotationsById[id] ?: return null
        val updated = current.copy(
            text = text ?: current.text,
            icon = icon ?: current.icon,
            color = color ?: current.color,
            revision = current.revision + 1,
        )
        annotations.replace(updated)
        estimatedNoteBytes += annotationStorageBytes(updated) - annotationStorageBytes(current)
        replaceIndexedAnnotation(current, updated)
        invalidateGuidance()
        markDirty()
        return updated
    }

    /** Replaces an annotation with its already revisioned outage-overlay value. */
    @Synchronized
    internal fun replaceAnnotation(annotation: TraceAnnotation): Boolean {
        val current = annotationsById[annotation.id] ?: return false
        require(annotation.position == current.position && annotation.targetBlock == current.targetBlock
            && annotation.createdByInternal == current.createdByInternal && annotation.team == current.team) {
            "annotation edit cannot change identity, location, owner, or team"
        }
        if (annotation == current) return true
        if (annotation.revision <= current.revision) return false
        annotations.replace(annotation)
        estimatedNoteBytes += annotationStorageBytes(annotation) - annotationStorageBytes(current)
        replaceIndexedAnnotation(current, annotation)
        invalidateGuidance()
        markDirty()
        return true
    }

    private fun eraseRecords(matches: Collection<FootTrace>): Int {
        if (matches.isEmpty()) return 0
        val removed = matches.filter { footTraces.remove(it) }
        if (removed.isEmpty()) return 0
        removed.forEach { removeIndexedTrace(it); estimatedFootBytes -= traceStorageBytes(it) }
        invalidateGuidance()
        markTraceTilesDirty(removed.mapTo(mutableSetOf()) { TraceTileId.containing(it.blockPos) })
        return removed.size
    }

    private fun tracesInTiles(boundsMin: BlockPos, boundsMax: BlockPos): List<FootTrace> = buildList {
        for (chunkX in Math.floorDiv(boundsMin.x, 16)..Math.floorDiv(boundsMax.x, 16)) {
            for (chunkZ in Math.floorDiv(boundsMin.z, 16)..Math.floorDiv(boundsMax.z, 16)) {
                addAll(tracesByTile[TraceTileId(chunkX, chunkZ)]?.values.orEmpty())
            }
        }
    }

    private fun annotationsInTiles(boundsMin: BlockPos, boundsMax: BlockPos): List<TraceAnnotation> = buildList {
        for (chunkX in Math.floorDiv(boundsMin.x, 16)..Math.floorDiv(boundsMax.x, 16)) {
            for (chunkZ in Math.floorDiv(boundsMin.z, 16)..Math.floorDiv(boundsMax.z, 16)) {
                addAll(annotationsByTile[TraceTileId(chunkX, chunkZ)]?.values.orEmpty())
            }
        }
    }

    private fun indexTrace(trace: FootTrace) {
        val tile = TraceTileId.containing(trace.blockPos)
        tracesByTile.getOrPut(tile) { linkedMapOf() }[trace.id] = trace
        if (trace.surviving) livingTraceCounts[tile] = (livingTraceCounts[tile] ?: 0) + 1
        val ordinal = traceOrdinalById.getOrPut(trace.id) { nextTraceOrdinal++ }
        traceIdsByTileOrdinal.getOrPut(tile) { TreeMap() }[ordinal] = trace.id
        tracesByCreatedAt.add(trace)
        tracesById[trace.id] = trace
        tracesByPosition.getOrPut(trace.blockPos) { linkedSetOf() }.add(trace.id)
        trace.support?.let { tracesBySupport.getOrPut(it.position) { linkedSetOf() }.add(trace.id) }
    }

    private fun indexAnnotation(annotation: TraceAnnotation) {
        val tile = TraceTileId.containing(annotation.position)
        annotationsByTile.getOrPut(tile) { linkedMapOf() }[annotation.id] = annotation
        val ordinal = noteOrdinalById.getOrPut(annotation.id) { nextNoteOrdinal++ }
        noteIdsByTileOrdinal.getOrPut(tile) { TreeMap() }[ordinal] = annotation.id
        annotationsById[annotation.id] = annotation
    }

    private fun replaceIndexedAnnotation(old: TraceAnnotation, updated: TraceAnnotation) {
        val ordinal = noteOrdinalById[old.id]
        removeIndexedAnnotation(old)
        if (ordinal != null) noteOrdinalById[updated.id] = ordinal
        indexAnnotation(updated)
    }

    private fun removeIndexedAnnotation(annotation: TraceAnnotation) {
        annotationsById.remove(annotation.id)
        val tile = TraceTileId.containing(annotation.position)
        noteOrdinalById.remove(annotation.id)?.let { ordinal ->
            noteIdsByTileOrdinal[tile]?.let { it.remove(ordinal); if (it.isEmpty()) noteIdsByTileOrdinal.remove(tile) }
        }
        annotationsByTile[tile]?.let { indexed ->
            indexed.remove(annotation.id, annotation)
            if (indexed.isEmpty()) annotationsByTile.remove(tile)
        }
    }

    private fun replaceIndexedTrace(old: FootTrace, updated: FootTrace) {
        val ordinal = traceOrdinalById[old.id]
        removeIndexedTrace(old)
        if (ordinal != null) traceOrdinalById[updated.id] = ordinal
        indexTrace(updated)
    }

    private fun removeIndexedTrace(trace: FootTrace) {
        val tile = TraceTileId.containing(trace.blockPos)
        tracesByTile[tile]?.let { indexed ->
            if (indexed.remove(trace.id, trace) && trace.surviving) {
                val remaining = (livingTraceCounts[tile] ?: 1) - 1
                if (remaining == 0) livingTraceCounts.remove(tile) else livingTraceCounts[tile] = remaining
            }
            if (indexed.isEmpty()) tracesByTile.remove(tile)
        }
        tracesByCreatedAt.remove(trace)
        tracesById.remove(trace.id)
        traceOrdinalById.remove(trace.id)?.let { ordinal ->
            traceIdsByTileOrdinal[tile]?.let { it.remove(ordinal); if (it.isEmpty()) traceIdsByTileOrdinal.remove(tile) }
        }
        tracesByPosition[trace.blockPos]?.let { it.remove(trace.id); if (it.isEmpty()) tracesByPosition.remove(trace.blockPos) }
        trace.support?.let { support -> tracesBySupport[support.position]?.let { ids ->
            ids.remove(trace.id); if (ids.isEmpty()) tracesBySupport.remove(support.position)
        } }
    }

    private class NavigableTraceIndex {
        private val byCreatedAt = TreeMap<Long, LinkedHashMap<UUID, FootTrace>>()

        fun add(trace: FootTrace) {
            byCreatedAt.getOrPut(trace.createdAt) { linkedMapOf() }[trace.id] = trace
        }

        fun remove(trace: FootTrace) {
            byCreatedAt[trace.createdAt]?.let { bucket ->
                bucket.remove(trace.id)
                if (bucket.isEmpty()) byCreatedAt.remove(trace.createdAt)
            }
        }

        fun after(createdAfter: Long): List<FootTrace> = buildList {
            byCreatedAt.tailMap(createdAfter, false).values.forEach { addAll(it.values) }
        }
    }

    private fun markTraceTilesDirty(tiles: Set<TraceTileId>) {
        if (tiles.isEmpty()) return
        tiles.forEach { tileRevisions[it] = nextTileRevision++ }
        markDirty()
    }
}
