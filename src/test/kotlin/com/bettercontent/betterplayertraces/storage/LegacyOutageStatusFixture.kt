package com.bettercontent.betterplayertraces.storage

/** Preserves original journal-era diagnostic assertions without claiming the new runtime journals queues. */
internal object LegacyOutageStatusFixture {
    fun format(frontier: StorageOutageFrontier): String =
        "storage outage frontier: rejected captures=${frontier.rejectedCaptures} " +
            "annotations=${frontier.rejectedAnnotations} affected regions=${frontier.affectedShards.size} " +
            "deferred=${frontier.deferredByKind.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name.lowercase()}=${it.value}" }} " +
            "accepted deferred mutations durably journaled; diagnostic counters are memory-only"
}
