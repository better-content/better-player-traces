package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.domain.FootTrace
import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import com.bettercontent.betterplayertraces.util.TraceShardId
import java.util.UUID

/** Archive-wide compact summaries. All iteration and contribution changes have a server-tick budget. */
internal class TraceSummaryIndex {
    private class Summary(
        var generation: Long, var archiveRevision: Long,
        var survivingStrength: Double = 0.0, var survivingCount: Long = 0,
        val strengths: MutableMap<Float, Long> = mutableMapOf(),
        val echoOwners: MutableMap<UUID, Int> = mutableMapOf(),
        val noteIds: MutableSet<UUID> = linkedSetOf(),
    ) {
        fun trace(trace: FootTrace, sign: Int) {
            if (!trace.surviving) return
            survivingStrength += sign * trace.strength.toDouble()
            survivingCount += sign
            strengths.adjust(trace.strength, sign.toLong())
        }
        fun note(note: TraceAnnotation?, sign: Int) {
            if (note != null) {
                if (note.hasEcho) echoOwners.adjust(note.createdByInternal, sign)
                if (sign > 0) noteIds.add(note.id) else noteIds.remove(note.id)
            }
        }
    }
    private enum class Phase { READ_TRACES, READ_NOTES, REMOVE_STRENGTHS, REMOVE_OWNERS, REMOVE_IDS,
        ADD_STRENGTHS, ADD_OWNERS, ADD_IDS }
    private class Work(val id: TraceShardId, val state: TraceShardState) {
        val generation = state.currentGeneration()
        val archiveRevision = state.archiveRevision()
        val next = Summary(generation, archiveRevision)
        val traces = state.footTraceCursor()
        val notes = state.annotationCursor()
        var phase = Phase.READ_TRACES
        var entries: Iterator<Map.Entry<*, *>>? = null
        var ids: Iterator<UUID>? = null
    }
    private val known = linkedSetOf<TraceShardId>()
    private val pending = linkedSetOf<TraceShardId>()
    private val summaries = mutableMapOf<TraceShardId, Summary>()
    private var work: Work? = null
    private var totalStrength = 0.0
    private var totalCount = 0L
    private val strengths = StrengthRankTree()
    private val owners = mutableMapOf<UUID, Int>()
    private val noteShards = mutableMapOf<UUID, TraceShardId>()

