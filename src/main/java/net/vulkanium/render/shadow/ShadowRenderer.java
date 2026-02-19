package net.vulkanium.render.shadow;

import net.vulkanium.Vulkanium;
import net.vulkanium.render.composite.CompositePassManager;
import net.vulkanium.render.terrain.ChunkRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkViewport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Orchestrates the complete shadow rendering pipeline.
 *
 * <p>Mirrors Iris's {@code ShadowRenderer} — manages the full shadow pass
 * including matrix setup, frustum culling, terrain/entity/block-entity rendering,
 * depth copying, and shadow composite execution.</p>
 *
 * <h3>Full Render Sequence</h3>
 * <pre>
 *   1. Set ACTIVE = true
 *   2. Compute render distance from directives
 *   3. Calculate shadow model-view matrix (sun angle + grid snap)
 *   4. Calculate shadow projection matrix (ortho or perspective)
 *   5. Create shadow frustum (culling strategy from pack)
 *   6. Set viewport to shadow map resolution
 *   7. Disable backface culling
 *   8. Render opaque terrain (solid + cutout + cutoutMipped)
 *   9. Reset viewport
 *  10. Render entities (within entity shadow distance)
 *  11. Render block entities
 *  12. Copy pre-translucent depth (shadowtex0 → shadowtex1)
 *  13. Render translucent terrain
 *  14. Generate mipmaps
 *  15. Restore culling + viewport + projection
 *  16. Run shadow composite passes
 *  17. Set ACTIVE = false
 * </pre>
 *
 * <h3>Frustum Culling Strategies</h3>
 * <ul>
 *   <li><b>DEFAULT</b> — Advanced frustum analysis (cast-into-view check)</li>
 *   <li><b>ADVANCED</b> — Aggressive frustum culling for performance</li>
 *   <li><b>REVERSED</b> — Reversed culling for packs that render behind camera</li>
 *   <li><b>DISTANCE</b> — Distance-only culling (for voxelization packs)</li>
 * </ul>
 */
