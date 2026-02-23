package net.vulkanium.compat;

import com.mojang.blaze3d.vertex.VertexSorting;
import net.vulkanium.Vulkanium;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vulkan replacement for {@code com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>All GL state is captured here. State changes are deferred — not applied until
 * a draw call triggers pipeline binding. Follows VulkanMod's VRenderSystem pattern.</p>
 */
public class VRenderSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RenderSystem");

    private static long windowHandle;

    // ─── Init ──────────────────────────────────────────────────────────

    public static void initRenderer() {
        LOGGER.info("VRenderSystem.initRenderer() — Vulkan renderer active");
    }

    public static void initWindow(long handle) {
        windowHandle = handle;
        LOGGER.info("VRenderSystem: Window handle set (0x{})", Long.toHexString(handle));
    }

    public static long getWindowHandle() { return windowHandle; }

    public static int maxSupportedTextureSize() {
        // TODO: Query VkPhysicalDeviceLimits.maxImageDimension2D
        return 16384;
    }

    // ─── Blend State ───────────────────────────────────────────────────

    private static boolean blendEnabled = false;
    private static int blendSrcRGB = 1;    // GL_ONE
    private static int blendDstRGB = 0;    // GL_ZERO
    private static int blendSrcAlpha = 1;
    private static int blendDstAlpha = 0;

    public static void enableBlend() { blendEnabled = true; markDirty(); }
    public static void disableBlend() { blendEnabled = false; markDirty(); }
    public static void blendFunc(int srcFactor, int dstFactor) {
        blendSrcRGB = srcFactor; blendDstRGB = dstFactor;
        blendSrcAlpha = srcFactor; blendDstAlpha = dstFactor;
        markDirty();
    }
    public static void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        blendSrcRGB = srcRGB; blendDstRGB = dstRGB;
        blendSrcAlpha = srcAlpha; blendDstAlpha = dstAlpha;
        markDirty();
    }

    // ─── Depth State ───────────────────────────────────────────────────

    private static boolean depthTestEnabled = false;
    private static boolean depthWriteEnabled = true;
    private static int depthFunc = 515; // GL_LEQUAL

    public static void enableDepthTest() { depthTestEnabled = true; markDirty(); }
    public static void disableDepthTest() { depthTestEnabled = false; markDirty(); }
    public static void depthFunc(int func) { depthFunc = func; markDirty(); }
    public static void depthMask(boolean write) { depthWriteEnabled = write; markDirty(); }

    // ─── Cull State ────────────────────────────────────────────────────

    private static boolean cullEnabled = true;

    public static void enableCull() { cullEnabled = true; markDirty(); }
    public static void disableCull() { cullEnabled = false; markDirty(); }

    // ─── Polygon Offset ────────────────────────────────────────────────

    private static boolean polygonOffsetEnabled = false;
    private static float polygonOffsetFactor = 0.0f;
    private static float polygonOffsetUnits = 0.0f;

    public static void enablePolygonOffset() { polygonOffsetEnabled = true; markDirty(); }
    public static void disablePolygonOffset() { polygonOffsetEnabled = false; markDirty(); }
    public static void polygonOffset(float factor, float units) {
        polygonOffsetFactor = factor; polygonOffsetUnits = units; markDirty();
    }

    // ─── Color Mask ────────────────────────────────────────────────────

    private static boolean colorMaskR = true, colorMaskG = true, colorMaskB = true, colorMaskA = true;

    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        colorMaskR = r; colorMaskG = g; colorMaskB = b; colorMaskA = a; markDirty();
    }

    // ─── Scissor ───────────────────────────────────────────────────────

    private static boolean scissorEnabled = false;
    private static int scissorX, scissorY, scissorWidth, scissorHeight;

    public static void enableScissor(int x, int y, int w, int h) {
        scissorEnabled = true;
        scissorX = x; scissorY = y; scissorWidth = w; scissorHeight = h;
    }
    public static void disableScissor() { scissorEnabled = false; }

    // ─── Viewport ──────────────────────────────────────────────────────

    private static int viewportX, viewportY, viewportWidth, viewportHeight;

    public static void viewport(int x, int y, int w, int h) {
        viewportX = x; viewportY = y; viewportWidth = w; viewportHeight = h;
    }

    // ─── Clear ─────────────────────────────────────────────────────────

    private static float clearR, clearG, clearB, clearA;
    private static double clearDepth = 1.0;

    public static void clearColor(float r, float g, float b, float a) {
        clearR = r; clearG = g; clearB = b; clearA = a;
    }
    public static void clearDepth(double depth) { clearDepth = depth; }

    public static void clear(int mask, boolean getError) {
        // Issue vkCmdClearAttachments inside the active render pass.
        // MC calls RenderSystem.clear(GL_DEPTH_BUFFER_BIT) between world and GUI rendering.
        // Without this, the depth buffer retains world geometry values and GUI draws
        // with LEQUAL depth test (RenderType.gui()) fail against close world objects.
        Vulkanium.clearAttachments(mask, clearR, clearG, clearB, clearA, (float) clearDepth);
    }

    // ─── Texture Binding ───────────────────────────────────────────────

    private static int activeTexture = 0;
    /**
     * GL-level bound textures (used by _bindTexture for upload targeting).
     * NOT used for rendering — use shaderTextures instead.
     */
    private static final long[] boundTextures = new long[32];
    /**
     * Shader-level bound textures (used by _setShaderTexture for rendering).
     * Draws should read from THIS array to get the correct texture for each slot.
     */
    private static final int[] shaderBoundTextures = new int[64];

    public static void activeTexture(int unit) {
        activeTexture = unit - 0x84C0; // GL_TEXTURE0 = 0x84C0
    }

    public static int getActiveTexture() { return activeTexture + 0x84C0; }
    public static int getActiveTextureUnit() { return activeTexture; }

    public static void bindTexture(long vkImageView) {
        int slot = Math.max(0, Math.min(activeTexture, boundTextures.length - 1));
        boundTextures[slot] = vkImageView;
    }

    /**
     * Bind a pseudo-GL texture ID to the active texture unit (GL-level).
     * Used for texture upload targeting — NOT for shader rendering.
     */
    public static void bindTexture(int textureId) {
        int slot = Math.max(0, Math.min(activeTexture, boundTextures.length - 1));
        boundTextures[slot] = textureId;
    }

    /**
     * Get the GL-bound texture ID for upload operations.
     */
    public static int getGlBoundTextureId(int unit) {
        if (unit < 0 || unit >= boundTextures.length) return 0;
        return (int) boundTextures[unit];
    }

    /**
     * Set a shader texture for a specific unit (called from RenderSystem._setShaderTexture).
     * This is what draws should use to look up the texture for rendering.
     */
    public static void setShaderTexture(int unit, int textureId) {
        if (unit >= 0 && unit < shaderBoundTextures.length) {
            shaderBoundTextures[unit] = textureId;
        }
    }

    /**
     * Get the shader-bound texture ID for rendering.
     * Used by recordDraw() to find the correct texture for each draw.
     */
    public static int getBoundTextureId(int unit) {
        if (unit < 0 || unit >= shaderBoundTextures.length) return 0;
        return shaderBoundTextures[unit];
    }

    // ─── Shader Binding ────────────────────────────────────────────────

    private static String currentShaderName = "";
    private static Object currentShaderInstance = null;

    public static void setShader(String shaderName) {
        currentShaderName = shaderName;
    }

    public static void setShader(net.minecraft.client.renderer.ShaderInstance shader) {
        currentShaderInstance = shader;
        currentShaderName = shader.getName();
    }

    public static void clearShader() {
        currentShaderName = "";
        currentShaderInstance = null;
    }

    public static void setActiveShaderName(String name) {
        currentShaderName = name != null ? name : "";
    }

    public static String getCurrentShaderName() { return currentShaderName; }

    // ─── State Snapshot ────────────────────────────────────────────────

    private static boolean stateDirty = true;

    private static void markDirty() { stateDirty = true; }

    /**
     * Capture the current GL-style state into a Vulkan pipeline state descriptor.
     * Called just before each draw to determine which pipeline to bind.
     */
    public static VulkanPipelineState captureState() {
        stateDirty = false;
        return new VulkanPipelineState(
                blendEnabled, blendSrcRGB, blendDstRGB, blendSrcAlpha, blendDstAlpha,
                depthTestEnabled, depthWriteEnabled, depthFunc,
                cullEnabled,
                polygonOffsetEnabled, polygonOffsetFactor, polygonOffsetUnits,
                colorMaskR, colorMaskG, colorMaskB, colorMaskA
        );
    }

    public static boolean isStateDirty() { return stateDirty; }

    // ─── Viewport/Scissor Getters ──────────────────────────────────────

    public static int getViewportX() { return viewportX; }
    public static int getViewportY() { return viewportY; }
    public static int getViewportWidth() { return viewportWidth; }
    public static int getViewportHeight() { return viewportHeight; }

    public static boolean isScissorEnabled() { return scissorEnabled; }
    public static int getScissorX() { return scissorX; }
    public static int getScissorY() { return scissorY; }
    public static int getScissorWidth() { return scissorWidth; }
    public static int getScissorHeight() { return scissorHeight; }

    public static float getClearR() { return clearR; }
    public static float getClearG() { return clearG; }
    public static float getClearB() { return clearB; }
    public static float getClearA() { return clearA; }
    public static double getClearDepth() { return clearDepth; }

    public static boolean isBlendEnabled() { return blendEnabled; }
    public static boolean isDepthTestEnabled() { return depthTestEnabled; }
    public static boolean isDepthWriteEnabled() { return depthWriteEnabled; }
    public static boolean isCullEnabled() { return cullEnabled; }

    public static boolean isPolygonOffsetEnabled() { return polygonOffsetEnabled; }
    public static float getPolygonOffsetFactor() { return polygonOffsetFactor; }
    public static float getPolygonOffsetUnits() { return polygonOffsetUnits; }

    // ─── Polygon Mode ─────────────────────────────────────────────────

    private static int polygonMode = 0x1B02; // GL_FILL

    public static void polygonMode(int face, int mode) {
        polygonMode = mode;
        markDirty();
    }

    // ─── Logic Op ──────────────────────────────────────────────────────

    private static boolean logicOpEnabled = false;
    private static int logicOpFunc = 0;

    public static void enableColorLogicOp() { logicOpEnabled = true; markDirty(); }
    public static void disableColorLogicOp() { logicOpEnabled = false; markDirty(); }
    public static void logicOp(int op) { logicOpFunc = op; markDirty(); }

    // ─── Matrix Uniforms ───────────────────────────────────────────────

    private static final Matrix4f projectionMat = new Matrix4f();
    private static final Matrix4f savedProjectionMat = new Matrix4f();
    private static final Matrix4f modelViewMat = new Matrix4f();
    private static final Matrix4f textureMat = new Matrix4f().identity();
    private static final Matrix4f mvpMat = new Matrix4f();
    private static VertexSorting vertexSorting = VertexSorting.DISTANCE_TO_ORIGIN;

    // ─── World-Render Matrix Snapshots ─────────────────────────────────
    // Captures the camera projection/modelView at world-render time before
    // GUI rendering overwrites the global matrices.  Fullscreen composite
    // passes must use these instead of the live (possibly GUI-overwritten) values.
    private static final Matrix4f worldRenderModelView = new Matrix4f();
    private static final Matrix4f worldRenderProjection = new Matrix4f();
    private static final Matrix4f prevWorldRenderModelView = new Matrix4f();
    private static final Matrix4f prevWorldRenderProjection = new Matrix4f();
    private static boolean hasWorldSnapshot = false;
    private static boolean hasPrevWorldSnapshot = false;

    /**
     * Snapshots the camera matrices captured from renderLevel parameters.
     * Uses the actual poseStack modelView and projection from MC's render call,
     * matching Iris's CapturedRenderingState approach for correct gbufferModelView.
     *
     * @param poseStackModelView the modelView matrix from poseStack.last().pose()
     * @param projectionMatrix   the projection matrix from renderLevel parameter
     */
    public static void snapshotWorldRenderMatrices(Matrix4f poseStackModelView,
                                                    Matrix4f projectionMatrix) {
        // Save previous world snapshot
        if (hasWorldSnapshot) {
            prevWorldRenderModelView.set(worldRenderModelView);
            prevWorldRenderProjection.set(worldRenderProjection);
            hasPrevWorldSnapshot = true;
        }
        worldRenderModelView.set(poseStackModelView);
        worldRenderProjection.set(projectionMatrix);
        hasWorldSnapshot = true;
    }

    public static Matrix4f getWorldRenderModelView() {
        return hasWorldSnapshot ? worldRenderModelView : modelViewMat;
    }

    public static Matrix4f getWorldRenderProjection() {
        return hasWorldSnapshot ? worldRenderProjection : projectionMat;
    }

    public static Matrix4f getPrevWorldRenderModelView() {
        return hasPrevWorldSnapshot ? prevWorldRenderModelView
                : (hasWorldSnapshot ? worldRenderModelView : modelViewMat);
    }

    public static Matrix4f getPrevWorldRenderProjection() {
        return hasPrevWorldSnapshot ? prevWorldRenderProjection
                : (hasWorldSnapshot ? worldRenderProjection : projectionMat);
    }

    public static void setProjectionMatrix(Matrix4f matrix, VertexSorting sorting) {
        // Guard against NaN projection — keep previous valid matrix.
        // This can happen during the first few frames before matrices are fully initialized.
        if (Float.isNaN(matrix.m00()) || Float.isNaN(matrix.m11())) {
            return;
        }
        projectionMat.set(matrix);
        // DO NOT overwrite savedProjectionMat here — it's only set by backupProjectionMatrix().
        // Overwriting it here causes the panorama's perspective projection to clobber the
        // saved ortho projection, making _restoreProjectionMatrix() restore the wrong matrix.
        vertexSorting = sorting;
        calculateMVP();
    }

    /**
     * Saves the current projection matrix for later restoration.
     * Called when MC's backupProjectionMatrix() is invoked.
     */
    public static void backupProjectionMatrix() {
        savedProjectionMat.set(projectionMat);
    }

    public static void applyModelViewMatrix() {
        // Deprecated — use setModelViewMatrix(Matrix4f) from MixinRenderSystem instead
        calculateMVP();
    }

    /**
     * Sets the model-view matrix from MC's pose stack and recalculates MVP.
     * Called from MixinRenderSystem.applyModelViewMatrix().
     */
    public static void setModelViewMatrix(Matrix4f matrix) {
        modelViewMat.set(matrix);
        calculateMVP();
    }

    public static void restoreProjectionMatrix() {
        projectionMat.set(savedProjectionMat);
        calculateMVP();
    }

    public static void setTextureMatrix(Matrix4f matrix) {
        textureMat.set(matrix);
    }

    public static void resetTextureMatrix() {
        textureMat.identity();
    }

    public static Matrix4f getTextureMatrix() { return textureMat; }
    public static boolean hasTextureMatrix() {
        // Check if texture matrix is non-identity (any glint/scroll active)
        return textureMat.m00() != 1.0f || textureMat.m01() != 0.0f || textureMat.m02() != 0.0f || textureMat.m03() != 0.0f
            || textureMat.m10() != 0.0f || textureMat.m11() != 1.0f || textureMat.m12() != 0.0f || textureMat.m13() != 0.0f
            || textureMat.m20() != 0.0f || textureMat.m21() != 0.0f || textureMat.m22() != 1.0f || textureMat.m23() != 0.0f
            || textureMat.m30() != 0.0f || textureMat.m31() != 0.0f || textureMat.m32() != 0.0f || textureMat.m33() != 1.0f;
    }

    private static void calculateMVP() {
        mvpMat.set(projectionMat).mul(modelViewMat);
    }

    public static Matrix4f getProjectionMatrix() { return projectionMat; }
    public static Matrix4f getModelViewMatrix() { return modelViewMat; }
    public static Matrix4f getMVPMatrix() { return mvpMat; }
    public static VertexSorting getVertexSorting() { return vertexSorting; }

    // ─── Buffer/VAO ID Generation ──────────────────────────────────────

    private static final AtomicInteger nextBufferId = new AtomicInteger(1);
    private static final AtomicInteger nextVertexArrayId = new AtomicInteger(1);

    public static int genBufferId() { return nextBufferId.getAndIncrement(); }
    public static int genVertexArrayId() { return nextVertexArrayId.getAndIncrement(); }
    public static void deleteBuffer(int id) { /* tracked for Vulkan resource cleanup */ }
    public static void deleteVertexArray(int id) { /* no-op in Vulkan */ }

    // ─── Shader Uniforms ───────────────────────────────────────────────

    private static float shaderColorR = 1.0f, shaderColorG = 1.0f, shaderColorB = 1.0f, shaderColorA = 1.0f;
    private static float fogStart = 0.0f, fogEnd = 0.0f;
    private static float fogColorR = 0.0f, fogColorG = 0.0f, fogColorB = 0.0f, fogColorA = 1.0f;
    private static int fogShape = 0; // 0 = sphere, 1 = cylinder
    private static int currentEntityId = 0;
    private static float lineWidth = 1.0f;

    // Per-chunk offset for terrain rendering (set via Uniform "ChunkOffset")
    private static float chunkOffsetX = 0.0f, chunkOffsetY = 0.0f, chunkOffsetZ = 0.0f;

    public static void setChunkOffset(float x, float y, float z) {
        chunkOffsetX = x; chunkOffsetY = y; chunkOffsetZ = z;
    }

    public static float getChunkOffsetX() { return chunkOffsetX; }
    public static float getChunkOffsetY() { return chunkOffsetY; }
    public static float getChunkOffsetZ() { return chunkOffsetZ; }
    public static boolean hasChunkOffset() {
        return chunkOffsetX != 0.0f || chunkOffsetY != 0.0f || chunkOffsetZ != 0.0f;
    }

    public static void setShaderColor(float r, float g, float b, float a) {
        shaderColorR = r; shaderColorG = g; shaderColorB = b; shaderColorA = a;
    }

    public static void setShaderFogStart(float start) { fogStart = start; }
    public static void setShaderFogEnd(float end) { fogEnd = end; }
    public static void setShaderFogColor(float r, float g, float b, float a) {
        fogColorR = r; fogColorG = g; fogColorB = b; fogColorA = a;
    }
    public static void setShaderFogShape(int shape) { fogShape = shape; }
    public static int getFogShape() { return fogShape; }

    public static void setCurrentEntityId(int id) { currentEntityId = id; }
    public static int getCurrentEntityId() { return currentEntityId; }

    public static void lineWidth(float width) { lineWidth = width; }
    public static float getLineWidth() { return lineWidth; }

    public static float getShaderColorR() { return shaderColorR; }
    public static float getShaderColorG() { return shaderColorG; }
    public static float getShaderColorB() { return shaderColorB; }
    public static float getShaderColorA() { return shaderColorA; }
    public static float getFogStart() { return fogStart; }
    public static float getFogEnd() { return fogEnd; }
    public static float getFogColorR() { return fogColorR; }
    public static float getFogColorG() { return fogColorG; }
    public static float getFogColorB() { return fogColorB; }
    public static float getFogColorA() { return fogColorA; }

    // ─── GL → Vulkan Conversion Helpers ────────────────────────────────

    /**
     * Converts a GL blend factor constant to the equivalent VK blend factor.
     * Matches VulkanMod's PipelineState.BlendInfo.glToVulkanBlendFactor().
     */
    public static int glToVkBlendFactor(int glFactor) {
        return switch (glFactor) {
            case 0      /* GL_ZERO */                   -> 0;  // VK_BLEND_FACTOR_ZERO
            case 1      /* GL_ONE */                    -> 1;  // VK_BLEND_FACTOR_ONE
            case 0x0300 /* GL_SRC_COLOR */              -> 2;  // VK_BLEND_FACTOR_SRC_COLOR
            case 0x0301 /* GL_ONE_MINUS_SRC_COLOR */    -> 3;  // VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR
            case 0x0302 /* GL_SRC_ALPHA */              -> 6;  // VK_BLEND_FACTOR_SRC_ALPHA
            case 0x0303 /* GL_ONE_MINUS_SRC_ALPHA */    -> 7;  // VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
            case 0x0304 /* GL_DST_ALPHA */              -> 8;  // VK_BLEND_FACTOR_DST_ALPHA
            case 0x0305 /* GL_ONE_MINUS_DST_ALPHA */    -> 9;  // VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA
            case 0x0306 /* GL_DST_COLOR */              -> 4;  // VK_BLEND_FACTOR_DST_COLOR
            case 0x0307 /* GL_ONE_MINUS_DST_COLOR */    -> 5;  // VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR
            case 0x0308 /* GL_SRC_ALPHA_SATURATE */     -> 14; // VK_BLEND_FACTOR_SRC_ALPHA_SATURATE
            default -> 1; // VK_BLEND_FACTOR_ONE
        };
    }

    /**
     * Converts a GL depth function to the equivalent VK compare op.
     */
    public static int glToVkDepthFunc(int glFunc) {
        return switch (glFunc) {
            case 512 /* GL_NEVER */    -> 0; // VK_COMPARE_OP_NEVER
            case 513 /* GL_LESS */     -> 1; // VK_COMPARE_OP_LESS
            case 514 /* GL_EQUAL */    -> 2; // VK_COMPARE_OP_EQUAL
            case 515 /* GL_LEQUAL */   -> 3; // VK_COMPARE_OP_LESS_OR_EQUAL
            case 516 /* GL_GREATER */  -> 4; // VK_COMPARE_OP_GREATER
            case 517 /* GL_NOTEQUAL */ -> 5; // VK_COMPARE_OP_NOT_EQUAL
            case 518 /* GL_GEQUAL */   -> 6; // VK_COMPARE_OP_GREATER_OR_EQUAL
            case 519 /* GL_ALWAYS */   -> 7; // VK_COMPARE_OP_ALWAYS
            default -> 3; // VK_COMPARE_OP_LESS_OR_EQUAL
        };
    }

    public static int getBlendSrcRGB()   { return blendSrcRGB; }
    public static int getBlendDstRGB()   { return blendDstRGB; }
    public static int getBlendSrcAlpha() { return blendSrcAlpha; }
    public static int getBlendDstAlpha() { return blendDstAlpha; }
    public static int getDepthFunc()     { return depthFunc; }

    // ─── Draw Dispatch ─────────────────────────────────────────────────

    /**
     * Upload vertex/index data and issue a Vulkan draw command.
     * Called from MixinBufferUploader.drawWithShader().
     */
    public static void uploadAndDraw(java.nio.ByteBuffer vertexData,
                                      java.nio.ByteBuffer indexData,
                                      com.mojang.blaze3d.vertex.VertexFormat format,
                                      com.mojang.blaze3d.vertex.VertexFormat.Mode mode,
                                      int vertexCount, int indexCount,
                                      boolean hasIndex) {
        if (vertexData == null || vertexCount <= 0) return;

        // Debug: log shader + format info for first few frames
        if (drawCallCounter < 50) {
            LOGGER.debug("[Draw #{}] shader='{}' format={} mode={} verts={} bytes={}",
                    drawCallCounter, currentShaderName, format, mode, vertexCount, vertexData.remaining());
        }
        drawCallCounter++;

        // Pass the actual mode so Vulkanium can select the correct index buffer
        // (QUADS, TRIANGLE_FAN, TRIANGLE_STRIP → indexed TRIANGLE_LIST; TRIANGLES → direct)
        net.vulkanium.Vulkanium.recordDraw(vertexData, vertexCount, mode, format.getVertexSize(), format);
    }
    private static int drawCallCounter = 0;
}
