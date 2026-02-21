package net.vulkanium.render.terrain;

import net.vulkanium.Vulkanium;
import net.vulkanium.compat.GlStateInterceptor;
import net.vulkanium.compat.VRenderSystem;
import net.vulkanium.core.*;
import net.vulkanium.render.terrain.build.ChunkBuildScheduler;
import net.vulkanium.render.terrain.build.ChunkBuildWorkerPool;
import net.vulkanium.render.terrain.cull.ChunkFrustumCuller;
import net.vulkanium.render.terrain.cull.ChunkOcclusionCuller;
import net.vulkanium.render.terrain.pass.TerrainPassType;
import net.vulkanium.render.terrain.pass.TerrainRenderPass;
import net.vulkanium.render.terrain.region.RenderRegion;
import net.vulkanium.render.terrain.region.RenderRegionManager;
import net.vulkanium.render.terrain.section.RenderSection;
import net.vulkanium.render.terrain.section.SectionVisibility;
import net.vulkanium.render.terrain.upload.ChunkUploadManager;
import net.vulkanium.render.pipeline.BasicPipeline;
import net.vulkanium.render.pipeline.DrawBatcher;
import net.vulkanium.render.shadow.ShadowRenderer;
import net.vulkanium.render.texture.VulkanTexture;
import net.vulkanium.resource.StagingRing;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

/**
 * Main terrain rendering orchestrator — the centerpiece of Vulkanium's rendering engine.
 *
 * <p>Coordinates all terrain-related systems:</p>
 * <ul>
 *   <li><b>Regions &amp; Sections:</b> RenderRegionManager tracks chunk sections grouped into
 *       render regions for multi-draw-indirect batching</li>
 *   <li><b>Mesh Building:</b> ChunkBuildWorkerPool asynchronously converts block states to
 *       vertex/index data on a work-stealing thread pool</li>
 *   <li><b>GPU Upload:</b> ChunkUploadManager transfers built mesh data to device-local
 *       GPU memory via the staging ring (future: transfer queue)</li>
 *   <li><b>Frustum Culling:</b> ChunkFrustumCuller tests section AABBs against camera frustum
 *       (CPU path; GPU compute path added in Phase 7)</li>
 *   <li><b>Occlusion Culling:</b> ChunkOcclusionCuller performs BFS graph traversal to skip
 *       sections hidden behind solid geometry</li>
 *   <li><b>Render Passes:</b> TerrainRenderPass records MDI draw calls per pass type
 *       (SOLID → CUTOUT → TRANSLUCENT)</li>
 * </ul>
 *
 * <h3>Per-Frame Flow</h3>
 * <pre>
 *   1. updateCamera()        — Update culling and scheduling with camera position
 *   2. submitBuilds()        — Send dirty sections to worker pool
 *   3. drainBuildResults()   — Collect finished builds and queue GPU upload
 *   4. processUploads()      — Upload mesh data to GPU via staging
 *   5. cullSections()        — Frustum + occlusion culling
 *   6. recordDrawCommands()  — Record MDI draw calls per pass
 * </pre>
 *
 * <h3>Performance Characteristics</h3>
 * <p>At 16-chunk render distance: ~4000 sections, ~40 regions. Per frame:</p>
 * <ul>
 *   <li>Frustum cull: ~0.2ms CPU (GPU: ~0.01ms in Phase 7)</li>
 *   <li>Draw calls: ~40 MDI calls (vs ~4000 per-section in VulkanMod)</li>
 *   <li>Build throughput: 16 sections/frame × 60fps = 960 sections/second</li>
 * </ul>
 */
