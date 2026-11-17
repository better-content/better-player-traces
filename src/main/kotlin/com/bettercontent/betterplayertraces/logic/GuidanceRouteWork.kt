package com.bettercontent.betterplayertraces.logic

import com.bettercontent.betterplayertraces.domain.FootTrace
import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import com.bettercontent.betterplayertraces.dto.GuidanceBuildResult
import com.bettercontent.betterplayertraces.dto.GuidancePointDto
import com.bettercontent.betterplayertraces.dto.GuidanceRouteDto
import net.minecraft.world.phys.Vec3
import java.util.TreeMap
import java.util.TreeSet
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Incremental canonical route graph. Every examined trace, edge, candidate, and result consumes a visit. */
internal class GuidanceRouteWork(private val player: Vec3) {
    private val sequences = TreeMap<UUID, TreeMap<Int, FootTrace>>(compareBy(UUID::toString))
    private val annotations = TreeMap<UUID, TraceAnnotation>(compareBy(UUID::toString))
    private val runs = mutableListOf<Run>()
    private val cells = mutableMapOf<Cell, MutableList<Anchor>>()
    private enum class Phase { BUILD, SEARCH, SELECT_ENDS, RESULTS, PATH, DONE }
    private var phase = Phase.BUILD
    private var sequenceIterator: Iterator<Map.Entry<UUID, TreeMap<Int, FootTrace>>>? = null
    private var traceIterator: Iterator<FootTrace>? = null
    private var currentRun: Run? = null
    private var annotationIterator: Iterator<TraceAnnotation>? = null
    private var note: TraceAnnotation? = null
    private var cellKeys: List<Cell> = emptyList()
    private var cellIndex = 0
    private var anchorIterator: Iterator<Anchor>? = null
    private val ends = mutableMapOf<Run, End>()
    private var endIterator: Iterator<Map.Entry<Run, End>>? = null
    private var best: Candidate? = null
    private val ranked = TreeSet<Candidate>(compareBy<Candidate> { it.distance }.thenBy { it.note.id.toString() })
    private var rankedIterator: Iterator<Candidate>? = null
    private val accepted = mutableListOf<GuidanceRouteDto>()
    private var acceptedPoints = 0
    private val distances = mutableMapOf<String, Double>()
    fun routeDistance(annotationId: String): Double? = distances[annotationId]
    private var pendingPath: Candidate? = null
    private val pathPoints = mutableListOf<GuidancePointDto>()
    var result: GuidanceBuildResult? = null
        private set

    fun addTrace(trace: FootTrace) {
        if (!trace.surviving) return
        val seq = sequences.getOrPut(trace.sequenceId) { TreeMap() }
        val old = seq[trace.sequenceIndex]
        if (old == null || trace.id.toString() < old.id.toString()) seq[trace.sequenceIndex] = trace
    }
    fun addAnnotation(note: TraceAnnotation) { annotations[note.id] = note }

