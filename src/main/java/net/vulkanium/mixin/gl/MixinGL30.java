package net.vulkanium.mixin.gl;

import net.vulkanium.Vulkanium;
import net.vulkanium.compat.GlStateInterceptor;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts LWJGL GL30 calls (FBOs, VAOs, renderbuffers) and routes them
 * through the Vulkan backend when active. In GL dev mode, passes through to real GL.
 */
@Mixin(value = GL30.class, remap = false)
public abstract class MixinGL30 {

    // ─── Framebuffer Objects ───────────────────────────────────────────

    @Inject(method = "glGenFramebuffers()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlGenFramebuffers(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.onGenFramebuffer()); }
    }

    @Inject(method = "glBindFramebuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlBindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) GlStateInterceptor.onBindFramebuffer(target, framebuffer);
            ci.cancel();
        }
    }

    @Inject(method = "glDeleteFramebuffers(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlDeleteFramebuffers(int framebuffer, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) GlStateInterceptor.onDeleteFramebuffer(framebuffer);
            ci.cancel();
        }
    }

    @Inject(method = "glFramebufferTexture2D", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlFramebufferTexture2D(int target, int attachment,
                                                   int textarget, int texture, int level, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            GlStateInterceptor.getInstance().onFramebufferTexture2D(target, attachment, textarget, texture, level);
            ci.cancel();
        }
    }

    @Inject(method = "glCheckFramebufferStatus", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlCheckFramebufferStatus(int target, CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(0x8CD5); /* GL_FRAMEBUFFER_COMPLETE */ }
    }

    @Inject(method = "glBlitFramebuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                             int dstX0, int dstY0, int dstX1, int dstY1,
                                             int mask, int filter, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            GlStateInterceptor.getInstance().onBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
            ci.cancel();
        }
    }

    // ─── Renderbuffer Objects ──────────────────────────────────────────

    @Inject(method = "glGenRenderbuffers()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlGenRenderbuffers(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.getInstance().onGenRenderbuffer()); }
    }

    @Inject(method = "glBindRenderbuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlBindRenderbuffer(int target, int renderbuffer, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.getInstance().onBindRenderbuffer(target, renderbuffer); ci.cancel(); }
    }

    @Inject(method = "glRenderbufferStorage", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlRenderbufferStorage(int target, int internalformat, int width, int height, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.getInstance().onRenderbufferStorage(target, internalformat, width, height); ci.cancel(); }
    }

    @Inject(method = "glFramebufferRenderbuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlFramebufferRenderbuffer(int target, int attachment,
                                                     int renderbuffertarget, int renderbuffer, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            GlStateInterceptor.getInstance().onFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
            ci.cancel();
        }
    }

    @Inject(method = "glDeleteRenderbuffers(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlDeleteRenderbuffers(int renderbuffer, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) GlStateInterceptor.getInstance().onDeleteRenderbuffer(renderbuffer);
            ci.cancel();
        }
    }

    // ─── Vertex Array Objects ──────────────────────────────────────────

    @Inject(method = "glGenVertexArrays()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlGenVertexArrays(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.getInstance().onGenVertexArray()); }
    }

    @Inject(method = "glBindVertexArray", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlBindVertexArray(int array, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.getInstance().onBindVertexArray(array); ci.cancel(); }
    }

    @Inject(method = "glDeleteVertexArrays(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlDeleteVertexArrays(int array, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) GlStateInterceptor.getInstance().onDeleteVertexArray(array);
            ci.cancel();
        }
    }

    @Inject(method = "glGenerateMipmap", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlGenerateMipmap(int target, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.getInstance().onGenerateMipmap(target); ci.cancel(); }
    }
}
