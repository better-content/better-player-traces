package com.bettercontent.betterplayertraces.client

import com.bettercontent.betterplayertraces.dto.TraceOwner
import com.mojang.authlib.GameProfile
import com.mojang.authlib.minecraft.MinecraftProfileTexture
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.ResourceLocation

/** Only the selected owner is requested. Vanilla performs profile/texture work asynchronously. */
object TraceOwnerSkins {
    private val skins = TraceSkinCache<ResourceLocation>()

    fun texture(owner: TraceOwner): ResourceLocation {
        val mc = Minecraft.getInstance()
        return skins.getOrRequest(owner.id, DefaultPlayerSkin.getDefaultSkin(owner.id)) { accept ->
            val profile = mc.connection?.getPlayerInfo(owner.id)?.profile ?: GameProfile(owner.id, owner.name)
            mc.skinManager.registerSkins(profile, { type, location, _ ->
                if (type == MinecraftProfileTexture.Type.SKIN) mc.execute { accept(location) }
            }, true)
        }
    }

    fun clear() { skins.clear() }
}
