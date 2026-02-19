package net.vulkanium.render.terrain.region;

import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.render.terrain.ChunkVertexFormat;
import net.vulkanium.render.terrain.pass.TerrainPassType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU buffer storage for one render pass within a region.
 *
 * <p>Contains a vertex buffer, index buffer, and indirect draw buffer — all device-local.
 * Section mesh data is sub-allocated within these buffers. When the region is rebuilt or
 * sections are added/removed, the buffers are compacted and re-uploaded.</p>
 *
 * <h3>Buffer Layout</h3>
 * <pre>
 * Vertex Buffer:  [Section0_verts | Section1_verts | ... | SectionN_verts]
 * Index Buffer:   [Section0_idx  | Section1_idx   | ... | SectionN_idx  ]
 * Indirect Buffer: [DrawCmd0 | DrawCmd1 | ... | DrawCmdN]  (20 bytes each)
 * </pre>
 *
 * <h3>Multi-Draw-Indirect</h3>
 * <p>Each visible section contributes one VkDrawIndexedIndirectCommand to the indirect buffer.
 * Rendering the entire region requires a single {@code vkCmdDrawIndexedIndirect()} call,
 * reducing draw call overhead by ~95% compared to per-section draws.</p>
 */
public class RegionGPUBuffers {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RegionGPU");

    /** VkDrawIndexedIndirectCommand size in bytes. */
    public static final int INDIRECT_COMMAND_SIZE = 20; // 5 × uint32

    /** Initial vertex buffer size per region (grows if needed). */
    private static final long INITIAL_VB_SIZE = 32 * 1024 * 1024; // 32 MB — larger for high view distances

    /** Initial index buffer size per region. */
    private static final long INITIAL_IB_SIZE = 16 * 1024 * 1024; // 16 MB

    private final VulkaniumMemory memory;
    private final RenderRegion region;
    private final TerrainPassType passType;

    // Device-local GPU buffers (VMA-allocated)
    private long vertexBuffer = VK_NULL_HANDLE;
    private long vertexAllocation = VK_NULL_HANDLE;
    private long vertexBufferSize = 0;
    private long vertexUsedBytes = 0;

    private long indexBuffer = VK_NULL_HANDLE;
    private long indexAllocation = VK_NULL_HANDLE;
    private long indexBufferSize = 0;
    private long indexUsedBytes = 0;

    // Indirect draw buffer (host-visible for CPU writes, BUT could be device-local
    // with GPU compute frustum cull writing it)
    private long indirectBuffer = VK_NULL_HANDLE;
    private long indirectAllocation = VK_NULL_HANDLE;
    private long indirectBufferSize = 0;

    /** Number of valid draw commands in the indirect buffer. */
    private int drawCommandCount = 0;

    /**
     * Whether the indirect buffer uses host-visible memory (CPU writes)
     * vs device-local (GPU compute writes via frustum cull shader).
     */
    private boolean indirectHostVisible = true;

    public RegionGPUBuffers(VulkaniumMemory memory, RenderRegion region, TerrainPassType passType) {
        this.memory = memory;
        this.region = region;
        this.passType = passType;
    }

    /**
     * Ensures vertex buffer has at least the specified capacity.
     * If the current buffer is too small, allocates a new one (old data is not preserved —
     * caller must re-upload).
     */
    public void ensureVertexCapacity(long requiredBytes) {
        if (vertexBuffer != VK_NULL_HANDLE && vertexBufferSize >= requiredBytes) {
            return;
        }

        // Free old buffer
        if (vertexBuffer != VK_NULL_HANDLE) {
            memory.deferBufferFree(vertexBuffer, vertexAllocation);
        }

        long newSize = Math.max(INITIAL_VB_SIZE, nextPowerOf2(requiredBytes));
        long[] result = memory.allocateBuffer(
                newSize,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VulkaniumMemory.DEVICE_LOCAL
        );
        vertexBuffer = result[0];
        vertexAllocation = result[1];
        vertexBufferSize = newSize;
        vertexUsedBytes = 0;

        LOGGER.debug("Region [{},{},{}] pass {} vertex buffer: {} KB",
                region.getRegionX(), region.getRegionY(), region.getRegionZ(),
                passType, newSize / 1024);
    }

    /**
     * Ensures index buffer has at least the specified capacity.
     */
    public void ensureIndexCapacity(long requiredBytes) {
        if (indexBuffer != VK_NULL_HANDLE && indexBufferSize >= requiredBytes) {
            return;
        }

        if (indexBuffer != VK_NULL_HANDLE) {
            memory.deferBufferFree(indexBuffer, indexAllocation);
        }

        long newSize = Math.max(INITIAL_IB_SIZE, nextPowerOf2(requiredBytes));
        long[] result = memory.allocateBuffer(
                newSize,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VulkaniumMemory.DEVICE_LOCAL
        );
        indexBuffer = result[0];
        indexAllocation = result[1];
        indexBufferSize = newSize;
        indexUsedBytes = 0;
    }

