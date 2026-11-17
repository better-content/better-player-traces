package com.bettercontent.betterplayertraces.network

import com.bettercontent.betterplayertraces.TracesMod
import com.bettercontent.betterplayertraces.config.TracesConfig
import com.bettercontent.betterplayertraces.domain.FootTrace
import com.bettercontent.betterplayertraces.dto.TraceOwner
import com.bettercontent.betterplayertraces.dto.VisibleAnnotationDto
import com.bettercontent.betterplayertraces.dto.VisibleTraceDto
import com.bettercontent.betterplayertraces.storage.ShardRead
import com.bettercontent.betterplayertraces.storage.TraceShardState
import com.bettercontent.betterplayertraces.storage.TraceStorageManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.util.UUID

internal data class TileCoordinate(val chunkX: Int, val chunkZ: Int)

internal fun <T> rotatedRoundRobin(players: List<T>, start: Int): List<T> =
    if (players.isEmpty()) emptyList() else players.indices.map { players[(start + it) % players.size] }

/** Precomputed offsets avoid re-sorting a full subscription radius on each five-tick refresh. */
internal fun nearestTileOffsets(radius: Int): List<TileCoordinate> {
    require(radius in 2..32)
    return buildList {
        for (x in -radius..radius) for (z in -radius..radius) add(TileCoordinate(x, z))
    }.sortedWith(compareBy<TileCoordinate> {
            it.chunkX.toLong() * it.chunkX.toLong() + it.chunkZ.toLong() * it.chunkZ.toLong()
        }.thenBy { it.chunkX }.thenBy { it.chunkZ })
}

internal class ServerTraceSubscription(
    val connection: ServerPlayer,
    val dimension: String,
    val radius: Int,
    val generation: Long,
    val loginGameTime: Long,
) {
    val sentRevisions = mutableMapOf<TileCoordinate, Long>()
    val sentStorageRevisions = mutableMapOf<TileCoordinate, Long>()
    val issuedRevisions = mutableMapOf<TileCoordinate, Long>()
    val unavailableNotified = mutableSetOf<TileCoordinate>()
    val wanted = mutableSetOf<TileCoordinate>()
    val queued = ArrayDeque<TileCoordinate>()
    val evictions = ArrayDeque<TileCoordinate>()
    val pendingEvictions = mutableSetOf<TileCoordinate>()
    var transfer: TraceTileTransfer? = null
    var centerX = Int.MIN_VALUE
    var centerZ = Int.MIN_VALUE
    var lastResponseTick = Long.MIN_VALUE
    var responseSent = false
    var metadataSelection: TraceMetadataSelection? = null
    val metadataVisible = mutableListOf<VisibleAnnotationDto>()
    var metadataReadyToPublish = false
}

internal class TraceTileTransfer(
    val tile: TileCoordinate, val storageRevision: Long, val wireRevision: Long,
    val totalRecords: Int, val pageCount: Int, val startedTick: Long,
    val stateIdentity: java.lang.ref.WeakReference<TraceShardState>?,
) {
    var pageIndex = 0
    var afterOrdinal = 0L
    val page = mutableListOf<VisibleTraceDto>()
    val owners = mutableMapOf<UUID, TraceOwner>()
}

