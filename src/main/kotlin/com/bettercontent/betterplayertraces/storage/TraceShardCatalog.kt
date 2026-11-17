package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.util.TraceShardId

/** Server-owned publication catalog survives LRU eviction; the archive is the rebuild source. */
internal class TraceShardCatalog : MutableSet<TraceShardId> by linkedSetOf<TraceShardId>() {
    fun published(id: TraceShardId) { add(id) }
    fun candidates(live: Collection<TraceShardId>): Set<TraceShardId> = this.toSet() + live
}