    /**
     * Ensures indirect draw buffer has capacity for the given number of draw commands.
     */
    public void ensureIndirectCapacity(int maxDrawCommands) {
        long requiredBytes = (long) maxDrawCommands * INDIRECT_COMMAND_SIZE;
        if (indirectBuffer != VK_NULL_HANDLE && indirectBufferSize >= requiredBytes) {
            return;
        }

        if (indirectBuffer != VK_NULL_HANDLE) {
            memory.deferBufferFree(indirectBuffer, indirectAllocation);
        }

        long newSize = Math.max(256L * INDIRECT_COMMAND_SIZE, nextPowerOf2(requiredBytes));
        int memoryType = indirectHostVisible ? VulkaniumMemory.HOST_VISIBLE : VulkaniumMemory.DEVICE_LOCAL;
        long[] result = memory.allocateBuffer(
                newSize,
                VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT |
                        (indirectHostVisible ? 0 : VK_BUFFER_USAGE_STORAGE_BUFFER_BIT),
                memoryType
        );
        indirectBuffer = result[0];
        indirectAllocation = result[1];
        indirectBufferSize = newSize;
    }

    /**
     * Records a sub-allocation of vertex data in the vertex buffer.
     * Returns the byte offset where the data was placed.
     */
    public long appendVertexData(long bytes) {
        long offset = vertexUsedBytes;
        vertexUsedBytes += bytes;
        return offset;
    }

    /**
     * Records a sub-allocation of index data in the index buffer.
     * Returns the byte offset where the data was placed.
     */
    public long appendIndexData(long bytes) {
        long offset = indexUsedBytes;
        indexUsedBytes += bytes;
        return offset;
    }

    /**
     * Resets the used counters for a fresh region rebuild cycle.
     */
    public void resetUsage() {
        vertexUsedBytes = 0;
        indexUsedBytes = 0;
        drawCommandCount = 0;
    }

    /**
     * Writes a VkDrawIndexedIndirectCommand into the indirect buffer at the given slot.
     * Only valid when indirectHostVisible is true (CPU write path).
     *
     * @param indexCount    Number of indices to draw
     * @param instanceCount Number of instances (always 1 for terrain)
     * @param firstIndex    Offset into the index buffer (in index elements)
     * @param vertexOffset  Offset added to each index value (base vertex)
     * @param firstInstance Instance offset (always 0)
     */
    public void writeIndirectCommand(int slot, int indexCount, int instanceCount,
                                     int firstIndex, int vertexOffset, int firstInstance) {
        if (!indirectHostVisible) {
            throw new IllegalStateException("Cannot CPU-write to device-local indirect buffer");
        }

        ByteBuffer mapped = memory.mapBuffer(indirectAllocation);
        if (mapped == null) {
            LOGGER.error("Failed to map indirect buffer for region [{},{},{}]",
                    region.getRegionX(), region.getRegionY(), region.getRegionZ());
            return;
        }

        int offset = slot * INDIRECT_COMMAND_SIZE;
        mapped.putInt(offset, indexCount);
        mapped.putInt(offset + 4, instanceCount);
        mapped.putInt(offset + 8, firstIndex);
        mapped.putInt(offset + 12, vertexOffset);
        mapped.putInt(offset + 16, firstInstance);

        memory.unmapBuffer(indirectAllocation);
        drawCommandCount = Math.max(drawCommandCount, slot + 1);
    }

    /**
     * Releases all GPU buffers.
     */
    public void destroy() {
        if (vertexBuffer != VK_NULL_HANDLE) {
            memory.deferBufferFree(vertexBuffer, vertexAllocation);
            vertexBuffer = VK_NULL_HANDLE;
        }
        if (indexBuffer != VK_NULL_HANDLE) {
            memory.deferBufferFree(indexBuffer, indexAllocation);
            indexBuffer = VK_NULL_HANDLE;
        }
        if (indirectBuffer != VK_NULL_HANDLE) {
            memory.deferBufferFree(indirectBuffer, indirectAllocation);
            indirectBuffer = VK_NULL_HANDLE;
        }
    }

    // ============== Getters ==============

    public long getVertexBuffer() { return vertexBuffer; }
    public long getIndexBuffer() { return indexBuffer; }
    public long getIndirectBuffer() { return indirectBuffer; }
    public int getDrawCommandCount() { return drawCommandCount; }
    public void setDrawCommandCount(int count) { this.drawCommandCount = count; }
    public boolean hasGeometry() { return drawCommandCount > 0; }
    public long getVertexUsedBytes() { return vertexUsedBytes; }
    public long getIndexUsedBytes() { return indexUsedBytes; }

    // ============== Utility ==============

    private static long nextPowerOf2(long v) {
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v |= v >> 32;
        return v + 1;
    }
}
