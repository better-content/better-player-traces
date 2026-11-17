package com.bettercontent.betterplayertraces.logic

import com.bettercontent.betterplayertraces.config.TracesConfig
import com.bettercontent.betterplayertraces.storage.ShardRead
import com.bettercontent.betterplayertraces.storage.TraceShardState
import com.bettercontent.betterplayertraces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel

/** Round-robin loaded-chunk maintenance. Neither the archive cache nor a world scan is a chunk registry. */
class ErosionService(
    private val storage: TraceStorageManager,
    private val config: TracesConfig.Common,
) {
    private data class Cursor(var ordinal: Long, var authority: TraceShardState, val rainPass: Boolean)
    private val loadedChunks = java.util.TreeSet<Long>(compareBy<Long>({ BlockPos.getX(it) }, { BlockPos.getZ(it) }))
    private var lastChunk: Long? = null
    private val cursors = mutableMapOf<Long, Cursor>()
    private val nextRainTick = mutableMapOf<Long, Long>()

    fun onChunkLoaded(x: Int, z: Int) {
        val key = BlockPos.asLong(x, 0, z)
        loadedChunks.add(key)
    }

    fun onChunkUnloaded(x: Int, z: Int) {
        val key = BlockPos.asLong(x, 0, z)
        loadedChunks.remove(key)
        cursors.remove(key)
        nextRainTick.remove(key)
        // The next sorted successor is resolved on demand; no stale queue entry survives.
    }

    fun tick(level: ServerLevel, tick: Int, budget: Int = MAX_RECORDS_PER_TICK): Int {
        var visited = 0
        var chunks = 0
        while (visited < budget && chunks < MAX_CHUNKS_PER_TICK && loadedChunks.isNotEmpty()) {
            val key = lastChunk?.let(loadedChunks::higher) ?: loadedChunks.first()
            lastChunk = key
            val chunkX = BlockPos.getX(key)
            val chunkZ = BlockPos.getZ(key)
            if (level.chunkSource.getChunkNow(chunkX, chunkZ) == null) {
                onChunkUnloaded(chunkX, chunkZ)
                continue
            }
            chunks++
            visited++ // Charge an inspected chunk even if its archive is absent or not ready.
            when (val read = storage.maintenanceTile(chunkX, chunkZ)) {
                is ShardRead.Present -> {
                    var cursor = cursors[key]
                    if (cursor == null || cursor.authority !== read.value) {
                        val reloaded = cursor != null
                        // An evicted/reloaded authority has a new ordinal index. Never replay a
                        // partially eroded rain pass on unknown bytes; resume next interval.
                        val rain = cursor == null && tick.toLong() >= (nextRainTick[key] ?: tick.toLong() + RAIN_INTERVAL) &&
                            level.getRainLevel(0.0f) > 0f
                        cursor = Cursor(0L, read.value, rain)
                        cursors[key] = cursor
                        if (key !in nextRainTick || reloaded) nextRainTick[key] = tick.toLong() + RAIN_INTERVAL
                    }
                    var slice = 0
                    while (visited < budget && slice < RECORDS_PER_CHUNK_SLICE) {
                        val next = read.value.nextTraceInTile(chunkX, chunkZ, cursor.ordinal)
                        if (next == null) {
                            cursors.remove(key)
                            if (cursor.rainPass) nextRainTick[key] = tick.toLong() + RAIN_INTERVAL
                            break
                        }
                        cursor.ordinal = next.first
                        val trace = next.second
                        visited++
                        slice++
                        val support = trace.support
                        if (support != null) {
                            val supportChunk = level.chunkSource.getChunkNow(support.position.x shr 4, support.position.z shr 4)
                            if (supportChunk != null) {
                                val state = supportChunk.getBlockState(support.position)
                                if (state.isAir || BuiltInRegistries.BLOCK.getKey(state.block) != support.blockId) {
                                    storage.removeTraceById(trace.id, trace.blockPos)
                                    continue
                                }
                            }
                        }
                        if (cursor.rainPass && trace.surviving && level.chunkSource.getChunkNow(trace.blockPos.x shr 4, trace.blockPos.z shr 4) != null &&
                            level.canSeeSky(trace.blockPos) && level.isRainingAt(trace.blockPos)) {
                            storage.weakenFootprint(trace.id, trace.blockPos, config.rainExposureFactor.get())
                        }
                    }
                }
                ShardRead.Absent -> { cursors.remove(key); nextRainTick[key] = tick.toLong() + RAIN_INTERVAL }
                // Unknown archive bytes cannot authorize pruning or become an empty tile.
                ShardRead.Loading, is ShardRead.Unavailable, is ShardRead.Corrupt, is ShardRead.Unsupported -> Unit
            }
        }
        if (tick % 20 == 0) storage.tickFlush()
        return visited
    }

    fun onFluidTick(blockPos: BlockPos) {
        storage.removeBySupport(blockPos)
        storage.removeByPosition(blockPos)
        for (dx in -1..1) for (dz in -1..1) {
            val p = BlockPos(blockPos.x + dx, blockPos.y, blockPos.z + dz)
            storage.removeBySupport(p)
            storage.removeByPosition(p)
        }
    }

    companion object {
        internal const val MAX_RECORDS_PER_TICK = 4096
        private const val RECORDS_PER_CHUNK_SLICE = 128
        private const val MAX_CHUNKS_PER_TICK = 64
        private const val RAIN_INTERVAL = 80L
    }
}
