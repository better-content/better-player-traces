package com.bettercontent.betterplayertraces.client

import com.bettercontent.betterplayertraces.network.TraceQueryResponsePacket

object TracesClientNetworkHandler {
    fun accept(payload: TraceQueryResponsePacket) {
        if (net.minecraft.client.Minecraft.getInstance().level?.dimension()?.location()?.toString() != payload.dimension) return
        if (!TracesClientState.beginTraceSubscription(
            payload.subscriptionGeneration,
            payload.dimension,
            payload.loginGameTime,
        )) return
        TracesClientState.acceptNetworkPayload(payload)
        if (com.bettercontent.betterplayertraces.config.TracesConfig.client.visualDiagnostics.get()) {
            TracesClientLog.LOGGER.info(
                "TRACES_VISUAL_READY traces={} annotations={} overlay={}",
                TracesClientState.lastPayloadTraceCount,
                payload.annotations.size,
                TracesClientState.overlayEnabled,
            )
        }
    }
}
