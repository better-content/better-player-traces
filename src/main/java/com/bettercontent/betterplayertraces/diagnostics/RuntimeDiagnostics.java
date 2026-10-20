package com.bettercontent.betterplayertraces.diagnostics;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

/** One process-local budget; never retains world/player/menu references. */
@Mod.EventBusSubscriber(modid = "better_player_traces")
public final class RuntimeDiagnostics {
    public static final PlaytestLog LOG = new PlaytestLog("better_player_traces");
    private RuntimeDiagnostics() {}
    public static void initialize() {
        var mods = net.minecraftforge.fml.ModList.get();
        var own = mods.getModFileById("better_player_traces");
        String source = LOG.sourceIdentity(own == null ? null : own.getFile().findResource("META-INF", "better-content-source.properties"));
        String version = mods.getModContainerById("better_player_traces").map(c -> c.getModInfo().getVersion().toString()).orElse("UNKNOWN");
        LOG.status("common", version, source, () -> PlaytestLog.fields("enabled", true, "quark", mods.isLoaded("quark"), "playeranimator", mods.isLoaded("playeranimator")));
    }
    public static void event(String category, String event, String result, String reason) {
        if (!LOG.enabled(category)) return;
        LOG.event(category, event, "server", result, reason, () -> "");
    }
    public static PlaytestLog.Context context(net.minecraft.world.level.Level level) {
        return new PlaytestLog.Context(level.isClientSide ? "client" : "server", level.dimension().location().toString(), level.getGameTime(), "", "");
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) LOG.summary("server");
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { LOG.reset("server"); }
}
