package net.vulkanium.mixin.world;

import net.vulkanium.Vulkanium;
import net.vulkanium.world.VulkaniumWorldRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks chunk load/unload packets for Vulkan terrain system.
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPlayNetworkHandler {

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void onChunkLoaded(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        int chunkX = packet.getX();
        int chunkZ = packet.getZ();
        VulkaniumWorldRenderer renderer = VulkaniumWorldRenderer.getInstance();
        if (renderer != null) {
            renderer.onChunkLoaded(chunkX, chunkZ);
        }
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("RETURN"))
    private void onChunkUnloaded(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        int chunkX = packet.getX();
        int chunkZ = packet.getZ();
        VulkaniumWorldRenderer renderer = VulkaniumWorldRenderer.getInstance();
        if (renderer != null) {
            renderer.onChunkUnloaded(chunkX, chunkZ);
        }
    }
}
