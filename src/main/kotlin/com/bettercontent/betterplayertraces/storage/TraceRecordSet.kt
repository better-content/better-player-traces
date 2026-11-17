package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.domain.FootTrace
import java.util.UUID

/** Insertion-ordered by stable ID; removing an erased trace never shifts a whole shard's list. */
internal class TraceRecordSet : AbstractMutableCollection<FootTrace>() {
    private val records = LinkedHashMap<UUID, FootTrace>()
    override val size: Int get() = records.size
    override fun add(element: FootTrace): Boolean = records.put(element.id, element) == null
    override fun iterator(): MutableIterator<FootTrace> = records.values.iterator()
    override fun remove(element: FootTrace): Boolean = records.remove(element.id, element)
    fun byId(id: UUID): FootTrace? = records[id]
    fun replace(element: FootTrace) { require(element.id in records); records[element.id] = element }
}
