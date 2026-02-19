package net.vulkanium.mixin.render;

import net.vulkanium.Vulkanium;
import net.vulkanium.compat.GlStateInterceptor;
import net.vulkanium.compat.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.blaze3d.platform.GlStateManager;

/**
 * Intercepts GlStateManager methods with Vulkan-tracked state changes.
 *
 * <p>Uses @Inject(HEAD, cancellable) so that in GL development mode the original
 * GlStateManager methods (which call real LWJGL GL functions) execute normally.</p>
 */
@Mixin(GlStateManager.class)
public abstract class MixinGlStateManager {

    // ─── Blend ─────────────────────────────────────────────────────────

    @Inject(method = "_enableBlend", at = @At("HEAD"), cancellable = true)
    private static void onEnableBlend(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.enableBlend(); ci.cancel(); }
    }

    @Inject(method = "_disableBlend", at = @At("HEAD"), cancellable = true)
    private static void onDisableBlend(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.disableBlend(); ci.cancel(); }
    }

    @Inject(method = "_blendFunc", at = @At("HEAD"), cancellable = true)
    private static void onBlendFunc(int srcFactor, int dstFactor, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.blendFunc(srcFactor, dstFactor); ci.cancel(); }
    }

    @Inject(method = "_blendFuncSeparate", at = @At("HEAD"), cancellable = true)
    private static void onBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha); ci.cancel(); }
    }

    // ─── Depth ─────────────────────────────────────────────────────────

    @Inject(method = "_enableDepthTest", at = @At("HEAD"), cancellable = true)
    private static void onEnableDepthTest(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.enableDepthTest(); ci.cancel(); }
    }

    @Inject(method = "_disableDepthTest", at = @At("HEAD"), cancellable = true)
    private static void onDisableDepthTest(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.disableDepthTest(); ci.cancel(); }
    }

    @Inject(method = "_depthFunc", at = @At("HEAD"), cancellable = true)
    private static void onDepthFunc(int func, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.depthFunc(func); ci.cancel(); }
    }

    @Inject(method = "_depthMask", at = @At("HEAD"), cancellable = true)
    private static void onDepthMask(boolean mask, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.depthMask(mask); ci.cancel(); }
    }

    // ─── Cull ──────────────────────────────────────────────────────────

    @Inject(method = "_enableCull", at = @At("HEAD"), cancellable = true)
    private static void onEnableCull(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.enableCull(); ci.cancel(); }
    }

    @Inject(method = "_disableCull", at = @At("HEAD"), cancellable = true)
    private static void onDisableCull(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.disableCull(); ci.cancel(); }
    }

    // ─── Color Mask ────────────────────────────────────────────────────

    @Inject(method = "_colorMask", at = @At("HEAD"), cancellable = true)
    private static void onColorMask(boolean r, boolean g, boolean b, boolean a, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.colorMask(r, g, b, a); ci.cancel(); }
    }

    // ─── Polygon Offset ────────────────────────────────────────────────

    @Inject(method = "_enablePolygonOffset", at = @At("HEAD"), cancellable = true)
    private static void onEnablePolygonOffset(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.enablePolygonOffset(); ci.cancel(); }
    }

    @Inject(method = "_disablePolygonOffset", at = @At("HEAD"), cancellable = true)
    private static void onDisablePolygonOffset(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.disablePolygonOffset(); ci.cancel(); }
    }

    @Inject(method = "_polygonOffset", at = @At("HEAD"), cancellable = true)
    private static void onPolygonOffset(float factor, float units, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.polygonOffset(factor, units); ci.cancel(); }
    }

    // ─── Texture ───────────────────────────────────────────────────────

    @Inject(method = "_activeTexture", at = @At("HEAD"), cancellable = true)
    private static void onActiveTexture(int texture, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) VRenderSystem.activeTexture(texture);
            ci.cancel();
        }
    }

    @Inject(method = "_bindTexture", at = @At("HEAD"), cancellable = true)
    private static void onBindTexture(int texture, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) VRenderSystem.bindTexture(texture);
            ci.cancel();
        }
    }

    @Inject(method = "_genTexture", at = @At("HEAD"), cancellable = true)
    private static void onGenTexture(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) {
                cir.setReturnValue(GlStateInterceptor.getInstance().onGenTexture());
            } else {
                cir.setReturnValue(900000 + (int)(System.nanoTime() & 0xFFFF)); // dummy ID post-shutdown
            }
        }
    }

    @Inject(method = "_deleteTexture", at = @At("HEAD"), cancellable = true)
    private static void onDeleteTexture(int texture, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) GlStateInterceptor.getInstance().onDeleteTexture(texture);
            ci.cancel(); // Always cancel if Vulkan was ever used (even after shutdown)
        }
    }

    @Inject(method = "_texParameter(III)V", at = @At("HEAD"), cancellable = true)
    private static void onTexParameterI(int target, int pname, int param, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            GlStateInterceptor.getInstance().onTexParameterI(target, pname, param);
            ci.cancel();
        }
    }

    @Inject(method = "_texParameter(IIF)V", at = @At("HEAD"), cancellable = true)
    private static void onTexParameterF(int target, int pname, float param, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) { ci.cancel(); }
    }

    // ─── Viewport / Scissor ────────────────────────────────────────────

    @Inject(method = "_viewport", at = @At("HEAD"), cancellable = true)
    private static void onViewport(int x, int y, int width, int height, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.viewport(x, y, width, height); ci.cancel(); }
    }

    @Inject(method = "_scissorBox", at = @At("HEAD"), cancellable = true)
    private static void onScissorBox(int x, int y, int width, int height, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.enableScissor(x, y, width, height); ci.cancel(); }
    }

    @Inject(method = "_enableScissorTest", at = @At("HEAD"), cancellable = true)
    private static void onEnableScissorTest(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); /* Scissor always enabled in Vulkan when set */ }
    }

    @Inject(method = "_disableScissorTest", at = @At("HEAD"), cancellable = true)
    private static void onDisableScissorTest(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.disableScissor(); ci.cancel(); }
    }

    // ─── Clear ─────────────────────────────────────────────────────────

    @Inject(method = "_clearColor", at = @At("HEAD"), cancellable = true)
    private static void onClearColor(float r, float g, float b, float a, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.clearColor(r, g, b, a); ci.cancel(); }
    }

    @Inject(method = "_clearDepth", at = @At("HEAD"), cancellable = true)
    private static void onClearDepth(double depth, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.clearDepth(depth); ci.cancel(); }
    }

    @Inject(method = "_clear", at = @At("HEAD"), cancellable = true)
    private static void onClear(int mask, boolean getError, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.clear(mask, getError); ci.cancel(); }
    }

    // ─── Pixel Store ───────────────────────────────────────────────────

    @Inject(method = "_pixelStore", at = @At("HEAD"), cancellable = true)
    private static void onPixelStore(int pname, int param, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) { ci.cancel(); }
    }

    // ─── GL Error ──────────────────────────────────────────────────────

    @Inject(method = "_getError", at = @At("HEAD"), cancellable = true)
    private static void onGetError(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.wasVulkanUsed()) { cir.setReturnValue(0); /* GL_NO_ERROR */ }
    }

    // ─── String / Integer Query ────────────────────────────────────────

    @Inject(method = "_getString", at = @At("HEAD"), cancellable = true)
    private static void onGetString(int name, CallbackInfoReturnable<String> cir) {
        if (Vulkanium.isVulkanReady()) {
            cir.setReturnValue(switch (name) {
                case 0x1F01 -> "Vulkanium";     // GL_RENDERER
                case 0x1F00 -> "Vulkanium";     // GL_VENDOR
                case 0x1F02 -> "Vulkan 1.2";    // GL_VERSION
                default -> "";
            });
        }
    }

    @Inject(method = "_getInteger", at = @At("HEAD"), cancellable = true)
    private static void onGetInteger(int pname, CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) {
            cir.setReturnValue(switch (pname) {
                case 0x0D33 -> 16384;  // GL_MAX_TEXTURE_SIZE
                case 0x8D57 -> 16;     // GL_MAX_ARRAY_TEXTURE_LAYERS
                default -> 0;
            });
        }
    }

    // ─── Texture Image Upload ──────────────────────────────────────────

    @Inject(method = "_texImage2D", at = @At("HEAD"), cancellable = true)
    private static void onTexImage2D(int target, int level, int internalFormat, int width, int height,
                                      int border, int format, int type, @org.jetbrains.annotations.Nullable java.nio.IntBuffer pixels, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            long ptr = pixels != null ? org.lwjgl.system.MemoryUtil.memAddress(pixels) : 0L;
            GlStateInterceptor.getInstance().onTexImage2D(target, level, internalFormat, width, height, format, type, ptr);
            ci.cancel();
        }
    }

    @Inject(method = "_texSubImage2D", at = @At("HEAD"), cancellable = true)
    private static void onTexSubImage2D(int target, int level, int xOff, int yOff, int width, int height,
                                         int format, int type, long pixels, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            GlStateInterceptor.getInstance().onTexSubImage2D(target, level, xOff, yOff, width, height, format, type, pixels);
            ci.cancel();
        }
    }

    @Inject(method = "_getTexLevelParameter", at = @At("HEAD"), cancellable = true)
    private static void onGetTexLevelParameter(int target, int level, int pname, CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) {
            cir.setReturnValue(GlStateInterceptor.getInstance().onGetTexLevelParameter(target, level, pname));
        }
    }

    // ─── GL Buffers ────────────────────────────────────────────────────

    @Inject(method = "_glGenBuffers", at = @At("HEAD"), cancellable = true)
    private static void onGlGenBuffers(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.onGenBuffer()); }
    }

    @Inject(method = "_glBindBuffer", at = @At("HEAD"), cancellable = true)
    private static void onGlBindBuffer(int target, int buffer, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.onBindBuffer(target, buffer); ci.cancel(); }
    }

    @Inject(method = "_glBufferData(IJI)V", at = @At("HEAD"), cancellable = true)
    private static void onGlBufferDataPtr(int target, long size, int usage, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.onBufferData(target, size, usage); ci.cancel(); }
    }

    @Inject(method = "_glDeleteBuffers", at = @At("HEAD"), cancellable = true)
    private static void onGlDeleteBuffers(int buffer, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) GlStateInterceptor.onDeleteBuffer(buffer);
            ci.cancel();
        }
    }

    // ─── GL Framebuffers ───────────────────────────────────────────────

    @Inject(method = "glGenFramebuffers", at = @At("HEAD"), cancellable = true)
    private static void onGenFramebuffers(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.onGenFramebuffer()); }
    }

    @Inject(method = "_glBindFramebuffer", at = @At("HEAD"), cancellable = true)
    private static void onBindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.onBindFramebuffer(target, framebuffer); ci.cancel(); }
    }

    @Inject(method = "_glFramebufferTexture2D", at = @At("HEAD"), cancellable = true)
    private static void onFramebufferTexture2D(int target, int attachment, int texTarget, int texture, int level, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.getInstance().onFramebufferTexture2D(target, attachment, texTarget, texture, level); ci.cancel(); }
    }

    @Inject(method = "_glFramebufferRenderbuffer", at = @At("HEAD"), cancellable = true)
    private static void onFramebufferRenderbuffer(int target, int attachment, int rbTarget, int renderbuffer, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.getInstance().onFramebufferRenderbuffer(target, attachment, rbTarget, renderbuffer); ci.cancel(); }
    }

    @Inject(method = "glCheckFramebufferStatus", at = @At("HEAD"), cancellable = true)
    private static void onCheckFramebufferStatus(int target, CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(0x8CD5); /* GL_FRAMEBUFFER_COMPLETE */ }
    }

    // ─── GL Renderbuffers ──────────────────────────────────────────────

    @Inject(method = "glGenRenderbuffers", at = @At("HEAD"), cancellable = true)
    private static void onGenRenderbuffers(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.getInstance().onGenRenderbuffer()); }
    }

    @Inject(method = "_glBindRenderbuffer", at = @At("HEAD"), cancellable = true)
    private static void onBindRenderbuffer(int target, int renderbuffer, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.getInstance().onBindRenderbuffer(target, renderbuffer); ci.cancel(); }
    }

    @Inject(method = "_glRenderbufferStorage", at = @At("HEAD"), cancellable = true)
    private static void onRenderbufferStorage(int target, int internalFormat, int width, int height, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.getInstance().onRenderbufferStorage(target, internalFormat, width, height); ci.cancel(); }
    }

    // ─── GL Program (shader) ───────────────────────────────────────────

    @Inject(method = "_glUseProgram", at = @At("HEAD"), cancellable = true)
    private static void onUseProgram(int program, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); /* Shader programs handled via Vulkan pipelines */ }
    }

    // ─── Vertex Attrib / VAO ───────────────────────────────────────────

    @Inject(method = "_enableVertexAttribArray", at = @At("HEAD"), cancellable = true)
    private static void onEnableVertexAttribArray(int index, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_disableVertexAttribArray", at = @At("HEAD"), cancellable = true)
    private static void onDisableVertexAttribArray(int index, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_vertexAttribPointer", at = @At("HEAD"), cancellable = true)
    private static void onVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_vertexAttribIPointer", at = @At("HEAD"), cancellable = true)
    private static void onVertexAttribIPointer(int index, int size, int type, int stride, long pointer, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glBindVertexArray", at = @At("HEAD"), cancellable = true)
    private static void onGlBindVertexArray(int array, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.getInstance().onBindVertexArray(array); ci.cancel(); }
    }

    @Inject(method = "_glGenVertexArrays", at = @At("HEAD"), cancellable = true)
    private static void onGlGenVertexArrays(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.getInstance().onGenVertexArray()); }
    }

    @Inject(method = "_glDeleteVertexArrays", at = @At("HEAD"), cancellable = true)
    private static void onGlDeleteVertexArrays(int array, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) GlStateInterceptor.getInstance().onDeleteVertexArray(array);
            ci.cancel();
        }
    }

    // ─── Draw / Read ───────────────────────────────────────────────────

    @Inject(method = "_drawElements", at = @At("HEAD"), cancellable = true)
    private static void onDrawElements(int mode, int count, int type, long indices, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); /* Draw handled via Vulkan command buffers */ }
    }

    @Inject(method = "_readPixels(IIIIIILjava/nio/ByteBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void onReadPixelsBuf(int x, int y, int width, int height, int format, int type, java.nio.ByteBuffer pixels, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); /* TODO: vkCmdCopyImageToBuffer */ }
    }

    @Inject(method = "_readPixels(IIIIIIJ)V", at = @At("HEAD"), cancellable = true)
    private static void onReadPixelsPtr(int x, int y, int width, int height, int format, int type, long pixels, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    // ─── Buffer Data (ByteBuffer overload) ─────────────────────────────

    @Inject(method = "_glBufferData(ILjava/nio/ByteBuffer;I)V", at = @At("HEAD"), cancellable = true)
    private static void onGlBufferDataBuf(int target, java.nio.ByteBuffer data, int usage, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            long size = data != null ? data.remaining() : 0;
            GlStateInterceptor.onBufferData(target, size, usage);
            if (data != null && data.remaining() > 0) {
                GlStateInterceptor.getInstance().onBufferSubData(target, 0, data);
            }
            ci.cancel();
        }
    }

    @Inject(method = "_glMapBuffer", at = @At("HEAD"), cancellable = true)
    private static void onGlMapBuffer(int target, int access, CallbackInfoReturnable<java.nio.ByteBuffer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(GlStateInterceptor.getInstance().onMapBuffer(target, access)); }
    }

    @Inject(method = "_glUnmapBuffer", at = @At("HEAD"), cancellable = true)
    private static void onGlUnmapBuffer(int target, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { GlStateInterceptor.getInstance().onUnmapBuffer(target); ci.cancel(); }
    }

    // ─── Framebuffer extras ────────────────────────────────────────────

    @Inject(method = "_glBlitFrameBuffer", at = @At("HEAD"), cancellable = true)
    private static void onGlBlitFrameBuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                             int dstX0, int dstY0, int dstX1, int dstY1,
                                             int mask, int filter, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glDeleteFramebuffers", at = @At("HEAD"), cancellable = true)
    private static void onGlDeleteFramebuffers(int framebuffer, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) GlStateInterceptor.onDeleteFramebuffer(framebuffer);
            ci.cancel();
        }
    }

    @Inject(method = "_glDeleteRenderbuffers", at = @At("HEAD"), cancellable = true)
    private static void onGlDeleteRenderbuffers(int renderbuffer, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) GlStateInterceptor.getInstance().onDeleteRenderbuffer(renderbuffer);
            ci.cancel();
        }
    }

    // ─── Tex / Image extras ────────────────────────────────────────────

    @Inject(method = "_glCopyTexSubImage2D", at = @At("HEAD"), cancellable = true)
    private static void onGlCopyTexSubImage2D(int target, int level, int xOff, int yOff,
                                               int x, int y, int width, int height, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glDrawPixels", at = @At("HEAD"), cancellable = true)
    private static void onGlDrawPixels(int width, int height, int format, int type, long pixels, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    // ─── Color Logic Op (GlStateManager level) ────────────────────────

    @Inject(method = "_enableColorLogicOp", at = @At("HEAD"), cancellable = true)
    private static void onEnableColorLogicOp(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.enableColorLogicOp(); ci.cancel(); }
    }

    @Inject(method = "_disableColorLogicOp", at = @At("HEAD"), cancellable = true)
    private static void onDisableColorLogicOp(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.disableColorLogicOp(); ci.cancel(); }
    }

    // ─── Shader Program Management ─────────────────────────────────────

    @Inject(method = "glCreateProgram", at = @At("HEAD"), cancellable = true)
    private static void onGlCreateProgram(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(VRenderSystem.genBufferId()); /* pseudo-program ID */ }
    }

    @Inject(method = "glCreateShader", at = @At("HEAD"), cancellable = true)
    private static void onGlCreateShader(int type, CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(VRenderSystem.genBufferId()); /* pseudo-shader ID */ }
    }

    @Inject(method = "glCompileShader", at = @At("HEAD"), cancellable = true)
    private static void onGlCompileShader(int shader, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); /* SPIR-V compilation handled elsewhere */ }
    }

    @Inject(method = "glLinkProgram", at = @At("HEAD"), cancellable = true)
    private static void onGlLinkProgram(int program, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "glDeleteProgram", at = @At("HEAD"), cancellable = true)
    private static void onGlDeleteProgram(int program, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) { ci.cancel(); }
    }

    @Inject(method = "glDeleteShader", at = @At("HEAD"), cancellable = true)
    private static void onGlDeleteShader(int shader, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) { ci.cancel(); }
    }

    @Inject(method = "glShaderSource", at = @At("HEAD"), cancellable = true)
    private static void onGlShaderSource(int shader, java.util.List<String> sources, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); /* GLSL source captured elsewhere for SPIR-V */ }
    }

    // ─── Shader Attrib / Uniform Locations ─────────────────────────────

    @Inject(method = "_glGetAttribLocation", at = @At("HEAD"), cancellable = true)
    private static void onGlGetAttribLocation(int program, CharSequence name, CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(0); /* Vulkan uses binding indices, not attrib locations */ }
    }

    @Inject(method = "_glGetUniformLocation", at = @At("HEAD"), cancellable = true)
    private static void onGlGetUniformLocation(int program, CharSequence name, CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(0); /* Vulkan uses descriptor sets, not GL uniforms */ }
    }

    @Inject(method = "_glBindAttribLocation", at = @At("HEAD"), cancellable = true)
    private static void onGlBindAttribLocation(int program, int index, CharSequence name, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    // ─── Uniform Uploads ───────────────────────────────────────────────

    @Inject(method = "_glUniform1i", at = @At("HEAD"), cancellable = true)
    private static void onGlUniform1i(int location, int value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glUniform1(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void onGlUniform1IntBuf(int location, java.nio.IntBuffer value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glUniform1(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void onGlUniform1FloatBuf(int location, java.nio.FloatBuffer value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glUniform2(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void onGlUniform2IntBuf(int location, java.nio.IntBuffer value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glUniform2(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void onGlUniform2FloatBuf(int location, java.nio.FloatBuffer value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glUniform3(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void onGlUniform3IntBuf(int location, java.nio.IntBuffer value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glUniform3(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void onGlUniform3FloatBuf(int location, java.nio.FloatBuffer value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glUniform4(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void onGlUniform4IntBuf(int location, java.nio.IntBuffer value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glUniform4(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void onGlUniform4FloatBuf(int location, java.nio.FloatBuffer value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glUniformMatrix2", at = @At("HEAD"), cancellable = true)
    private static void onGlUniformMatrix2(int location, boolean transpose, java.nio.FloatBuffer value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glUniformMatrix3", at = @At("HEAD"), cancellable = true)
    private static void onGlUniformMatrix3(int location, boolean transpose, java.nio.FloatBuffer value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_glUniformMatrix4", at = @At("HEAD"), cancellable = true)
    private static void onGlUniformMatrix4(int location, boolean transpose, java.nio.FloatBuffer value, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    // ─── Shader Query Methods ──────────────────────────────────────────

    @Inject(method = "glGetShaderi", at = @At("HEAD"), cancellable = true)
    private static void onGlGetShaderi(int shader, int pname, CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) {
            // 0x8B81 = GL_COMPILE_STATUS — return GL_TRUE (1) to indicate success
            cir.setReturnValue(pname == 0x8B81 ? 1 : 0);
        }
    }

    @Inject(method = "glGetProgrami", at = @At("HEAD"), cancellable = true)
    private static void onGlGetProgrami(int program, int pname, CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) {
            // 0x8B82 = GL_LINK_STATUS — return GL_TRUE (1)
            // 0x8B84 = GL_INFO_LOG_LENGTH — return 0
            cir.setReturnValue(pname == 0x8B82 ? 1 : 0);
        }
    }

    @Inject(method = "glAttachShader", at = @At("HEAD"), cancellable = true)
    private static void onGlAttachShader(int program, int shader, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "glGetShaderInfoLog", at = @At("HEAD"), cancellable = true)
    private static void onGlGetShaderInfoLog(int shader, int maxLength, CallbackInfoReturnable<String> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(""); }
    }

    @Inject(method = "glGetProgramInfoLog", at = @At("HEAD"), cancellable = true)
    private static void onGlGetProgramInfoLog(int program, int maxLength, CallbackInfoReturnable<String> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(""); }
    }

    // ─── Blend Equation ────────────────────────────────────────────────

    @Inject(method = "_blendEquation", at = @At("HEAD"), cancellable = true)
    private static void onBlendEquation(int mode, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); /* Vulkan blend equation set at pipeline creation */ }
    }

    // ─── Stencil ───────────────────────────────────────────────────────

    @Inject(method = "_stencilFunc", at = @At("HEAD"), cancellable = true)
    private static void onStencilFunc(int func, int ref, int mask, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_stencilMask", at = @At("HEAD"), cancellable = true)
    private static void onStencilMask(int mask, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_stencilOp", at = @At("HEAD"), cancellable = true)
    private static void onStencilOp(int sfail, int dpfail, int dppass, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "_clearStencil", at = @At("HEAD"), cancellable = true)
    private static void onClearStencil(int stencil, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    // ─── Polygon Mode / Logic Op (GlStateManager _methods) ────────────

    @Inject(method = "_polygonMode", at = @At("HEAD"), cancellable = true)
    private static void onPolygonMode(int face, int mode, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.polygonMode(face, mode); ci.cancel(); }
    }

    @Inject(method = "_logicOp", at = @At("HEAD"), cancellable = true)
    private static void onLogicOp(int op, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.logicOp(op); ci.cancel(); }
    }

    // ─── Texture extras ────────────────────────────────────────────────

    @Inject(method = "_genTextures", at = @At("HEAD"), cancellable = true)
    private static void onGenTextures(int[] ids, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            for (int i = 0; i < ids.length; i++) {
                ids[i] = GlStateInterceptor.getInstance().onGenTexture();
            }
            ci.cancel();
        }
    }

    @Inject(method = "_deleteTextures", at = @At("HEAD"), cancellable = true)
    private static void onDeleteTextures(int[] ids, CallbackInfo ci) {
        if (Vulkanium.wasVulkanUsed()) {
            if (Vulkanium.isVulkanReady()) {
                for (int id : ids) { GlStateInterceptor.getInstance().onDeleteTexture(id); }
            }
            ci.cancel(); // Always cancel if Vulkan was ever used
        }
    }

    @Inject(method = "_getActiveTexture", at = @At("HEAD"), cancellable = true)
    private static void onGetActiveTexture(CallbackInfoReturnable<Integer> cir) {
        if (Vulkanium.isVulkanReady()) { cir.setReturnValue(VRenderSystem.getActiveTexture()); }
    }

    @Inject(method = "_getTexImage", at = @At("HEAD"), cancellable = true)
    private static void onGetTexImage(int target, int level, int format, int type, long pixels, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); /* TODO: vkCmdCopyImageToBuffer */ }
    }

    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private static void onUpload(int texture, int width, int height, int x, int y,
                                  com.mojang.blaze3d.platform.NativeImage.Format format,
                                  java.nio.IntBuffer pixels, java.util.function.Consumer<java.nio.IntBuffer> onDone,
                                  CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            if (onDone != null) { onDone.accept(pixels); }
            ci.cancel();
        }
    }

    // ─── Lighting setup (GlStateManager level) ────────────────────────

    @Inject(method = "setupLevelDiffuseLighting", at = @At("HEAD"), cancellable = true)
    private static void onSetupLevelDiffuseLighting(org.joml.Vector3f light0, org.joml.Vector3f light1, org.joml.Matrix4f matrix, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); /* Lighting via UBOs in Vulkan */ }
    }

    @Inject(method = "setupGuiFlatDiffuseLighting", at = @At("HEAD"), cancellable = true)
    private static void onSetupGuiFlatDiffuseLighting(org.joml.Vector3f light0, org.joml.Vector3f light1, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    @Inject(method = "setupGui3DDiffuseLighting", at = @At("HEAD"), cancellable = true)
    private static void onSetupGui3DDiffuseLighting(org.joml.Vector3f light0, org.joml.Vector3f light1, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { ci.cancel(); }
    }

    // ─── Misc GL wrappers at GlStateManager level ─────────────────────

    @Inject(method = "glActiveTexture", at = @At("HEAD"), cancellable = true)
    private static void onGlActiveTexture(int texture, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.activeTexture(texture); ci.cancel(); }
    }

    @Inject(method = "glBlendFuncSeparate", at = @At("HEAD"), cancellable = true)
    private static void onGlBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) { VRenderSystem.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha); ci.cancel(); }
    }
}
