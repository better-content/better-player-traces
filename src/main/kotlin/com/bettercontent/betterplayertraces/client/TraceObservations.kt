package com.bettercontent.betterplayertraces.client

import com.bettercontent.gameplaynotices.GameplayNotice
import com.bettercontent.gameplaynotices.NoticeClient
import com.bettercontent.gameplaynotices.NoticeTheme
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.network.chat.Component
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** Client-only adapter: bounded nearby tile candidates once per tick; HUD consumes selected state. */
object TraceObservations {
    private const val NOTICE_KEY = "traces:nearby"
    private val aim = TraceAimSelection()
    private val encounter = TraceEncounter()
    private var world: Any? = null
    private var epoch = Long.MIN_VALUE
    private var near = false
    private var fresh = false

    internal val selectedOwner get() = aim.selected?.owner

    fun reset(preserveCooldown: Boolean = false) {
        NoticeClient.cancelTransient(NOTICE_KEY)
        aim.clear(); encounter.reset(preserveCooldown); TraceOwnerSkins.clear()
        world = null; epoch = Long.MIN_VALUE; near = false; fresh = false
    }

    private fun presentationAllowed(mc: Minecraft): Boolean =
        mc.player != null && mc.level != null && mc.screen == null && !mc.options.hideGui

    private fun hintValid(): Boolean {
        val mc = Minecraft.getInstance()
        return presentationAllowed(mc) && !TracesClientState.overlayEnabled && mc.level === world &&
            epoch == TracesClientState.observationEpoch && fresh && near &&
            TracesClientState.subscriptionFresh(mc.level!!.dimension().location().toString())
    }

    fun tick(now: Long = Util.getMillis()) {
        val mc = Minecraft.getInstance()
        val level = mc.level
        val player = mc.player
        if (level == null || player == null) { if (world != null) reset(preserveCooldown = true); return }
        if (world !== level) { reset(preserveCooldown = true); world = level }
        if (epoch != TracesClientState.observationEpoch) {
            epoch = TracesClientState.observationEpoch
            aim.clear(); near = false
            NoticeClient.cancelTransient(NOTICE_KEY)
        }
        fresh = TracesClientState.subscriptionFresh(level.dimension().location().toString(), now)
        near = false
        var outer = false
        val candidates = ArrayList<TraceAimCandidate>()
        if (fresh) {
            val camera = mc.gameRenderer.mainCamera
            val eye = player.eyePosition
            val origin = camera.position
            val direction = Vec3(camera.lookVector).normalize()
            val local = (TracesClientState.nearbyTraces(player.x, player.z, 13.0).filter {
                abs(it.y - player.y) <= 5.0
            } + if (TracesClientState.overlayEnabled && presentationAllowed(mc)) {
                TracesClientState.nearbyTraces(origin.x, origin.z, 13.0).filter { abs(it.y - origin.y) <= 13.0 }
            } else emptyList()).distinct()
            val marks = FootprintRenderCache.localFootprints(level, local)
            fun unobstructed(from: Vec3, to: Vec3): Boolean {
                val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                return hit.type == HitResult.Type.MISS || from.distanceToSqr(hit.location) + 0.0025 >= from.distanceToSqr(to)
            }
            marks.forEach { mark ->
                val trace = mark.mark.trace
                val center = mark.bounds.center
                if (trace.owner != null && trace.owner.id != player.uuid) {
                    if (TraceObservationGeometry.inside(center, player.position(), 12.0, 4.0)) outer = true
                    if (!near && TraceObservationGeometry.inside(center, player.position(), 8.0, 3.0) && unobstructed(eye, center)) near = true
                }
                if (TracesClientState.overlayEnabled && presentationAllowed(mc)) {
                    TraceObservationGeometry.aim(center, origin, direction) { unobstructed(origin, center) }?.let { (perpendicular, along) ->
                        candidates += TraceAimCandidate(trace.owner, perpendicular, along,
                            "${trace.owner?.id}:${trace.x}:${trace.y}:${trace.z}:${trace.sequenceIndex}:${trace.kind}")
                    }
                }
            }
        }
        if (fresh && TracesClientState.overlayEnabled && presentationAllowed(mc)) aim.update(candidates, now) else aim.clear()
        if (!hintValid()) NoticeClient.cancelTransient(NOTICE_KEY)
        if (encounter.update(now, fresh, near, outer, TracesClientState.overlayEnabled, presentationAllowed(mc))) {
            NoticeClient.submitTransient(NOTICE_KEY, GameplayNotice(NOTICE_KEY, NoticeTheme.TRACES,
                Component.translatable("message.traces.nearby"),
                Component.translatable("message.traces.nearby_hint", Component.keybind("key.traces.reveal"))),
                { hintValid() }, { encounter.shown(Util.getMillis()) })
        }
    }

    fun render(graphics: GuiGraphics, width: Int, height: Int) {
        val mc = Minecraft.getInstance()
        if (!presentationAllowed(mc) || !TracesClientState.overlayEnabled || !fresh || world !== mc.level ||
            epoch != TracesClientState.observationEpoch || !TracesClientState.subscriptionFresh(mc.level!!.dimension().location().toString())) return
        val selected = aim.selected ?: return
        val alpha = TraceSightOverlayRenderer.visibility()
        if (alpha < 0.02f) return
        val owner = selected.owner
        val label = when {
            owner?.id == mc.player?.uuid -> Component.translatable("message.traces.owner_self")
            owner?.name != null -> Component.translatable("message.traces.owner", owner.name)
            else -> Component.translatable("message.traces.owner_unknown")
        }.string
        val centerX = width / 2
        val available = (centerX - 18 - 4).coerceAtLeast(0)
        if (available < 8 || centerX + 18 + 16 > width - 4) return
        val text = if (mc.font.width(label) <= available) label else
            mc.font.plainSubstrByWidth(label, (available - mc.font.width("…")).coerceAtLeast(0)) + "…"
        val color = ((alpha * 255).toInt() shl 24) or 0xFFFFFF
        graphics.drawString(mc.font, text, centerX - 18 - mc.font.width(text), height / 2 - 4, color, true)
        val texture = owner?.let(TraceOwnerSkins::texture) ?: DefaultPlayerSkin.getDefaultSkin(java.util.UUID(0L, 0L))
        com.mojang.blaze3d.systems.RenderSystem.enableBlend()
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc()
        graphics.setColor(1f, 1f, 1f, alpha)
        try { PlayerFaceRenderer.draw(graphics, texture, centerX + 18, height / 2 - 8, 16, true, false) }
        finally {
            graphics.setColor(1f, 1f, 1f, 1f)
            com.mojang.blaze3d.systems.RenderSystem.disableBlend()
        }
    }
}