    /** Returns actual visits, never more than [budget]. Call again on the next server tick. */
    fun step(budget: Int): Int {
        var remaining = budget.coerceAtLeast(0)
        while (remaining > 0 && phase != Phase.DONE) {
            when (phase) {
                Phase.BUILD -> {
                    if (sequenceIterator == null) sequenceIterator = sequences.entries.iterator()
                    if (traceIterator?.hasNext() != true) {
                        if (sequenceIterator!!.hasNext()) {
                            traceIterator = sequenceIterator!!.next().value.values.iterator()
                            currentRun = null
                            continue
                        }
                        annotationIterator = annotations.values.iterator()
                        phase = Phase.SEARCH
                        continue
                    }
                    val trace = traceIterator!!.next()
                    val prior = currentRun?.traces?.lastOrNull()
                    if (prior == null || trace.sequenceIndex != prior.sequenceIndex + 1 ||
                        squared(trace.x - prior.x, trace.y - prior.y, trace.z - prior.z) > 25.0) {
                        currentRun = Run(trace.sequenceId, runs.size).also { runs.add(it) }
                    }
                    val run = requireNotNull(currentRun)
                    val index = run.traces.size
                    run.traces.add(trace)
                    val distance = if (prior != null && index > 0)
                        sqrt(squared(trace.x - prior.x, trace.y - prior.y, trace.z - prior.z)) else 0.0
                    run.prefix.add((run.prefix.lastOrNull() ?: 0.0) + distance)
                    val dx = trace.x - player.x; val dy = trace.y - player.y; val dz = trace.z - player.z
                    val startDistance = squared(dx, dy, dz)
                    if (startDistance <= 25.0) {
                        if (startDistance < run.startDistance - EPSILON) {
                            run.startDistance = startDistance; run.starts.clear()
                        }
                        if (abs(startDistance - run.startDistance) <= EPSILON) run.starts.add(index)
                    }
                    cells.getOrPut(cell(trace.x, trace.y, trace.z)) { mutableListOf() }.add(Anchor(run, index, trace))
                    remaining--
                }
                Phase.SEARCH -> {
                    if (note == null) {
                        if (annotationIterator?.hasNext() != true) {
                            rankedIterator = ranked.iterator(); phase = Phase.RESULTS; continue
                        }
                        note = annotationIterator!!.next()
                        val p = requireNotNull(note).position
                        cellKeys = nearbyCells(p.x + 0.5, p.y.toDouble(), p.z + 0.5)
                        cellIndex = 0; anchorIterator = null; ends.clear(); best = null
                        remaining--
                        continue
                    }
                    if (anchorIterator?.hasNext() != true) {
                        if (cellIndex == cellKeys.size) {
                            endIterator = ends.entries.iterator(); phase = Phase.SELECT_ENDS; continue
                        }
                        anchorIterator = cells[cellKeys[cellIndex++]].orEmpty().iterator()
                        continue
                    }
                    val anchor = anchorIterator!!.next()
                    remaining--
                    val run = anchor.run
                    if (run.traces.size < 2 || run.starts.isEmpty()) continue
                    val p = requireNotNull(note).position
                    val d = squared(anchor.trace.x - (p.x + 0.5), anchor.trace.y - p.y, anchor.trace.z - (p.z + 0.5))
                    if (d > 25.0) continue
                    val choice = closestStart(run, anchor.index) ?: continue
                    val old = ends[run]
                    if (old == null || d < old.attachDistance - EPSILON ||
                        abs(d - old.attachDistance) <= EPSILON &&
                        (choice.distance < old.pathDistance - EPSILON ||
                            abs(choice.distance - old.pathDistance) <= EPSILON &&
                            (choice.start < old.start || choice.start == old.start && anchor.index < old.end))) {
                        ends[run] = End(d, choice.distance, choice.start, anchor.index)
                    }
                }
                Phase.SELECT_ENDS -> {
                    if (endIterator?.hasNext() != true) {
                        best?.let(ranked::add)
                        note = null; phase = Phase.SEARCH; continue
                    }
                    val (run, end) = endIterator!!.next()
                    remaining--
                    val candidate = Candidate(requireNotNull(note), run, end.start, end.end, end.pathDistance)
                    val previous = best
                    if (previous == null || candidate.distance < previous.distance - EPSILON ||
                        abs(candidate.distance - previous.distance) <= EPSILON &&
                        (run.sequenceId.toString() < previous.run.sequenceId.toString() ||
                            run.sequenceId == previous.run.sequenceId && run.ordinal < previous.run.ordinal)) best = candidate
                }
                Phase.RESULTS -> {
                    if (rankedIterator?.hasNext() != true) {
                        result = GuidanceBuildResult(ranked.size, accepted.toList(), accepted.size != ranked.size)
                        phase = Phase.DONE; continue
                    }
                    val candidate = rankedIterator!!.next()
                    remaining--
                    val count = abs(candidate.start - candidate.end) + 1
                    if (accepted.size >= GuidanceEngine.MAX_ROUTES || acceptedPoints + count > GuidanceEngine.MAX_TOTAL_POINTS) continue
                    pendingPath = candidate
                    pathPoints.clear()
                    phase = Phase.PATH
                }
                Phase.PATH -> {
                    val candidate = requireNotNull(pendingPath)
                    val index = candidate.start + pathPoints.size * if (candidate.start < candidate.end) 1 else -1
                    val trace = candidate.run.traces[index]
                    pathPoints.add(GuidancePointDto(trace.x, trace.y, trace.z))
                    remaining--
                    if (pathPoints.size == abs(candidate.start - candidate.end) + 1) {
                        accepted.add(GuidanceRouteDto(candidate.note.id.toString(), candidate.note.revision, pathPoints.toList()))
                        distances[candidate.note.id.toString()] = candidate.distance
                        acceptedPoints += pathPoints.size
                        pendingPath = null
                        phase = Phase.RESULTS
                    }
                }
                Phase.DONE -> Unit
            }
        }
        return budget.coerceAtLeast(0) - remaining
    }

    private data class Cell(val x: Int, val y: Int, val z: Int)
    private class Run(val sequenceId: UUID, val ordinal: Int) {
        val traces = mutableListOf<FootTrace>()
        val prefix = mutableListOf<Double>()
        var startDistance = Double.POSITIVE_INFINITY
        val starts = mutableListOf<Int>()
    }
    private data class Anchor(val run: Run, val index: Int, val trace: FootTrace)
    private data class Start(val start: Int, val distance: Double)
    private data class End(val attachDistance: Double, val pathDistance: Double, val start: Int, val end: Int)
    private data class Candidate(val note: TraceAnnotation, val run: Run, val start: Int, val end: Int, val distance: Double)

    private fun closestStart(run: Run, end: Int): Start? {
        val i = run.starts.binarySearch(end)
        val point = if (i >= 0) i else -(i + 1)
        val options = listOf(point - 1, point, point + 1).filter { it in run.starts.indices }
            .map { run.starts[it] }.filter { it != end }
        return options.map { Start(it, abs(run.prefix[it] - run.prefix[end])) }
            .minWithOrNull(compareBy<Start> { it.distance }.thenBy { it.start })
    }
    private fun cell(x: Double, y: Double, z: Double) = Cell(floor(x / 5.0).toInt(), floor(y / 5.0).toInt(), floor(z / 5.0).toInt())
    private fun nearbyCells(x: Double, y: Double, z: Double): List<Cell> {
        val origin = cell(x, y, z)
        return buildList { for (dx in -1..1) for (dy in -1..1) for (dz in -1..1)
            add(Cell(origin.x + dx, origin.y + dy, origin.z + dz)) }
    }
    private fun squared(x: Double, y: Double, z: Double) = x * x + y * y + z * z
    companion object { private const val EPSILON = 1.0e-9 }
}
