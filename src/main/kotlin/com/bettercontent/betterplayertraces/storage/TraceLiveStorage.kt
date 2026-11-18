package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.config.TracesConfig
import com.bettercontent.betterplayertraces.domain.FootTrace
import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import com.bettercontent.betterplayertraces.domain.TraceKind
import com.bettercontent.betterplayertraces.domain.TraceSupport
import com.bettercontent.betterplayertraces.util.Geometry
import com.bettercontent.betterplayertraces.util.TraceShardId
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** A read is never represented by an empty shard unless absence was established. */
sealed interface ShardRead<out T> {
    data class Present<T>(val value: T) : ShardRead<T>
    data object Absent : ShardRead<Nothing>
    data object Loading : ShardRead<Nothing>
    data class Unavailable(val detail: String) : ShardRead<Nothing>
    data class Corrupt(val detail: String) : ShardRead<Nothing>
    data class Unsupported(val detail: String) : ShardRead<Nothing>
}

class TraceReadUnavailableException(val status: ShardRead<*>) : IllegalStateException("Trace archive is not ready: $status")

/** Shared by ALL dimensions of a server; workers receive only immutable bytes and paths. */
class TraceIoCoordinator(private val queueByteLimit: () -> Long = { 128L * 1024L * 1024L }) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { job ->
        Thread(job, "better-traces-archive-io").apply { isDaemon = true }
    }
    private val budget = TraceAuthorityBudget()
    private val stagedExtra = mutableMapOf<TraceShardId, Long>()
    @Synchronized fun reserve(bytes: Int, maxCount: Int, maxBytes: Long): Boolean =
        budget.reserveQueue(bytes, maxCount, maxBytes)
    @Synchronized fun reserveWrite(bytes: Int, maxCount: Int, maxBytes: Long): Boolean =
        budget.reserveQueue(bytes, maxCount, maxBytes, write = true)
    @Synchronized fun reserveNote(bytes: Int, maxCount: Int, maxBytes: Long): Boolean =
        budget.reserveQueue(bytes, maxCount, maxBytes, note = true)
    @Synchronized fun release(bytes: Int) = budget.releaseQueue(bytes)
    @Synchronized fun releaseNote(bytes: Int) = budget.releaseQueue(bytes, note = true)
    @Synchronized fun releaseReconciliationRead(bytes: Int) = budget.releaseBorrowedRead(bytes)
    @Synchronized fun resize(bytes: Int, exact: Int, maxBytes: Long): Boolean = budget.resizeQueue(bytes, exact, maxBytes)
    @Synchronized fun reserveDirty(id: TraceShardId, projected: Long, count: Int, bytes: Long): Boolean =
        budget.reserveDirty(id, projected + (stagedExtra[id] ?: 0L), count, bytes)
    @Synchronized fun stageExtra(id: TraceShardId, bytes: Long) { check(id !in stagedExtra); stagedExtra[id] = bytes }
    @Synchronized fun finishStage(id: TraceShardId) { stagedExtra.remove(id) }
    @Synchronized fun previousDirty(id: TraceShardId): Long? = budget.previousDirty(id)
    @Synchronized fun restoreDirty(id: TraceShardId, prior: Long?) = budget.restoreDirty(id, prior)
    @Synchronized fun releaseDirty(id: TraceShardId) = budget.releaseDirty(id)
    @Synchronized fun dirtyBacklog(): Pair<Int, Long> = budget.dirtySnapshot()
    fun <T> submit(task: Callable<T>): Future<T> = executor.submit(task) // caller already reserved bytes
    /** Reserve through server-thread consumption, not merely until the worker finishes. */
    fun <T> submitRead(maxCount: Int, resultReservation: Int, task: Callable<T>): Future<T>? {
        if (!reserve(resultReservation, maxCount, queueByteLimit())) return null
        return try { executor.submit(task) }
        catch (error: Exception) { release(resultReservation); throw error }
    }
    /** Borrow the completed note writer's count slot; even saveQueueMax=1 can reconcile. */
    fun <T> submitReconciliationRead(resultReservation: Int, task: Callable<T>): Future<T>? {
        val accepted = synchronized(this) { budget.borrowNoteRead(resultReservation, queueByteLimit()) }
        if (!accepted) return null
        return try { executor.submit(task) }
        catch (error: Exception) { releaseReconciliationRead(resultReservation); throw error }
    }
    fun shutdown(timeoutMs: Long = 30_000L): Boolean {
        executor.shutdown()
        return try { executor.awaitTermination(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
    }
    @Synchronized fun backlog(): Pair<Int, Long> = budget.queueSnapshot()
}

/** This schema has no production migration path; old populated roots are left untouched. */
internal object LiveTraceRoot {
    private const val SCHEMA = "better-player-traces-v4\n"
    private val SHARD = Regex("r\\.(-?\\d+)\\.(-?\\d+)\\.traces(?:\\.manifest(?:\\.bak)?)?")

    fun open(root: Path, dimension: ResourceLocation): Set<Pair<Int, Int>> {
        val oldDimension = root.parent.resolve("traces").resolve(dimension.namespace).resolve(dimension.path)
        if (Files.isDirectory(oldDimension) && Files.list(oldDimension).use { it.findAny().isPresent }) {
            throw UnsupportedRootException("Legacy trace data at $oldDimension; explicit recovery is required")
        }
        val marker = root.resolve("schema")
        if (Files.exists(marker)) {
            if (Files.readString(marker) != SCHEMA) throw UnsupportedRootException("Unsupported trace root schema: $marker")
        } else {
            if (Files.exists(root) && Files.walk(root).use { it.anyMatch { p -> p != root } }) {
                throw UnsupportedRootException("Populated trace root without v4 schema: $root")
            }
            Files.createDirectories(root)
            FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                val buffer = ByteBuffer.wrap(SCHEMA.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) out.write(buffer)
                out.force(true)
            }
            FileChannel.open(root, StandardOpenOption.READ).use { it.force(true) }
        }
        val directory = root.resolve(dimension.namespace).resolve(dimension.path)
        if (!Files.isDirectory(directory)) return emptySet()
        val discovered = linkedSetOf<Pair<Int, Int>>()
        Files.list(directory).use { stream ->
            stream.forEach { path ->
                val name = path.fileName.toString()
                val match = SHARD.matchEntire(name)
                if (match != null) {
                    val x = match.groupValues[1].toIntOrNull() ?: throw UnsupportedRootException("Invalid shard coordinate: $path")
                    val z = match.groupValues[2].toIntOrNull() ?: throw UnsupportedRootException("Invalid shard coordinate: $path")
                    discovered += x to z
                    if (discovered.size > 100_000) throw UnsupportedRootException("Too many trace shard manifests in $directory")
                } else if (name.startsWith("r.") && (name.endsWith(".traces") || ".manifest" in name)) {
                    throw UnsupportedRootException("Unrecognized shard file $path")
                }
            }
        }
        return discovered
    }
}

