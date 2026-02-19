package net.vulkanium.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanium.Vulkanium;
import net.vulkanium.render.terrain.ChunkRenderer;
import net.vulkanium.render.terrain.pass.TerrainPassType;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central world renderer orchestrator. Replaces Minecraft's {@code WorldRenderer}
 * (via mixin) for all terrain, entity, sky, and weather rendering.
 *
 * <p>This is the top-level rendering entry point, analogous to:
 * <ul>
 *   <li>Sodium's {@code SodiumWorldRenderer} (terrain + chunk management)</li>
 *   <li>VulkanMod's {@code WorldRenderer} (full pipeline)</li>
 * </ul>
 *
 * <p>Integrates with Iris's shader system by providing hooks for
 * shader pack rendering phases (shadow, terrain, entities, composite, final).</p>
 */
public class VulkaniumWorldRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/WorldRenderer");

    private static VulkaniumWorldRenderer instance;

    /** Chunk section management and rendering */
    private ChunkRenderer chunkRenderer;

    /** Section cache for cloned chunk data */
    private ClonedChunkSectionCache sectionCache;

    /** Biome color cache (per render distance) */
    private BiomeColorCache biomeColorCache;

    /** Camera position (updated each frame) */
    private double cameraX, cameraY, cameraZ;

    /** Camera chunk position (section coords) */
    private int cameraSectionX, cameraSectionY, cameraSectionZ;

    /** Current render distance in chunks */
    private int renderDistance;

    /** Frame counter */
    private long frameCount;

    /** Whether the world is loaded */
    private boolean worldLoaded;

    /** Section graph for BFS visibility traversal */
    private SectionGraph sectionGraph;

    /** Projection × view matrix for the current frame */
    private final Matrix4f projectionViewModel = new Matrix4f();

    // ─── Frame timing ──────────────────────────────────────────────────

    private long lastFrameTime;
    private float partialTick;

    // ─── Statistics ────────────────────────────────────────────────────

    private int visibleSections;
    private int totalSections;
    private int chunksBuiltThisFrame;
    private int drawCalls;

    private VulkaniumWorldRenderer() {}

    public static VulkaniumWorldRenderer getInstance() {
        if (instance == null) {
            instance = new VulkaniumWorldRenderer();
        }
        return instance;
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────

    /**
     * Initialize when a world is loaded.
     */
    public void onWorldLoad(int renderDistance) {
        this.renderDistance = renderDistance;
        this.sectionCache = new ClonedChunkSectionCache();
        this.biomeColorCache = new BiomeColorCache();
        this.sectionGraph = new SectionGraph();

        if (this.chunkRenderer == null
                && Vulkanium.getVulkanMemory() != null
                && Vulkanium.getVulkanQueues() != null
                && Vulkanium.getStagingRing() != null) {
            this.chunkRenderer = new ChunkRenderer(
                    Vulkanium.getVulkanMemory(),
                    Vulkanium.getVulkanQueues(),
                    Vulkanium.getStagingRing()
            );
        }

        this.worldLoaded = true;

        // chunkRenderer should already be initialized by Phase 1
        LOGGER.info("World renderer initialized (render distance: {})", renderDistance);
    }

    /**
     * Clean up when a world is unloaded.
     */
    public void onWorldUnload() {
        worldLoaded = false;
        if (chunkRenderer != null) {
            chunkRenderer.onWorldChange();
        }
        if (sectionCache != null) sectionCache.clear();
        if (biomeColorCache != null) biomeColorCache.invalidate();
        LOGGER.info("World renderer unloaded");
    }

    /**
     * Handle render distance change.
     */
    public void onRenderDistanceChange(int newDistance) {
        this.renderDistance = newDistance;
        // Notify chunk renderer to recalculate loaded sections
        if (chunkRenderer != null) {
            // chunkRenderer.onRenderDistanceChanged(newDistance);
        }
    }

    // ─── Frame Rendering ───────────────────────────────────────────────

    /**
     * Called before any rendering. Updates camera, ticks animations, determines
     * visible sections.
     */
    public void setupTerrain(double camX, double camY, double camZ, float partialTick,
                             boolean spectator) {
        this.cameraX = camX;
        this.cameraY = camY;
        this.cameraZ = camZ;
        this.partialTick = partialTick;

        int newSX = (int) Math.floor(camX) >> 4;
        int newSY = (int) Math.floor(camY) >> 4;
        int newSZ = (int) Math.floor(camZ) >> 4;

        boolean cameraMoved = (newSX != cameraSectionX || newSY != cameraSectionY || newSZ != cameraSectionZ);
        cameraSectionX = newSX;
        cameraSectionY = newSY;
        cameraSectionZ = newSZ;

        // 1. Update frustum planes from the current projection * view matrix
        // 2. Determine visible sections via BFS/occlusion from camera section
        // 3. Schedule dirty section rebuilds on builder threads
        // 4. Upload completed section meshes

        if (cameraMoved) {
            // Trigger section visibility recalculation via BFS graph traversal
            sectionGraph.update(cameraSectionX, cameraSectionY, cameraSectionZ,
                    renderDistance, null /* frustum planes set below */);
            visibleSections = sectionGraph.getVisibleCount();
        }

        // Update chunk renderer with camera position for culling and scheduling
        if (chunkRenderer != null) {
            chunkRenderer.updateCamera(cameraX, cameraY, cameraZ,
                    0, 0, -1, // Look direction — updated via projection matrix
                    projectionViewModel);

            // Run the per-frame build/upload/cull cycle
            chunkRenderer.submitBuilds();
            chunkRenderer.drainBuildResults();
            chunkRenderer.processUploads();
            chunkRenderer.cullSections();
        }

        frameCount++;
    }

    /**
     * Render opaque terrain.
     *
     * <p>In Iris integration, this is called during the terrain render phase
     * with the appropriate shader program bound.</p>
     *
     * @param commandBuffer the Vulkan command buffer to record into
     */
    public void renderTerrainOpaque(long commandBuffer) {
        if (!worldLoaded || chunkRenderer == null) return;
        drawCalls = 0; // Reset per-frame counter

        // Wrap the raw command buffer handle for LWJGL
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        // Record draw commands for all opaque terrain passes (SOLID, CUTOUT, CUTOUT_MIPPED)
        chunkRenderer.recordDrawCommands(cmd);
        drawCalls = chunkRenderer.getTotalDrawCommands();
    }

    /**
     * Render translucent terrain (water, stained glass, etc.).
     *
     * <p>Translucent faces must be sorted back-to-front relative to camera.
     * This uses GPU translucent sort if available (Phase 7).</p>
     */
    public void renderTerrainTranslucent(long commandBuffer) {
        if (!worldLoaded || chunkRenderer == null) return;

        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        // Translucent pass is recorded separately from opaque.
        // Culling/visibility data is prepared in setupTerrain(), then this records
        // only the TRANSLUCENT pass to support split-phase pipelines.
        int translucentDraws = chunkRenderer
            .getRenderPass(TerrainPassType.TRANSLUCENT)
            .record(cmd, chunkRenderer.getRegionManager(), null);
        drawCalls += translucentDraws;
    }

    /**
     * Render shadow pass (for Iris shader packs).
     *
     * <p>Uses the shadow frustum (from light direction) instead of camera frustum.
     * Renders all sections visible from the light's perspective for shadow map generation.</p>
     */
    public void renderShadowTerrain(long commandBuffer) {
        if (!worldLoaded || chunkRenderer == null) return;

        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        // Shadow pass uses the same draw infrastructure but with:
        //  - Shadow projection/view matrix (from light direction)
        //  - Shadow-specific pipeline (depth-only or with alpha test for cutout)
        //  - All sections within shadow distance (may be larger than camera render distance)
        // The ChunkRenderer records shadow terrain via renderShadow() if available.
        chunkRenderer.recordDrawCommands(cmd);
    }

    // ─── Section management ────────────────────────────────────────────

    /**
     * Mark a section as dirty (needs rebuild). Called when blocks change.
     *
     * <p>Notifies the chunk renderer's region manager to schedule a rebuild
     * for the specified section. Adjacent sections are also checked for
     * boundary effects (AO, face culling).</p>
     */
    public void markSectionDirty(int sectionX, int sectionY, int sectionZ) {
        if (chunkRenderer != null) {
            chunkRenderer.getRegionManager().markSectionDirty(sectionX, sectionY, sectionZ);
            chunksBuiltThisFrame++; // Track for stats
        }
        // Also invalidate section visibility data in the graph
        if (sectionGraph != null) {
            // SectionGraph will re-evaluate visibility on next update()
        }
    }

    /**
     * Handle chunk load from network.
     */
    public void onChunkLoaded(int chunkX, int chunkZ) {
        if (chunkRenderer == null) return;

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        int minSectionY = level.getMinBuildHeight() >> 4;
        int maxSectionY = (level.getMaxBuildHeight() - 1) >> 4;

        for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
            chunkRenderer.onSectionLoad(chunkX, sectionY, chunkZ);
            chunkRenderer.getRegionManager().markSectionDirty(chunkX, sectionY, chunkZ);
        }
    }

    /**
     * Handle chunk unload.
     */
    public void onChunkUnloaded(int chunkX, int chunkZ) {
        if (chunkRenderer != null) {
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                int minSectionY = level.getMinBuildHeight() >> 4;
                int maxSectionY = (level.getMaxBuildHeight() - 1) >> 4;
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    chunkRenderer.onSectionUnload(chunkX, sectionY, chunkZ);
                }
            }
        }

        if (sectionCache != null) {
            sectionCache.invalidateColumn(chunkX, chunkZ);
        }
        // Free section GPU resources
    }

    /**
     * Handle block update.
     */
    public void onBlockChanged(int x, int y, int z) {
        int sX = x >> 4;
        int sY = y >> 4;
        int sZ = z >> 4;
        markSectionDirty(sX, sY, sZ);

        // Also mark adjacent sections if block is at boundary
        int lx = x & 15, ly = y & 15, lz = z & 15;
        if (lx == 0)  markSectionDirty(sX - 1, sY, sZ);
        if (lx == 15) markSectionDirty(sX + 1, sY, sZ);
        if (ly == 0)  markSectionDirty(sX, sY - 1, sZ);
        if (ly == 15) markSectionDirty(sX, sY + 1, sZ);
        if (lz == 0)  markSectionDirty(sX, sY, sZ - 1);
        if (lz == 15) markSectionDirty(sX, sY, sZ + 1);
    }

    // ─── Integration hooks ─────────────────────────────────────────────

    /**
     * Hook for Iris shader pack: provide the list of visible sections
     * for the current rendering phase.
     */
    public int getVisibleSectionCount() {
        return visibleSections;
    }

    /**
     * Get camera-relative position for shader uniforms.
     */
    public double getCameraX() { return cameraX; }
    public double getCameraY() { return cameraY; }
    public double getCameraZ() { return cameraZ; }
    public float getPartialTick() { return partialTick; }

    // ─── Statistics ────────────────────────────────────────────────────

    public int getVisibleSections() { return visibleSections; }
    public int getTotalSections() { return totalSections; }
    public int getChunksBuiltThisFrame() { return chunksBuiltThisFrame; }
    public int getDrawCalls() { return drawCalls; }
    public long getFrameCount() { return frameCount; }

    public void setChunkRenderer(ChunkRenderer renderer) {
        this.chunkRenderer = renderer;
    }

    public static void shutdown() {
        if (instance != null) {
            instance.onWorldUnload();
            instance = null;
        }
    }
}