/** One revisitable tile cursor per connection; never retains a whole shard or full-tile copy. */
internal class TraceServerStream(
    private val send: (ServerPlayer, Any) -> Unit,
    private val metadata: (ServerPlayer, Int, ServerTraceSubscription, List<VisibleAnnotationDto>, net.minecraft.core.BlockPos) -> Unit,
) {
    private val log = LoggerFactory.getLogger(TraceServerStream::class.java)
    private val subscriptions = mutableMapOf<UUID, ServerTraceSubscription>()
    private val pending = mutableMapOf<UUID, Int>()
    private val logins = mutableMapOf<UUID, Long>()
    private val offsetCache = mutableMapOf<Int, List<TileCoordinate>>()
    private val budget = TraceStreamBudget()
    private var nextGeneration = 1L
    private var rotation = 0
    private var unavailableReads = 0L
    private var revisionRestarts = 0L
    private var sentPages = 0L
    private var sendFailures = 0L
    private var metadataInvalidations = 0L
    private var currentTick = 0L

    fun subscription(playerId: UUID): ServerTraceSubscription? = subscriptions[playerId]
    fun login(player: ServerPlayer) {
        unsubscribe(player.uuid)
        logins[player.uuid] = player.serverLevel().gameTime
    }
    fun logout(player: ServerPlayer) { unsubscribe(player.uuid); logins.remove(player.uuid) }
    fun clear() {
        subscriptions.keys.toList().forEach(::unsubscribe)
        pending.clear(); logins.clear()
    }
    private fun unsubscribe(id: UUID) {
        subscriptions.remove(id)?.let { sub ->
            if (sub.transfer != null) budget.releasePinned(id, PAGE_RESERVATION_BYTES)
            sub.metadataSelection?.let { budget.releasePinned(id, it.reservedBytes) }
        }
        pending.remove(id)
        budget.forget(id)
    }
    fun request(player: ServerPlayer, radius: Int) { pending[player.uuid] = radius }

    fun tick(server: MinecraftServer) {
        val tick = server.tickCount.toLong()
        currentTick = tick
        val config = TracesConfig.common
        budget.beginTick(tick, TraceStreamLimits(
            tilesPerPlayerTick = config.traceTilesPerPlayerTick.get(),
            tilesServerTick = config.traceTilesServerTick.get(),
            recordsPerPlayerWindow = config.traceRecordsPerPlayerWindow.get(),
            recordsServerTick = config.traceRecordsServerTick.get(),
            bytesPerPlayerWindow = config.traceBytesPerPlayerWindow.get(),
            bytesServerTick = config.traceBytesServerTick.get(),
            pinnedPerPlayer = config.tracePinnedBytesPerPlayer.get().toLong(),
            pinnedServer = config.tracePinnedBytesServer.get().toLong(),
        ))
        val players = server.playerList.players.sortedBy { it.uuid.toString() }
        if (players.isEmpty()) return
        for (player in rotatedRoundRobin(players, rotation)) {
            val id = player.uuid
            val radius = pending[id]
            var sub = subscriptions[id]
            if (sub != null && (sub.connection !== player || sub.dimension != player.serverLevel().dimension().location().toString())) {
                unsubscribe(id)
                sub = null
            }
            if (radius != null) {
                if (sub == null || sub.radius != radius) {
                    unsubscribe(id)
                    pending[id] = radius // an asynchronous initial metadata scan must survive later ticks
                    val created = ServerTraceSubscription(player, player.serverLevel().dimension().location().toString(), radius,
                        nextGeneration++, logins.getOrPut(id) { player.serverLevel().gameTime })
                    sub = created
                    subscriptions[id] = created
                }
                val active = requireNotNull(sub)
                val storage = TracesMod.getRuntime(server).storage(player.serverLevel())
                if (prepareMetadata(player, storage, active)) {
                    val selection = requireNotNull(active.metadataSelection)
                    if (selection.complete && (active.lastResponseTick == Long.MIN_VALUE ||
                            tick - active.lastResponseTick >= REQUEST_INTERVAL_TICKS) &&
                        mapMetadata(player, storage, active)) {
                        if (!active.metadataReadyToPublish) {
                            // Seen flags can take multiple ticks to map. Revalidate every tile pin
                            // AFTER that mapping, before publishing the complete response.
                            selection.beginValidation(tick)
                            active.metadataReadyToPublish = true
                            continue
                        }
                        val delivered = runCatching { metadata(player, radius, active, active.metadataVisible.toList(), selection.anchor) }
                            .onFailure { error ->
                                unavailableReads++
                                if (error !is com.bettercontent.betterplayertraces.storage.TraceReadUnavailableException &&
                                    (unavailableReads == 1L || unavailableReads % 200L == 0L))
                                    log.warn("Trace metadata delivery failed for {}: {}", id, error.message, error)
                            }.isSuccess
                        if (delivered) {
                            active.lastResponseTick = tick
                            active.responseSent = true
                            active.metadataVisible.clear()
                            active.metadataReadyToPublish = false
                            pending.remove(id)
                            if (selection.anchor != player.blockPosition()) {
                                budget.releasePinned(id, selection.reservedBytes)
                                active.metadataSelection = null
                                pending[id] = radius // latest center gets the next complete selection
                            }
                            refreshTiles(active, player.chunkPosition().x, player.chunkPosition().z, force = true)
                        } else active.metadataReadyToPublish = false
                    }
                }
            }
            if (sub != null && sub.responseSent) {
                refreshTiles(sub, player.chunkPosition().x, player.chunkPosition().z)
                val storage = TracesMod.getRuntime(server).storage(player.serverLevel())
                pump(player, player.serverLevel(), storage, sub)
            }
        }
        rotation = (rotation + 1) % players.size
        if (tick % 200L == 0L && com.bettercontent.betterplayertraces.diagnostics.RuntimeDiagnostics.LOG.enabled("network")) {
            log.info("TRACES_STREAM examinedTiles={} examinedRecords={} actualEncodedBytes={} pages={} pinnedBytes={} " +
                "tileSaturated={} recordSaturated={} byteSaturated={} pinSaturated={} notReady={} restarted={} metadataInvalidated={} sendFailures={} oldestTransferTicks={} oldestMetadataTicks={}",
                budget.examinedTiles, budget.examinedRecords, budget.encodedBytes, sentPages, budget.pinnedBytes(),
                budget.saturatedTiles, budget.saturatedRecords, budget.saturatedBytes, budget.saturatedPinned,
                unavailableReads, revisionRestarts, metadataInvalidations, sendFailures,
                subscriptions.values.mapNotNull { it.transfer?.startedTick }.minOrNull()?.let { tick - it } ?: 0,
                subscriptions.values.mapNotNull { sub ->
                    sub.metadataSelection?.takeIf { pending[sub.connection.uuid] != null }?.pendingSinceTick
                }.minOrNull()?.let { tick - it } ?: 0)
        }
    }

    private fun prepareMetadata(player: ServerPlayer, storage: TraceStorageManager,
                                sub: ServerTraceSubscription): Boolean {
        val epoch = storage.annotationSelectionEpoch()
        val targets = TracesMod.getRuntime(player.server).guidance(player.serverLevel()).query(player)
            .routes.mapTo(HashSet()) { it.targetAnnotationId }
        val capacity = TracesConfig.common.maxPayloadAnnotations.get()
        val previous = sub.metadataSelection
        // A moving player finishes one coherent old snapshot before starting the next center.
        // Repeated requests at that center never reset an in-progress cursor.
        if (previous != null && (previous.epoch != epoch || previous.targets != targets || previous.capacity != capacity)) {
            budget.releasePinned(player.uuid, previous.reservedBytes)
            sub.metadataSelection = null
            sub.metadataVisible.clear()
            sub.metadataReadyToPublish = false
            metadataInvalidations++
        }
        if (sub.metadataSelection == null) {
            val level = player.serverLevel()
            val work = TraceMetadataSelection(player.blockPosition(), sub.radius, epoch, targets, capacity,
                level.minBuildHeight, level.maxBuildHeight - 1, currentTick)
            if (!budget.reservePinned(player.uuid, work.reservedBytes)) return false
            sub.metadataSelection = work
            sub.metadataReadyToPublish = false
        }
        val work = requireNotNull(sub.metadataSelection)
        if (work.complete) return true
        if (!work.advance(storage, budget, player.uuid)) {
            if (work.invalidated) {
                budget.releasePinned(player.uuid, work.reservedBytes)
                sub.metadataSelection = null
                sub.metadataVisible.clear()
                sub.metadataReadyToPublish = false
                metadataInvalidations++
            }
            if (work.unavailable) unavailableReads++
            return false
        }
        return true
    }

    private fun mapMetadata(player: ServerPlayer, storage: TraceStorageManager,
                            sub: ServerTraceSubscription): Boolean {
        val work = requireNotNull(sub.metadataSelection)
        if (storage.annotationSelectionEpoch() != work.epoch) return false
        val selected = work.selected()
        while (sub.metadataVisible.size < selected.size) {
            if (!budget.inspectTile(player.uuid) || !budget.inspectRecord(player.uuid)) return false
            val note = selected[sub.metadataVisible.size]
            val seen = try { storage.seenRevisionAt(player.uuid, note.id,
                Math.floorDiv(note.position.x, 16), Math.floorDiv(note.position.z, 16)) >= note.revision }
            catch (_: com.bettercontent.betterplayertraces.storage.TraceReadUnavailableException) {
                unavailableReads++; return false
            }
            sub.metadataVisible.add(VisibleAnnotationDto(
                id = note.id.toString(), text = note.text, icon = note.icon, color = note.color,
                x = note.position.x, y = note.position.y, z = note.position.z,
                team = com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM.id,
                revision = note.revision, seen = seen,
                canEdit = note.owner == player.uuid || player.hasPermissions(2),
                hasEcho = note.hasEcho, echoRevision = if (note.hasEcho) note.revision else 0,
            ))
        }
        return storage.annotationSelectionEpoch() == work.epoch
    }

    private fun refreshTiles(sub: ServerTraceSubscription, x: Int, z: Int, force: Boolean = false) {
        if (sub.centerX == x && sub.centerZ == z &&
            (!force || sub.queued.isNotEmpty() || sub.transfer != null || sub.evictions.isNotEmpty())) return
        sub.centerX = x; sub.centerZ = z
        val desired = linkedSetOf<TileCoordinate>()
        offsetCache.getOrPut(sub.radius) { nearestTileOffsets(sub.radius) }.forEach { offset ->
            desired.add(TileCoordinate(x + offset.chunkX, z + offset.chunkZ))
        }
        val leaving = (sub.sentRevisions.keys + sub.issuedRevisions.keys)
            .filter { it !in desired && it !in sub.pendingEvictions }.distinct()
            .sortedWith(compareBy<TileCoordinate> { it.chunkX }.thenBy { it.chunkZ })
        sub.evictions.removeAll { tile ->
            if (tile in desired) { sub.pendingEvictions.remove(tile); true } else false
        }
        leaving.forEach { tile ->
            sub.sentRevisions.remove(tile)
            sub.sentStorageRevisions.remove(tile)
            // Retain the last issued wire revision until the eviction packet is delivered.
            // A quick leave/re-enter must not roll back a revision the client still holds.
            sub.unavailableNotified.remove(tile)
            sub.evictions.addLast(tile)
            sub.pendingEvictions.add(tile)
        }
        sub.wanted.clear(); sub.wanted.addAll(desired)
        if (sub.transfer?.let { it.tile !in desired } == true) cancelTransfer(sub)
        sub.queued.clear()
        desired.forEach(sub.queued::addLast)
    }

    private fun pump(player: ServerPlayer, level: ServerLevel, storage: TraceStorageManager, sub: ServerTraceSubscription) {
        val id = player.uuid
        while (sub.evictions.isNotEmpty()) {
            val batch = mutableListOf<TraceTileCoordinate>()
            for (tile in sub.evictions.take(64)) {
                if (!budget.inspectTile(id)) break
                batch.add(TraceTileCoordinate(tile.chunkX, tile.chunkZ))
            }
            if (batch.isEmpty()) return
            val packet = TraceTileEvictPacket(sub.generation, sub.dimension, batch)
            if (!sendMeasured(player, packet, id)) return
            repeat(batch.size) {
                val removed = sub.evictions.removeFirst()
                sub.pendingEvictions.remove(removed)
                sub.issuedRevisions.remove(removed)
            }
        }
        while (sub.transfer != null || sub.queued.isNotEmpty()) {
            if (!budget.inspectTile(id)) return
            var transfer = sub.transfer
            if (transfer == null) {
                val tile = sub.queued.removeFirst()
                if (tile !in sub.wanted) continue
                when (val read = storage.maintenanceTile(tile.chunkX, tile.chunkZ)) {
                    ShardRead.Absent -> {
                        if (sub.sentStorageRevisions[tile] == 0L && tile in sub.sentRevisions) continue
                        transfer = startTransfer(sub, tile, null, 0L, id) ?: return
                    }
                    is ShardRead.Present -> {
                        val revision = read.value.tileRevision(tile.chunkX, tile.chunkZ)
                        if (sub.sentStorageRevisions[tile] == revision && tile in sub.sentRevisions) continue
                        transfer = startTransfer(sub, tile, read.value, revision, id) ?: return
                    }
                    else -> { signalUnavailable(player, sub, tile); sub.queued.addLast(tile); return }
                }
            }
            val active = requireNotNull(transfer)
            val tile = active.tile
            when (val read = storage.maintenanceTile(tile.chunkX, tile.chunkZ)) {
                ShardRead.Absent -> {
                    if (active.storageRevision != 0L || active.totalRecords != 0) {
                        restart(sub, tile); continue
                    }
                    if (!sendPage(player, sub, active, id)) return
                }
                is ShardRead.Present -> {
                    val current = read.value.tileRevision(tile.chunkX, tile.chunkZ)
                    if (active.stateIdentity?.get() !== read.value || current != active.storageRevision ||
                        read.value.livingTraceTileCount(tile.chunkX, tile.chunkZ) != active.totalRecords) {
                        restart(sub, tile); continue
                    }
                    if (!fillPage(player, level, sub, active, read.value, id)) return
                }
                else -> {
                    cancelTransfer(sub)
                    sub.queued.addLast(tile)
                    signalUnavailable(player, sub, tile)
                    return
                }
            }
        }
    }

    private fun startTransfer(sub: ServerTraceSubscription, tile: TileCoordinate, state: TraceShardState?,
                              revision: Long, id: UUID): TraceTileTransfer? {
        if (!budget.reservePinned(id, PAGE_RESERVATION_BYTES)) {
            sub.queued.addFirst(tile)
            return null
        }
        val count = state?.livingTraceTileCount(tile.chunkX, tile.chunkZ) ?: 0
        val previousIssued = sub.issuedRevisions[tile]
        val wireRevision = nextTraceTileWireRevision(previousIssued, sub.sentStorageRevisions[tile], revision, null, 0)
        val issued = if (previousIssued != null && wireRevision <= previousIssued && previousIssued < Long.MAX_VALUE)
            previousIssued + 1L else wireRevision
        sub.issuedRevisions[tile] = issued
        return TraceTileTransfer(tile, revision, issued, count, maxOf(1, (count + PAGE_SIZE - 1) / PAGE_SIZE),
            currentTick, state?.let { java.lang.ref.WeakReference(it) })
            .also { sub.transfer = it }
    }

    private fun fillPage(player: ServerPlayer, level: ServerLevel, sub: ServerTraceSubscription,
                         transfer: TraceTileTransfer, state: TraceShardState, id: UUID): Boolean {
        val target = minOf(PAGE_SIZE, transfer.totalRecords - transfer.pageIndex * PAGE_SIZE)
        while (transfer.page.size < target) {
            if (!budget.inspectRecord(id)) return false
            val next = state.nextTraceInTile(transfer.tile.chunkX, transfer.tile.chunkZ, transfer.afterOrdinal)
            if (next == null) { restart(sub, transfer.tile); return true }
            transfer.afterOrdinal = next.first
            val trace = next.second
            if (!trace.surviving) continue
            val owner = transfer.owners.getOrPut(trace.sourcePlayerInternal) {
                val name = (level.server.playerList.getPlayer(trace.sourcePlayerInternal)?.gameProfile?.name
                    ?: level.server.profileCache?.get(trace.sourcePlayerInternal)?.orElse(null)?.name)
                    ?.takeIf { it.matches(OWNER_NAME) }
                TraceOwner(trace.sourcePlayerInternal, name)
            }
            transfer.page.add(visibleTrace(trace).copy(owner = owner))
        }
        return sendPage(player, sub, transfer, id)
    }

    private fun sendPage(player: ServerPlayer, sub: ServerTraceSubscription, transfer: TraceTileTransfer, id: UUID): Boolean {
        val packet = TraceTileSnapshotPacket(sub.generation, sub.dimension, transfer.tile.chunkX, transfer.tile.chunkZ,
            transfer.wireRevision, transfer.pageIndex, transfer.pageCount, transfer.page.toList())
        if (!sendMeasured(player, packet, id)) return false
        sentPages++
        transfer.pageIndex++
        transfer.page.clear(); transfer.owners.clear()
        if (transfer.pageIndex == transfer.pageCount) {
            sub.sentRevisions[transfer.tile] = transfer.wireRevision
            sub.sentStorageRevisions[transfer.tile] = transfer.storageRevision
            sub.unavailableNotified.remove(transfer.tile)
            cancelTransfer(sub)
        }
        return true
    }

    private fun signalUnavailable(player: ServerPlayer, sub: ServerTraceSubscription, tile: TileCoordinate) {
        unavailableReads++
        if (!sub.unavailableNotified.add(tile)) return
        val packet = TraceTileUnavailablePacket(sub.generation, sub.dimension, tile.chunkX, tile.chunkZ)
        if (!sendMeasured(player, packet, player.uuid)) sub.unavailableNotified.remove(tile)
    }
    private fun restart(sub: ServerTraceSubscription, tile: TileCoordinate) {
        revisionRestarts++
        cancelTransfer(sub)
        sub.queued.addFirst(tile)
    }
    private fun cancelTransfer(sub: ServerTraceSubscription) {
        if (sub.transfer != null) budget.releasePinned(sub.connection.uuid, PAGE_RESERVATION_BYTES)
        sub.transfer = null
    }
    private fun sendMeasured(player: ServerPlayer, packet: Any, id: UUID): Boolean {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        val bytes = try {
            runCatching {
                when (packet) {
                    is TraceTileSnapshotPacket -> packet.encode(buffer)
                    is TraceTileEvictPacket -> packet.encode(buffer)
                    is TraceTileUnavailablePacket -> packet.encode(buffer)
                    else -> error("unexpected trace stream packet")
                }
                buffer.readableBytes()
            }.onFailure {
                sendFailures++
                if (sendFailures == 1L || sendFailures % 200L == 0L)
                    log.warn("Trace tile encoding failed for {}: {}", id, it.message)
            }.getOrNull()
        } finally { buffer.release() }
        if (bytes == null || !budget.canEncode(id, bytes)) return false
        return runCatching { send(player, packet) }.onFailure {
            sendFailures++
            if (sendFailures == 1L || sendFailures % 200L == 0L)
                log.warn("Trace tile delivery failed for {}: {}", id, it.message)
        }.isSuccess
    }
    private fun visibleTrace(trace: FootTrace) = VisibleTraceDto(
        id = "", sequenceId = "", movementClass = trace.movementClass,
        x = trace.x, y = trace.y, z = trace.z, facingYaw = trace.facingYaw,
        strength = trace.strength, sequenceIndex = trace.sequenceIndex,
        kind = trace.kind, createdAt = trace.createdAt, support = trace.support,
    )
    private companion object {
        const val REQUEST_INTERVAL_TICKS = 5L
        const val PAGE_SIZE = 512
        const val PAGE_RESERVATION_BYTES = 512L * 1024L
        val OWNER_NAME = Regex("[A-Za-z0-9_]{1,16}")
    }
}
