package net.vulkanium.vulkan.memory;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Typed index buffer for indexed drawing.
 *
 * <p>Replaces OpenGL's glBufferData(GL_ELEMENT_ARRAY_BUFFER, ...).
 * Supports both UINT16 and UINT32 index types.</p>
 */
public class IndexBuffer extends VulkanBuffer {

    public enum IndexType {
        UINT16(2, VK_INDEX_TYPE_UINT16),
        UINT32(4, VK_INDEX_TYPE_UINT32);

        public final int byteSize;
        public final int vkValue;

        IndexType(int byteSize, int vkValue) {
            this.byteSize = byteSize;
            this.vkValue = vkValue;
        }
    }

    private final IndexType indexType;
    private int indexCount;

    public IndexBuffer(long size, IndexType indexType, String debugName) {
        super(size, USAGE_INDEX | USAGE_TRANSFER_DST, MemoryType.GPU_ONLY, debugName);
        this.indexType = indexType;
    }

    public IndexBuffer(long size, IndexType indexType, MemoryType memoryType, String debugName) {
        super(size, USAGE_INDEX | USAGE_TRANSFER_DST, memoryType, debugName);
        this.indexType = indexType;
    }

    /**
     * Upload index data and track count.
     */
    public void uploadIndices(long commandBuffer, VulkanBuffer staging,
                              long stagingOffset, long dataSize) {
        this.indexCount = (int) (dataSize / indexType.byteSize);
        copyFrom(commandBuffer, staging, stagingOffset, 0, dataSize);
    }

    /**
     * Bind this index buffer for drawing.
     */
    public void bind(long commandBuffer) {
        bind(commandBuffer, 0);
    }

    public void bind(long commandBuffer, long offset) {
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
        vkCmdBindIndexBuffer(cmd, buffer, offset, indexType.vkValue);
    }

    /**
     * Generate a quad index buffer (shared across all chunk renders).
     * Pattern: 0,1,2, 2,3,0 for each quad.
     */
    public static IndexBuffer createSharedQuadIndices(int maxQuads) {
        IndexType type = maxQuads * 4 > 65535 ? IndexType.UINT32 : IndexType.UINT16;
        long size = (long) maxQuads * 6 * type.byteSize;
        IndexBuffer ib = new IndexBuffer(size, type, MemoryType.CPU_TO_GPU, "shared_quad_indices");
        ib.create();
        ib.indexCount = maxQuads * 6;

        long ptr = ib.map();
        if (ptr != 0) {
            for (int q = 0; q < maxQuads; q++) {
                int base = q * 4;
                if (type == IndexType.UINT16) {
                    long off = (long) q * 12; // 6 indices × 2 bytes
                    MemoryUtil.memPutShort(ptr + off,      (short) base);
                    MemoryUtil.memPutShort(ptr + off + 2,  (short) (base + 1));
                    MemoryUtil.memPutShort(ptr + off + 4,  (short) (base + 2));
                    MemoryUtil.memPutShort(ptr + off + 6,  (short) (base + 2));
                    MemoryUtil.memPutShort(ptr + off + 8,  (short) (base + 3));
                    MemoryUtil.memPutShort(ptr + off + 10, (short) base);
                } else {
                    long off = (long) q * 24; // 6 indices × 4 bytes
                    MemoryUtil.memPutInt(ptr + off,      base);
                    MemoryUtil.memPutInt(ptr + off + 4,  base + 1);
                    MemoryUtil.memPutInt(ptr + off + 8,  base + 2);
                    MemoryUtil.memPutInt(ptr + off + 12, base + 2);
                    MemoryUtil.memPutInt(ptr + off + 16, base + 3);
                    MemoryUtil.memPutInt(ptr + off + 20, base);
                }
            }
            ib.flush(0, size);
        }
        return ib;
    }

    public IndexType getIndexType() { return indexType; }
    public int getIndexCount() { return indexCount; }
    public void setIndexCount(int count) { this.indexCount = count; }
}
