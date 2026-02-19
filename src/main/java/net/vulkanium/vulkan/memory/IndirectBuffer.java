package net.vulkanium.vulkan.memory;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;

/**
 * Typed indirect draw buffer for GPU-driven rendering.
 *
 * <p>Stores VkDrawIndexedIndirectCommand structs that allow the GPU to
 * determine draw parameters, enabling GPU frustum culling and
 * multi-draw-indirect patterns.</p>
 *
 * <p>Each command is 20 bytes (VkDrawIndexedIndirectCommand):
 * indexCount(4) + instanceCount(4) + firstIndex(4) + vertexOffset(4) + firstInstance(4)</p>
 */
public class IndirectBuffer extends VulkanBuffer {

    /** Size of one VkDrawIndexedIndirectCommand in bytes */
    public static final int DRAW_COMMAND_SIZE = 20;

    /** Size of one VkDrawIndirectCommand in bytes (non-indexed) */
    public static final int DRAW_COMMAND_SIZE_NON_INDEXED = 16;

    /** Maximum number of draw commands */
    private final int maxDrawCommands;

    /** Actual draw command count (set by GPU compute or CPU) */
    private int drawCount;

    /** Whether this uses indexed draw commands */
    private final boolean indexed;

    public IndirectBuffer(int maxDrawCommands, boolean indexed, String debugName) {
        super(
            (long) maxDrawCommands * (indexed ? DRAW_COMMAND_SIZE : DRAW_COMMAND_SIZE_NON_INDEXED),
            USAGE_INDIRECT | USAGE_STORAGE | USAGE_TRANSFER_DST,
            MemoryType.CPU_TO_GPU,
            debugName
        );
        this.maxDrawCommands = maxDrawCommands;
        this.indexed = indexed;
    }

    /**
     * For GPU-driven rendering: create as storage buffer that compute shaders can write to.
     */
    public static IndirectBuffer createGPUWritable(int maxCommands, String debugName) {
        IndirectBuffer buf = new IndirectBuffer(maxCommands, true, debugName);
        return buf;
    }

    /**
     * Write one indexed draw command at the given slot.
     */
    public void writeDrawCommand(int slot, int indexCount, int instanceCount,
                                  int firstIndex, int vertexOffset, int firstInstance) {
        if (slot >= maxDrawCommands || mappedPointer == 0) return;
        long ptr = mappedPointer + (long) slot * DRAW_COMMAND_SIZE;
        MemoryUtil.memPutInt(ptr,      indexCount);
        MemoryUtil.memPutInt(ptr + 4,  instanceCount);
        MemoryUtil.memPutInt(ptr + 8,  firstIndex);
        MemoryUtil.memPutInt(ptr + 12, vertexOffset);
        MemoryUtil.memPutInt(ptr + 16, firstInstance);
        drawCount = Math.max(drawCount, slot + 1);
    }

    /**
     * Write one non-indexed draw command.
     */
    public void writeDrawCommandNonIndexed(int slot, int vertexCount, int instanceCount,
                                           int firstVertex, int firstInstance) {
        if (slot >= maxDrawCommands || mappedPointer == 0) return;
        long ptr = mappedPointer + (long) slot * DRAW_COMMAND_SIZE_NON_INDEXED;
        MemoryUtil.memPutInt(ptr,      vertexCount);
        MemoryUtil.memPutInt(ptr + 4,  instanceCount);
        MemoryUtil.memPutInt(ptr + 8,  firstVertex);
        MemoryUtil.memPutInt(ptr + 12, firstInstance);
        drawCount = Math.max(drawCount, slot + 1);
    }

    /**
     * Issue a vkCmdDrawIndexedIndirect call using this buffer.
     */
    public void dispatchIndirect(long commandBuffer) {
        if (drawCount == 0) return;
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
        if (indexed) {
            vkCmdDrawIndexedIndirect(cmd, buffer, 0, drawCount, DRAW_COMMAND_SIZE);
        } else {
            vkCmdDrawIndirect(cmd, buffer, 0, drawCount, DRAW_COMMAND_SIZE_NON_INDEXED);
        }
    }

    /**
     * Issue a vkCmdDrawIndexedIndirectCount (Vulkan 1.2+).
     * Reads actual count from a separate count buffer (written by compute shader).
     */
    public void dispatchIndirectCount(long commandBuffer, VulkanBuffer countBuffer,
                                      long countOffset) {
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
        vkCmdDrawIndexedIndirectCount(cmd, buffer, 0,
                countBuffer.getHandle(), countOffset, maxDrawCommands, DRAW_COMMAND_SIZE);
    }

    /**
     * Clear draw count (CPU side).
     */
    public void resetDrawCount() {
        this.drawCount = 0;
    }

    public int getMaxDrawCommands() { return maxDrawCommands; }
    public int getDrawCount() { return drawCount; }
    public void setDrawCount(int count) { this.drawCount = count; }
    public boolean isIndexed() { return indexed; }
}
