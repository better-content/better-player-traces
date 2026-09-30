package com.bettercontent.betterplayertraces.client

import com.bettercontent.betterplayertraces.network.TraceQueryResponsePacket

object TracesClientNetworkHandler {
    fun accept(payload: TraceQueryResponsePacket) {
        TracesClientState.beginTraceSubscription(
            payload.subscriptionGeneration,
            payload.dimension,
            payload.loginGameTime,
        )
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
