package com.bettercontent.betterplayertraces

import com.bettercontent.betterplayertraces.config.TracesConfig
import com.bettercontent.betterplayertraces.events.TracesForgeEvents
import com.bettercontent.betterplayertraces.commands.TracesCommands
import com.bettercontent.betterplayertraces.item.TracesItems
import com.bettercontent.betterplayertraces.logic.TraceQueryService
import com.bettercontent.betterplayertraces.network.TracesNetwork
import com.bettercontent.betterplayertraces.server.TraceServerRuntime
import com.bettercontent.betterplayertraces.storage.TraceStorageManager
import com.bettercontent.betterplayertraces.storage.ArenaDuelArchiveApi
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import java.util.WeakHashMap

@Mod(TracesMod.MOD_ID)
class TracesMod {
    init {
        val modBus = FMLJavaModLoadingContext.get().modEventBus
        TracesItems.REGISTRY.register(modBus)
        MinecraftForge.EVENT_BUS.register(TracesForgeEvents)
        MinecraftForge.EVENT_BUS.register(TracesCommands)

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, TracesConfig.serverSpec)
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, TracesConfig.clientSpec)

        TracesNetwork.register()
    }

    companion object {
        const val MOD_ID = "better_player_traces"

        private val runtimes = WeakHashMap<MinecraftServer, TraceServerRuntime>()

        fun getRuntime(server: MinecraftServer): TraceServerRuntime {
            return runtimes.getOrPut(server) { TraceServerRuntime(server) }
        }

        fun removeRuntime(server: MinecraftServer) {
            runtimes.remove(server)?.close()
        }

        fun storageFor(server: MinecraftServer, level: ServerLevel): TraceStorageManager =
            getRuntime(server).storage(level)

        fun queryService(): TraceQueryService = TraceQueryService()

        @JvmStatic fun appendArenaDuel(level: ServerLevel, arenaId: String, trace: ByteArray): Int =
            ArenaDuelArchiveApi.append(level, arenaId, trace)

        @JvmStatic fun arenaDuelCount(level: ServerLevel, arenaId: String): Int =
            ArenaDuelArchiveApi.count(level, arenaId)

        @JvmStatic fun arenaDuel(level: ServerLevel, arenaId: String, index: Int): ByteArray? =
            ArenaDuelArchiveApi.get(level, arenaId, index)
    }
}
