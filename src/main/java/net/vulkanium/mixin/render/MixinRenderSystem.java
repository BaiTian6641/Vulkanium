package net.vulkanium.mixin.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanium.compat.GlStateInterceptor;
import net.vulkanium.compat.VRenderSystem;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * Total replacement of RenderSystem for Vulkan rendering.
 * Every method that touches GL state is @Overwritten.
 * With GLFW_NO_API there is no GL context — all GL calls must be intercepted.
 */
@Mixin(RenderSystem.class)
public abstract class MixinRenderSystem {

    @Shadow private static PoseStack modelViewStack;
    @Shadow private static Matrix4f projectionMatrix;
    @Shadow private static Matrix4f savedProjectionMatrix;
    @Shadow private static Matrix4f modelViewMatrix;
    @Shadow private static float[] shaderColor;
    @Shadow private static float[] shaderFogColor;
    @Shadow private static int[] shaderTextures;
    @Shadow private static VertexSorting vertexSorting;
    @Shadow private static VertexSorting savedVertexSorting;
    @Shadow @Nullable private static ShaderInstance shader;

    /** @author Vulkanium @reason Vulkan init */
    @Overwrite public static void initRenderer(int debugVerbosity, boolean debugSync) {
        VRenderSystem.initRenderer();
    }

    /** @author Vulkanium @reason No GL state */
    @Overwrite public static void setupDefaultState(int x, int y, int width, int height) {
        VRenderSystem.viewport(x, y, width, height);
    }

    /** @author Vulkanium @reason Vulkan present – must still replay the render-call queue */
    @Overwrite public static void flipFrame(long window) {
        RenderSystem.replayQueue();
        GLFW.glfwPollEvents();
    }

    /** @author Vulkanium @reason Vulkan limits */
    @Overwrite public static int maxSupportedTextureSize() { return VRenderSystem.maxSupportedTextureSize(); }

    // ─── Blend ─────────────────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void enableBlend() { VRenderSystem.enableBlend(); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void disableBlend() { VRenderSystem.disableBlend(); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void blendFunc(int src, int dst) { VRenderSystem.blendFunc(src, dst); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void blendFuncSeparate(int srcRGB, int dstRGB, int srcA, int dstA) { VRenderSystem.blendFuncSeparate(srcRGB, dstRGB, srcA, dstA); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void blendFunc(GlStateManager.SourceFactor src, GlStateManager.DestFactor dst) { VRenderSystem.blendFunc(src.value, dst.value); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void blendFuncSeparate(GlStateManager.SourceFactor srcRGB, GlStateManager.DestFactor dstRGB, GlStateManager.SourceFactor srcA, GlStateManager.DestFactor dstA) { VRenderSystem.blendFuncSeparate(srcRGB.value, dstRGB.value, srcA.value, dstA.value); }

    // ─── Depth ─────────────────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void enableDepthTest() { VRenderSystem.enableDepthTest(); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void disableDepthTest() { VRenderSystem.disableDepthTest(); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void depthFunc(int func) { VRenderSystem.depthFunc(func); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void depthMask(boolean mask) { VRenderSystem.depthMask(mask); }

    // ─── Cull ──────────────────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void enableCull() { VRenderSystem.enableCull(); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void disableCull() { VRenderSystem.disableCull(); }

    // ─── Color Mask ────────────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void colorMask(boolean r, boolean g, boolean b, boolean a) { VRenderSystem.colorMask(r, g, b, a); }

    // ─── Polygon ───────────────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void enablePolygonOffset() { VRenderSystem.enablePolygonOffset(); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void disablePolygonOffset() { VRenderSystem.disablePolygonOffset(); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void polygonOffset(float factor, float units) { VRenderSystem.polygonOffset(factor, units); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void polygonMode(int face, int mode) { VRenderSystem.polygonMode(face, mode); }

    // ─── Logic Op ──────────────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void enableColorLogicOp() { VRenderSystem.enableColorLogicOp(); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void disableColorLogicOp() { VRenderSystem.disableColorLogicOp(); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void logicOp(GlStateManager.LogicOp op) { VRenderSystem.logicOp(op.value); }

    // ─── Viewport / Scissor ────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void viewport(int x, int y, int w, int h) { VRenderSystem.viewport(x, y, w, h); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void enableScissor(int x, int y, int w, int h) { VRenderSystem.enableScissor(x, y, w, h); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void disableScissor() { VRenderSystem.disableScissor(); }

    // ─── Clear ─────────────────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void clear(int mask, boolean getError) { VRenderSystem.clear(mask, getError); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void clearColor(float r, float g, float b, float a) { VRenderSystem.clearColor(r, g, b, a); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void clearDepth(double depth) { VRenderSystem.clearDepth(depth); }

