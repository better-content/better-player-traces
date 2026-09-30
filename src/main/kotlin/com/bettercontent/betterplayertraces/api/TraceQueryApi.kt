package com.bettercontent.betterplayertraces.api

import com.bettercontent.betterplayertraces.domain.TraceQueryResult
import com.bettercontent.betterplayertraces.domain.TrafficPotential
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

interface TraceQueryApi {
    fun tracesWithin(level: Level, boundsMin: BlockPos, boundsMax: BlockPos): TraceQueryResult
    fun trafficPotential(level: Level, pos: BlockPos): TrafficPotential
}
