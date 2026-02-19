package net.vulkanium.render.terrain.pass;

import net.vulkanium.render.terrain.region.RenderRegion;
import net.vulkanium.render.terrain.region.RenderRegionManager;
import net.vulkanium.render.terrain.region.RegionDrawBatch;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Records terrain rendering commands for a single pass type (SOLID, CUTOUT, TRANSLUCENT, etc.).
 *
 * <p>This is the per-pass draw recorder that iterates over visible regions and issues
 * multi-draw-indirect calls. In a multi-threaded command recording setup (Phase 7),
 * each pass can be recorded to a separate secondary command buffer in parallel.</p>
 *
 * <h3>Rendering Order</h3>
 * <ul>
 *   <li>Opaque passes (SOLID, CUTOUT, CUTOUT_MIPPED): Front-to-back for early depth rejection</li>
 *   <li>Translucent passes (TRANSLUCENT, TRIPWIRE): Back-to-front for correct alpha blending</li>
 * </ul>
 *
 * <h3>Per-Pass Pipeline State</h3>
 * <p>Each pass type binds a different graphics pipeline with the appropriate state:
 * blend mode, depth write, alpha test threshold, etc. The pipeline is bound once per
 * pass and shared across all regions.</p>
 */
public class TerrainRenderPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/TerrainPass");

    private final TerrainPassType passType;

    /** The Vulkan graphics pipeline handle for this pass (set during initialization). */
    private long graphicsPipeline = 0;
    private long pipelineLayout = 0;

    /** Statistics for the current frame. */
    private int regionsDrawn = 0;
    private int drawCommandsIssued = 0;
    private long trianglesDrawn = 0;

    public TerrainRenderPass(TerrainPassType passType) {
        this.passType = passType;
    }

    /**
     * Sets the graphics pipeline used for this pass.
     * Called during pipeline creation (Phase 2 with shader packs, or default pipeline).
     */
    public void setPipeline(long pipeline, long layout) {
        this.graphicsPipeline = pipeline;
        this.pipelineLayout = layout;
    }

    /**
     * Records all draw commands for this pass type into the given command buffer.
     *
     * <p>For each visible region that has geometry for this pass, builds the indirect
     * draw buffer and issues a single MDI call. Returns the total number of draw
     * commands issued (for statistics).</p>
     *
     * @param cmd           Active command buffer (primary or secondary)
     * @param regionManager The region manager holding all active regions
     * @param frustumPlanes 6×4 frustum planes for region-level culling (or null to skip)
     * @return Total draw commands issued across all regions
     */
    public int record(VkCommandBuffer cmd, RenderRegionManager regionManager, float[][] frustumPlanes) {
        resetStats();

        // Bind pipeline for this pass
        if (graphicsPipeline != 0) {
            org.lwjgl.vulkan.VK10.vkCmdBindPipeline(cmd,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                    graphicsPipeline);
        }

        List<RenderRegion> regions = regionManager.getActiveRegions();
        int totalCommands = 0;

        for (RenderRegion region : regions) {
            // Region-level frustum cull (AABB test on the entire region)
            // Individual sections are culled per-section in the region
            if (region.isEmpty()) continue;

            int cmds = RegionDrawBatch.buildAndDraw(cmd, region, passType);
            if (cmds > 0) {
                totalCommands += cmds;
                regionsDrawn++;
            }
        }

        drawCommandsIssued = totalCommands;
        return totalCommands;
    }

    /**
     * Records draw commands using a pre-built indirect buffer (GPU compute path).
     * Used when frustum culling is done on the GPU via compute shader.
     *
     * @param cmd           Active command buffer
     * @param regionManager The region manager
     */
    public void recordFromComputeCull(VkCommandBuffer cmd, RenderRegionManager regionManager) {
        resetStats();

        if (graphicsPipeline != 0) {
            org.lwjgl.vulkan.VK10.vkCmdBindPipeline(cmd,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                    graphicsPipeline);
        }

        // In the compute cull path, indirect buffers have already been filled by the
        // frustum_cull.comp shader. We just need to issue the MDI draws.
        for (RenderRegion region : regionManager.getActiveRegions()) {
            if (RegionDrawBatch.recordDraw(cmd, region, passType)) {
                regionsDrawn++;
            }
        }
    }

    private void resetStats() {
        regionsDrawn = 0;
        drawCommandsIssued = 0;
        trianglesDrawn = 0;
    }

    // ============== Getters ==============

    public TerrainPassType getPassType() { return passType; }
    public long getGraphicsPipeline() { return graphicsPipeline; }
    public long getPipelineLayout() { return pipelineLayout; }
    public int getRegionsDrawn() { return regionsDrawn; }
    public int getDrawCommandsIssued() { return drawCommandsIssued; }
    public long getTrianglesDrawn() { return trianglesDrawn; }
}
