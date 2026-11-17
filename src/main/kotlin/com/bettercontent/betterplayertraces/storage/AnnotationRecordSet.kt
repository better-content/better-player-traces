package com.bettercontent.betterplayertraces.storage

import com.bettercontent.betterplayertraces.domain.TraceAnnotation
import java.util.UUID

/** Stable insertion order without whole-shard list shifts for one note's update or deletion. */
internal class AnnotationRecordSet : AbstractMutableCollection<TraceAnnotation>() {
    private val records = LinkedHashMap<UUID, TraceAnnotation>()
    override val size: Int get() = records.size
    override fun add(element: TraceAnnotation): Boolean = records.put(element.id, element) == null
    override fun iterator(): MutableIterator<TraceAnnotation> = records.values.iterator()
    override fun remove(element: TraceAnnotation): Boolean = records.remove(element.id, element)
    fun replace(element: TraceAnnotation) { require(element.id in records); records[element.id] = element }
}
