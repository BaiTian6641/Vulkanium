package net.vulkanium.vulkan.memory;

import org.lwjgl.system.MemoryUtil;

/**
 * Typed uniform buffer for shader uniforms.
 *
 * <p>Replaces OpenGL's uniform block / UBO binding. In Vulkanium, we use a single
 * large UBO per frame (see UniformBridge) but also support per-draw dynamic UBOs.</p>
 *
 * <p>Always CPU_VISIBLE (and typically persistently mapped) so the CPU can
 * write uniforms directly without staging.</p>
 */
public class UniformBuffer extends VulkanBuffer {

    /** The required minUniformBufferOffsetAlignment from device limits */
    private static long uniformAlignment = 256; // Conservative default

    /** Per-frame offset (for dynamic UBO indexing) */
    private long currentOffset;

    /** Usable capacity (may be less than size due to alignment padding) */
    private final long capacity;

    public UniformBuffer(long size, String debugName) {
        super(alignToUniform(size), USAGE_UNIFORM, MemoryType.CPU_VISIBLE, debugName);
        this.capacity = size;
    }

    /**
     * Create a per-frame dynamic uniform buffer that supports offset indexing.
     *
     * @param capacity  logical capacity per frame
     * @param frames    number of frames in flight
     */
    public static UniformBuffer createDynamic(long capacity, int frames, String debugName) {
        long aligned = alignToUniform(capacity);
        long totalSize = aligned * frames;
        UniformBuffer ubo = new UniformBuffer(totalSize, debugName);
        return ubo;
    }

    /**
     * Set the frame index to compute the write offset.
     */
    public void setFrameIndex(int frameIndex) {
        this.currentOffset = alignToUniform(capacity) * frameIndex;
    }

    /**
     * Get the dynamic offset for the current frame (for vkCmdBindDescriptorSets).
     */
    public long getDynamicOffset() {
        return currentOffset;
    }

    /**
     * Write data at the current frame's offset.
     */
    public void write(long localOffset, long srcAddress, long writeSize) {
        upload(currentOffset + localOffset, srcAddress, writeSize);
    }

    /**
     * Write a single float.
     */
    public void writeFloat(long localOffset, float value) {
        if (mappedPointer == 0) return;
        MemoryUtil.memPutFloat(mappedPointer + currentOffset + localOffset, value);
    }

    /**
     * Write a 4x4 float matrix (64 bytes, column-major for std140).
     */
    public void writeMatrix4f(long localOffset, float[] matrix) {
        if (mappedPointer == 0 || matrix.length < 16) return;
        long ptr = mappedPointer + currentOffset + localOffset;
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(ptr + (long) i * 4, matrix[i]);
        }
    }

    /**
     * Write a vec4.
     */
    public void writeVec4(long localOffset, float x, float y, float z, float w) {
        if (mappedPointer == 0) return;
        long ptr = mappedPointer + currentOffset + localOffset;
        MemoryUtil.memPutFloat(ptr, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
        MemoryUtil.memPutFloat(ptr + 12, w);
    }

    /**
     * Write an int (for samplers, switches etc.).
     */
    public void writeInt(long localOffset, int value) {
        if (mappedPointer == 0) return;
        MemoryUtil.memPutInt(mappedPointer + currentOffset + localOffset, value);
    }

    public long getCapacity() { return capacity; }

    // ─── Alignment ─────────────────────────────────────────────────────

    public static void setUniformAlignment(long alignment) {
        uniformAlignment = alignment;
    }

    public static long getUniformAlignment() {
        return uniformAlignment;
    }

    public static long alignToUniform(long size) {
        return (size + uniformAlignment - 1) & ~(uniformAlignment - 1);
    }
}
