package com.bettercontent.betterplayertraces.network

import java.util.UUID

/** Provisional, uncalibrated ceilings; counts are examined work and actual packet encoding. */
internal data class TraceStreamLimits(
    val tilesPerPlayerTick: Int = 64,
    val tilesServerTick: Int = 512,
    val recordsPerPlayerWindow: Int = 4096,
    val recordsServerTick: Int = 4096,
    val bytesPerPlayerWindow: Int = 512 * 1024,
    val bytesServerTick: Int = 1024 * 1024,
    val pinnedPerPlayer: Long = 8L * 1024 * 1024,
    val pinnedServer: Long = 64L * 1024 * 1024,
)

internal class TraceStreamBudget(private var limits: TraceStreamLimits = TraceStreamLimits()) {
    private data class PlayerWindow(
        var started: Long, var tick: Long, var tiles: Int = 0,
        var records: Int = 0, var bytes: Int = 0, var pinned: Long = 0,
    )
    private val players = mutableMapOf<UUID, PlayerWindow>()
    private var tick = Long.MIN_VALUE
    private var serverTiles = 0
    private var serverRecords = 0
    private var serverBytes = 0
    private var serverPinned = 0L
    var saturatedTiles = 0L; private set
    var saturatedRecords = 0L; private set
    var saturatedBytes = 0L; private set
    var saturatedPinned = 0L; private set
    var examinedTiles = 0L; private set
    var examinedRecords = 0L; private set
    var encodedBytes = 0L; private set

    fun beginTick(now: Long, configured: TraceStreamLimits = limits) {
        limits = configured
        if (tick == now) return
        tick = now
        serverTiles = 0; serverRecords = 0; serverBytes = 0
    }
    private fun window(player: UUID): PlayerWindow {
        val current = players.getOrPut(player) { PlayerWindow(tick, tick) }
        if (tick - current.started >= 5L || tick < current.started) {
            current.started = tick; current.records = 0; current.bytes = 0
        }
        if (current.tick != tick) { current.tick = tick; current.tiles = 0 }
        return current
    }
    fun inspectTile(player: UUID): Boolean {
        val current = window(player)
        if (current.tiles >= limits.tilesPerPlayerTick || serverTiles >= limits.tilesServerTick) {
            saturatedTiles++; return false
        }
        current.tiles++; serverTiles++; examinedTiles++; return true
    }
    fun inspectRecord(player: UUID): Boolean {
        val current = window(player)
        if (current.records >= limits.recordsPerPlayerWindow || serverRecords >= limits.recordsServerTick) {
            saturatedRecords++; return false
        }
        current.records++; serverRecords++; examinedRecords++; return true
    }
    fun canEncode(player: UUID, size: Int): Boolean {
        require(size >= 0)
        val current = window(player)
        if (size > limits.bytesPerPlayerWindow - current.bytes || size > limits.bytesServerTick - serverBytes) {
            saturatedBytes++; return false
        }
        current.bytes += size; serverBytes += size; encodedBytes += size.toLong(); return true
    }
    fun reservePinned(player: UUID, size: Long): Boolean {
        require(size > 0)
        val current = window(player)
        if (size > limits.pinnedPerPlayer - current.pinned || size > limits.pinnedServer - serverPinned) {
            saturatedPinned++; return false
        }
        current.pinned += size; serverPinned += size; return true
    }
    fun releasePinned(player: UUID, size: Long) {
        val current = players[player] ?: return
        require(size >= 0L && current.pinned >= size && serverPinned >= size)
        current.pinned -= size; serverPinned -= size
    }
    fun forget(player: UUID) {
        val current = players.remove(player) ?: return
        serverPinned -= current.pinned
    }
    fun pinnedBytes() = serverPinned
    fun playerBytes(player: UUID) = players[player]?.bytes ?: 0
}
