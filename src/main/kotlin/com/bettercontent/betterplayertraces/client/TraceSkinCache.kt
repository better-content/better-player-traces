package com.bettercontent.betterplayertraces.client

import java.util.UUID

/** Main-thread cache with a generation token: old-world async completions can never repopulate it. */
internal class TraceSkinCache<T>(private val capacity: Int = 256) {
    private var generation = 0L
    private val values = LinkedHashMap<UUID, T>()

    fun getOrRequest(id: UUID, fallback: T, request: ((T) -> Unit) -> Unit): T {
        values[id]?.let { return it }
        if (values.size >= capacity) clear()
        values[id] = fallback
        val token = generation
        request { resolved -> if (token == generation && values.containsKey(id)) values[id] = resolved }
        return values.getValue(id)
    }

    fun clear() { generation++; values.clear() }
}
