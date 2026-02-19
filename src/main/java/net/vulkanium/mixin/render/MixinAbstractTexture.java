package net.vulkanium.mixin.render;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.vulkanium.Vulkanium;
import net.vulkanium.compat.GlStateInterceptor;
import net.vulkanium.compat.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts Minecraft's texture binding, filtering, and lifecycle management in
 * AbstractTexture. Routes operations to Vulkan image + sampler management.
 *
 * <p>MC's AbstractTexture wraps a GL texture ID + filtering state. With Vulkan,
 * each texture is a VkImage + VkImageView, and filtering is set on VkSampler objects.</p>
 */
@Mixin(AbstractTexture.class)
public abstract class MixinAbstractTexture {

    @Shadow protected int id;
    @Shadow protected boolean blur;
    @Shadow protected boolean mipmap;

    /**
     * Intercept getId() to ensure a Vulkan-backed texture ID is allocated.
     */
    @Inject(method = "getId", at = @At("HEAD"), cancellable = true)
    private void onGetId(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        if (!Vulkanium.isVulkanReady()) return;
        if (this.id == -1) {
            // Allocate a new Vulkan-backed pseudo-GL texture ID
            this.id = GlStateInterceptor.getInstance().onGenTexture();
        }
        cir.setReturnValue(this.id);
    }

    /**
     * Intercept bind — in Vulkan, this updates the descriptor set binding.
     */
    @Inject(method = "bind", at = @At("HEAD"), cancellable = true)
    private void onBind(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        VRenderSystem.bindTexture(this.id);
        ci.cancel(); // Skip GL bind
    }

    /**
     * Intercept releaseId to destroy the Vulkan image/view.
     */
    @Inject(method = "releaseId", at = @At("HEAD"), cancellable = true)
    private void onReleaseId(CallbackInfo ci) {
        if (!Vulkanium.wasVulkanUsed()) return;
        if (Vulkanium.isVulkanReady() && this.id != -1) {
            GlStateInterceptor.getInstance().onDeleteTexture(this.id);
        }
        this.id = -1;
        ci.cancel(); // Skip GL delete — no GL context exists
    }

    /**
     * Intercept setFilter — in Vulkan, this updates the sampler object.
     */
    @Inject(method = "setFilter", at = @At("HEAD"), cancellable = true)
    private void onSetFilter(boolean blur, boolean mipmap, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        this.blur = blur;
        this.mipmap = mipmap;
        // Update Vulkan sampler for this texture
        GlStateInterceptor.getInstance().onTextureFilter(this.id, blur, mipmap);
        ci.cancel(); // Skip GL texParameter calls
    }
}
