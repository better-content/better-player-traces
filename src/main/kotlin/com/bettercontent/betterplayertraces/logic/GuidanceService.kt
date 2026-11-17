package com.bettercontent.betterplayertraces.logic

import com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM
import com.bettercontent.betterplayertraces.dto.GuidanceBuildResult
import com.bettercontent.betterplayertraces.dto.GuidanceRouteDto
import com.bettercontent.betterplayertraces.storage.ShardRead
import com.bettercontent.betterplayertraces.storage.TraceReadUnavailableException
import com.bettercontent.betterplayertraces.storage.TraceStorageManager
import com.bettercontent.betterplayertraces.storage.TraceShardState
import com.bettercontent.betterplayertraces.util.Geometry
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import com.mojang.logging.LogUtils
import java.util.UUID
import java.lang.ref.WeakReference
import kotlin.math.sqrt

/** Queries only publish completed snapshots. Server ticks own ALL route work and XP observations. */
class GuidanceService(
    private val storage: TraceStorageManager,
    private val progress: GuidanceProgressTracker = GuidanceProgressTracker(),
) {
    private val log = LogUtils.getLogger()
    private data class Pin(val authority: WeakReference<TraceShardState>, val guidanceRevision: Long, val archiveRevision: Long)
    private data class Completed(val center: Vec3, val pins: Map<Pair<Int, Int>, Pin>, val result: GuidanceBuildResult)
    private data class Tile(val x: Int, val z: Int)
    private class Source(val center: Vec3, val min: BlockPos, val max: BlockPos) {
        val pins = linkedMapOf<Pair<Int, Int>, Pin>()
        val tiles = buildList {
            for (x in (min.x shr 4)..(max.x shr 4)) for (z in (min.z shr 4)..(max.z shr 4)) add(Tile(x, z))
        }
        val builder = GuidanceRouteWork(center)
        var tileIndex = 0
        var traceOrdinal = 0L
        var noteOrdinal = 0L
        var notesPhase = false
        var collected = false
        var tooDense = false
        var traceCount = 0
        var noteCount = 0
    }
    private class Follow(val route: GuidanceRouteDto, var remaining: Double, var index: Int = 0, var observed: Boolean = false)
    private class Session {
        var active: Source? = null
        var completed: Completed? = null
        var visible: GuidanceBuildResult? = null
        var follow: Follow? = null
        var lastBuildTick = -1
        var retryAfterTick = 0
        var lastFollowTick = 0
        var lastPosition: Vec3? = null
    }
    private val sessions = mutableMapOf<UUID, Session>()
    private var rotation = 0

    fun query(player: ServerPlayer, radiusBlocks: Int = RADIUS_BLOCKS): GuidanceBuildResult {
        if (radiusBlocks != RADIUS_BLOCKS) return NOT_READY
        val session = sessions[player.uuid] ?: return NOT_READY
        val source = session.completed ?: return NOT_READY
        if (source.center.distanceTo(player.position()) > RADIUS_BLOCKS || !pinsValid(source.pins)) return NOT_READY
        return source.result
    }

    /** Fair slices across players; caller provides the remaining *server-wide* 4096-visit budget. */
    fun tick(level: ServerLevel, players: List<ServerPlayer>, tick: Int, budget: Int): Int {
        if (budget <= 0 || players.isEmpty()) return 0
        val ordered = players.sortedBy { it.uuid.toString() }
        var remaining = budget
        var processed = 0
        while (remaining > 0 && processed < ordered.size) {
            val player = ordered[(rotation + processed) % ordered.size]
            val session = sessions.getOrPut(player.uuid) { Session() }
            val slice = minOf(remaining, PLAYER_SLICE)
            val used = advance(level, player, session, tick, slice)
            remaining -= used
            processed++
        }
        rotation = (rotation + processed) % ordered.size
        return budget - remaining
    }

    private fun advance(level: ServerLevel, player: ServerPlayer, session: Session, tick: Int, budget: Int): Int {
        var remaining = budget
        val position = player.position()
        if (session.lastPosition?.distanceTo(position)?.let { it > GuidanceProgressTracker.MAX_OBSERVATION_MOVEMENT } == true) {
            // Detect per-tick discontinuities separately from elapsed-time prorating.
            session.active = null; session.completed = null; session.visible = null; session.follow = null
            progress.clear(player.uuid)
        }
        session.lastPosition = position
        val completed = session.completed
        if (completed != null && (completed.center.distanceTo(player.position()) > RADIUS_BLOCKS || !pinsValid(completed.pins))) {
            session.completed = null; session.visible = null; session.follow = null
        }
        if (tick % CADENCE_TICKS == 0) {
            session.follow?.let { follow ->
                val spent = observe(player, follow, remaining, tick - session.lastFollowTick)
                if (follow.observed) session.lastFollowTick = tick
                remaining -= spent
            }
            val needsBuild = session.completed == null ||
                session.completed!!.center.distanceTo(player.position()) >= REBUILD_DISTANCE ||
                session.completed!!.result.routes.isEmpty() && tick - session.lastBuildTick >= EMPTY_RETRY_TICKS
            if (session.active == null && needsBuild && session.lastBuildTick != tick && tick >= session.retryAfterTick) {
                session.active = source(level, player.position())
                session.lastBuildTick = tick
            }
        }
        val active = session.active ?: return budget - remaining
        if (!pinsValid(active.pins) || active.center.distanceTo(player.position()) > MAX_SNAPSHOT_MOVEMENT) {
            session.active = null
            return budget - remaining
        }
        if (remaining <= 0) return budget
        if (!active.collected) {
            val spent = collect(player, active, remaining)
            remaining -= spent
        }
        if (active.tooDense) {
            // A local archive may contain millions of records. Never retain an unbounded
            // per-player graph or award from a deliberately incomplete selection.
            session.active = null; session.completed = null; session.visible = null; session.follow = null
            session.retryAfterTick = tick + 100
            return budget - remaining
        }
        if (active.collected && remaining > 0) remaining -= active.builder.step(remaining)
        val result = active.builder.result
        if (result != null) {
            session.active = null
            if (pinsValid(active.pins) && active.center.distanceTo(player.position()) <= MAX_SNAPSHOT_MOVEMENT) {
                session.completed = Completed(active.center, active.pins.toMap(), result)
                session.visible = result
                session.follow = result.routes.firstOrNull()?.let { route ->
                    val remaining = requireNotNull(active.builder.routeDistance(route.targetAnnotationId))
                    progress.seed(player.uuid, player.position(), route, remaining)
                    session.lastFollowTick = tick
                    Follow(route, remaining)
                }
            }
        }
        return budget - remaining
    }

    /** Both tile probes and examined records consume visits; unknown archive reads pause, not erase. */
    private fun collect(player: ServerPlayer, source: Source, budget: Int): Int {
        var visited = 0
        while (visited < budget && source.tileIndex < source.tiles.size) {
            val tile = source.tiles[source.tileIndex]
            val region = Geometry.worldToShard(BlockPos(tile.x shl 4, 0, tile.z shl 4))
            visited++
            val state = when (val read = storage.guidanceShard(region.first, region.second)) {
                is ShardRead.Present -> read.value
                ShardRead.Absent -> { source.tileIndex++; source.traceOrdinal = 0; source.noteOrdinal = 0; source.notesPhase = false; continue }
                else -> return visited
            }
            val pin = Pin(WeakReference(state), state.guidanceRevision(), state.archiveRevision())
            val prior = source.pins.putIfAbsent(region, pin)
            if (prior != null && !pinMatches(prior, state)) return visited // invalidated; discarded by the next tick
            if (visited == budget) return visited
            if (!source.notesPhase) {
                val next = state.nextTraceInTile(tile.x, tile.z, source.traceOrdinal)
                if (next != null) {
                    source.traceOrdinal = next.first
                    val t = next.second
                    val p = t.blockPos
                    if (p.x in source.min.x..source.max.x && p.y in source.min.y..source.max.y &&
                        p.z in source.min.z..source.max.z) {
                        if (source.traceCount == MAX_INPUT_TRACES) { source.tooDense = true; return visited }
                        source.builder.addTrace(t); source.traceCount++
                    }
                    visited++
                    continue
                }
                source.notesPhase = true
            }
            val next = state.nextAnnotationInTile(tile.x, tile.z, source.noteOrdinal)
            if (next != null) {
                val note = next.second
                val p = note.position
                if (p.x in source.min.x..source.max.x && p.y in source.min.y..source.max.y &&
                    p.z in source.min.z..source.max.z && note.team == GLOBAL_TEAM && note.createdByInternal != player.uuid) {
                    try {
                        if (note.revision > storage.getSeen(player.uuid, note.id)) {
                            if (source.noteCount == MAX_INPUT_NOTES) { source.tooDense = true; return visited }
                            source.builder.addAnnotation(note); source.noteCount++
                        }
                    } catch (_: TraceReadUnavailableException) { return visited }
                }
                source.noteOrdinal = next.first
                visited++
            } else {
                source.tileIndex++
                source.traceOrdinal = 0; source.noteOrdinal = 0; source.notesPhase = false
            }
        }
        if (source.tileIndex == source.tiles.size) source.collected = true
        return visited
    }

    private fun pinsValid(pins: Map<Pair<Int, Int>, Pin>): Boolean = pins.all { (region, pin) ->
        when (val read = storage.guidanceShard(region.first, region.second)) {
            is ShardRead.Present -> pinMatches(pin, read.value)
            else -> false
        }
    }

    private fun pinMatches(pin: Pin, state: TraceShardState): Boolean =
        if (pin.authority.get() === state) state.guidanceRevision() == pin.guidanceRevision
        else state.archiveRevision() == pin.archiveRevision && state.guidanceRevision() == 0L

    private fun observe(player: ServerPlayer, follow: Follow, budget: Int, elapsedTicks: Int): Int {
        if (budget <= 0) return 0
        var visits = 0
        follow.observed = false
        val path = follow.route.path
        while (visits < budget && follow.index < path.lastIndex) {
            val from = path[follow.index]
            val to = path[follow.index + 1]
            visits++
            val dx = player.x - to.x; val dy = player.y - to.y; val dz = player.z - to.z
            if (dx * dx + dy * dy + dz * dz > GuidanceProgressTracker.ROUTE_REACH * GuidanceProgressTracker.ROUTE_REACH) break
            val px = from.x - to.x; val py = from.y - to.y; val pz = from.z - to.z
            follow.remaining = (follow.remaining - sqrt(px * px + py * py + pz * pz)).coerceAtLeast(0.0)
            follow.index++
        }
        if (visits == budget && follow.index < path.lastIndex) return visits // incomplete observation cannot award XP
        val remaining = follow.route.copy(path = path.subList(follow.index, path.size))
        val reward = progress.observeRemaining(player.uuid, player.position(), remaining, follow.remaining, elapsedTicks)
        follow.observed = true
        if (reward.experience > 0) {
            player.giveExperiencePoints(reward.experience)
            log.info("TRACES_GUIDANCE_XP player={} annotation={} revision={} awarded={} advancedDistance={}",
                player.gameProfile.name, follow.route.targetAnnotationId, follow.route.targetRevision,
                reward.experience, reward.advancedDistance)
        }
        return visits
    }

    private fun source(level: ServerLevel, position: Vec3): Source {
        val radius = RADIUS_BLOCKS
        val center = BlockPos.containing(position)
        return Source(position, BlockPos(center.x - radius, level.minBuildHeight, center.z - radius),
            BlockPos(center.x + radius, level.maxBuildHeight - 1, center.z + radius))
    }

    fun clearProgress(player: ServerPlayer) { sessions.remove(player.uuid); progress.clear(player.uuid) }

    companion object {
        const val RADIUS_BLOCKS = 72
        const val MAX_VISITS_PER_SERVER_TICK = 4096
        const val CADENCE_TICKS = 20
        private const val PLAYER_SLICE = 256
        private const val MAX_INPUT_TRACES = 65_536
        private const val MAX_INPUT_NOTES = 1_024
        private const val MAX_SNAPSHOT_MOVEMENT = 8.0
        private const val REBUILD_DISTANCE = 16.0
        private const val EMPTY_RETRY_TICKS = 100
        private val NOT_READY = GuidanceBuildResult(0, emptyList(), true)
    }
}
