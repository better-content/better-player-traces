package com.bettercontent.betterplayertraces.domain

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation

data class TraceSupport(
    val position: BlockPos,
    val blockId: ResourceLocation,
)
