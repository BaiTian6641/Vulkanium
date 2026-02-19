package net.vulkanium.mixin.gl;

import net.vulkanium.Vulkanium;
import net.vulkanium.compat.GlStateInterceptor;
import net.vulkanium.compat.VRenderSystem;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts LWJGL GL11 calls and routes them through the Vulkan backend
 * when Vulkan is active. In GL dev mode, all calls pass through to real GL.
 */
@Mixin(value = GL11.class, remap = false)
public abstract class MixinGL11 {

    @Inject(method = "glEnable", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlEnable(int cap, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            switch (cap) {
                case 0x0B71 -> VRenderSystem.enableDepthTest();
                case 0x0BE2 -> VRenderSystem.enableBlend();
                case 0x0B44 -> VRenderSystem.enableCull();
                case 0x0C11 -> {} // GL_SCISSOR_TEST
                case 0x8037 -> {} // GL_POLYGON_OFFSET_FILL
                case 0x0DE1 -> {} // GL_TEXTURE_2D
            }
            ci.cancel();
        }
    }

    @Inject(method = "glDisable", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlDisable(int cap, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            switch (cap) {
                case 0x0B71 -> VRenderSystem.disableDepthTest();
                case 0x0BE2 -> VRenderSystem.disableBlend();
                case 0x0B44 -> VRenderSystem.disableCull();
                case 0x0C11 -> VRenderSystem.disableScissor();
                case 0x8037 -> VRenderSystem.disablePolygonOffset();
            }
            ci.cancel();
        }
    }

    @Inject(method = "glBindTexture", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlBindTexture(int target, int texture, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.bindTexture(texture); ci.cancel(); }
    }

    @Inject(method = "glGenTextures()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlGenTextures(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.getInstance().onGenTexture()); }
    }

    @Inject(method = "glDeleteTextures(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlDeleteTextures(int texture, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) GlStateInterceptor.getInstance().onDeleteTexture(texture);
            ci.cancel();
        }
    }

    @Inject(method = "glClear", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlClear(int mask, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.clear(mask, false); ci.cancel(); }
    }

    @Inject(method = "glClearColor", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlClearColor(float r, float g, float b, float a, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.clearColor(r, g, b, a); ci.cancel(); }
    }

    @Inject(method = "glDepthMask", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlDepthMask(boolean flag, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.depthMask(flag); ci.cancel(); }
    }

    @Inject(method = "glDepthFunc", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlDepthFunc(int func, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.depthFunc(func); ci.cancel(); }
    }

    @Inject(method = "glBlendFunc", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlBlendFunc(int sfactor, int dfactor, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.blendFunc(sfactor, dfactor); ci.cancel(); }
    }

    @Inject(method = "glViewport", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlViewport(int x, int y, int w, int h, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.viewport(x, y, w, h); ci.cancel(); }
    }

    @Inject(method = "glColorMask", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlColorMask(boolean r, boolean g, boolean b, boolean a, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.colorMask(r, g, b, a); ci.cancel(); }
    }

    @Inject(method = "glScissor", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlScissor(int x, int y, int w, int h, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.enableScissor(x, y, w, h); ci.cancel(); }
    }

    @Inject(method = "glPixelStorei", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlPixelStorei(int pname, int param, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "glTexParameteri", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlTexParameteri(int target, int pname, int param, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "glReadPixels(IIIIIIJ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlReadPixels(int x, int y, int width, int height,
                                        int format, int type, long pixels, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); /* TODO: vkCmdCopyImageToBuffer */ }
    }

    @Inject(method = "glPolygonOffset", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlPolygonOffset(float factor, float units, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.polygonOffset(factor, units); ci.cancel(); }
    }

    @Inject(method = "glGetError", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlGetError(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(0); }
    }

    @Inject(method = "glGetString", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlGetString(int name, CallbackInfoReturnable<String> cir) {
        if (Vulkanium.isVulkanReady()) {
            cir.setReturnValue(switch (name) {
                case 0x1F01 -> "Vulkanium";
                case 0x1F00 -> "Vulkanium";
                case 0x1F02 -> "Vulkan 1.2";
                default -> "";
            });
        }
    }

    @Inject(method = "glGetInteger", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlGetInteger(int pname, CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) {
            cir.setReturnValue(switch (pname) {
                case 0x0D33 -> 16384;
                case 0x8D57 -> 16;
                case 0x84E8 -> 16;
                case 0x8824 -> 16;
                case 0x8869 -> 16;
                case 0x8CDF -> 16;
                case 0x8B4C -> 65536;
                case 0x8B49 -> 65536;
                default -> 0;
            });
        }
    }

    @Inject(method = "glTexImage2D(IIIIIIIIJ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlTexImage2D(int target, int level, int internalformat,
                                        int width, int height, int border,
                                        int format, int type, long pixels, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            GlStateInterceptor.getInstance().onTexImage2D(target, level, internalformat,
                    width, height, format, type, pixels);
            ci.cancel();
        }
    }

    @Inject(method = "glTexSubImage2D(IIIIIIIIJ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                           int width, int height,
                                           int format, int type, long pixels, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            GlStateInterceptor.getInstance().onTexSubImage2D(target, level, xoffset, yoffset,
                    width, height, format, type, pixels);
            ci.cancel();
        }
    }

    @Inject(method = "glDrawArrays", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlDrawArrays(int mode, int first, int count, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            GlStateInterceptor.getInstance().onDrawArrays(mode, first, count);
            ci.cancel();
        }
    }

    @Inject(method = "glDrawElements(IIIJ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlDrawElements(int mode, int count, int type, long indices, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            GlStateInterceptor.getInstance().onDrawElements(mode, count, type, indices);
            ci.cancel();
        }
    }
}