public class ChunkRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ChunkRenderer");

    /** Layer bitmask constants for shadow rendering. */
    public static final int LAYER_SOLID          = 1;
    public static final int LAYER_CUTOUT         = 1 << 1;
    public static final int LAYER_CUTOUT_MIPPED  = 1 << 2;
    public static final int LAYER_TRANSLUCENT    = 1 << 3;

    // Sub-systems
    private final RenderRegionManager regionManager;
    private final ChunkBuildWorkerPool buildPool;
    private final ChunkBuildScheduler buildScheduler;
    private final ChunkUploadManager uploadManager;
    private final ChunkFrustumCuller frustumCuller;
    private final ChunkOcclusionCuller occlusionCuller;

    // Per-pass renderers
    private final TerrainRenderPass[] renderPasses;

    // Camera state
    private double cameraX, cameraY, cameraZ;
    private float lookDirX, lookDirY, lookDirZ;
    private final Matrix4f projectionViewMatrix = new Matrix4f();

    // Frame counter
    private long frameIndex = 0;

    // Statistics
    private int sectionsBuiltThisFrame;
    private int sectionsUploadedThisFrame;
    private int totalDrawCommands;
    private int totalRegionsDrawn;

    // Shadow terrain pipeline (set externally for shadow pass, reset after)
    private long shadowTerrainPipeline = 0;
    private long shadowTerrainPipelineLayout = 0;

    public ChunkRenderer(VulkaniumMemory memory, VulkaniumQueues queues, StagingRing stagingRing) {
        this.regionManager = new RenderRegionManager();
        this.buildPool = new ChunkBuildWorkerPool();
        this.buildScheduler = new ChunkBuildScheduler();
        this.uploadManager = new ChunkUploadManager(memory, queues, stagingRing);
        this.frustumCuller = new ChunkFrustumCuller();
        this.occlusionCuller = new ChunkOcclusionCuller();

        // Create per-pass renderers
        this.renderPasses = new TerrainRenderPass[TerrainPassType.count()];
        for (TerrainPassType pass : TerrainPassType.values()) {
            renderPasses[pass.ordinal()] = new TerrainRenderPass(pass);
        }

        LOGGER.info("Chunk renderer initialized");
    }

    // =============== Per-Frame Operations ===============

    /**
     * Step 1: Update camera position for culling and scheduling.
     */
    public void updateCamera(double x, double y, double z,
                             float lookX, float lookY, float lookZ,
                             Matrix4f projectionView) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        this.lookDirX = lookX;
        this.lookDirY = lookY;
        this.lookDirZ = lookZ;
        this.projectionViewMatrix.set(projectionView);

        regionManager.updateCameraPosition(x, y, z);
        buildScheduler.updateCamera(x, y, z, lookX, lookY, lookZ);
        frustumCuller.updateFrustum(projectionView);
    }

    /**
     * Step 2: Submit dirty sections for async mesh building.
     */
    public void submitBuilds() {
        List<net.vulkanium.render.terrain.section.RenderSection> buildQueue =
                regionManager.collectBuildQueue(64);

        List<net.vulkanium.render.terrain.section.RenderSection> prioritized =
                buildScheduler.prioritize(buildQueue, 16);

        sectionsBuiltThisFrame = buildPool.submitBatch(prioritized);
    }

    /**
     * Step 3: Collect finished build results and queue for GPU upload.
     */
    public void drainBuildResults() {
        buildPool.drainResults((section, data) -> {
            uploadManager.enqueueUpload(section, data);
        }, 32);
    }

    /**
     * Step 4: Process GPU uploads (staging → device-local transfer).
     */
    public void processUploads() {
        uploadManager.resetFrameStats();
        sectionsUploadedThisFrame = uploadManager.processUploads();
    }

    /**
     * Step 5: Perform frustum + occlusion culling.
     */
    public void cullSections() {
        // Frustum culling (CPU)
        frustumCuller.cullSections(regionManager, frameIndex);

        // Occlusion culling (graph BFS)
        int cameraSX = (int) Math.floor(cameraX) >> 4;
        int cameraSY = (int) Math.floor(cameraY) >> 4;
        int cameraSZ = (int) Math.floor(cameraZ) >> 4;
        occlusionCuller.performOcclusionCull(regionManager, cameraSX, cameraSY, cameraSZ);
    }

    /**
     * Step 6: Record draw commands for all terrain passes.
     *
     * @param cmd Active command buffer
     */
    public void recordDrawCommands(VkCommandBuffer cmd) {
        // First, record any pending buffer copies
        if (uploadManager.hasPendingCopies()) {
            uploadManager.recordCopyCommands(cmd);

            // Insert buffer memory barrier (transfer → vertex/index read)
            // This ensures uploads complete before draws start
            VulkaniumCommand.insertBufferMemoryBarrier(cmd);
        }

        totalDrawCommands = 0;
        totalRegionsDrawn = 0;

        // Record each pass in draw order: SOLID → CUTOUT → TRANSLUCENT
        for (TerrainPassType pass : TerrainPassType.drawOrder()) {
            TerrainRenderPass renderPass = renderPasses[pass.ordinal()];
            int cmds = renderPass.record(cmd, regionManager, frustumCuller.getFrustumPlanes());
            totalDrawCommands += cmds;
            totalRegionsDrawn += renderPass.getRegionsDrawn();
        }

        frameIndex++;
    }

    /**
     * Runs all per-frame terrain operations in sequence.
     * Convenience method that calls steps 2-5.
     */
    public void update() {
        submitBuilds();
        drainBuildResults();
        processUploads();
        cullSections();
    }

    // =============== World Events ===============

    /**
     * Called when a chunk section is loaded or enters render distance.
     */
    public void onSectionLoad(int sectionX, int sectionY, int sectionZ) {
        regionManager.addSection(sectionX, sectionY, sectionZ);
    }

    /**
     * Called when a chunk section is unloaded or leaves render distance.
     */
    public void onSectionUnload(int sectionX, int sectionY, int sectionZ) {
        long key = net.vulkanium.render.terrain.section.RenderSection.packSectionKey(
                sectionX, sectionY, sectionZ);
        buildPool.cancelTask(key);
        regionManager.removeSection(sectionX, sectionY, sectionZ);
    }

    /**
     * Called when a block changes within a section.
     */
    public void onBlockUpdate(int blockX, int blockY, int blockZ) {
        int sectionX = blockX >> 4;
        int sectionY = blockY >> 4;
        int sectionZ = blockZ >> 4;
        regionManager.markSectionDirty(sectionX, sectionY, sectionZ);

        // Also mark neighbors that might be affected (face-adjacent sections)
        regionManager.markSectionDirty(sectionX - 1, sectionY, sectionZ);
        regionManager.markSectionDirty(sectionX + 1, sectionY, sectionZ);
        regionManager.markSectionDirty(sectionX, sectionY - 1, sectionZ);
        regionManager.markSectionDirty(sectionX, sectionY + 1, sectionZ);
        regionManager.markSectionDirty(sectionX, sectionY, sectionZ - 1);
        regionManager.markSectionDirty(sectionX, sectionY, sectionZ + 1);
    }

    /**
     * Called on render distance change.
     */
    public void onRenderDistanceChange(int newDistance) {
        regionManager.setRenderDistance(newDistance);
        regionManager.cullDistantSections();
    }

    /**
     * Called on world change (dimension change, server disconnect, etc.).
     */
    public void onWorldChange() {
        buildPool.cancelAll();
        occlusionCuller.clear();
        regionManager.destroy();
        LOGGER.info("Chunk renderer reset for world change");
    }

    // =============== Lifecycle ===============

    /**
     * Sets the graphics pipeline for a specific pass type.
     * Called during shader pack loading / pipeline creation.
     */
    public void setPassPipeline(TerrainPassType pass, long pipeline, long layout) {
        renderPasses[pass.ordinal()].setPipeline(pipeline, layout);
    }

    /**
     * Returns the render pass for a specific pass type.
     */
    public TerrainRenderPass getRenderPass(TerrainPassType pass) {
        return renderPasses[pass.ordinal()];
    }

    /**
     * Sets an override shadow terrain pipeline for shadow pass rendering.
     * When set, {@link #recordShadowLayers} binds this pipeline instead of
     * the per-pass terrain pipelines. Pass (0, 0) to clear.
     */
    public void setShadowTerrainPipeline(long pipeline, long layout) {
        this.shadowTerrainPipeline = pipeline;
        this.shadowTerrainPipelineLayout = layout;
    }

    /**
     * Records shadow-pass draw commands for the specified terrain layers.
     *
     * @param commandBuffer Active command buffer handle
     * @param frustum       The shadow frustum for culling
     * @param layerMask     Bitmask of LAYER_* constants to render
     */
    public void renderShadow(long commandBuffer, ShadowRenderer.ShadowFrustum frustum, int layerMask) {
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        if (frustum == null) {
            recordShadowLayers(cmd, layerMask);
            return;
        }

        List<SectionVisibility> touchedVisibility = new ArrayList<>();
        List<Boolean> previousFrustumVisible = new ArrayList<>();
        List<Boolean> previousOccluded = new ArrayList<>();

        try {
            for (RenderRegion region : regionManager.getActiveRegions()) {
                for (int i = 0; i < RenderRegion.SECTION_COUNT; i++) {
                    RenderSection section = region.getSection(i);
                    if (section == null) continue;

                    SectionVisibility visibility = section.getVisibility();
                    touchedVisibility.add(visibility);
                    previousFrustumVisible.add(visibility.isFrustumVisible());
                    previousOccluded.add(visibility.isOccluded());

                    boolean readyAndNonEmpty =
                            section.getBuildState() == RenderSection.SectionBuildState.READY
                                    && !section.isEmpty();
                    boolean visible = readyAndNonEmpty && frustum.testVisibility(
                            section.getBlockX(),
                            section.getBlockY(),
                            section.getBlockZ(),
                            section.getBlockX() + 16.0,
                            section.getBlockY() + 16.0,
                            section.getBlockZ() + 16.0
                    );

                    visibility.setFrustumVisible(visible);
                    visibility.setOccluded(false);
                }
            }

            recordShadowLayers(cmd, layerMask);
        } finally {
            for (int i = 0; i < touchedVisibility.size(); i++) {
                SectionVisibility visibility = touchedVisibility.get(i);
                visibility.setFrustumVisible(previousFrustumVisible.get(i));
                visibility.setOccluded(previousOccluded.get(i));
            }
        }
    }

    private void recordShadowLayers(VkCommandBuffer cmd, int layerMask) {
        // If a shadow-specific pipeline is set, bind it once for all layers
        if (shadowTerrainPipeline != 0) {
            org.lwjgl.vulkan.VK10.vkCmdBindPipeline(cmd,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                    shadowTerrainPipeline);

            // Bind descriptor set with shadow uniforms — REQUIRED before any draw calls.
            // The shadow pipeline layout declares UBO + 64 sampler bindings; without a
            // valid descriptor set bound, the GPU reads garbage pointers → SIGSEGV.
            bindShadowDescriptors(cmd);
        }

        if ((layerMask & LAYER_SOLID) != 0) {
            if (shadowTerrainPipeline != 0) {
                // Draw regions directly without rebinding per-pass pipeline
                recordShadowRegions(cmd, TerrainPassType.SOLID);
            } else {
                renderPasses[TerrainPassType.SOLID.ordinal()].record(cmd, regionManager, null);
            }
        }
        if ((layerMask & LAYER_CUTOUT) != 0) {
            if (shadowTerrainPipeline != 0) {
                recordShadowRegions(cmd, TerrainPassType.CUTOUT);
            } else {
                renderPasses[TerrainPassType.CUTOUT.ordinal()].record(cmd, regionManager, null);
            }
        }
        if ((layerMask & LAYER_CUTOUT_MIPPED) != 0) {
            if (shadowTerrainPipeline != 0) {
                recordShadowRegions(cmd, TerrainPassType.CUTOUT_MIPPED);
            } else {
                renderPasses[TerrainPassType.CUTOUT_MIPPED.ordinal()].record(cmd, regionManager, null);
            }
        }
        if ((layerMask & LAYER_TRANSLUCENT) != 0) {
            if (shadowTerrainPipeline != 0) {
                recordShadowRegions(cmd, TerrainPassType.TRANSLUCENT);
                recordShadowRegions(cmd, TerrainPassType.TRIPWIRE);
            } else {
                renderPasses[TerrainPassType.TRANSLUCENT.ordinal()].record(cmd, regionManager, null);
                renderPasses[TerrainPassType.TRIPWIRE.ordinal()].record(cmd, regionManager, null);
            }
        }
    }

    /**
     * Binds a descriptor set with shadow-specific uniform data for shadow terrain draws.
     *
     * <p>The shadow pipeline (BasicPipeline) declares a descriptor set layout with:
     * <ul>
     *   <li>Binding 0: UBO_DYNAMIC — MVP matrices, fog, color modulator, chunk offset</li>
     *   <li>Bindings 1-64: COMBINED_IMAGE_SAMPLER — texture samplers</li>
     * </ul>
     * Without a valid descriptor set bound, any draw call triggers undefined behavior
     * (typically SIGSEGV on AMD RADV as the GPU dereferences null/garbage descriptor pointers).
     *
     * <p>Shadow matrices are read from {@link ShadowRenderer#MODELVIEW} and
     * {@link ShadowRenderer#PROJECTION}, which are populated in
     * {@code ShadowRenderer.renderShadows()} step 2 before terrain draws begin.
     *
     * <p>Texture bindings use 1×1 white placeholder textures. For cutout alpha testing,
     * the block atlas should be bound at binding 1 in a future refinement.
     */
    private void bindShadowDescriptors(VkCommandBuffer cmd) {
        if (shadowTerrainPipelineLayout == 0) return;

        DrawBatcher drawBatcher = Vulkanium.getDrawBatcher();
        if (drawBatcher == null) return;

        int frameIndex = Vulkanium.getFrameOrchestrator().getCurrentFrame();

        // Shadow matrices (already computed by ShadowRenderer.renderShadows() step 2)
        Matrix4f shadowMV = ShadowRenderer.MODELVIEW;
        Matrix4f shadowProj = ShadowRenderer.PROJECTION;

        float[] modelView = new float[16];
        float[] modelViewInv = new float[16];
        float[] projection = new float[16];
        float[] projectionInv = new float[16];

        shadowMV.get(modelView);
        new Matrix4f(shadowMV).invert().get(modelViewInv);
        shadowProj.get(projection);
        new Matrix4f(shadowProj).invert().get(projectionInv);

        float[] colorMod = {1.0f, 1.0f, 1.0f, 1.0f};
        float[] fogParams = {1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 10000.0f}; // no fog in shadow pass
        float[] chunkOffset = {0.0f, 0.0f, 0.0f};

        int uboOffset = drawBatcher.uploadUniformsShaderpack(frameIndex,
                modelView, modelViewInv, projection, projectionInv,
                colorMod, fogParams, null, chunkOffset);

        // Fill all sampler bindings with placeholder textures to satisfy the layout.
        int maxTex = BasicPipeline.getMaxTextureBindings();
        long placeholderView = Vulkanium.getPlaceholderImageView();
        long placeholderSampler = Vulkanium.getPlaceholderSampler();
        long[] views = new long[maxTex];
        long[] samplers = new long[maxTex];
        Arrays.fill(views, placeholderView);
        Arrays.fill(samplers, placeholderSampler);

        // Bind MC block atlas at gtexture (binding 0) for cutout alpha testing.
        // Shadow shaders sample gtexture to discard transparent pixels (leaves, grass).
        int atlasId = VRenderSystem.getBoundTextureId(0);
        if (atlasId > 0) {
            VulkanTexture atlasTex = GlStateInterceptor.getVulkanTexture(atlasId);
            if (atlasTex != null && atlasTex.isAllocated()
                    && atlasTex.getImageView() != VK_NULL_HANDLE
                    && atlasTex.getSampler() != VK_NULL_HANDLE) {
                views[0] = atlasTex.getImageView();
                samplers[0] = atlasTex.getSampler();
            }
        }

        // Bind MC lightmap at lightmap binding (1) — used by some shadow shaders.
        int lmId = VRenderSystem.getBoundTextureId(2); // MC lightmap GL unit
        if (lmId > 0) {
            VulkanTexture lmTex = GlStateInterceptor.getVulkanTexture(lmId);
            if (lmTex != null && lmTex.isAllocated()
                    && lmTex.getImageView() != VK_NULL_HANDLE
                    && lmTex.getSampler() != VK_NULL_HANDLE) {
                views[1] = lmTex.getImageView();
                samplers[1] = lmTex.getSampler();
            }
        }

        int setIdx = drawBatcher.updateDescriptorSet(frameIndex, views, samplers);
        drawBatcher.bindDescriptorSet(cmd, shadowTerrainPipelineLayout, setIdx, uboOffset);
    }

    /**
     * Records draw commands for shadow rendering, issuing per-section draws
     * with proper chunk offset. The MDI approach cannot be used here because
     * vertex positions in VBOs are section-relative (0-16 range) and each
     * section needs its own chunk offset to position it correctly in shadow space.
     *
     * <p>Without per-section chunk offset, all terrain sections would be rendered
     * at the origin → overlapping → broken shadow map → everything in shadow.</p>
     */
    private void recordShadowRegions(VkCommandBuffer cmd, TerrainPassType passType) {
        DrawBatcher drawBatcher = Vulkanium.getDrawBatcher();
        if (drawBatcher == null || shadowTerrainPipelineLayout == 0) return;

        int frameIndex = Vulkanium.getFrameOrchestrator().getCurrentFrame();

        // Shadow matrices (already computed by ShadowRenderer.renderShadows() step 2)
        Matrix4f shadowMV = ShadowRenderer.MODELVIEW;
        Matrix4f shadowProj = ShadowRenderer.PROJECTION;
        float[] modelView = new float[16];
        float[] modelViewInv = new float[16];
        float[] projection = new float[16];
        float[] projectionInv = new float[16];
        shadowMV.get(modelView);
        new Matrix4f(shadowMV).invert().get(modelViewInv);
        shadowProj.get(projection);
        new Matrix4f(shadowProj).invert().get(projectionInv);

        float[] colorMod = {1.0f, 1.0f, 1.0f, 1.0f};
        float[] fogParams = {1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 10000.0f};

        // Get camera position for computing camera-relative chunk offsets
        double camX = 0, camY = 0, camZ = 0;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
                net.minecraft.world.phys.Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
                camX = cam.x;
                camY = cam.y;
                camZ = cam.z;
            }
        } catch (Exception ignored) {}

        // Prepare sampler arrays (shared across all section draws)
        int maxTex = BasicPipeline.getMaxTextureBindings();
        long placeholderView = Vulkanium.getPlaceholderImageView();
        long placeholderSampler = Vulkanium.getPlaceholderSampler();
        long[] views = new long[maxTex];
        long[] samplers = new long[maxTex];
        Arrays.fill(views, placeholderView);
        Arrays.fill(samplers, placeholderSampler);

        // Bind block atlas at gtexture for cutout alpha testing
        int atlasId = VRenderSystem.getBoundTextureId(0);
        if (atlasId > 0) {
            VulkanTexture atlasTex = GlStateInterceptor.getVulkanTexture(atlasId);
            if (atlasTex != null && atlasTex.isAllocated()
                    && atlasTex.getImageView() != VK_NULL_HANDLE
                    && atlasTex.getSampler() != VK_NULL_HANDLE) {
                views[0] = atlasTex.getImageView();
                samplers[0] = atlasTex.getSampler();
            }
        }
        // Lightmap at binding 1
        int lmId = VRenderSystem.getBoundTextureId(2);
        if (lmId > 0) {
            VulkanTexture lmTex = GlStateInterceptor.getVulkanTexture(lmId);
            if (lmTex != null && lmTex.isAllocated()
                    && lmTex.getImageView() != VK_NULL_HANDLE
                    && lmTex.getSampler() != VK_NULL_HANDLE) {
                views[1] = lmTex.getImageView();
                samplers[1] = lmTex.getSampler();
            }
        }

        // Per-section draws with proper chunk offset
        for (net.vulkanium.render.terrain.region.RenderRegion region : regionManager.getActiveRegions()) {
            if (region.isEmpty()) continue;

            net.vulkanium.render.terrain.region.RegionGPUBuffers buffers = region.getPassBuffers(passType);
            if (buffers == null || !buffers.hasGeometry()) continue;

            // Bind vertex/index buffers once per region
            long[] vertexBuffers = {buffers.getVertexBuffer()};
            long[] offsets = {0L};
            org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers(cmd, 0, vertexBuffers, offsets);
            org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer(cmd, buffers.getIndexBuffer(), 0,
                    org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32);

            for (int i = 0; i < net.vulkanium.render.terrain.region.RenderRegion.SECTION_COUNT; i++) {
                net.vulkanium.render.terrain.section.RenderSection section = region.getSection(i);
                if (section == null) continue;
                if (!section.getVisibility().isFrustumVisible()) continue;
                if (section.getBuildState() != net.vulkanium.render.terrain.section.RenderSection.SectionBuildState.READY) continue;

                net.vulkanium.render.terrain.section.RenderSection.PassGPUSlot gpu = section.getGPUSlot(passType);
                if (gpu == null || !gpu.hasGeometry()) continue;

                // Compute camera-relative chunk offset for this section
                // Vertex positions in VBO are section-local [0, 16), so we need
                // to translate by (sectionBlockPos - cameraPos)
                float[] chunkOffset = {
                        (float) (section.getBlockX() - camX),
                        (float) (section.getBlockY() - camY),
                        (float) (section.getBlockZ() - camZ)
                };

                // Upload UBO with per-section chunk offset
                int uboOffset = drawBatcher.uploadUniformsShaderpack(frameIndex,
                        modelView, modelViewInv, projection, projectionInv,
                        colorMod, fogParams, null, chunkOffset);

                int setIdx = drawBatcher.updateDescriptorSet(frameIndex, views, samplers);
                drawBatcher.bindDescriptorSet(cmd, shadowTerrainPipelineLayout, setIdx, uboOffset);

                // Issue per-section indexed draw
                int firstIndex = (int) (gpu.indexOffset() / 4);
                int vertexOffset = (int) (gpu.vertexOffset() / net.vulkanium.render.terrain.ChunkVertexFormat.STRIDE);
                org.lwjgl.vulkan.VK10.vkCmdDrawIndexed(cmd,
                        gpu.indexCount(),   // indexCount
                        1,                  // instanceCount
                        firstIndex,         // firstIndex
                        vertexOffset,       // vertexOffset
                        0);                 // firstInstance
            }
        }
    }

    /**
     * Shuts down the chunk renderer and frees all resources.
     */
    public void destroy() {
        buildPool.shutdown();
        regionManager.destroy();
        LOGGER.info("Chunk renderer destroyed");
    }

    // =============== Statistics ===============

    public int getSectionsBuiltThisFrame() { return sectionsBuiltThisFrame; }
    public int getSectionsUploadedThisFrame() { return sectionsUploadedThisFrame; }
    public int getTotalDrawCommands() { return totalDrawCommands; }
    public int getTotalRegionsDrawn() { return totalRegionsDrawn; }
    public int getTotalSections() { return regionManager.getSectionCount(); }
    public int getTotalRegions() { return regionManager.getRegionCount(); }
    public int getVisibleSections() { return frustumCuller.getTotalVisible(); }
    public int getCulledSections() { return frustumCuller.getTotalCulled(); }
    public int getOccludedSections() { return occlusionCuller.getSectionsOccluded(); }
    public int getPendingBuilds() { return buildPool.getPendingTaskCount(); }
    public int getPendingUploads() { return uploadManager.getPendingUploadCount(); }
    public RenderRegionManager getRegionManager() { return regionManager; }
}
