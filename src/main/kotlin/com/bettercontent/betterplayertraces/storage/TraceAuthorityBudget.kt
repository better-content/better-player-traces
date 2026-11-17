package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.util.TraceShardId

/** Pure server-thread ledger. Queue bytes, pinned dirty bytes, and read-result bytes are distinct. */
internal class TraceAuthorityBudget {
    companion object { const val MAX_SHARD_BYTES = 64L * 1024L * 1024L }
    private val dirty = linkedMapOf<TraceShardId, Long>()
    private var dirtyBytes = 0L
    private var queueCount = 0
    private var queueBytes = 0L
    private var pendingNotes = 0
    private var borrowedReadBytes = 0L

    fun reserveDirty(id: TraceShardId, projected: Long, maxCount: Int, maxBytes: Long): Boolean {
        val prior = dirty[id]
        // The common pre-mutation path includes any pending positive note delta.
        if (projected < 0 || projected > MAX_SHARD_BYTES || (prior == null && dirty.size >= maxCount)) return false
        val total = dirtyBytes - (prior ?: 0L) + projected
        if (total > maxBytes || total < 0) return false
        dirtyBytes = total
        dirty[id] = projected
        return true
    }
    fun previousDirty(id: TraceShardId): Long? = dirty[id]
    fun restoreDirty(id: TraceShardId, prior: Long?) {
        dirtyBytes -= dirty.remove(id) ?: 0L
        if (prior != null) { dirty[id] = prior; dirtyBytes += prior }
    }
    fun releaseDirty(id: TraceShardId) = restoreDirty(id, null)
    fun dirtySnapshot(): Pair<Int, Long> = dirty.size to dirtyBytes

    fun reserveQueue(bytes: Int, maxCount: Int, maxBytes: Long, note: Boolean = false, write: Boolean = false): Boolean {
        if (bytes < 0 || queueCount >= maxCount) return false
        val ceiling = if (note || (write && pendingNotes > 0)) minOf(maxBytes, 64L * 1024L * 1024L) else maxBytes
        if (bytes.toLong() > ceiling - queueBytes) return false
        queueCount++
        queueBytes += bytes
        if (note) pendingNotes++
        return true
    }
    /** A failed note future has completed; its reserved count slot now owns the reconciliation read. */
    fun borrowNoteRead(bytes: Int, maxBytes: Long): Boolean {
        if (pendingNotes <= 0 || bytes < 0 || bytes.toLong() > maxBytes - queueBytes) return false
        queueBytes += bytes
        borrowedReadBytes += bytes
        return true
    }
    fun releaseBorrowedRead(bytes: Int) {
        check(bytes >= 0 && borrowedReadBytes >= bytes && queueBytes >= bytes)
        borrowedReadBytes -= bytes
        queueBytes -= bytes
    }
    fun releaseQueue(bytes: Int, note: Boolean = false) {
        check(queueCount > 0 && bytes >= 0 && queueBytes >= bytes && (!note || pendingNotes > 0))
        queueCount--
        queueBytes -= bytes
        if (note) pendingNotes--
    }
    fun resizeQueue(reserved: Int, exact: Int, maxBytes: Long): Boolean {
        require(reserved >= 0 && exact >= 0)
        if (exact <= reserved) { queueBytes -= reserved - exact; return true }
        val ceiling = if (pendingNotes > 0) minOf(maxBytes, 64L * 1024L * 1024L) else maxBytes
        val adjusted = queueBytes - reserved + exact
        if (adjusted > ceiling || adjusted < 0) return false
        queueBytes = adjusted
        return true
    }
    fun queueSnapshot(): Pair<Int, Long> = queueCount to queueBytes
}
