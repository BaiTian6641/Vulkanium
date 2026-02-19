package net.vulkanium.vulkan.memory;

import net.vulkanium.core.VulkaniumDevice;
import net.vulkanium.core.VulkaniumMemory;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Base Vulkan buffer abstraction using VMA. All typed buffer classes extend this.
 *
 * <p>Manages a VkBuffer + VmaAllocation pair. Provides mapping, unmapping,
 * and basic copy operations. Memory allocation strategy is configurable
 * (GPU-only, CPU-visible, CPU-to-GPU staged).</p>
 *
 * <p>Corresponds to VulkanMod's buffer abstraction layer and replaces
 * OpenGL's glGenBuffers / glBufferData / glBufferSubData workflow.</p>
 */
public class VulkanBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Buffer");

    /** VkBuffer handle */
    protected long buffer;

    /** VMA allocation handle */
    protected long allocation;

    /** Size in bytes */
    protected long size;

    /** VkBufferUsageFlags */
    protected final int usageFlags;

    /** Memory property flags */
    protected final MemoryType memoryType;

    /** Mapped pointer (0 if not mapped) */
    protected long mappedPointer;

    /** Name for debug markers */
    protected final String debugName;

    /** Whether this buffer is currently valid */
    protected boolean alive;

    public enum MemoryType {
        /** GPU-only, must stage data. Best for static geometry. */
        GPU_ONLY,
        /** CPU-visible, coherent. Good for uniform buffers, small dynamic data. */
        CPU_VISIBLE,
        /** Persistently mapped, host-visible. Best for streaming / staging ring. */
        CPU_TO_GPU,
        /** GPU→CPU readback. For async compute results, screenshots. */
        GPU_TO_CPU
    }

    // ─── Shared device reference ───────────────────────────────────────
    private static long sharedAllocator;
    private static long sharedDevice;

    /** Must be called once during engine init to provide VMA allocator + device handles. */
    public static void initShared(long vmaAllocator, long vkDevice) {
        sharedAllocator = vmaAllocator;
        sharedDevice = vkDevice;
    }

    public static long getAllocator() { return sharedAllocator; }
    public static long getDevice() { return sharedDevice; }

    protected VulkanBuffer(long size, int usageFlags, MemoryType memoryType, String debugName) {
        this.size = size;
        this.usageFlags = usageFlags;
        this.memoryType = memoryType;
        this.debugName = debugName;
    }

    /**
     * Allocate the VkBuffer via VMA.
     */
    public void create() {
        try (MemoryStack stack = stackPush()) {
            // Translate usage flags (our constants match VK constants)
            int vkUsage = usageFlags | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

            VkBufferCreateInfo bufferCI = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(vkUsage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocCI = VmaAllocationCreateInfo.calloc(stack);
            switch (memoryType) {
                case GPU_ONLY -> allocCI
                        .usage(VMA_MEMORY_USAGE_GPU_ONLY);
                case CPU_VISIBLE -> allocCI
                        .usage(VMA_MEMORY_USAGE_CPU_TO_GPU)
                        .flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);
                case CPU_TO_GPU -> allocCI
                        .usage(VMA_MEMORY_USAGE_CPU_TO_GPU)
                        .flags(VMA_ALLOCATION_CREATE_MAPPED_BIT
                               | VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
                case GPU_TO_CPU -> allocCI
                        .usage(VMA_MEMORY_USAGE_GPU_TO_CPU)
                        .flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);
            }

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);

            int result = vmaCreateBuffer(sharedAllocator, bufferCI, allocCI, pBuffer, pAllocation, null);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create buffer '%s' (size=%d): VkResult %d"
                        .formatted(debugName, size, result));
            }

            this.buffer = pBuffer.get(0);
            this.allocation = pAllocation.get(0);

            // Auto-map for CPU-accessible memory types
            if (memoryType != MemoryType.GPU_ONLY) {
                PointerBuffer pData = stack.mallocPointer(1);
                result = vmaMapMemory(sharedAllocator, allocation, pData);
                if (result == VK_SUCCESS) {
                    this.mappedPointer = pData.get(0);
                } else {
                    LOGGER.warn("Failed to map buffer '{}': VkResult {}", debugName, result);
                }
            }
        }

        // Track allocation in MemoryManager
        MemoryManager.getInstance().trackAllocation(allocation, size, debugName);

        alive = true;
        LOGGER.debug("Created buffer '{}' size={} type={}", debugName, size, memoryType);
    }

    /**
     * Map the buffer for CPU access. Only valid for CPU_VISIBLE / CPU_TO_GPU.
     */
    public long map() {
        if (mappedPointer != 0) return mappedPointer;
        if (memoryType == MemoryType.GPU_ONLY) {
            throw new IllegalStateException("Cannot map GPU-only buffer: " + debugName);
        }

        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int result = vmaMapMemory(sharedAllocator, allocation, pData);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to map buffer '%s': VkResult %d"
                        .formatted(debugName, result));
            }
            this.mappedPointer = pData.get(0);
        }
        return mappedPointer;
    }

    /**
     * Unmap the buffer.
     */
    public void unmap() {
        if (mappedPointer == 0) return;
        vmaUnmapMemory(sharedAllocator, allocation);
        mappedPointer = 0;
    }

    /**
     * Flush mapped region to make writes visible to GPU (non-coherent memory only).
     */
    public void flush(long offset, long flushSize) {
        vmaFlushAllocation(sharedAllocator, allocation, offset, flushSize);
    }

    /**
     * Upload data to a specific offset from a source address via memcpy.
     * Buffer must be mapped.
     */
    public void upload(long offset, long srcAddress, long copySize) {
        if (mappedPointer == 0) {
            throw new IllegalStateException("Buffer not mapped: " + debugName);
        }
        MemoryUtil.memCopy(srcAddress, mappedPointer + offset, copySize);
    }

    /**
     * Record a vkCmdCopyBuffer command. For GPU_ONLY buffers, data must
     * be staged first.
     */
    public void copyFrom(VkCommandBuffer commandBuffer, VulkanBuffer src, long srcOffset,
                         long dstOffset, long copySize) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                    .srcOffset(srcOffset)
                    .dstOffset(dstOffset)
                    .size(copySize);
            vkCmdCopyBuffer(commandBuffer, src.buffer, this.buffer, region);
        }
    }

    /** Overload accepting raw long command buffer handle. */
    public void copyFrom(long commandBuffer, VulkanBuffer src, long srcOffset,
                         long dstOffset, long copySize) {
        // For callers passing raw long handles, we still use the raw Vulkan call
        try (MemoryStack stack = stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                    .srcOffset(srcOffset)
                    .dstOffset(dstOffset)
                    .size(copySize);
            // Use native call with raw handle
            org.lwjgl.vulkan.VK10.nvkCmdCopyBuffer(
                    new VkCommandBuffer(commandBuffer, VulkaniumDevice.getGlobalDevice()),
                    src.buffer, this.buffer, 1, region.address());
        }
    }

    /**
     * Resize the buffer. Allocates a new buffer and destroys the old one.
     */
    public void resize(long newSize) {
        if (newSize == size) return;
        long oldBuffer = buffer;
        long oldAllocation = allocation;
        long oldSize = size;
        boolean wasMapped = mappedPointer != 0;

        // Unmap old before destroy
        if (wasMapped) {
            vmaUnmapMemory(sharedAllocator, oldAllocation);
            mappedPointer = 0;
        }

        this.size = newSize;
        create(); // creates new buffer (and maps if needed)

        // Destroy old buffer
        MemoryManager.getInstance().untrackAllocation(oldAllocation, oldSize);
        vmaDestroyBuffer(sharedAllocator, oldBuffer, oldAllocation);

        LOGGER.debug("Resized buffer '{}' to {} bytes", debugName, newSize);
    }

    /**
     * Destroy the buffer and free memory.
     */
    public void destroy() {
        if (!alive) return;
        if (mappedPointer != 0) {
            vmaUnmapMemory(sharedAllocator, allocation);
            mappedPointer = 0;
        }

        MemoryManager.getInstance().untrackAllocation(allocation, size);
        vmaDestroyBuffer(sharedAllocator, buffer, allocation);

        buffer = 0;
        allocation = 0;
        alive = false;
    }

    // Getters
    public long getHandle() { return buffer; }
    public long getSize() { return size; }
    public long getMappedPointer() { return mappedPointer; }
    public boolean isMapped() { return mappedPointer != 0; }
    public boolean isAlive() { return alive; }
    public String getDebugName() { return debugName; }
    public MemoryType getMemoryType() { return memoryType; }

    // ─── Usage flag constants (match VkBufferUsageFlagBits) ────────────

    public static final int USAGE_TRANSFER_SRC = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    public static final int USAGE_TRANSFER_DST = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    public static final int USAGE_UNIFORM_TEXEL = VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
    public static final int USAGE_STORAGE_TEXEL = VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT;
    public static final int USAGE_UNIFORM = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
    public static final int USAGE_STORAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    public static final int USAGE_INDEX = VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
    public static final int USAGE_VERTEX = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    public static final int USAGE_INDIRECT = VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
}
