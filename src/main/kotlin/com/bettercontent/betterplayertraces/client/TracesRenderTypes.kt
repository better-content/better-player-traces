package com.bettercontent.betterplayertraces.client

import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import com.bettercontent.betterplayertraces.domain.TraceKind

object TracesRenderTypes {
    private val footprintTexture = ResourceLocation.fromNamespaceAndPath("better_player_traces", "textures/effect/leg_contact.png")
    private val whiteTexture = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/white.png")
    private val bloodPoolTexture = ResourceLocation.fromNamespaceAndPath("better_player_traces", "textures/effect/blood_pool.png")

    val footprints: RenderType = RenderType.entityTranslucent(footprintTexture)
    private val arrival: RenderType = RenderType.entityTranslucent(
        ResourceLocation.fromNamespaceAndPath("better_player_traces", "textures/effect/icon_pin.png"),
    )
    private val departure: RenderType = RenderType.entityTranslucent(
        ResourceLocation.fromNamespaceAndPath("better_player_traces", "textures/effect/icon_direction.png"),
    )
    fun trace(kind: TraceKind): RenderType = when (kind) {
        TraceKind.FOOTPRINT -> footprints
        TraceKind.ARRIVAL -> arrival
        TraceKind.DEPARTURE -> departure
    }
    val traceTypes: Set<RenderType> = setOf(footprints, arrival, departure)
    private val noteTypes = com.bettercontent.betterplayertraces.domain.AnnotationComponents.icons.associateWith { icon ->
        RenderType.entityTranslucent(ResourceLocation.fromNamespaceAndPath("better_player_traces", "textures/effect/icon_$icon.png"))
    }
    fun note(icon: String): RenderType = noteTypes[icon] ?: noteTypes.getValue("pin")
    val guidance: RenderType = RenderType.entityTranslucent(whiteTexture)
    val bloodPools: RenderType = RenderType.entityTranslucent(bloodPoolTexture)
}
