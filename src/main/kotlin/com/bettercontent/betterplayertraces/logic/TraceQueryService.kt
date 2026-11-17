package com.bettercontent.betterplayertraces.logic

import com.bettercontent.betterplayertraces.TracesMod
import com.bettercontent.betterplayertraces.api.TraceQueryApi
import com.bettercontent.betterplayertraces.domain.TraceQueryResult
import com.bettercontent.betterplayertraces.domain.TrafficPotential
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import com.bettercontent.betterplayertraces.util.Geometry

class TraceQueryService : TraceQueryApi {
    override fun tracesWithin(level: Level, boundsMin: BlockPos, boundsMax: BlockPos): TraceQueryResult {
        if (level !is ServerLevel) {
            return TraceQueryResult(emptyList(), emptyList(), boundsMin to boundsMax, trafficPotential(level, boundsMin))
        }
        val storage = TracesMod.getRuntime(level.server).storage(level)
        val traces = storage.queryTraces(boundsMin, boundsMax)
        val annotations = storage.queryAnnotations(boundsMin, boundsMax)
        val potential = trafficPotential(level, boundsMin)
        return TraceQueryResult(traces, annotations, boundsMin to boundsMax, potential)
    }

    override fun trafficPotential(level: Level, pos: BlockPos): TrafficPotential {
        if (level !is ServerLevel) {
            return TrafficPotential(level.toString(), pos.asLong(), 0, 0, 0f, 0f, 0, false, globalReady = false)
        }
        val runtime = TracesMod.getRuntime(level.server)
        val levelKey = level.dimension().location().toString()
        val min = BlockPos(pos.x - 48, level.minBuildHeight, pos.z - 48)
        val max = BlockPos(pos.x + 48, level.maxBuildHeight - 1, pos.z + 48)
        val storage = runtime.storage(level)
        val localTraces = storage.queryTraces(min, max)
        val localAlive = localTraces.filter { it.surviving }
        val localSurvivingStrength = localAlive.sumOf { it.strength.toDouble() }.toFloat()

        val shard = Geometry.worldToShard(pos)
        val regionalBounds = Geometry.shardToBounds(shard.first, shard.second)
        val regionalTraces = storage.queryTraces(
            BlockPos(regionalBounds.first.x, level.minBuildHeight, regionalBounds.first.z),
            BlockPos(regionalBounds.second.x, level.maxBuildHeight - 1, regionalBounds.second.z),
        )
        val regionalAlive = regionalTraces.filter { it.surviving }
        val regionalSurvivingStrength = regionalAlive.sumOf { it.strength.toDouble() }.toFloat()

        val globalStrength = storage.serverSurvivingStrength()
        val globalPercentile = localAlive.maxOfOrNull { it.strength }?.let(storage::serverPercentile)
        val globalReady = globalStrength != null && (localAlive.isEmpty() || globalPercentile != null)
        val serverSurvivingStrength = globalStrength ?: 0f
        val regionalShare = if (globalReady && serverSurvivingStrength > 0f) regionalSurvivingStrength / serverSurvivingStrength else 0f
        val alive = localTraces.count { it.surviving }
        val strength = localTraces.map { it.strength }
        return TrafficPotential(
            levelKey = levelKey,
            position = pos.asLong(),
            traceCount = localTraces.size,
            aliveCount = alive,
            maxStrength = strength.maxOrNull() ?: 0f,
            meanStrength = if (strength.isNotEmpty()) strength.average().toFloat() else 0f,
            sequenceCount = localTraces.map { it.sequenceId }.toSet().size,
            isRainExposed = level.isRaining,
            localSurvivingStrength = localSurvivingStrength,
            regionalSurvivingStrength = regionalSurvivingStrength,
            serverSurvivingStrength = serverSurvivingStrength,
            regionalShare = regionalShare,
            serverShare = if (globalReady && serverSurvivingStrength > 0f) localSurvivingStrength / serverSurvivingStrength else 0f,
            percentile = globalPercentile ?: 0f,
            globalReady = globalReady,
        )
    }
}
