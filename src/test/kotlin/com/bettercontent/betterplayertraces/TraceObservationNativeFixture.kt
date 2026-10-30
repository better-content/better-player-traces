package com.bettercontent.betterplayertraces

import com.bettercontent.betterplayertraces.client.TracesClientState
import com.bettercontent.betterplayertraces.client.TraceObservations
import com.bettercontent.betterplayertraces.client.TracesClientConfig
import com.bettercontent.betterplayertraces.domain.FootTrace
import com.bettercontent.betterplayertraces.domain.MovementClass
import com.bettercontent.betterplayertraces.logic.TraceSupportResolver
import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.logging.LogUtils
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.util.UUID

/** Nonshipping, explicitly enabled disposable multiplayer fixture. Never injects client observations. */
@Mod.EventBusSubscriber(modid = "better_player_traces")
object TraceObservationNativeServer {
    private val log = LogUtils.getLogger()
    @SubscribeEvent @JvmStatic fun login(event: PlayerEvent.PlayerLoggedInEvent) {
        if (!java.lang.Boolean.getBoolean("traces.observationFixture")) return
        val player = event.entity as? ServerPlayer ?: return
        val level = player.serverLevel()
        for (x in -4..14) for (z in -10..2) {
            level.setBlockAndUpdate(BlockPos(x, 100, z), Blocks.SMOOTH_STONE.defaultBlockState())
            for (y in 101..105) level.setBlockAndUpdate(BlockPos(x, y, z), Blocks.AIR.defaultBlockState())
        }
        level.setDayTime(6000)
        player.server.playerList.op(player.gameProfile)
        val names = listOf("TraceAlice", "TraceBob", null, player.scoreboardName)
        names.forEachIndexed { index, name ->
            val id = if (index == 3) player.uuid else UUID.nameUUIDFromBytes("OfflinePlayer:${name ?: "UnknownTrace"}".toByteArray())
            if (name != null) player.server.profileCache?.add(GameProfile(id, name))
            val point = Vec3(0.5 + index * 3, 101.0, -3.0)
            val surface = checkNotNull(TraceSupportResolver.resolve(level, point, 4))
            check(TracesMod.getRuntime(player.server).storage(level).addFootTrace(FootTrace(
                UUID.randomUUID(), level.dimension().location().toString(), surface.position.x, surface.position.y, surface.position.z,
                0f, MovementClass.WALK, 1f, UUID.randomUUID(), 0, level.gameTime, level.gameTime, true, id, support = surface.support)))
        }
        player.teleportTo(level, 0.5, 101.0, -6.5, 0f, 25f)
        log.info("TRACE_OBSERVATION_FIXTURE server_seeded profiles=2 unresolved=1 own=1 observer={}", player.scoreboardName)
    }
}

@Mod.EventBusSubscriber(modid = "better_player_traces", value = [Dist.CLIENT])
object TraceObservationNativeClient {
    private val log = LogUtils.getLogger()
    private var liveTicks = 0
    private fun capture(mc: Minecraft, label: String, expected: String? = null, own: Boolean = false) {
        val selected = TraceObservations.selectedOwner
        if (expected != null) check(selected?.name == expected) { "Expected $expected, observed $selected" }
        if (own) check(selected?.id == mc.player?.uuid) { "Own trace attribution failed: $selected" }
        Screenshot.grab(mc.gameDirectory, "observation-$label.png", mc.mainRenderTarget) { result ->
            log.info("TRACE_OBSERVATION_CAPTURE {} {} selected={}", label, result.string, selected)
        }
    }
    @SubscribeEvent @JvmStatic fun tick(event: TickEvent.ClientTickEvent) {
        if (!java.lang.Boolean.getBoolean("traces.observationFixture") || event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.screen != null || !TracesClientState.hasResponse) return
        liveTicks++
        when (liveTicks) {
            1 -> { TracesClientConfig.revealToggle.key = InputConstants.Type.KEYSYM.getOrCreate(71); KeyMapping.resetMapping() }
            35 -> capture(mc, "anonymous-hint")
            80 -> TracesClientState.overlayEnabled = true
            110 -> capture(mc, "alice", "TraceAlice")
            120 -> player.connection.sendCommand("tp @s 3.5 101 -6.5 0 25")
            155 -> capture(mc, "bob", "TraceBob")
            165 -> player.connection.sendCommand("tp @s 6.5 101 -6.5 0 25")
            200 -> { check(TraceObservations.selectedOwner != null && TraceObservations.selectedOwner?.name == null); capture(mc, "unknown") }
            210 -> player.connection.sendCommand("tp @s 9.5 101 -6.5 0 25")
            245 -> capture(mc, "own", own = true)
            255 -> mc.options.hideGui = true
            265 -> capture(mc, "hidden-hud")
            275 -> { mc.options.hideGui = false; mc.options.guiScale().set(3); mc.resizeDisplay() }
            295 -> capture(mc, "gui-scale-3", own = true)
            310 -> { log.info("TRACE_OBSERVATION_FIXTURE client_complete"); player.connection.sendCommand("stop"); mc.stop() }
        }
    }
}
