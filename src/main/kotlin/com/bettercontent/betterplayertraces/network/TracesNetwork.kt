package com.bettercontent.betterplayertraces.network

import com.bettercontent.betterplayertraces.TracesMod
import com.bettercontent.betterplayertraces.config.TracesConfig
import com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import java.util.function.Supplier
import java.util.UUID
import com.mojang.logging.LogUtils

internal fun noteCommandMayStage(expectedDimension: String, actualDimension: String, sameConnection: Boolean): Boolean =
    sameConnection && expectedDimension.isNotEmpty() && expectedDimension == actualDimension

internal fun nextTraceTileWireRevision(lastSent: Long?, previousStorageRevision: Long?, storageRevision: Long, previousCleanupCount: Int?, cleanupCount: Int): Long {
    require(storageRevision >= 0L && cleanupCount >= 0)
    if (lastSent == null) return storageRevision
    val changed = previousStorageRevision != storageRevision || previousCleanupCount != cleanupCount
    if (!changed) return lastSent
    return maxOf(storageRevision, if (lastSent == Long.MAX_VALUE) Long.MAX_VALUE else lastSent + 1L)
}

object TracesNetwork {
    private val logger = LogUtils.getLogger()
    internal const val PROTOCOL = "better_player_traces_v11"
    private val streamer by lazy {
        TraceServerStream(
            send = { player, packet -> channel.send(PacketDistributor.PLAYER.with { player }, packet) },
            metadata = ::sendQueryResponse,
        )
    }
    private val channel: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath("better_player_traces", "main"),
        { PROTOCOL },
        { it == PROTOCOL },
        { it == PROTOCOL }
    )

    fun register() {
        channel.registerMessage(0, TraceQueryRequestPacket::class.java, TraceQueryRequestPacket::encode, TraceQueryRequestPacket.Companion::decode) { msg, context ->
            onRequest(msg, context)
        }
        channel.registerMessage(1, TraceQueryResponsePacket::class.java, TraceQueryResponsePacket::encode, TraceQueryResponsePacket.Companion::decode) { msg, context ->
            onResponse(msg, context)
        }
        channel.registerMessage(2, TraceAnnotationsSeenPacket::class.java, TraceAnnotationsSeenPacket::encode, TraceAnnotationsSeenPacket.Companion::decode) { msg, context ->
            onAnnotationsSeen(msg, context)
        }
        channel.registerMessage(3, AnnotationCreatePacket::class.java, AnnotationCreatePacket::encode, AnnotationCreatePacket.Companion::decode) { msg, context -> onCreate(msg, context) }
        channel.registerMessage(4, AnnotationUpdatePacket::class.java, AnnotationUpdatePacket::encode, AnnotationUpdatePacket.Companion::decode) { msg, context -> onUpdate(msg, context) }
        channel.registerMessage(5, AnnotationDeletePacket::class.java, AnnotationDeletePacket::encode, AnnotationDeletePacket.Companion::decode) { msg, context -> onDelete(msg, context) }
        channel.registerMessage(6, DeathCaptureRequestPacket::class.java, DeathCaptureRequestPacket::encode, DeathCaptureRequestPacket.Companion::decode) { msg, context -> onDeathCaptureRequest(msg, context) }
        channel.registerMessage(7, DeathEchoSubmitPacket::class.java, DeathEchoSubmitPacket::encode, DeathEchoSubmitPacket.Companion::decode) { msg, context -> onDeathEchoSubmit(msg, context) }
        channel.registerMessage(8, AnnotationMutationResultPacket::class.java, AnnotationMutationResultPacket::encode, AnnotationMutationResultPacket.Companion::decode) { msg, context -> onMutationResult(msg, context) }
        channel.registerMessage(9, AnnotationEchoRequestPacket::class.java, AnnotationEchoRequestPacket::encode, AnnotationEchoRequestPacket.Companion::decode) { msg, context -> onAnnotationEchoRequest(msg, context) }
        channel.registerMessage(10, AnnotationEchoResponsePacket::class.java, AnnotationEchoResponsePacket::encode, AnnotationEchoResponsePacket.Companion::decode) { msg, context -> onAnnotationEchoResponse(msg, context) }
        channel.registerMessage(11, TraceTileSnapshotPacket::class.java, TraceTileSnapshotPacket::encode, TraceTileSnapshotPacket.Companion::decode) { msg, context -> onTraceTileSnapshot(msg, context) }
        channel.registerMessage(12, TraceTileEvictPacket::class.java, TraceTileEvictPacket::encode, TraceTileEvictPacket.Companion::decode) { msg, context -> onTraceTileEvict(msg, context) }
        channel.registerMessage(13, TraceTileUnavailablePacket::class.java, TraceTileUnavailablePacket::encode,
            TraceTileUnavailablePacket.Companion::decode) { msg, context -> onTraceTileUnavailable(msg, context) }
    }

    private fun onRequest(msg: TraceQueryRequestPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        val sender = context.sender ?: return
        context.enqueueWork {
            if (sender.server.playerList.getPlayer(sender.uuid) === sender)
                streamer.request(sender, msg.radius.coerceIn(2, TracesConfig.common.maxRenderDistance.get()))
        }
        context.packetHandled = true
    }

    private fun sendQueryResponse(sender: net.minecraft.server.level.ServerPlayer, radius: Int,
                                  subscription: ServerTraceSubscription,
                                  visibleAnnotations: List<com.bettercontent.betterplayertraces.dto.VisibleAnnotationDto>,
                                  anchor: net.minecraft.core.BlockPos) {
            val level = sender.serverLevel()
            val pos = anchor
            val blockRadius = radius * 16
            val min = net.minecraft.core.BlockPos(pos.x - blockRadius, level.minBuildHeight, pos.z - blockRadius)
            val max = net.minecraft.core.BlockPos(pos.x + blockRadius, level.maxBuildHeight - 1, pos.z + blockRadius)
            val runtime = TracesMod.getRuntime(level.server)
            val guidance = runtime.guidance(level).query(sender)
            val deathData = runtime.deathTraces(level)
            val minX = min.x.toDouble(); val maxX = max.x.toDouble()
            val minZ = min.z.toDouble(); val maxZ = max.z.toDouble()
            val bloodPools = deathData.poolsWithin(minX, maxX, minZ, maxZ)
                .sortedBy {
                    val dx = it.x - (pos.x + 0.5); val dz = it.z - (pos.z + 0.5)
                    dx * dx + dz * dz
                }
                .take(TracesConfig.common.maxPayloadBloodPools.get())
                .map { com.bettercontent.betterplayertraces.dto.VisibleBloodPoolDto(it.id.toString(), it.ownerName, it.x, it.y, it.z, it.createdAt) }
            val deathEchoes = deathData.echoesWithin(minX, maxX, minZ, maxZ)
                .sortedBy {
                    val dx = it.x - (pos.x + 0.5); val dz = it.z - (pos.z + 0.5)
                    dx * dx + dz * dz
                }
                .take(TracesConfig.common.maxPayloadDeathEchoes.get())
                .map { com.bettercontent.betterplayertraces.dto.VisibleDeathEchoDto(it.id.toString(), it.ownerName, it.x, it.y, it.z, it.createdAt, it.encodedClip) }
            val dimension = level.dimension().location().toString()
            val response = TraceQueryResponsePacket(
                traces = emptyList(),
                annotations = visibleAnnotations,
                guidanceRoutes = guidance.routes,
                guidanceTotal = guidance.totalReachable,
                guidanceTruncated = guidance.truncated,
                bloodPools = bloodPools,
                deathEchoes = deathEchoes,
                subscriptionGeneration = subscription.generation,
                dimension = dimension,
                loginGameTime = subscription.loginGameTime,
            )
            if (java.lang.Boolean.getBoolean("traces.visualValidation")) {
                logger.debug(
                    "Trace query response: traces={}, annotations={}, annotationEchoes={}, bloodPools={}, deathEchoes={}, radius={}",
                    response.traces.size, response.annotations.size, visibleAnnotations.count { it.hasEcho },
                    response.bloodPools.size, response.deathEchoes.size, radius,
                )
            }
            channel.send(PacketDistributor.PLAYER.with { sender }, response)
    }

    private fun onResponse(msg: TraceQueryResponsePacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork {
            logger.debug("Trace query payload received on client: traces={}, annotations={}", msg.traces.size, msg.annotations.size)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                Runnable { com.bettercontent.betterplayertraces.client.TracesClientNetworkHandler.accept(msg) }
            }
        }
        context.packetHandled = true
    }

    private fun onTraceTileSnapshot(msg: TraceTileSnapshotPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
                if (net.minecraft.client.Minecraft.getInstance().level?.dimension()?.location()?.toString() != msg.dimension)
                    return@Runnable
                com.bettercontent.betterplayertraces.client.TracesClientState.acceptTraceTilePage(
                    msg.generation,
                    msg.dimension,
                    com.bettercontent.betterplayertraces.client.TraceTileKey(msg.chunkX, msg.chunkZ),
                    msg.revision,
                    msg.pageIndex,
                    msg.pageCount,
                    msg.traces.map { it.copy(own = it.owner != null && it.owner.id == net.minecraft.client.Minecraft.getInstance().player?.uuid) },
                )
            } }
        }
        context.packetHandled = true
    }

    private fun onTraceTileUnavailable(msg: TraceTileUnavailablePacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork { DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
            com.bettercontent.betterplayertraces.client.TracesClientState.markTraceTileUnavailable(
                msg.generation, msg.dimension,
                com.bettercontent.betterplayertraces.client.TraceTileKey(msg.chunkX, msg.chunkZ),
            )
        } } }
        context.packetHandled = true
    }

    private fun onTraceTileEvict(msg: TraceTileEvictPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
                com.bettercontent.betterplayertraces.client.TracesClientState.evictTraceTiles(
                    msg.generation,
                    msg.dimension,
                    msg.tiles.map { com.bettercontent.betterplayertraces.client.TraceTileKey(it.chunkX, it.chunkZ) },
                )
            } }
        }
        context.packetHandled = true
    }

    private fun onAnnotationsSeen(msg: TraceAnnotationsSeenPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        val sender = context.sender ?: return
        context.enqueueWork {
            val annotations = TracesMod.getRuntime(sender.server).annotations(sender.serverLevel())
            msg.annotations.forEach {
                val id = runCatching { UUID.fromString(it.id) }.getOrNull() ?: return@forEach
                annotations.acknowledgeViewed(sender, id, it.revision)
            }
        }
        context.packetHandled = true
    }

    private fun onDeathCaptureRequest(msg: DeathCaptureRequestPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                Runnable { com.bettercontent.betterplayertraces.client.death.DeathEchoRecorder.onDeathConfirmed(msg) }
            }
        }
        context.packetHandled = true
    }

    private fun onDeathEchoSubmit(msg: DeathEchoSubmitPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        val sender = context.sender ?: return
        context.enqueueWork {
            runCatching { TracesMod.getRuntime(sender.server).acceptDeathEcho(sender, msg) }
                .onFailure { logger.warn("Rejected death echo from {}: {}", sender.scoreboardName, it.message) }
        }
        context.packetHandled = true
    }

    private fun onCreate(msg: AnnotationCreatePacket, ctx: Supplier<NetworkEvent.Context>) = mutateAnnotation(ctx, msg.requestId, msg.sessionId, msg.dimension) { sender, report ->
        if (msg.echoMutation == com.bettercontent.betterplayertraces.domain.EchoMutation.REMOVE ||
            (msg.echoMutation == com.bettercontent.betterplayertraces.domain.EchoMutation.REPLACE) != (msg.encodedClip != null))
            com.bettercontent.betterplayertraces.storage.NoteMutationResult.Rejected(com.bettercontent.betterplayertraces.storage.NoteRejectReason.INVALID_INPUT)
        else TracesMod.getRuntime(sender.server).annotations(sender.serverLevel()).submitCreate(
            sender.serverLevel(), sender, msg.requestId, net.minecraft.core.BlockPos.of(msg.target),
            msg.text, msg.icon, msg.color, msg.encodedClip, report)
    }

    private fun onUpdate(msg: AnnotationUpdatePacket, ctx: Supplier<NetworkEvent.Context>) = mutateAnnotation(ctx, msg.requestId, msg.sessionId, msg.dimension) { sender, report ->
        val id = runCatching { UUID.fromString(msg.id) }.getOrNull()
        if (id == null) com.bettercontent.betterplayertraces.storage.NoteMutationResult.Rejected(com.bettercontent.betterplayertraces.storage.NoteRejectReason.INVALID_INPUT)
        else TracesMod.getRuntime(sender.server).annotations(sender.serverLevel()).submitUpdate(
            sender.serverLevel(), sender, msg.requestId, id, msg.expectedRevision,
            msg.text, msg.icon, msg.color, msg.echoMutation, msg.encodedClip, report)
    }

    private fun onDelete(msg: AnnotationDeletePacket, ctx: Supplier<NetworkEvent.Context>) = mutateAnnotation(ctx, msg.requestId, msg.sessionId, msg.dimension) { sender, report ->
        val id = runCatching { UUID.fromString(msg.id) }.getOrNull()
        if (id == null) com.bettercontent.betterplayertraces.storage.NoteMutationResult.Rejected(com.bettercontent.betterplayertraces.storage.NoteRejectReason.INVALID_INPUT)
        else TracesMod.getRuntime(sender.server).annotations(sender.serverLevel()).submitDelete(
            sender.serverLevel(), sender, msg.requestId, id, msg.expectedRevision, report)
    }

    private fun mutateAnnotation(
        ctx: Supplier<NetworkEvent.Context>, requestId: UUID, sessionId: UUID, expectedDimension: String,
        mutation: (net.minecraft.server.level.ServerPlayer, (com.bettercontent.betterplayertraces.storage.NoteMutationResult) -> Unit) -> com.bettercontent.betterplayertraces.storage.NoteMutationResult,
    ) {
        val context = ctx.get(); val sender = context.sender ?: return
        context.enqueueWork {
            val dimension = sender.serverLevel().dimension().location().toString()
            val report: (com.bettercontent.betterplayertraces.storage.NoteMutationResult) -> Unit = { result ->
                // A late writer completion must not target a new connection or dimension.
                if (noteCommandMayStage(dimension, sender.serverLevel().dimension().location().toString(),
                        sender.server.playerList.getPlayer(sender.uuid) === sender))
                    runCatching { channel.send(PacketDistributor.PLAYER.with { sender }, noteResponse(requestId, sessionId, dimension, result)) }
                        .onFailure { logger.warn("Unable to deliver annotation publication result for {}", requestId, it) }
            }
            val initial = try {
                if (!noteCommandMayStage(expectedDimension, dimension,
                        sender.server.playerList.getPlayer(sender.uuid) === sender))
                    com.bettercontent.betterplayertraces.storage.NoteMutationResult.Rejected(com.bettercontent.betterplayertraces.storage.NoteRejectReason.INVALID_INPUT)
                else mutation(sender, report)
            } catch (error: Exception) {
                logger.warn("Annotation command rejected before staging: {}", error.message)
                com.bettercontent.betterplayertraces.storage.NoteMutationResult.Rejected(com.bettercontent.betterplayertraces.storage.NoteRejectReason.STORAGE_UNAVAILABLE)
            }
            report(initial)
        }
        context.packetHandled = true
    }

    private fun noteResponse(
        requestId: UUID, sessionId: UUID, dimension: String,
        result: com.bettercontent.betterplayertraces.storage.NoteMutationResult,
    ): AnnotationMutationResultPacket = when (result) {
        is com.bettercontent.betterplayertraces.storage.NoteMutationResult.Pending ->
            AnnotationMutationResultPacket(requestId, sessionId, dimension, NoteWireStatus.PENDING, "", 0)
        is com.bettercontent.betterplayertraces.storage.NoteMutationResult.Committed ->
            AnnotationMutationResultPacket(requestId, sessionId, dimension, NoteWireStatus.COMMITTED,
                result.annotationId?.toString().orEmpty(), result.revision, deleted = result.annotation == null)
        is com.bettercontent.betterplayertraces.storage.NoteMutationResult.Indeterminate ->
            AnnotationMutationResultPacket(requestId, sessionId, dimension, NoteWireStatus.INDETERMINATE, "", 0)
        is com.bettercontent.betterplayertraces.storage.NoteMutationResult.Rejected ->
            AnnotationMutationResultPacket(requestId, sessionId, dimension, NoteWireStatus.REJECTED, "", 0, result.reason)
    }

    private fun onMutationResult(msg: AnnotationMutationResultPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork { DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
            com.bettercontent.betterplayertraces.client.AnnotationDrafts.accept(msg)
        } } }
        context.packetHandled = true
    }

    private fun onAnnotationEchoRequest(msg: AnnotationEchoRequestPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get(); val sender = context.sender ?: return
        context.enqueueWork {
            val level = sender.serverLevel()
            val subscription = streamer.subscription(sender.uuid)
            if (subscription == null || subscription.connection !== sender || subscription.generation != msg.generation ||
                subscription.dimension != msg.dimension || level.dimension().location().toString() != msg.dimension) return@enqueueWork
            val id = runCatching { UUID.fromString(msg.annotationId) }.getOrNull()
            val read = runCatching { id?.let { TracesMod.getRuntime(sender.server).storage(level).annotationById(it) } }
            val note = read.getOrNull()?.takeIf { it.revision == msg.echoRevision && it.team == GLOBAL_TEAM &&
                sender.distanceToSqr(it.position.x + 0.5, it.position.y + 0.5, it.position.z + 0.5) <= 1024.0 * 1024.0 }
            val clip = note?.encodedEcho
            channel.send(PacketDistributor.PLAYER.with { sender }, AnnotationEchoResponsePacket(
                msg.annotationId, msg.echoRevision, clip, msg.generation, msg.dimension,
                retryable = read.exceptionOrNull() is com.bettercontent.betterplayertraces.storage.TraceReadUnavailableException,
            ))
            if (java.lang.Boolean.getBoolean("traces.visualValidation")) {
                logger.debug("Annotation echo response: annotation={}, revision={}, bytes={}", msg.annotationId, msg.echoRevision, clip?.size ?: 0)
            }
        }
        context.packetHandled = true
    }

    private fun onAnnotationEchoResponse(msg: AnnotationEchoResponsePacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork { DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
            com.bettercontent.betterplayertraces.client.TracesClientState.acceptAnnotationEcho(msg)
        } } }
        context.packetHandled = true
    }

    fun requestNearby(radius: Int) {
        channel.sendToServer(TraceQueryRequestPacket(radius.coerceIn(2, 32)))
    }

    fun onPlayerLogin(player: net.minecraft.server.level.ServerPlayer) = streamer.login(player)

    fun onPlayerLogout(player: net.minecraft.server.level.ServerPlayer) = streamer.logout(player)

    fun onServerTick(server: net.minecraft.server.MinecraftServer) = streamer.tick(server)

    fun onServerStopping() = streamer.clear()

    fun acknowledgeAnnotations(annotations: List<com.bettercontent.betterplayertraces.dto.VisibleAnnotationDto>) {
        if (annotations.isNotEmpty()) {
            channel.sendToServer(TraceAnnotationsSeenPacket(annotations.map { SeenAnnotation(it.id, it.revision) }))
        }
    }

    fun createAnnotation(packet: AnnotationCreatePacket) = channel.sendToServer(packet)
    fun updateAnnotation(packet: AnnotationUpdatePacket) = channel.sendToServer(packet)
    fun deleteAnnotation(packet: AnnotationDeletePacket) = channel.sendToServer(packet)
    fun requestDeathEcho(player: net.minecraft.server.level.ServerPlayer, packet: DeathCaptureRequestPacket) =
        channel.send(PacketDistributor.PLAYER.with { player }, packet)
    fun submitDeathEcho(packet: DeathEchoSubmitPacket) = channel.sendToServer(packet)
    fun requestAnnotationEcho(id: String, revision: Int, generation: Long, dimension: String) =
        channel.sendToServer(AnnotationEchoRequestPacket(id, revision, generation, dimension))

}
