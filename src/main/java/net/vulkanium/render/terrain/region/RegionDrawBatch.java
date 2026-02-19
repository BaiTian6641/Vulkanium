package net.vulkanium.render.terrain.region;

import net.vulkanium.render.terrain.ChunkVertexFormat;
import net.vulkanium.render.terrain.pass.TerrainPassType;
import net.vulkanium.render.terrain.section.RenderSection;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Builds and issues multi-draw-indirect commands for a single region and pass type.
 *
 * <p>This is the core of Vulkanium's terrain rendering performance — instead of issuing
 * one draw call per chunk section (thousands per frame), we issue one MDI call per
 * visible region (~20-50 per frame), each of which internally draws all visible sections
 * in that region via the indirect draw buffer.</p>
 *
 * <h3>Draw Flow</h3>
 * <pre>
 * 1. Collect visible sections in this region for the given pass
 * 2. Write VkDrawIndexedIndirectCommand per section into indirect buffer
 * 3. Issue single vkCmdDrawIndexedIndirect() with drawCount = visible sections
 * </pre>
 *
 * <h3>Performance Impact</h3>
 * <p>For 16-chunk render distance: ~4000 sections visible → ~40 regions.
 * So 4000 draw calls → 40 MDI calls = <b>~100× reduction in draw calls</b>.</p>
 *
 * <p>When GPU compute frustum culling is active (Phase 7+), the indirect buffer is
 * written by a compute shader instead, achieving true zero-CPU-cost culling.</p>
 */
public class RegionDrawBatch {

    /**
     * Updates the indirect draw buffer for a region's pass, populating it with draw
     * commands for all visible sections.
     *
     * @param region  The region to build a batch for
     * @param pass    The terrain pass type
     * @return Number of draw commands written (0 if nothing to draw)
     */
    public static int buildIndirectCommands(RenderRegion region, TerrainPassType pass) {
        RegionGPUBuffers buffers = region.getPassBuffers(pass);
        if (buffers == null) {
            return 0;
        }

        List<RenderSection> visible = region.collectVisibleSections(pass);
        if (visible.isEmpty()) {
            buffers.setDrawCommandCount(0);
            return 0;
        }

        buffers.ensureIndirectCapacity(visible.size());

        int slot = 0;
        for (RenderSection section : visible) {
            RenderSection.PassGPUSlot gpu = section.getGPUSlot(pass);
            if (gpu == null || !gpu.hasGeometry()) continue;

            // VkDrawIndexedIndirectCommand:
            //   indexCount, instanceCount, firstIndex, vertexOffset, firstInstance
            int firstIndex = (int) (gpu.indexOffset() / 4); // byte offset → index element
            int vertexOffset = (int) (gpu.vertexOffset() / ChunkVertexFormat.STRIDE);

            buffers.writeIndirectCommand(slot,
                    gpu.indexCount(),    // indexCount
                    1,                   // instanceCount (always 1)
                    firstIndex,          // firstIndex (in index elements)
                    vertexOffset,        // vertexOffset (base vertex)
                    0                    // firstInstance
            );
            slot++;
        }

        buffers.setDrawCommandCount(slot);
        return slot;
    }

    /**
     * Records the MDI draw call for a region's pass into the given command buffer.
     *
     * <p>This binds the region's vertex/index buffers and issues a single
     * {@code vkCmdDrawIndexedIndirect()} call that renders all visible sections
     * in the region for the specified pass.</p>
     *
     * @param cmd     Active command buffer
     * @param region  The region to draw
     * @param pass    The terrain pass type
     * @return true if a draw was issued, false if the region had no geometry for this pass
     */
    public static boolean recordDraw(VkCommandBuffer cmd, RenderRegion region, TerrainPassType pass) {
        RegionGPUBuffers buffers = region.getPassBuffers(pass);
        if (buffers == null || !buffers.hasGeometry()) {
            return false;
        }

        // Bind vertex buffer at binding 0
        long[] vertexBuffers = {buffers.getVertexBuffer()};
        long[] offsets = {0L};
        vkCmdBindVertexBuffers(cmd, 0, vertexBuffers, offsets);

        // Bind index buffer (uint32 indices)
        vkCmdBindIndexBuffer(cmd, buffers.getIndexBuffer(), 0, VK_INDEX_TYPE_UINT32);

        // Issue multi-draw-indirect
        vkCmdDrawIndexedIndirect(cmd,
                buffers.getIndirectBuffer(),
                0,                                        // offset in indirect buffer
                buffers.getDrawCommandCount(),            // drawCount
                RegionGPUBuffers.INDIRECT_COMMAND_SIZE    // stride between commands
        );

        return true;
    }

    /**
     * Convenience method: build indirect commands + record draw in one call.
     *
     * @return Number of draw commands issued
     */
    public static int buildAndDraw(VkCommandBuffer cmd, RenderRegion region, TerrainPassType pass) {
        int cmdCount = buildIndirectCommands(region, pass);
        if (cmdCount > 0) {
            recordDraw(cmd, region, pass);
        }
        return cmdCount;
    }
}
