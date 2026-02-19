package net.vulkanium.mixin.render;

import com.mojang.blaze3d.shaders.Uniform;
import net.vulkanium.Vulkanium;
import net.vulkanium.compat.VRenderSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ChunkOffset");
    private static final boolean DEBUG_CHUNK_OFFSET = Boolean.getBoolean("vulkanium.debug.chunkoffset");

    @Shadow
    private String name;

    @Inject(method = "set(FFF)V", at = @At("HEAD"))
    private void onSet3f(float x, float y, float z, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        if ("ChunkOffset".equals(this.name)) {
            VRenderSystem.setChunkOffset(x, y, z);
            if (DEBUG_CHUNK_OFFSET) {
                LOGGER.info("[ChunkOffset] set(FFF): ({}, {}, {})", x, y, z);
            }
        }
    }

    @Inject(method = "set(FFFF)V", at = @At("HEAD"), require = 0)
    private void onSet4f(float x, float y, float z, float w, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        if ("ChunkOffset".equals(this.name)) {
            VRenderSystem.setChunkOffset(x, y, z);
            if (DEBUG_CHUNK_OFFSET) {
                LOGGER.info("[ChunkOffset] set(FFFF): ({}, {}, {}, w={})", x, y, z, w);
            }
        }
    }

    @Inject(method = "set([F)V", at = @At("HEAD"), require = 0)
    private void onSetFloatArray(float[] values, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        if (!"ChunkOffset".equals(this.name)) return;
        if (values == null || values.length < 3) return;
        VRenderSystem.setChunkOffset(values[0], values[1], values[2]);
        if (DEBUG_CHUNK_OFFSET) {
            LOGGER.info("[ChunkOffset] set([F): ({}, {}, {}) len={}",
                    values[0], values[1], values[2], values.length);
        }
    }
}
