package net.vulkanium.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
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
 * Intercepts Minecraft's RenderTarget (framebuffer wrapper) to use Vulkan FBOs.
 *
 * <p>MC's RenderTarget wraps a GL framebuffer with color + depth attachments.
 * With Vulkan, this maps to:
 * <ul>
 *   <li>Color attachment → VkImage + VkImageView (COLOR_ATTACHMENT_OPTIMAL)</li>
 *   <li>Depth attachment → VkImage + VkImageView (DEPTH_STENCIL_ATTACHMENT_OPTIMAL)</li>
 *   <li>Bind → begin/end VkRenderPass targeting these attachments</li>
 *   <li>Blit → vkCmdBlitImage between render targets</li>
 * </ul></p>
 */
@Mixin(RenderTarget.class)
public abstract class MixinRenderTarget {

    @Shadow public int width;
    @Shadow public int height;
    @Shadow public int viewWidth;
    @Shadow public int viewHeight;
    @Shadow protected int colorTextureId;
    @Shadow protected int depthBufferId;
    @Shadow public int frameBufferId;

    /**
     * Intercept createBuffers — create Vulkan images instead of GL textures/FBOs.
     */
    @Inject(method = "createBuffers", at = @At("HEAD"), cancellable = true)
    private void onCreateBuffers(int width, int height, boolean clearOnInit, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        this.viewWidth = width;
        this.viewHeight = height;
        this.width = width;
        this.height = height;

        // Allocate Vulkan color image + depth image
        this.colorTextureId = GlStateInterceptor.getInstance().onGenTexture();
        this.depthBufferId = GlStateInterceptor.getInstance().onGenRenderbuffer();
        this.frameBufferId = GlStateInterceptor.getInstance().onGenFramebuffer();

        // Configure Vulkan framebuffer attachments
        GlStateInterceptor.getInstance().onCreateRenderTarget(
                this.frameBufferId, this.colorTextureId, this.depthBufferId,
                width, height
        );

        if (clearOnInit) {
            VRenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
            VRenderSystem.clear(0x4100, false); // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
        }

        ci.cancel();
    }

    /**
     * Intercept destroyBuffers — destroy Vulkan images and framebuffer.
     */
    @Inject(method = "destroyBuffers", at = @At("HEAD"), cancellable = true)
    private void onDestroyBuffers(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        if (this.colorTextureId > -1) {
            GlStateInterceptor.getInstance().onDeleteTexture(this.colorTextureId);
            this.colorTextureId = -1;
        }
        if (this.depthBufferId > -1) {
            GlStateInterceptor.getInstance().onDeleteRenderbuffer(this.depthBufferId);
            this.depthBufferId = -1;
        }
        if (this.frameBufferId > -1) {
            GlStateInterceptor.getInstance().onDeleteFramebuffer(this.frameBufferId);
            this.frameBufferId = -1;
        }
        ci.cancel();
    }

    /**
     * Intercept bindRead — bind color texture for sampling in next pass.
     */
    @Inject(method = "bindRead", at = @At("HEAD"), cancellable = true)
    private void onBindRead(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        VRenderSystem.bindTexture(this.colorTextureId);
        ci.cancel();
    }

    /**
     * Intercept bindWrite — begin rendering to this target.
     */
    @Inject(method = "bindWrite(Z)V", at = @At("HEAD"), cancellable = true)
    private void onBindWrite(boolean setViewport, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        GlStateInterceptor.getInstance().onBindFramebuffer(0x8D40 /* GL_FRAMEBUFFER */,
                this.frameBufferId);
        if (setViewport) {
            VRenderSystem.viewport(0, 0, this.viewWidth, this.viewHeight);
        }
        ci.cancel();
    }

    /**
     * Intercept unbindRead.
     */
    @Inject(method = "unbindRead", at = @At("HEAD"), cancellable = true)
    private void onUnbindRead(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        VRenderSystem.bindTexture(0);
        ci.cancel();
    }

    /**
     * Intercept unbindWrite — end rendering to this target.
     */
    @Inject(method = "unbindWrite", at = @At("HEAD"), cancellable = true)
    private void onUnbindWrite(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        GlStateInterceptor.getInstance().onBindFramebuffer(0x8D40, 0);
        ci.cancel();
    }

    /**
     * Intercept clear — NO-OP for Vulkan.
     *
     * <p>MC clears its RenderTarget (FBO) to (0,0,0,0) at the start of each frame.
     * Since we draw directly to the swapchain (not to MC's FBO), performing this clear
     * would wipe all previously rendered content to black. The render pass load-op
     * already handles the initial clear with the correct sky/background color.</p>
     */
    @Inject(method = "clear(Z)V", at = @At("HEAD"), cancellable = true)
    private void onClear(boolean getError, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        // Skip — RenderTarget.clear() must not touch our swapchain.
        // The render pass load-op and RenderSystem.clear() handle clearing.
        ci.cancel();
    }

    /**
     * Intercept blitToScreen — blit this render target to the swapchain image.
     */
    @Inject(method = "blitToScreen(II)V", at = @At("HEAD"), cancellable = true)
    private void onBlitToScreen(int width, int height, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        // Blit from this render target's color attachment to the swapchain
        GlStateInterceptor.getInstance().onBlitFramebuffer(
                0, 0, this.width, this.height,  // src
                0, 0, width, height,              // dst (screen)
                0x4000, // GL_COLOR_BUFFER_BIT
                0x2601  // GL_LINEAR
        );

        ci.cancel();
    }
}
