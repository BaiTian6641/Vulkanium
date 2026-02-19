package net.vulkanium.vulkan.memory;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Typed vertex buffer for chunk geometry and entity data.
 *
 * <p>Replaces OpenGL's glBufferData(GL_ARRAY_BUFFER, ...) pattern.
 * Stores interleaved vertex data in Vulkanium's vertex format
 * (32 bytes: pos + color + uv + normal + tangent + light + entity).</p>
 *
 * <p>Typically allocated as GPU_ONLY with data uploaded via staging ring.</p>
 */
public class VertexBuffer extends VulkanBuffer {

    /** Vertex stride in bytes */
    private final int stride;

    /** Number of vertices stored */
    private int vertexCount;

    public VertexBuffer(long size, int stride, String debugName) {
        super(size, USAGE_VERTEX | USAGE_TRANSFER_DST, MemoryType.GPU_ONLY, debugName);
        this.stride = stride;
    }

    public VertexBuffer(long size, int stride, MemoryType memoryType, String debugName) {
        super(size, USAGE_VERTEX | USAGE_TRANSFER_DST
              | (memoryType != MemoryType.GPU_ONLY ? USAGE_TRANSFER_SRC : 0),
              memoryType, debugName);
        this.stride = stride;
    }

    /**
     * Upload vertex data and track count.
     */
    public void uploadVertices(long commandBuffer, VulkanBuffer staging,
                               long stagingOffset, long dataSize) {
        this.vertexCount = (int) (dataSize / stride);
        copyFrom(commandBuffer, staging, stagingOffset, 0, dataSize);
    }

    /**
     * Bind this vertex buffer for drawing.
     * Equivalent to vkCmdBindVertexBuffers.
     */
    public void bind(long commandBuffer) {
        try (MemoryStack stack = stackPush()) {
            long[] buffers = { buffer };
            long[] offsets = { 0 };
            nvkCmdBindVertexBuffers(commandBuffer, buffers, offsets);
        }
    }

    /**
     * Bind at a specific binding point and offset.
     */
    public void bind(long commandBuffer, int binding, long offset) {
        try (MemoryStack stack = stackPush()) {
            long[] buffers = { buffer };
            long[] offsets = { offset };
            nvkCmdBindVertexBuffers(commandBuffer, binding, buffers, offsets);
        }
    }

    /** Native bind helper using raw command buffer handle. */
    private static void nvkCmdBindVertexBuffers(long commandBuffer, long[] buffers, long[] offsets) {
        try (MemoryStack stack = stackPush()) {
            var pBuffers = stack.mallocLong(buffers.length);
            var pOffsets = stack.mallocLong(offsets.length);
            for (int i = 0; i < buffers.length; i++) {
                pBuffers.put(i, buffers[i]);
                pOffsets.put(i, offsets[i]);
            }
            VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                    net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
            vkCmdBindVertexBuffers(cmd, 0, pBuffers, pOffsets);
        }
    }

    /** Native bind helper with custom first binding. */
    private static void nvkCmdBindVertexBuffers(long commandBuffer, int firstBinding,
                                                 long[] buffers, long[] offsets) {
        try (MemoryStack stack = stackPush()) {
            var pBuffers = stack.mallocLong(buffers.length);
            var pOffsets = stack.mallocLong(offsets.length);
            for (int i = 0; i < buffers.length; i++) {
                pBuffers.put(i, buffers[i]);
                pOffsets.put(i, offsets[i]);
            }
            VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                    net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
            vkCmdBindVertexBuffers(cmd, firstBinding, pBuffers, pOffsets);
        }
    }

    public int getStride() { return stride; }
    public int getVertexCount() { return vertexCount; }
    public void setVertexCount(int count) { this.vertexCount = count; }
}
