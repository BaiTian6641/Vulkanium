package net.vulkanium.mixin.world;

import net.vulkanium.Vulkanium;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks chunk build uploads for Vulkan staging buffer management.
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class MixinChunkBuilder {

    @Inject(method = "uploadAllPendingUploads", at = @At("HEAD"), require = 0)
    private void onUpload(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        Vulkanium.LOGGER.trace("ChunkRenderDispatcher.upload intercepted");
    }

    @Inject(method = "dispose", at = @At("HEAD"), require = 0)
    private void onReset(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        Vulkanium.LOGGER.debug("ChunkRenderDispatcher dispose — clearing pending Vulkan mesh uploads");
    }
}
