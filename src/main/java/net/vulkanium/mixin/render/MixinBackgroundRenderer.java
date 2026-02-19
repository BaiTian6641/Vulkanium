package net.vulkanium.mixin.render;

import net.vulkanium.Vulkanium;
import net.vulkanium.compat.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Intercepts fog rendering to capture fog parameters for Vulkan shaders.
 */
@Mixin(FogRenderer.class)
public abstract class MixinBackgroundRenderer {

    /**
     * Let MC's fog calculation run normally — our MixinRenderSystem intercepts
     * {@code setShaderFogStart/End} and stores the values in VRenderSystem.
     * Cancelling this method broke sky/fog colors because MC's fog parameters
     * (biome-aware, underwater-aware, etc.) were replaced with hardcoded values.
     */
    @Inject(method = "setupFog", at = @At("HEAD"))
    private static void onApplyFog(Camera camera, FogRenderer.FogMode fogMode,
                                   float viewDistance, boolean doNearFog, float tickDelta,
                                   CallbackInfo ci) {
        // Observational only — MC will call RenderSystem.setShaderFogStart/End
        // which our MixinRenderSystem properly intercepts and stores in VRenderSystem.
        // Do NOT cancel: MC needs to compute proper fog for biomes, underwater, etc.
    }

    @Inject(method = "setupColor", at = @At("HEAD"))
    private static void onRender(Camera camera, float partialTick,
                                 ClientLevel level,
                                 int renderDistance, float darkenWorldAmount, CallbackInfo ci) {
        // Observational — capture fog color for Vulkan uniform bridge
        if (!Vulkanium.isVulkanReady()) return;
    }
}
