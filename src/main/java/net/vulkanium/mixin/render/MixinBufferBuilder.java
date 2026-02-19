package net.vulkanium.mixin.render;

import net.vulkanium.Vulkanium;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks BufferBuilder begin/end for Vulkan draw state tracking.
 */
@Mixin(BufferBuilder.class)
public abstract class MixinBufferBuilder {

    @Shadow
    private boolean building;

    @Inject(method = "begin", at = @At("HEAD"))
    private void onBegin(VertexFormat.Mode drawMode, VertexFormat format, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            Vulkanium.LOGGER.trace("BufferBuilder.begin: mode={} format={}",
                    drawMode.name(), format);
        }
    }
}