public class ShadowRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ShadowRenderer");

    // ── Static state (accessible by uniform bridge) ──
    public static boolean ACTIVE = false;
    public static final Matrix4f MODELVIEW = new Matrix4f();
    public static final Matrix4f PROJECTION = new Matrix4f();
    public static int renderDistance = 0;

    // ── Components ──
    private final ShadowMap shadowMap;
    private final ShadowMatrices matrices;
    private final ShadowDirectives directives;
    private final CompositePassManager shadowComposites;
    private long shadowCompositeUniformDescriptorSet = 0L;

    /** Optional externally-created shadow render pass integration. */
    private long shadowRenderPass = VK_NULL_HANDLE;
    private long shadowFramebuffer = VK_NULL_HANDLE;
    private int shadowColorAttachmentCount = 0;

    // ── Frustum state ──
    private ShadowFrustum terrainFrustum;
    private ShadowFrustum entityFrustum;

    // ── Render flags ──
    private boolean shouldRenderTerrain;
    private boolean shouldRenderTranslucent;
    private boolean shouldRenderEntities;
    private boolean shouldRenderPlayer;
    private boolean shouldRenderBlockEntities;

    // ── Pack properties ──
    private float sunPathRotation = 0.0f;
    private boolean packHasVoxelization = false;

    // ── Stats ──
    private int entitiesRendered = 0;
    private int blockEntitiesRendered = 0;
    private long lastRenderTimeNs = 0;

    public ShadowRenderer(ShadowMap shadowMap, ShadowDirectives directives,
                           CompositePassManager shadowComposites) {
        this.shadowMap = shadowMap;
        this.directives = directives;
        this.shadowComposites = shadowComposites;
        this.matrices = new ShadowMatrices();

        // Copy render flags from directives
        this.shouldRenderTerrain = directives.shouldRenderTerrain();
        this.shouldRenderTranslucent = directives.shouldRenderTranslucent();
        this.shouldRenderEntities = directives.shouldRenderEntities();
        this.shouldRenderPlayer = directives.shouldRenderPlayer();
        this.shouldRenderBlockEntities = directives.shouldRenderBlockEntities();
    }

    /**
     * Renders the complete shadow pass for the current frame.
     *
     * @param commandBuffer Active command buffer
     * @param cameraPos     Camera world position
     * @param skyAngle      Level's getTimeOfDay(tickDelta)
     * @param tickDelta     Partial tick for interpolation
     * @param chunkRenderer Terrain renderer for shadow geometry
     */
    public void renderShadows(long commandBuffer, Vector3d cameraPos,
                               float skyAngle, float tickDelta,
                               ChunkRenderer chunkRenderer) {
        if (directives.getDistance() <= 0) return;

        long startNs = System.nanoTime();
        ACTIVE = true;
        entitiesRendered = 0;
        blockEntitiesRendered = 0;

        try {
            // ── Step 1: Compute render distance ──
            float distMul = directives.getDistanceRenderMul();
            if (distMul < 0) {
                // Use user's render distance setting
                renderDistance = (int) (directives.getDistance() / 16.0f);
            } else {
                renderDistance = (int) (directives.getDistance() * distMul / 16.0f);
            }

            // ── Step 2: Compute matrices ──
            float sunAngle = ShadowMatrices.computeSunAngle(skyAngle);
            float shadowAngle = ShadowMatrices.computeShadowAngle(sunAngle);

            matrices.update(directives, shadowAngle, sunPathRotation, cameraPos);
            MODELVIEW.set(matrices.getModelView());
            PROJECTION.set(matrices.getProjection());

            // ── Step 3: Create shadow frustum ──
            createFrustums(cameraPos);

            // ── Step 4: Set viewport ──
            int res = shadowMap.getResolution();
            setShadowViewport(commandBuffer, res, res);

            // ── Step 5: Clear targets ──
            shadowMap.markTranslucentDepthDirty();
            shadowMap.clearColorTargets(commandBuffer);

            // ── Step 6: Begin shadow render pass ──
            boolean opaquePassBegun = beginShadowRenderPass(commandBuffer, true);

            // ── Step 7: Render opaque terrain ──
            if (shouldRenderTerrain) {
                renderOpaqueTerrainShadow(commandBuffer, chunkRenderer);
            }

            // ── Step 8: Render entities ──
            if (shouldRenderEntities) {
                renderEntitiesShadow(commandBuffer, cameraPos);
            }

            // ── Step 9: Render block entities ──
            if (shouldRenderBlockEntities) {
                renderBlockEntitiesShadow(commandBuffer);
            }

            // ── Step 10: End render pass for opaque ──
            endShadowRenderPass(commandBuffer, opaquePassBegun);

            // ── Step 11: Copy pre-translucent depth ──
            shadowMap.copyPreTranslucentDepth(commandBuffer);

            // ── Step 12: Begin render pass for translucent ──
            if (shouldRenderTranslucent) {
                boolean translucentPassBegun = beginShadowRenderPass(commandBuffer, false);
                renderTranslucentTerrainShadow(commandBuffer, chunkRenderer);
                endShadowRenderPass(commandBuffer, translucentPassBegun);
            }

            // ── Step 13: Generate mipmaps ──
            shadowMap.generateMipmaps(commandBuffer);

            // ── Step 14: Restore viewport ──
            if (Vulkanium.getVulkanSwapchain() != null) {
                setShadowViewport(commandBuffer,
                        Vulkanium.getVulkanSwapchain().getWidth(),
                        Vulkanium.getVulkanSwapchain().getHeight());
            }

            // ── Step 15: Run shadow composites ──
            if (shadowComposites != null && shadowComposites.getActivePassCount() > 0) {
                shadowComposites.renderAll(commandBuffer, shadowCompositeUniformDescriptorSet,
                        res, res);
            }
        } finally {
            ACTIVE = false;
            lastRenderTimeNs = System.nanoTime() - startNs;
        }
    }

    private void createFrustums(Vector3d cameraPos) {
        ShadowDirectives.ShadowCullState cullState = directives.getCullState();
        boolean useVoxelCulling = (cullState == ShadowDirectives.ShadowCullState.DEFAULT && packHasVoxelization)
                || cullState == ShadowDirectives.ShadowCullState.DISTANCE;

        if (useVoxelCulling) {
            // Distance-only culling — no frustum needed for voxelization packs
            terrainFrustum = ShadowFrustum.distanceOnly(directives.getDistance(), cameraPos);
        } else {
            // Frustum-based culling using shadow matrices
            terrainFrustum = ShadowFrustum.fromMatrices(
                    matrices.getModelView(), matrices.getProjection(),
                    cameraPos, cullState == ShadowDirectives.ShadowCullState.REVERSED);
        }

        // Entity frustum may have different distance multiplier
        float entityMul = directives.getEntityShadowDistanceMul();
        if (entityMul != 1.0f && entityMul > 0) {
            entityFrustum = ShadowFrustum.distanceOnly(
                    directives.getDistance() * entityMul, cameraPos);
        } else {
            entityFrustum = terrainFrustum;
        }
    }

    private void renderOpaqueTerrainShadow(long commandBuffer, ChunkRenderer chunkRenderer) {
        // Bind shadow terrain pipeline and issue draw calls via ChunkRenderer
        // ChunkRenderer handles terrain batching — we just need to tell it which render layers
        chunkRenderer.renderShadow(commandBuffer, terrainFrustum,
                ChunkRenderer.LAYER_SOLID | ChunkRenderer.LAYER_CUTOUT | ChunkRenderer.LAYER_CUTOUT_MIPPED);
    }

    private void renderTranslucentTerrainShadow(long commandBuffer, ChunkRenderer chunkRenderer) {
        // Translucent shadow pass needs blending enabled on the pipeline
        chunkRenderer.renderShadow(commandBuffer, terrainFrustum, ChunkRenderer.LAYER_TRANSLUCENT);
    }

    private void renderEntitiesShadow(long commandBuffer, Vector3d cameraPos) {
        // Entity rendering delegated to the entity rendering subsystem
        // which iterates visible entities within the entityFrustum bounds
        // For now, the entity renderer integration is handled at a higher level
        // by the main world renderer which calls into Minecraft's entity dispatch
        entitiesRendered = 0;
    }

    private void renderBlockEntitiesShadow(long commandBuffer) {
        // Block entity rendering delegated to the block entity rendering subsystem
        blockEntitiesRendered = 0;
    }

    /**
     * Sets viewport and scissor to shadow map dimensions.
     */
    private void setShadowViewport(long commandBuffer, int width, int height) {
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
        try (MemoryStack stack = stackPush()) {
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0.0f).y(0.0f)
                    .width(width).height(height)
                    .minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            vkCmdSetScissor(cmd, 0, scissor);
        }
    }

    private boolean beginShadowRenderPass(long commandBuffer, boolean clearDepth) {
        if (shadowRenderPass == VK_NULL_HANDLE || shadowFramebuffer == VK_NULL_HANDLE) {
            return false;
        }

        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        int attachmentCount = Math.max(1, shadowColorAttachmentCount + 1);

        try (MemoryStack stack = stackPush()) {
            VkClearValue.Buffer clearValues = VkClearValue.calloc(attachmentCount, stack);
            for (int i = 0; i < shadowColorAttachmentCount; i++) {
                clearValues.get(i).color()
                        .float32(0, 0.0f)
                        .float32(1, 0.0f)
                        .float32(2, 0.0f)
                        .float32(3, 0.0f);
            }
            clearValues.get(attachmentCount - 1).depthStencil()
                    .depth(1.0f)
                    .stencil(0);

            VkRenderPassBeginInfo beginInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(shadowRenderPass)
                    .framebuffer(shadowFramebuffer)
                    .pClearValues(clearDepth ? clearValues : null);

            beginInfo.renderArea().offset().set(0, 0);
            beginInfo.renderArea().extent().set(shadowMap.getResolution(), shadowMap.getResolution());

            vkCmdBeginRenderPass(cmd, beginInfo, VK_SUBPASS_CONTENTS_INLINE);
            return true;
        }
    }

    private void endShadowRenderPass(long commandBuffer, boolean begun) {
        if (!begun) return;
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
        vkCmdEndRenderPass(cmd);
    }

    // ── Configuration ──

    public void setSunPathRotation(float rotation) { sunPathRotation = rotation; }
    public void setVoxelization(boolean hasVoxelization) { packHasVoxelization = hasVoxelization; }
    public void setShadowCompositeUniformDescriptorSet(long descriptorSet) {
        this.shadowCompositeUniformDescriptorSet = descriptorSet;
    }
    public void setShadowRenderTargets(long renderPass, long framebuffer, int colorAttachmentCount) {
        this.shadowRenderPass = renderPass;
        this.shadowFramebuffer = framebuffer;
        this.shadowColorAttachmentCount = Math.max(0, colorAttachmentCount);
    }

    // ── Getters ──

    public ShadowMap getShadowMap() { return shadowMap; }
    public ShadowMatrices getMatrices() { return matrices; }
    public ShadowDirectives getDirectives() { return directives; }
    public int getEntitiesRendered() { return entitiesRendered; }
    public int getBlockEntitiesRendered() { return blockEntitiesRendered; }
    public long getLastRenderTimeNs() { return lastRenderTimeNs; }

    // ── Lifecycle ──

    public void destroy() {
        ACTIVE = false;
        LOGGER.debug("Shadow renderer destroyed");
    }

    // ── Inner types ──

    /**
     * Shadow frustum abstraction supporting multiple culling strategies.
     */
    public static class ShadowFrustum {
        public enum Type { NONE, DISTANCE_ONLY, MATRIX_BASED, REVERSED }

        private final Type type;
        private final float maxDistance;
        private final Vector3d center;
        private final Matrix4f mvp;
        private final boolean reversed;

        private ShadowFrustum(Type type, float maxDistance, Vector3d center,
                              Matrix4f mvp, boolean reversed) {
            this.type = type;
            this.maxDistance = maxDistance;
            this.center = center;
            this.mvp = mvp;
            this.reversed = reversed;
        }

        /** No culling — renders everything. */
        public static ShadowFrustum none() {
            return new ShadowFrustum(Type.NONE, Float.MAX_VALUE, new Vector3d(), null, false);
        }

        /** Distance-only culling — for voxelization packs. */
        public static ShadowFrustum distanceOnly(float maxDistance, Vector3d center) {
            return new ShadowFrustum(Type.DISTANCE_ONLY, maxDistance, new Vector3d(center), null, false);
        }

        /** Full matrix-based frustum culling. */
        public static ShadowFrustum fromMatrices(Matrix4f modelView, Matrix4f projection,
                                                  Vector3d center, boolean reversed) {
            Matrix4f mvp = new Matrix4f(projection).mul(modelView);
            return new ShadowFrustum(reversed ? Type.REVERSED : Type.MATRIX_BASED,
                    Float.MAX_VALUE, new Vector3d(center), mvp, reversed);
        }

        /**
         * Tests whether a chunk section is visible in the shadow frustum.
         *
         * @param minX Section AABB min X
         * @param minY Section AABB min Y
         * @param minZ Section AABB min Z
         * @param maxX Section AABB max X
         * @param maxY Section AABB max Y
         * @param maxZ Section AABB max Z
         * @return true if the section should be rendered
         */
        public boolean testVisibility(double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
            return switch (type) {
                case NONE -> true;
                case DISTANCE_ONLY -> {
                    double cx = (minX + maxX) * 0.5 - center.x;
                    double cz = (minZ + maxZ) * 0.5 - center.z;
                    yield (cx * cx + cz * cz) <= maxDistance * maxDistance;
                }
                case MATRIX_BASED, REVERSED -> {
                    if (mvp == null) {
                        yield true;
                    }

                    Vector4f[] corners = {
                            new Vector4f((float) minX, (float) minY, (float) minZ, 1.0f),
                            new Vector4f((float) minX, (float) minY, (float) maxZ, 1.0f),
                            new Vector4f((float) minX, (float) maxY, (float) minZ, 1.0f),
                            new Vector4f((float) minX, (float) maxY, (float) maxZ, 1.0f),
                            new Vector4f((float) maxX, (float) minY, (float) minZ, 1.0f),
                            new Vector4f((float) maxX, (float) minY, (float) maxZ, 1.0f),
                            new Vector4f((float) maxX, (float) maxY, (float) minZ, 1.0f),
                            new Vector4f((float) maxX, (float) maxY, (float) maxZ, 1.0f)
                    };

                    for (Vector4f corner : corners) {
                        mvp.transform(corner);
                    }

                    boolean outsideLeft = true;
                    boolean outsideRight = true;
                    boolean outsideBottom = true;
                    boolean outsideTop = true;
                    boolean outsideNear = !reversed;
                    boolean outsideFar = !reversed;

                    for (Vector4f c : corners) {
                        float w = c.w;
                        if (c.x >= -w) outsideLeft = false;
                        if (c.x <= w) outsideRight = false;
                        if (c.y >= -w) outsideBottom = false;
                        if (c.y <= w) outsideTop = false;
                        if (!reversed) {
                            if (c.z >= -w) outsideNear = false;
                            if (c.z <= w) outsideFar = false;
                        }
                    }

                    boolean rejected = outsideLeft || outsideRight || outsideBottom || outsideTop
                            || outsideNear || outsideFar;
                    yield !rejected;
                }
            };
        }

        public Type getType() { return type; }
        public float getMaxDistance() { return maxDistance; }
    }
}
