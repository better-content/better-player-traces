package com.bettercontent.betterplayertraces.client

import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

internal object TraceObservationGeometry {
    fun inside(center: Vec3, player: Vec3, horizontal: Double, vertical: Double): Boolean {
        val dx = center.x - player.x; val dz = center.z - player.z
        return dx * dx + dz * dz <= horizontal * horizontal && abs(center.y - player.y) <= vertical
    }

    fun aim(center: Vec3, origin: Vec3, direction: Vec3, clearView: () -> Boolean): Pair<Double, Double>? {
        val delta = center.subtract(origin)
        val along = delta.dot(direction)
        val perpendicular = sqrt((delta.lengthSqr() - along * along).coerceAtLeast(0.0))
        if (delta.lengthSqr() > 144.0 || along <= 0 || perpendicular > 0.6 || !clearView()) return null
        return perpendicular to along
    }
}
