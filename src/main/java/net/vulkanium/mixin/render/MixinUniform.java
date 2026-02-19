package net.vulkanium.mixin.render;

import com.mojang.blaze3d.shaders.Uniform;
import net.vulkanium.Vulkanium;
import net.vulkanium.compat.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts Uniform.set(float,float,float) to capture per-chunk offset
 * for terrain rendering. MC's renderChunkLayer sets "ChunkOffset" uniform
 * before each chunk draw — we capture that value for use in MixinVertexBuffer.draw().
 */
@Mixin(Uniform.class)
public abstract class MixinUniform {

    @Shadow
    private String name;

    @Inject(method = "set(FFF)V", at = @At("HEAD"))
    private void onSet3f(float x, float y, float z, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        if ("ChunkOffset".equals(this.name)) {
            VRenderSystem.setChunkOffset(x, y, z);
        }
    }
}