    // ─── Texture ───────────────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void activeTexture(int texture) { VRenderSystem.activeTexture(texture); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void _setShaderTexture(int unit, int textureId) {
        if (unit >= 0 && unit < shaderTextures.length) shaderTextures[unit] = textureId;
        VRenderSystem.setShaderTexture(unit, textureId);
    }
    /** @author Vulkanium @reason VK */ @Overwrite public static void _setShaderTexture(int unit, net.minecraft.resources.ResourceLocation texture) {
        net.minecraft.client.renderer.texture.AbstractTexture tex = net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(texture);
        int texId = tex.getId();
        GlStateInterceptor.onShaderTextureResource(texId, texture.toString());
        if (unit >= 0 && unit < shaderTextures.length) shaderTextures[unit] = texId;
        VRenderSystem.setShaderTexture(unit, texId);
    }
    /** @author Vulkanium @reason VK */ @Overwrite public static void texParameter(int target, int pname, int value) {
        // Forward to GlStateInterceptor so filter/clamp settings reach VulkanTexture.
        // AbstractTexture.setFilter() calls this for MIN_FILTER / MAG_FILTER.
        GlStateInterceptor.getInstance().onTexParameterI(target, pname, value);
    }

    // ─── Shader ────────────────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void setShader(Supplier<ShaderInstance> supplier) {
        if (supplier != null) {
            ShaderInstance s = supplier.get();
            if (s != null) {
                shader = s; // Keep vanilla field in sync — needed by renderChunkLayer etc
                VRenderSystem.setShader(s);
                return;
            }
        }
        shader = null;
        VRenderSystem.clearShader();
    }
    /** @author Vulkanium @reason VK */ @Overwrite public static void _setShaderColor(float r, float g, float b, float a) {
        shaderColor[0] = r; shaderColor[1] = g; shaderColor[2] = b; shaderColor[3] = a;
        VRenderSystem.setShaderColor(r, g, b, a);
    }
    /** @author Vulkanium @reason VK */ @Overwrite public static void _setShaderFogColor(float r, float g, float b, float a) {
        shaderFogColor[0] = r; shaderFogColor[1] = g; shaderFogColor[2] = b; shaderFogColor[3] = a;
        VRenderSystem.setShaderFogColor(r, g, b, a);
    }

    // ─── Matrix ────────────────────────────────────────────────────────
    /** @author Vulkanium @reason VK — MUST keep vanilla projectionMatrix in sync for getProjectionMatrix() callers */
    @Overwrite public static void setProjectionMatrix(Matrix4f matrix, VertexSorting sorting) {
        // Only set the active projection — DO NOT touch savedProjectionMatrix.
        // The saved copy is managed exclusively by backupProjectionMatrix().
        // Vanilla MC does the same: setProjectionMatrix only writes projectionMatrix.
        // If we overwrite savedProjectionMatrix here, panorama's perspective projection
        // clobbers the saved ortho, and _restoreProjectionMatrix() restores the wrong matrix.
        projectionMatrix = new Matrix4f(matrix);
        vertexSorting = sorting;
        VRenderSystem.setProjectionMatrix(matrix, sorting);
    }
    /** @author Vulkanium @reason VK */ @Overwrite public static void applyModelViewMatrix() {
        Matrix4f pose = modelViewStack.last().pose();
        modelViewMatrix = new Matrix4f(pose); // Keep vanilla field in sync
        VRenderSystem.setModelViewMatrix(pose);
    }
    /** @author Vulkanium @reason VK */ @Overwrite public static void _restoreProjectionMatrix() {
        projectionMatrix = savedProjectionMatrix; // Keep vanilla field in sync
        vertexSorting = savedVertexSorting;
        VRenderSystem.restoreProjectionMatrix();
    }
    /** @author Vulkanium @reason VK */ @Overwrite public static void backupProjectionMatrix() {
        savedProjectionMatrix = projectionMatrix;
        savedVertexSorting = vertexSorting;
        VRenderSystem.backupProjectionMatrix();
    }
    /** @author Vulkanium @reason VK */ @Overwrite public static void setTextureMatrix(Matrix4f matrix) { VRenderSystem.setTextureMatrix(matrix); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void resetTextureMatrix() { VRenderSystem.resetTextureMatrix(); }

    // ─── Line Width / Misc ─────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void lineWidth(float width) { VRenderSystem.lineWidth(width); }

    // ─── Buffer/VAO Stubs ──────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void glGenBuffers(Consumer<Integer> consumer) { consumer.accept(VRenderSystem.genBufferId()); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void glGenVertexArrays(Consumer<Integer> consumer) { consumer.accept(VRenderSystem.genVertexArrayId()); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void glDeleteBuffers(int buffer) { VRenderSystem.deleteBuffer(buffer); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void glDeleteVertexArrays(int vao) { VRenderSystem.deleteVertexArray(vao); }

    // ─── Shader Uniforms ───────────────────────────────────────────────
    /** @author Vulkanium @reason VK */ @Overwrite public static void setShaderFogStart(float start) { VRenderSystem.setShaderFogStart(start); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void setShaderFogEnd(float end) { VRenderSystem.setShaderFogEnd(end); }
    /** @author Vulkanium @reason VK */ @Overwrite public static void setShaderFogColor(float r, float g, float b, float a) {
        shaderFogColor[0] = r; shaderFogColor[1] = g; shaderFogColor[2] = b; shaderFogColor[3] = a;
        VRenderSystem.setShaderFogColor(r, g, b, a);
    }
    /** @author Vulkanium @reason VK */ @Overwrite public static void setShaderFogColor(float r, float g, float b) {
        shaderFogColor[0] = r; shaderFogColor[1] = g; shaderFogColor[2] = b; shaderFogColor[3] = 1.0f;
        VRenderSystem.setShaderFogColor(r, g, b, 1.0f);
    }
    /** @author Vulkanium @reason VK */ @Overwrite public static void setShaderColor(float r, float g, float b, float a) {
        shaderColor[0] = r; shaderColor[1] = g; shaderColor[2] = b; shaderColor[3] = a;
        VRenderSystem.setShaderColor(r, g, b, a);
    }
}
