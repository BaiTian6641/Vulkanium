package net.vulkanium.mixin.gl;

import net.vulkanium.Vulkanium;
import net.vulkanium.compat.GlStateInterceptor;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts LWJGL GL15 buffer object calls and routes them through Vulkan
 * when Vulkan is active. In GL dev mode, all calls pass through to real GL.
 */
@Mixin(value = GL15.class, remap = false)
public abstract class MixinGL15 {

    @Inject(method = "glGenBuffers()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlGenBuffers(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.getInstance().onGenBuffer()); }
    }

    @Inject(method = "glBindBuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlBindBuffer(int target, int buffer, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.onBindBuffer(target, buffer); ci.cancel(); }
    }

    @Inject(method = "glBufferData(IJI)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlBufferDataSize(int target, long size, int usage, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.onBufferData(target, size, usage); ci.cancel(); }
    }

    @Inject(method = "glBufferData(ILjava/nio/ByteBuffer;I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlBufferDataBuf(int target, java.nio.ByteBuffer data, int usage, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            long size = data != null ? data.remaining() : 0;
            GlStateInterceptor.onBufferData(target, size, usage);
            if (data != null && size > 0) {
                GlStateInterceptor.getInstance().onBufferSubData(target, 0, data);
            }
            ci.cancel();
        }
    }

    @Inject(method = "glBufferSubData", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlBufferSubData(int target, long offset, java.nio.ByteBuffer data, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.getInstance().onBufferSubData(target, offset, data); ci.cancel(); }
    }

    @Inject(method = "glDeleteBuffers(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlDeleteBuffers(int buffer, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) GlStateInterceptor.onDeleteBuffer(buffer);
            ci.cancel();
        }
    }

    @Inject(method = "glMapBuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlMapBuffer(int target, int access, CallbackInfoReturnable<java.nio.ByteBuffer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.getInstance().onMapBuffer(target, access)); }
    }

    @Inject(method = "glUnmapBuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlUnmapBuffer(int target, CallbackInfoReturnable<Boolean> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.getInstance().onUnmapBuffer(target)); }
    }

    @Inject(method = "glIsBuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlIsBuffer(int buffer, CallbackInfoReturnable<Boolean> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.getInstance().isBuffer(buffer)); }
    }
}
