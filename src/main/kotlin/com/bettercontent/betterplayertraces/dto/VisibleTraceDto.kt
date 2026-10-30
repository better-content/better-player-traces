package com.bettercontent.betterplayertraces.dto

import com.bettercontent.betterplayertraces.domain.MovementClass
import com.bettercontent.betterplayertraces.domain.TraceKind
import com.bettercontent.betterplayertraces.domain.TraceSupport

/** Public attribution only: no skin properties or unrelated profiles are sent. */
data class TraceOwner(val id: java.util.UUID, val name: String? = null) {
    init {
        require(id != java.util.UUID(0L, 0L)) { "invalid trace owner UUID" }
        require(name == null || name.matches(Regex("[A-Za-z0-9_]{1,16}"))) { "invalid trace owner name" }
    }
}

data class VisibleTraceDto(
    val id: String,
    val sequenceId: String,
    val movementClass: MovementClass,
    val x: Double,
    val y: Double,
    val z: Double,
    val facingYaw: Float,
    val strength: Float,
    val sequenceIndex: Int,
    val own: Boolean = false,
    val kind: TraceKind = TraceKind.FOOTPRINT,
    val createdAt: Long = 0L,
    val support: TraceSupport? = null,
    val owner: TraceOwner? = null,
) {
    constructor(
        id: String, sequenceId: String, movementClass: MovementClass, x: Int, y: Int, z: Int,
        strength: Float, sequenceIndex: Int, own: Boolean = false,
    ) : this(id, sequenceId, movementClass, x + 0.5, y.toDouble(), z + 0.5, 0f, strength, sequenceIndex, own)
}