    val workingId: TraceShardId? get() = work?.id
    fun schedule(id: TraceShardId) { known.add(id); pending.add(id) }
    fun acceptLoaded(id: TraceShardId, state: TraceShardState) {
        known.add(id)
        val cached = summaries[id]
        if (cached == null || cached.generation != state.currentGeneration() ||
            cached.archiveRevision != state.archiveRevision()) pending.add(id)
    }
    fun ready(): Boolean = pending.isEmpty() && work == null && summaries.size == known.size
    fun echoCount(owner: UUID): Int? = if (ready()) owners[owner] ?: 0 else null
    fun noteShard(id: UUID): TraceShardId? = if (ready()) noteShards[id] else null
    /** Positive hits in completed shards are usable before unrelated regions finish scanning. */
    fun knownNoteShard(id: UUID): TraceShardId? = noteShards[id]?.takeIf { it !in pending && it != work?.id }
    fun serverStrength(): Float? = if (ready()) totalStrength.toFloat() else null
    fun percentile(value: Float): Float? {
        if (!ready()) return null
        if (totalCount == 0L) return 0f
        val rank = strengths.countAtMost(value)
        return (rank.toDouble() / totalCount.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** A hot loaded shard remains queryable without re-reading the world after each capture or rain factor. */
    fun traceChanged(id: TraceShardId, before: Long, state: TraceShardState, old: FootTrace?, next: FootTrace?) {
        val cached = summaries[id]
        if (cached == null || cached.generation != before || work?.id == id) { schedule(id); return }
        old?.let { cached.trace(it, -1); adjustGlobalTrace(it, -1) }
        next?.let { cached.trace(it, 1); adjustGlobalTrace(it, 1) }
        cached.generation = state.currentGeneration()
        cached.archiveRevision = state.archiveRevision()
    }

    fun noteChanged(id: TraceShardId, before: Long, state: TraceShardState, old: TraceAnnotation?, next: TraceAnnotation?) {
        val cached = summaries[id]
        if (cached == null || cached.generation != before || work?.id == id) { schedule(id); return }
        old?.let { cached.note(it, -1); adjustGlobalOwner(it, -1); noteShards.remove(it.id, id) }
        next?.let { cached.note(it, 1); adjustGlobalOwner(it, 1); noteShards[it.id] = id }
        cached.generation = state.currentGeneration()
        cached.archiveRevision = state.archiveRevision()
    }

    fun nonAggregateChanged(id: TraceShardId, before: Long, state: TraceShardState) {
        val cached = summaries[id]
        if (cached == null || cached.generation != before || work?.id == id) { schedule(id); return }
        cached.generation = state.currentGeneration()
        cached.archiveRevision = state.archiveRevision()
    }

    /** At most [budget] archive records or summary entries per invocation; unknown reads stay pending. */
    fun tick(read: (TraceShardId) -> ShardRead<TraceShardState>, budget: Int = 4096): Int {
        var remaining = budget.coerceAtLeast(0)
        var unavailableAttempts = 0
        while (remaining > 0) {
            if (work == null) {
                val id = pending.firstOrNull() ?: break
                remaining-- // A missing or unavailable shard still costs a catalog/read visit.
                pending.remove(id)
                when (val result = read(id)) {
                    is ShardRead.Present -> work = Work(id, result.value)
                    ShardRead.Absent -> work = Work(id, TraceShardState())
                    else -> {
                        pending.add(id)
                        if (++unavailableAttempts >= minOf(4, pending.size)) break
                        continue
                    }
                }
            }
            val current = requireNotNull(work)
            if (current.phase == Phase.READ_TRACES || current.phase == Phase.READ_NOTES) {
                if (current.state.currentGeneration() != current.generation ||
                    current.state.archiveRevision() != current.archiveRevision) {
                    work = null
                    pending.add(current.id)
                    continue
                }
            }
            when (current.phase) {
                Phase.READ_TRACES -> if (current.traces.hasNext()) {
                    current.next.trace(current.traces.next(), 1); remaining--
                } else current.phase = Phase.READ_NOTES
                Phase.READ_NOTES -> if (current.notes.hasNext()) {
                    current.next.note(current.notes.next(), 1); remaining--
                } else {
                    current.phase = Phase.REMOVE_STRENGTHS
                    current.entries = summaries[current.id]?.strengths?.entries?.iterator()
                    summaries[current.id]?.let { totalStrength -= it.survivingStrength; totalCount -= it.survivingCount }
                }
                Phase.REMOVE_STRENGTHS -> if (current.entries?.hasNext() == true) {
                    val entry = current.entries!!.next()
                    @Suppress("UNCHECKED_CAST")
                    strengths.adjust(entry.key as Float, -(entry.value as Long)); remaining--
                } else { current.phase = Phase.REMOVE_OWNERS; current.entries = summaries[current.id]?.echoOwners?.entries?.iterator() }
                Phase.REMOVE_OWNERS -> if (current.entries?.hasNext() == true) {
                    val entry = current.entries!!.next()
                    owners.adjust(entry.key as UUID, -(entry.value as Int)); remaining--
                } else { current.phase = Phase.REMOVE_IDS; current.ids = summaries[current.id]?.noteIds?.iterator() }
                Phase.REMOVE_IDS -> if (current.ids?.hasNext() == true) {
                    noteShards.remove(current.ids!!.next(), current.id); remaining--
                } else {
                    current.phase = Phase.ADD_STRENGTHS
                    current.entries = current.next.strengths.entries.iterator()
                    totalStrength += current.next.survivingStrength
                    totalCount += current.next.survivingCount
                }
                Phase.ADD_STRENGTHS -> if (current.entries?.hasNext() == true) {
                    val entry = current.entries!!.next()
                    strengths.adjust(entry.key as Float, entry.value as Long); remaining--
                } else { current.phase = Phase.ADD_OWNERS; current.entries = current.next.echoOwners.entries.iterator() }
                Phase.ADD_OWNERS -> if (current.entries?.hasNext() == true) {
                    val entry = current.entries!!.next()
                    owners.adjust(entry.key as UUID, entry.value as Int); remaining--
                } else { current.phase = Phase.ADD_IDS; current.ids = current.next.noteIds.iterator() }
                Phase.ADD_IDS -> if (current.ids?.hasNext() == true) {
                    noteShards[current.ids!!.next()] = current.id; remaining--
                } else {
                    summaries[current.id] = current.next
                    work = null
                    // Capture/erase during merging cannot make stale bytes appear complete.
                    if (current.state.currentGeneration() != current.generation ||
                        current.state.archiveRevision() != current.archiveRevision) schedule(current.id)
                    else pending.remove(current.id) // duplicate load scheduling while the cursor was active
                }
            }
        }
        return budget.coerceAtLeast(0) - remaining
    }

    private fun adjustGlobalTrace(trace: FootTrace, sign: Int) {
        if (!trace.surviving) return
        totalStrength += sign * trace.strength.toDouble()
        totalCount += sign
        strengths.adjust(trace.strength, sign.toLong())
    }
    private fun adjustGlobalOwner(note: TraceAnnotation, sign: Int) {
        if (note.hasEcho) owners.adjust(note.createdByInternal, sign)
    }
}

private fun <K> MutableMap<K, Long>.adjust(key: K, delta: Long) {
    val count = (this[key] ?: 0L) + delta
    if (count == 0L) remove(key) else this[key] = count
}
private fun <K> MutableMap<K, Int>.adjust(key: K, delta: Int) {
    val count = (this[key] ?: 0) + delta
    if (count == 0) remove(key) else this[key] = count
}