internal class UnsupportedRootException(message: String) : IllegalStateException(message)

internal object LiveShardReader {
    fun read(path: Path): ShardRead<TraceShardState> {
        if (!TraceArchive.hasManifest(path)) {
            return if (Files.exists(path)) ShardRead.Unsupported("Unversioned legacy shard $path") else ShardRead.Absent
        }
        return try {
            val snapshot = TraceArchive.readLatest(path)
            val state = TraceSerializer.decodeV4(snapshot.payload, path)
            state.setArchiveRevision(snapshot.revision)
            ShardRead.Present(state)
        } catch (error: TraceSerializer.UnsupportedShardVersionException) {
            ShardRead.Unsupported("$path: ${error.message}")
        } catch (error: java.io.IOException) {
            ShardRead.Unavailable("$path: ${error.message}")
        } catch (error: Exception) {
            if (generateSequence(error as Throwable?) { it.cause }.any { it is java.io.IOException })
                ShardRead.Unavailable("$path: ${error.message}")
            else ShardRead.Corrupt("$path: ${error.message}")
        }
    }
}

/** Server-thread authority; I/O tasks may read/write only immutable archive values. */
class TraceStorageManager(
    private val level: ServerLevel,
    private val config: TracesConfig.Common,
    private val io: TraceIoCoordinator = TraceIoCoordinator(),
    private val ownsIo: Boolean = true,
) {
    companion object {
        internal fun dimensionRoot(worldRoot: Path, dimension: ResourceLocation): Path =
            worldRoot.toAbsolutePath().normalize().resolve("data/better_player_traces")
                .resolve(dimension.namespace).resolve(dimension.path)
        private const val READ_SLOT_BYTES = 64 * 1024 * 1024 // worst-case encoded shard; retained until consumed
        private const val DISCOVERY_SLOT_BYTES = 8 * 1024 * 1024
    }
    private val log = LoggerFactory.getLogger(TraceStorageManager::class.java)
    private val MAX_PENDING_BYTES: Long get() = config.saveQueueBytes.get().toLong()
    private val worldRoot = level.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
    private val root = worldRoot.resolve("data/better_player_traces")
    private val dimension = level.dimension().location()
    private val dimensionName = dimension.toString()
    private val directory = dimensionRoot(worldRoot, dimension)
    private val loaded = LinkedHashMap<TraceShardId, TraceShardState>(16, 0.75f, true)
    private val reads = linkedMapOf<TraceShardId, Future<ShardRead<TraceShardState>>>()
    private val discovered = TraceShardCatalog()
    private val summaries = TraceSummaryIndex()
    private var discovery: Future<Set<Pair<Int, Int>>>? = null
    private var discoveryIds: Iterator<Pair<Int, Int>>? = null
    private var lastDiscoverySliceTick = -1L
    private var discoveryComplete = false
    private var nextDiscoveryTick = 0L
    private val writes = linkedMapOf<TraceShardId, PendingWrite>()
    private val notes = linkedMapOf<UUID, PendingNote>()
    private val annotationOwners = linkedMapOf<Pair<UUID, BlockPos>, UUID>()
    private val annotationIds = linkedMapOf<UUID, TraceShardId>()
    private var annotationSelectionEpoch = 0L
    fun annotationSelectionEpoch(): Long = annotationSelectionEpoch
    private val unavailable = linkedMapOf<TraceShardId, ShardRead<Nothing>>()
    private val failedWrites = linkedSetOf<TraceShardId>()
    /** Sticky: an unexpected higher revision must never be overwritten by local authority. */
    private val conflicts = linkedSetOf<TraceShardId>()
    private var closed = false
    private var paused = false
    private var rejectedCaptures = 0L
    private var rejectedAnnotations = 0L
    private var omittedIncidental = 0L
    private var failure: String? = null
    private var ticks = 0L
    private var nextShutdownPollNanos = 0L

    private data class PendingWrite(
        val revision: Long, val generation: Long, val size: Int,
        val future: Future<Boolean>,
    )
    private data class PendingNote(
        val id: UUID, val shard: TraceShardId, val requestId: UUID,
        val next: TraceAnnotation?, val previous: TraceAnnotation?, val expectedRevision: Int?,
        val size: Int, val payload: ByteArray, var future: Future<Boolean>, val publishedRevision: Long,
        val previousDirtyBytes: Long?,
        val completion: (NoteMutationResult) -> Unit,
        var reconciliation: Future<ShardRead<TraceShardState>>? = null,
        var indeterminateReported: Boolean = false,
        var retryAtTick: Long = 0L,
        var nextRecoveryTick: Long = 0L,
    )

    private fun idFor(position: BlockPos): TraceShardId {
        val (x, z) = Geometry.worldToShard(position)
        return TraceShardId(dimensionName, x, z)
    }
    private fun idForChunk(x: Int, z: Int) = idFor(BlockPos(x shl 4, 0, z shl 4))
    private fun path(id: TraceShardId) = directory.resolve("r.${id.regionX}.${id.regionZ}.traces")

    /** Poll on the server thread; never wait on a future while handling a player or query. */
    private fun ready(): ShardRead<Nothing>? {
        failure?.let { return ShardRead.Unsupported(it) }
        if (discoveryComplete) return null
        if (ticks < nextDiscoveryTick) return ShardRead.Unavailable("Trace root retry pending")
        val task = discovery ?: run {
            val rootPath = root
            val dimensionId = dimension
            io.submitRead(config.saveQueueMax.get(), DISCOVERY_SLOT_BYTES, Callable { LiveTraceRoot.open(rootPath, dimensionId) })
                ?.also { discovery = it } ?: return ShardRead.Loading
        }
        if (!task.isDone) return ShardRead.Loading
        try {
            val iterator = discoveryIds ?: task.get().iterator().also { discoveryIds = it }
            val serverTick = level.server.tickCount.toLong()
            if (lastDiscoverySliceTick == serverTick) return ShardRead.Loading
            lastDiscoverySliceTick = serverTick
            repeat(4096) {
                if (!iterator.hasNext()) return@repeat
                val (x, z) = iterator.next()
                val id = TraceShardId(dimensionName, x, z)
                discovered += id
                summaries.schedule(id)
            }
            if (iterator.hasNext()) return ShardRead.Loading
            discoveryIds = null
            discovery = null
            io.release(DISCOVERY_SLOT_BYTES)
            discoveryComplete = true
            return null
        } catch (error: Exception) {
            discovery = null
            discoveryIds = null
            io.release(DISCOVERY_SLOT_BYTES)
            val cause = error.cause ?: error
            if (cause is UnsupportedRootException) {
                failure = "Trace root rejected: ${cause.message}"
                paused = true
                return ShardRead.Unsupported(requireNotNull(failure))
            }
            paused = true
            nextDiscoveryTick = ticks + 1L
            return ShardRead.Unavailable("Trace root I/O unavailable: ${cause.message}")
        }
    }
    private fun shard(id: TraceShardId): ShardRead<TraceShardState> {
        ready()?.let { return it }
        if (id in conflicts) return ShardRead.Unavailable("conflicting published revision for $id; manual recovery required")
        loaded[id]?.let { return ShardRead.Present(it) }
        unavailable[id]?.let { return it }
        if (id !in discovered && id !in reads) {
            if (!makeRoom(id)) { paused = true; return ShardRead.Unavailable("dirty shard cache is pinned") }
            return TraceShardState().also { loaded[id] = it; summaries.acceptLoaded(id, it) }.let { ShardRead.Absent }
        }
        val read = reads[id]
        if (read == null) {
            val archivePath = path(id)
            io.submitRead(config.saveQueueMax.get(), READ_SLOT_BYTES, Callable { LiveShardReader.read(archivePath) })?.let { reads[id] = it }
            return ShardRead.Loading
        }
        if (!read.isDone) return ShardRead.Loading
        reads.remove(id)
        val result = try { read.get() } catch (error: Exception) {
            ShardRead.Unavailable("$id: ${error.cause?.message ?: error.message}")
        } finally { io.release(READ_SLOT_BYTES) }
        return when (result) {
            is ShardRead.Present -> {
                if (!makeRoom(id)) { paused = true; ShardRead.Unavailable("dirty shard cache is pinned") }
                else {
                    loaded[id] = result.value
                    // The bounded summary cursor rebuilds the note-ID catalog; never walk a
                    // 64 MiB shard inline on the server thread merely because its read completed.
                    summaries.acceptLoaded(id, result.value)
                    ShardRead.Present(result.value)
                }
            }
            ShardRead.Absent -> {
                if (id in discovered) {
                    val missing = ShardRead.Corrupt("Discovered shard $id has no valid manifest")
                    unavailable[id] = missing; paused = true; missing
                } else if (!makeRoom(id)) { paused = true; ShardRead.Unavailable("dirty shard cache is pinned") }
                else TraceShardState().also { loaded[id] = it; summaries.acceptLoaded(id, it) }.let { ShardRead.Absent }
            }
            ShardRead.Loading -> ShardRead.Loading
            is ShardRead.Unavailable, is ShardRead.Corrupt, is ShardRead.Unsupported -> {
                @Suppress("UNCHECKED_CAST") val error = result as ShardRead<Nothing>
                unavailable[id] = error; paused = true; error
            }
        }
    }
    private fun materialized(id: TraceShardId): TraceShardState = when (val result = shard(id)) {
        is ShardRead.Present -> result.value
        ShardRead.Absent -> loaded.getValue(id)
        else -> throw TraceReadUnavailableException(result)
    }
    private fun makeRoom(id: TraceShardId): Boolean {
        if (id in loaded) return true
        if (loaded.size < config.shardCacheSize.get()) return true
        val evict = loaded.entries.firstOrNull { (key, state) -> !state.dirty && key != summaries.workingId &&
            key !in writes && notes.values.none { it.shard == key } }
            ?: return false
        loaded.remove(evict.key)
        // IDs are a compact catalog, not part of the shard LRU; preserve exact lookups after eviction.
        return true
    }
    /** Internal server-thread maintenance cursor; absence is safe, unknown archives are not. */
    fun maintenanceTile(x: Int, z: Int): ShardRead<TraceShardState> {
        ready()?.let { return it }
        val id = idForChunk(x, z)
        if (id !in discovered && id !in loaded) return ShardRead.Absent
        return shard(id)
    }

    /** Selected-note seen lookup uses its already-known tile, never an archive-wide ID search. */
    fun seenRevisionAt(player: UUID, annotation: UUID, chunkX: Int, chunkZ: Int): Int =
        when (val read = maintenanceTile(chunkX, chunkZ)) {
            is ShardRead.Present -> {
                if (read.value.annotationById(annotation) == null)
                    throw TraceReadUnavailableException(ShardRead.Loading)
                read.value.seenRevision(player, annotation)
            }
            ShardRead.Absent -> throw TraceReadUnavailableException(ShardRead.Loading)
            else -> throw TraceReadUnavailableException(read)
        }

    fun guidanceShard(regionX: Int, regionZ: Int): ShardRead<TraceShardState> {
        val id = TraceShardId(dimensionName, regionX, regionZ)
        return when (val result = shard(id)) {
            ShardRead.Absent -> ShardRead.Present(loaded.getValue(id)) // proven absence, now a pinned empty live shard
            else -> result
        }
    }

    fun readTile(x: Int, z: Int): ShardRead<TraceTileSnapshot> = when (val result = shard(idForChunk(x, z))) {
        is ShardRead.Present -> ShardRead.Present(result.value.traceTileSnapshot(x, z))
        ShardRead.Absent -> ShardRead.Absent
        else -> result as ShardRead<Nothing>
    }
    fun traceTileSnapshot(x: Int, z: Int): TraceTileSnapshot = when (val result = readTile(x, z)) {
        is ShardRead.Present -> result.value
        ShardRead.Absent -> TraceTileSnapshot(TraceTileId(x, z), 0L, emptyList())
        else -> throw TraceReadUnavailableException(result)
    }
    fun tileRevision(x: Int, z: Int) = traceTileSnapshot(x, z).revision
    fun pendingBlockCleanupCount(x: Int, z: Int): Int = 0
    fun queryTraceTile(x: Int, z: Int) = traceTileSnapshot(x, z).traces

    fun queryTraces(min: BlockPos, max: BlockPos): List<FootTrace> = query(min, max) { it.nearbyFootTraces(min, max) }
    fun queryTracesAfter(min: BlockPos, max: BlockPos, after: Long): List<FootTrace> =
        query(min, max) { it.nearbyFootTracesAfter(min, max, after) }
    fun queryAnnotations(min: BlockPos, max: BlockPos): List<TraceAnnotation> =
        query(min, max) { it.nearbyAnnotations(min, max) }
    private fun <T> query(min: BlockPos, max: BlockPos, select: (TraceShardState) -> List<T>): List<T> {
        val (minX, minZ) = Geometry.worldToShard(min)
        val (maxX, maxZ) = Geometry.worldToShard(max)
        val result = ArrayList<T>()
        for (x in minX..maxX) for (z in minZ..maxZ) {
            when (val read = shard(TraceShardId(dimensionName, x, z))) {
                is ShardRead.Present -> result.addAll(select(read.value))
                ShardRead.Absent -> Unit
                else -> throw TraceReadUnavailableException(read)
            }
        }
        return result
    }

    private fun mayMutate(id: TraceShardId): TraceShardState? {
        if (closed || paused || failure != null || id in conflicts || io.backlog().first >= config.saveQueueMax.get()) return null
        val state = try { materialized(id) } catch (_: TraceReadUnavailableException) { return null }
        return state
    }
    fun addFootTrace(trace: FootTrace): Boolean {
        require(trace.levelKey == dimensionName && (trace.kind != TraceKind.FOOTPRINT || trace.support != null))
        val id = idFor(trace.blockPos)
        val state = mayMutate(id)
        // Estimate admission without encoding the shard for every capture.
        // Five bytes of block/accounting overhead and a possible new 16-byte tile revision.
        val addedBytes = traceStorageBytes(trace) + 5L + 16L
        if (state == null || state.counts().first >= 500_000 ||
            estimatedBytes(state) + addedBytes > 64L * 1024 * 1024 ||
            !io.reserveDirty(id, estimatedBytes(state) + addedBytes, config.saveQueueMax.get(), MAX_PENDING_BYTES)) {
            rejectedCaptures++
            omittedIncidental++
            // A capture rejection must never undo an existing disk-failure pause.
            paused = pauseAfterRejectedCapture(paused, state != null)
            return false
        }
        val before = state.currentGeneration()
        state.addFootTrace(trace)
        summaries.traceChanged(id, before, state, null, trace)
        // Shrink conservative reservation only after the mutation is safely pinned.
        io.reserveDirty(id, estimatedBytes(state), config.saveQueueMax.get(), MAX_PENDING_BYTES)
        return true // memory accepted; crash can lose the unflushed footprint
    }
    private fun estimatedBytes(state: TraceShardState): Long {
        val (foot, annotations, seen) = state.counts()
        return 1024L + state.estimatedFootBytes + state.estimatedNoteBytes + 64L * seen +
            16L * state.tileRevisionCount() + 32L * state.noteReceipts.size +
            5L * (foot + annotations + seen)
    }
    private fun mutateFoot(id: TraceShardId, action: (TraceShardState) -> Int): Int {
        val state = mayMutate(id) ?: return 0
        val prior = io.previousDirty(id)
        if (!io.reserveDirty(id, estimatedBytes(state), config.saveQueueMax.get(), MAX_PENDING_BYTES)) { paused = true; return 0 }
        return try {
            val before = state.currentGeneration()
            action(state).also {
                if (state.currentGeneration() != before) summaries.schedule(id)
                if (state.dirty) io.reserveDirty(id, estimatedBytes(state), config.saveQueueMax.get(), MAX_PENDING_BYTES)
                else io.restoreDirty(id, prior)
            }
        } catch (error: Exception) { if (!state.dirty) io.restoreDirty(id, prior); throw error }
    }
    fun removeByPosition(pos: BlockPos) { mutateFoot(idFor(pos)) { it.removeAtPosition(pos) } }
    fun removeBySupport(pos: BlockPos): Int = mutateFoot(idFor(pos)) { it.removeBySupport(pos) }
    fun removeTraceById(id: UUID, pos: BlockPos): Boolean = mutateFoot(idFor(pos)) { it.removeTraceById(id) } != 0
    fun pruneInvalidSupports(x: Int, z: Int, isValid: (TraceSupport) -> Boolean): Int =
        mutateFoot(idForChunk(x, z)) { it.pruneInvalidSupports(x, z) { support ->
            !shouldPruneInvalidSupport(support, { p -> level.chunkSource.getChunkNow(p.x shr 4, p.z shr 4) != null }, isValid)
        } }
    fun removeFootTraces(min: BlockPos, max: BlockPos): Int {
        val (minX, minZ) = Geometry.worldToShard(min)
        val (maxX, maxZ) = Geometry.worldToShard(max)
        // Never perform a partial destructive operation if some shard is unavailable.
        val ids = (minX..maxX).flatMap { x -> (minZ..maxZ).map { z -> TraceShardId(dimensionName, x, z) } }
        val states = ids.map { mayMutate(it) ?: return 0 }
        val prior = ids.associateWith(io::previousDirty)
        for ((id, state) in ids.zip(states)) {
            if (!io.reserveDirty(id, estimatedBytes(state), config.saveQueueMax.get(), MAX_PENDING_BYTES)) {
                prior.forEach { (key, size) -> io.restoreDirty(key, size) }; paused = true; return 0
            }
        }
        return ids.zip(states).sumOf { (id, state) ->
            val before = state.currentGeneration()
            state.removeFootTraces(min, max).also {
                if (state.currentGeneration() != before) summaries.schedule(id)
                if (state.dirty) io.reserveDirty(id, estimatedBytes(state), config.saveQueueMax.get(), MAX_PENDING_BYTES)
                else io.restoreDirty(id, prior[id])
            }
        }
    }
    fun weakenAround(pos: BlockPos, radius: Int, factor: Double) {
        require(factor.isFinite() && factor >= 0)
        val min = pos.offset(-radius, 0, -radius)
        val max = pos.offset(radius, 0, radius)
        val (x0, z0) = Geometry.worldToShard(min)
        val (x1, z1) = Geometry.worldToShard(max)
        val ids = (x0..x1).flatMap { x -> (z0..z1).map { z -> TraceShardId(dimensionName, x, z) } }
        val shards = ids.map { mayMutate(it) ?: return }
        val prior = ids.associateWith(io::previousDirty)
        for ((id, shard) in ids.zip(shards)) {
            if (!io.reserveDirty(id, estimatedBytes(shard), config.saveQueueMax.get(), MAX_PENDING_BYTES)) {
                prior.forEach { (key, size) -> io.restoreDirty(key, size) }; paused = true; return
            }
        }
        ids.zip(shards).forEach { (id, shard) ->
            val before = shard.currentGeneration()
            shard.updateWeakness(pos, factor)
            if (shard.currentGeneration() != before) summaries.schedule(id)
            if (shard.dirty) io.reserveDirty(id, estimatedBytes(shard), config.saveQueueMax.get(), MAX_PENDING_BYTES)
            else io.restoreDirty(id, prior[id])
        }
    }
    fun weakenFootprint(id: UUID, pos: BlockPos, factor: Double): Boolean {
        val shardId = idFor(pos)
        val state = mayMutate(shardId) ?: return false
        val previous = io.previousDirty(shardId)
        if (!io.reserveDirty(shardId, estimatedBytes(state), config.saveQueueMax.get(), MAX_PENDING_BYTES)) { paused = true; return false }
        val before = state.currentGeneration()
        val priorTrace = state.traceById(id)
        val changed = state.updateTraceWeakness(id, factor)
        if (changed) {
            summaries.traceChanged(shardId, before, state, priorTrace, state.traceById(id))
            io.reserveDirty(shardId, estimatedBytes(state), config.saveQueueMax.get(), MAX_PENDING_BYTES)
        }
        else io.restoreDirty(shardId, previous)
        return changed
    }

    /** Archive summaries progress on each server tick, independently of the 20-tick flush cadence. */
    fun tickSummaries(budget: Int = 4096): Int {
        if (budget <= 0 || ready() != null) return 0
        return summaries.tick({ id -> guidanceShard(id.regionX, id.regionZ) }, budget)
    }

    fun echoOwnerCount(owner: UUID): Int? =
        if (ready() != null || unavailable.isNotEmpty() || conflicts.isNotEmpty()) null else summaries.echoCount(owner)

    fun serverSurvivingStrength(): Float? =
        if (ready() != null || unavailable.isNotEmpty() || conflicts.isNotEmpty()) null else summaries.serverStrength()

    fun serverPercentile(strength: Float): Float? =
        if (ready() != null || unavailable.isNotEmpty() || conflicts.isNotEmpty()) null else summaries.percentile(strength)

    fun allStorageShards(): Sequence<TraceShardState> = loaded.values.toList().asSequence()
    fun allLivingFootTraces(): List<FootTrace> = loaded.values.flatMap { it.footTracesSnapshot().filter(FootTrace::surviving) }
    fun shardIdsWithLivingTraces(radiusLimit: Int = 64): List<TraceShardId> =
        loaded.filterValues { state -> state.footTracesSnapshot().any(FootTrace::surviving) }.keys.toList()
    fun allSurvivingFootTraces(levelKey: String): List<FootTrace> {
        ready()?.let { throw TraceReadUnavailableException(it) }
        return discovered.candidates(loaded.keys).sortedWith(compareBy({ it.regionX }, { it.regionZ }))
            .flatMap { materialized(it).footTracesSnapshot().filter { t -> t.levelKey == levelKey && t.surviving } }
    }
    fun allAnnotations(): List<TraceAnnotation> {
        ready()?.let { throw TraceReadUnavailableException(it) }
        return discovered.candidates(loaded.keys).sortedWith(compareBy({ it.regionX }, { it.regionZ }))
            .flatMap { materialized(it).annotationsSnapshot() }
    }
    fun annotationById(id: UUID): TraceAnnotation? {
        annotationIds[id]?.let { return materialized(it).annotationById(id) }
        ready()?.let { throw TraceReadUnavailableException(it) }
        summaries.knownNoteShard(id)?.let { return materialized(it).annotationById(id) }
        if (unavailable.isNotEmpty() || conflicts.isNotEmpty() || !summaries.ready())
            throw TraceReadUnavailableException(ShardRead.Loading)
        val shardId = summaries.noteShard(id) ?: return null
        annotationIds[id] = shardId
        return materialized(shardId).annotationById(id)
    }
    fun annotationEcho(id: UUID): com.bettercontent.betterplayertraces.domain.AnnotationEchoRecord? =
        annotationById(id)?.let { a -> a.encodedEcho?.let { bytes ->
            com.bettercontent.betterplayertraces.domain.AnnotationEchoRecord(a.id, a.revision, a.createdByInternal, bytes.copyOf())
        } }
    fun getSeen(player: UUID, annotation: UUID): Int {
        val id = annotationIds[annotation] ?: run { annotationById(annotation); annotationIds[annotation] } ?: return 0
        return materialized(id).seenRevision(player, annotation)
    }
    fun setSeen(player: UUID, annotation: UUID, revision: Int) {
        if (closed || paused || revision < 0) return
        val id = annotationIds[annotation] ?: run { annotationById(annotation); annotationIds[annotation] } ?: return
        val state = mayMutate(id) ?: return
        if (state.annotationById(annotation) == null) return
        val prior = io.previousDirty(id)
        if (!io.reserveDirty(id, estimatedBytes(state) + 69L, config.saveQueueMax.get(), MAX_PENDING_BYTES)) {
            paused = true; return
        }
        val before = state.currentGeneration()
        synchronized(state) {
            val existing = state.seenStates.byKey(annotation, player)
            if (existing != null) {
                if (revision > existing.highestRevision) {
                    state.seenStates.replace(SeenStateRecord(annotation, player, revision))
                    state.invalidateGuidance(); state.markDirty()
                }
            } else if (state.seenStates.size < 1_000_000 && estimatedBytes(state) + 69L <= 64L * 1024 * 1024) {
                val added = SeenStateRecord(annotation, player, revision)
                state.seenStates += added; state.indexSeen(added)
                state.invalidateGuidance(); state.markDirty()
            } else paused = true
        }
        if (state.currentGeneration() != before) summaries.nonAggregateChanged(id, before, state)
        if (state.dirty) io.reserveDirty(id, estimatedBytes(state), config.saveQueueMax.get(), MAX_PENDING_BYTES)
        else io.restoreDirty(id, prior)
    }

    /** Gate2 replaces sync command callers with this completion API. No sync ACK can claim durability. */
    fun stageNote(
        requestId: UUID, id: UUID, expectedRevision: Int?, replacement: TraceAnnotation?,
        completion: (NoteMutationResult) -> Unit,
    ): NoteMutationResult {
        if (closed || paused || failure != null) return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_UNAVAILABLE)
        val shardId = try {
            replacement?.let { idFor(it.position) } ?: annotationIds[id]
                ?: run { annotationById(id); annotationIds[id] }
        } catch (_: TraceReadUnavailableException) { return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_UNAVAILABLE) }
            ?: return NoteMutationResult.Rejected(NoteRejectReason.NOT_FOUND)
        if (notes.containsKey(id) || notes.values.any { it.requestId == requestId } ||
            !canStageShard(notes.values.map { it.shard }, shardId, shardId in writes))
            return NoteMutationResult.Rejected(NoteRejectReason.BUSY)
        val state = mayMutate(shardId) ?: return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_UNAVAILABLE)
        val prior = state.annotationById(id)
        if (prior?.revision != expectedRevision) return NoteMutationResult.Rejected(NoteRejectReason.REVISION_CONFLICT)
        if (prior == null && replacement == null) return NoteMutationResult.Rejected(NoteRejectReason.NOT_FOUND)
        if (replacement != null) {
            if (replacement.id != id || replacement.revision != (prior?.revision ?: 0) + 1 ||
                (prior != null && (prior.position != replacement.position || prior.targetBlock != replacement.targetBlock ||
                    prior.createdByInternal != replacement.createdByInternal || prior.team != replacement.team)))
                return NoteMutationResult.Rejected(NoteRejectReason.INVALID_INPUT)
            try {
                com.bettercontent.betterplayertraces.domain.AnnotationComponents.validate(
                    replacement.text, replacement.icon, replacement.color, replacement.encodedEcho != null)
                replacement.encodedEcho?.let { com.bettercontent.betterplayertraces.logic.AnnotationEchoValidation.decode(it) }
            } catch (_: IllegalArgumentException) { return NoteMutationResult.Rejected(NoteRejectReason.INVALID_INPUT) }
            val targetKey = replacement.createdByInternal to replacement.targetBlock
            val conflicting = try {
                queryAnnotations(replacement.position, replacement.position).any {
                    it.id != id && it.createdByInternal == replacement.createdByInternal && it.targetBlock == replacement.targetBlock
                }
            } catch (_: TraceReadUnavailableException) { return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_UNAVAILABLE) }
            if (annotationOwners.containsKey(targetKey) || conflicting) return NoteMutationResult.Rejected(NoteRejectReason.TARGET_BUSY)
            annotationOwners[targetKey] = id
        }
        if (replacement != null && prior == null && state.counts().second >= 16_384) {
            annotationOwners.remove(replacement.createdByInternal to replacement.targetBlock)
            return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_CAPACITY)
        }
        if (id !in state.noteReceipts && state.noteReceipts.size >= 500_000) {
            replacement?.let { annotationOwners.remove(it.createdByInternal to it.targetBlock) }
            return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_CAPACITY)
        }
        val originalDirty = io.previousDirty(shardId)
        val projected = estimatedBytes(state) + (replacement?.let(::annotationStorageBytes) ?: 0L) -
            (prior?.let(::annotationStorageBytes) ?: 0L) +
            (if (replacement != null) 5L else 0L) - (if (prior != null) 5L else 0L) +
            (if (id !in state.noteReceipts) 32L else 0L)
        val estimate = maxOf(projected, estimatedBytes(state))
        fun undo() {
            io.restoreDirty(shardId, originalDirty)
            replacement?.let { annotationOwners.remove(it.createdByInternal to it.targetBlock) }
        }
        if (estimate > 64L * 1024L * 1024L ||
            !io.reserveDirty(shardId, estimate, config.saveQueueMax.get(), MAX_PENDING_BYTES)) {
            replacement?.let { annotationOwners.remove(it.createdByInternal to it.targetBlock) }
            paused = true; rejectedAnnotations++
            return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_CAPACITY)
        }
        val reserved = estimate.toInt()
        if (!io.reserveNote(reserved, config.saveQueueMax.get(), MAX_PENDING_BYTES)) {
            undo(); paused = true; rejectedAnnotations++
            return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_CAPACITY)
        }
        if (state.archiveRevision() >= Long.MAX_VALUE - 2L) {
            io.releaseNote(reserved); undo()
            return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_CAPACITY)
        }
        val publishRevision = state.archiveRevision() + 2L
        val bytes = try {
            val staged = state.snapshot().first
            staged.applyPublishedAnnotation(id, replacement, requestId)
            staged.setArchiveRevision(publishRevision)
            TraceSerializer.encodeV4(staged, Geometry.shardToBounds(shardId.regionX, shardId.regionZ))
        } catch (error: Exception) {
            io.releaseNote(reserved); undo()
            return NoteMutationResult.Rejected(NoteRejectReason.INVALID_INPUT)
        }
        if (bytes.size > 64 * 1024 * 1024 || !io.resize(reserved, bytes.size, MAX_PENDING_BYTES)) {
            io.releaseNote(reserved); undo()
            return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_CAPACITY)
        }
        val archivePath = path(shardId)
        val future = try { io.submit(Callable { TraceArchive.publish(archivePath, bytes, publishRevision) }) }
        catch (error: Exception) {
            io.releaseNote(bytes.size); undo(); paused = true
            return NoteMutationResult.Rejected(NoteRejectReason.STORAGE_UNAVAILABLE)
        }
        notes[id] = PendingNote(id, shardId, requestId, replacement?.copy(), prior, expectedRevision,
            bytes.size, bytes, future, publishRevision, originalDirty, completion)
        io.stageExtra(shardId, maxOf(0L, projected - estimatedBytes(state)))
        return NoteMutationResult.Pending(requestId)
    }
    private fun completeNote(note: PendingNote) {
        if (!note.future.isDone) return
        val successful = try { note.future.get() } catch (_: Exception) { false }
        if (successful) {
            resolveNote(note, NoteMutationResult.Committed(note.requestId, note.next, note.id, note.next?.revision ?: note.expectedRevision ?: 0))
            return
        }
        if (note.retryAtTick == 0L) note.retryAtTick = ticks + 1L
        if (mayStartNoteRecovery(note.reconciliation != null, ticks, note.nextRecoveryTick)) {
            // Failed reads, missing heads and double poll in one flush may not spin I/O every server tick.
            note.nextRecoveryTick = ticks + 1L
            val archivePath = path(note.shard)
            val noteId = note.id
            val request = note.requestId
            note.reconciliation = try {
                io.submitReconciliationRead(READ_SLOT_BYTES, Callable {
                    val observed = LiveShardReader.read(archivePath)
                    if (observed is ShardRead.Present && observed.value.noteReceipts[noteId] == request) {
                        try {
                            if (!TraceArchive.confirmDurable(archivePath, observed.value.archiveRevision()))
                                ShardRead.Unavailable("publication changed during durability verification")
                            else observed
                        } catch (error: Exception) { ShardRead.Unavailable("directory durability unconfirmed: ${error.message}") }
                    } else observed
                })
            } catch (_: java.util.concurrent.RejectedExecutionException) { null }
        }
        val reconciliation = note.reconciliation
        if (reconciliation == null) {
            paused = true
            if (!note.indeterminateReported) {
                note.indeterminateReported = true
                note.completion(NoteMutationResult.Indeterminate(note.requestId))
            }
            return
        }
        if (reconciliation.isDone) {
            val read = try { reconciliation.get() } catch (_: Exception) { ShardRead.Unavailable("read failed") }
                .also { io.releaseReconciliationRead(READ_SLOT_BYTES) }
            note.reconciliation = null
            when (classifyNoteRecovery(read, note.id, note.requestId, note.publishedRevision, note.shard in discovered)) {
                NoteRecoveryDecision.COMMITTED -> {
                    resolveNote(note, NoteMutationResult.Committed(note.requestId, note.next, note.id, note.next?.revision ?: note.expectedRevision ?: 0)); return
                }
                NoteRecoveryDecision.CONFLICT -> {
                    // A different published head cannot prove this request was never durable earlier.
                    resolveNote(note, NoteMutationResult.Indeterminate(note.requestId, terminal = true)); return
                }
                NoteRecoveryDecision.RETRY_EXACT -> if (ticks >= note.retryAtTick) {
                    // This is the SAME immutable snapshot, request ID and revision; never allocate a new note identity.
                    val archivePath = path(note.shard)
                    val payload = note.payload
                    val revision = note.publishedRevision
                    try {
                        note.future = io.submit(Callable { TraceArchive.publish(archivePath, payload, revision) })
                        note.retryAtTick = ticks + 1L
                        return
                    } catch (_: java.util.concurrent.RejectedExecutionException) { /* unresolved; keep authority pinned */ }
                }
                NoteRecoveryDecision.INDETERMINATE -> Unit
            }
        }
        paused = true
        if (!note.indeterminateReported) {
            note.indeterminateReported = true
            note.completion(NoteMutationResult.Indeterminate(note.requestId))
        }
    }
    private fun resolveNote(note: PendingNote, result: NoteMutationResult) {
        io.finishStage(note.shard)
        if (result is NoteMutationResult.Committed) {
            annotationSelectionEpoch++
            discovered.published(note.shard)
            loaded[note.shard]?.let { state ->
                val before = state.currentGeneration()
                state.setArchiveRevision(maxOf(state.archiveRevision(), note.publishedRevision))
                state.applyPublishedAnnotation(note.id, note.next, note.requestId)
                summaries.noteChanged(note.shard, before, state, note.previous, note.next)
            }
            if (note.next != null) annotationIds[note.id] = note.shard else annotationIds.remove(note.id)
        } else {
            paused = true
            conflicts.add(note.shard)
        } // unexpected revision requires explicit recovery, never blind rewrite
        notes.remove(note.id)
        val live = loaded[note.shard]
        if (live?.dirty == true) {
            if (!io.reserveDirty(note.shard, estimatedBytes(live), config.saveQueueMax.get(), MAX_PENDING_BYTES)) paused = true
        } else io.releaseDirty(note.shard)
        note.next?.let { annotationOwners.remove(it.createdByInternal to it.targetBlock) }
        io.releaseNote(note.size)
        note.completion(result)
    }
    private fun flush(id: TraceShardId, state: TraceShardState) {
        if (!shouldSubmitLatestSnapshot(state.dirty, id in writes, notes.values.any { it.shard == id }, id in conflicts)) return
        val estimate = estimatedBytes(state)
        if (estimate > 64L * 1024L * 1024L ||
            !io.reserveWrite(estimate.toInt(), config.saveQueueMax.get(), MAX_PENDING_BYTES)) {
            paused = true; return
        }
        val (snapshot, generation) = try { state.snapshot() } catch (error: Exception) {
            io.release(estimate.toInt()); paused = true; log.error("Trace snapshot failed: {}", id, error); return
        }
        val revision = snapshot.archiveRevision()
        val bytes = try { TraceSerializer.encodeV4(snapshot, Geometry.shardToBounds(id.regionX, id.regionZ)) }
        catch (error: Exception) {
            io.release(estimate.toInt()); paused = true; log.error("Trace shard cannot be encoded: {}", id, error); return
        }
        if (bytes.size > 64 * 1024 * 1024 || !io.resize(estimate.toInt(), bytes.size, MAX_PENDING_BYTES)) {
            io.release(estimate.toInt()); paused = true; return
        }
        val archivePath = path(id)
        val future = try { io.submit(Callable { TraceArchive.publish(archivePath, bytes, revision) }) }
        catch (error: Exception) {
            io.release(bytes.size); paused = true; return
        }
        writes[id] = PendingWrite(revision, generation, bytes.size, future)
    }
    private fun completeWrites() {
        for ((id, pending) in writes.toList()) {
            if (!pending.future.isDone) continue
            val saved = try { pending.future.get() } catch (error: Exception) {
                log.warn("Trace archive write failed: {}", id, error); null
            }
            when (saved) {
                true -> {
                    discovered.published(id)
                    loaded[id]?.let { state ->
                        if (snapshotCompletionClearsDirty(pending.generation, state.currentGeneration(), published = true))
                            state.clearDirtyIfUnchanged(pending.generation)
                    }
                    if (loaded[id]?.dirty != true) io.releaseDirty(id)
                    failedWrites.remove(id)
                }
                false -> {
                    log.warn("Trace archive snapshot conflict: shard={} requestedRevision={}", id, pending.revision)
                    paused = true; conflicts.add(id)
                }
                null -> { paused = true; failedWrites.add(id) }
            }
            writes.remove(id)
            io.release(pending.size)
        }
    }
    /** Deliver finished immutable I/O on every server tick; never hold UI ACK for the 20-tick retry cadence. */
    fun pollCompletions() {
        completeWrites()
        notes.values.toList().forEach(::completeNote)
    }

    /** One retry window each 20 ticks; never discard the accepted dirty authority on outage. */
    fun tickFlush() {
        ticks++
        ready() // recover discovery or retry transient startup I/O on this 20-tick cadence only
        reads.keys.toList().forEach { id -> if (reads[id]?.isDone == true && id !in unavailable) shard(id) }
        pollCompletions()
        if (paused) {
            unavailable.keys.toList().forEach { id ->
                if (id !in reads) {
                    val archivePath = path(id)
                    try { io.submitRead(config.saveQueueMax.get(), READ_SLOT_BYTES, Callable { LiveShardReader.read(archivePath) })?.let { reads[id] = it } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { }
                }
                val task = reads[id] ?: return@forEach
                if (task.isDone) {
                    reads.remove(id)
                    val result = try { task.get() } catch (_: Exception) { ShardRead.Unavailable("retry read failed") }
                    io.release(READ_SLOT_BYTES)
                    when (result) {
                        is ShardRead.Present -> {
                            unavailable.remove(id)
                            // Do not overwrite dirty live authority with a recovered disk snapshot.
                            if (id !in loaded) { loaded[id] = result.value; result.value.annotationsSnapshot().forEach { annotationIds[it.id] = id } }
                        }
                        ShardRead.Absent -> if (id !in discovered) unavailable.remove(id)
                        else -> Unit
                    }
                }
            }
            notes.values.toList().forEach(::completeNote)
            // Resolve accepted dirty authority before reopening mutation admission.
            loaded.forEach { (id, state) -> flush(id, state) }
            if (failure == null && canReopenAdmission(conflicts.size, unavailable.size, failedWrites.size,
                    notes.size, writes.size, loaded.values.count { it.dirty }, io.backlog().first < config.saveQueueMax.get()))
                paused = false
            return
        }
        loaded.forEach { (id, state) -> flush(id, state) }
    }
    internal fun outageFrontier(): StorageOutageFrontier = StorageOutageFrontier(
        rejectedCaptures, rejectedAnnotations, unavailable.keys.toSet(),
    )
    fun omittedCaptures(): Long = omittedIncidental
    fun stopAdmission() { closed = true }
    /** Called on the server thread until clean or deadline, before the sole worker shuts down. */
    fun drainShutdownStep(): Boolean {
        check(closed) { "stop admission before draining" }
        val now = System.nanoTime()
        if (now >= nextShutdownPollNanos) {
            nextShutdownPollNanos = now + TimeUnit.SECONDS.toNanos(1)
            tickFlush()
        }
        return writes.isEmpty() && notes.isEmpty() && reads.isEmpty() && discovery == null &&
            loaded.values.none { it.dirty } && io.dirtyBacklog().first == 0
    }
    fun close() {
        if (closed) return
        stopAdmission()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline && !drainShutdownStep()) {
            try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        if (ownsIo) {
            val remaining = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0))
            if (!io.shutdown(remaining)) log.error("Timed out draining standalone Traces archive I/O")
            finishShutdown()
        }
        if (writes.isNotEmpty() || notes.isNotEmpty() || loaded.values.any { it.dirty })
            log.error("Trace shutdown has unresolved authority: dimension={} dirty={} writes={} notes={}",
                dimensionName, loaded.values.count { it.dirty }, writes.size, notes.size)
    }
    internal fun finishShutdown() {
        completeWrites()
        notes.values.toList().forEach(::completeNote)
        reads.entries.toList().forEach { (id, task) ->
            if (task.isDone) {
                reads.remove(id)
                io.release(READ_SLOT_BYTES)
            }
        }
        if (discovery?.isDone == true) {
            discovery = null
            io.release(DISCOVERY_SLOT_BYTES)
        }
        if (writes.isNotEmpty() || notes.isNotEmpty() || reads.isNotEmpty() || loaded.values.any { it.dirty })
            log.error("Trace archive shutdown unresolved: dimension={} dirty={} writes={} notes={} reads={} conflicts={}",
                dimensionName, loaded.values.count { it.dirty }, writes.size, notes.size, reads.size, conflicts)
    }
}

enum class NoteRejectReason { STORAGE_UNAVAILABLE, STORAGE_CAPACITY, NOT_FOUND, BUSY, TARGET_BUSY, REVISION_CONFLICT, INVALID_INPUT, PERMISSION, OUT_OF_REACH }

sealed interface NoteMutationResult {
    data class Pending(val requestId: UUID) : NoteMutationResult
    data class Committed(val requestId: UUID, val annotation: TraceAnnotation?,
                         val annotationId: UUID? = annotation?.id, val revision: Int = annotation?.revision ?: 0) : NoteMutationResult
    data class Rejected(val reason: NoteRejectReason) : NoteMutationResult
    data class Indeterminate(val requestId: UUID, val terminal: Boolean = false) : NoteMutationResult
}
