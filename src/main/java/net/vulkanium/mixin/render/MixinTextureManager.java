package net.vulkanium.mixin.render;

import net.vulkanium.Vulkanium;
import net.vulkanium.compat.GlStateInterceptor;
import net.vulkanium.compat.VRenderSystem;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts texture manager operations for Vulkan texture tracking.
 */
@Mixin(TextureManager.class)
public abstract class MixinTextureManager {

    @Inject(method = "register(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V",
            at = @At("HEAD"))
    private void onRegisterTexture(ResourceLocation id, AbstractTexture texture, CallbackInfo ci) {
        Vulkanium.LOGGER.debug("[TextureManager] register called: {} (thread={})", id, Thread.currentThread().getName());
    }

    @Inject(method = "register(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V",
            at = @At("RETURN"))
    private void onRegisterTextureReturn(ResourceLocation id, AbstractTexture texture, CallbackInfo ci) {
        Vulkanium.LOGGER.debug("[TextureManager] register COMPLETE: {}", id);
    }

    @Inject(method = "bindForSetup", at = @At("HEAD"))
    private void onBindTexture(ResourceLocation id, CallbackInfo ci) {
        // Observational — Vulkan texture binding happens via GlStateManager mixin
        if (!Vulkanium.isVulkanReady()) return;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            Vulkanium.LOGGER.info("TextureManager closing — destroying all Vulkan textures");
            GlStateInterceptor.getInstance().destroyAllTextures();
        }
    }
}
