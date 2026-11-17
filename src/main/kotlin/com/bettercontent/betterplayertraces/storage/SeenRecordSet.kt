package com.bettercontent.betterplayertraces.storage

import java.util.UUID

/** Exact (annotation, viewer) index and stable persisted iteration order. */
internal class SeenRecordSet : AbstractMutableCollection<SeenStateRecord>() {
    private val records = LinkedHashMap<Pair<UUID, UUID>, SeenStateRecord>()
    override val size: Int get() = records.size
    override fun add(element: SeenStateRecord): Boolean = records.put(element.annotationId to element.playerId, element) == null
    override fun iterator(): MutableIterator<SeenStateRecord> = records.values.iterator()
    override fun remove(element: SeenStateRecord): Boolean = records.remove(element.annotationId to element.playerId, element)
    fun byKey(annotation: UUID, player: UUID): SeenStateRecord? = records[annotation to player]
    fun replace(element: SeenStateRecord) { require((element.annotationId to element.playerId) in records); add(element) }
}
