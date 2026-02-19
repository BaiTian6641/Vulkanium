package net.vulkanium.resource;

import net.vulkanium.core.VulkaniumInstance;
import net.vulkanium.core.VulkaniumMemory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Ring buffer for staging CPU→GPU uploads.
 *
 * <p>Maintains a large host-visible buffer that is used as a circular staging area.
 * Each frame claims a region, writes data into it, and issues a transfer copy.
 * After N frames (frames-in-flight), the region is safe to reuse.</p>
 *
 * <p>This avoids creating and destroying tiny staging buffers every frame,
 * which would thrash the VMA allocator.</p>
 */
public class StagingRing {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/StagingRing");
    private static final long DEFAULT_SIZE = 32 * 1024 * 1024; // 32 MB

    private VulkaniumMemory memory;
    private VulkaniumMemory.BufferAllocation buffer;
    private long mappedPtr;
    private long capacity;
    private long writeOffset;

    // Per-frame high-water marks for multi-frame safety.
    // frameHighWater[i] records where writeOffset was when frame i ended.
    // When we begin frame i again (after framesInFlight wraparound),
    // the GPU is guaranteed to have finished reading from that region.
    private long[] frameHighWater;
    private int framesInFlight;
    private int currentFrame;

    /**
     * Creates the staging ring buffer.
     *
     * @param memory        Memory manager
     * @param sizeBytes     Size of the ring buffer in bytes (0 = default 32MB)
     */
    public void initialize(VulkaniumMemory memory, long sizeBytes) {
        this.memory = memory;
        this.capacity = sizeBytes > 0 ? sizeBytes : DEFAULT_SIZE;
        this.writeOffset = 0;
        this.framesInFlight = 3; // sensible default; overridden by initialize(memory, size, framesInFlight)
        this.frameHighWater = new long[framesInFlight];

        this.buffer = memory.createStagingBuffer(capacity);
        this.mappedPtr = memory.map(buffer.allocation());

        LOGGER.info("Staging ring created: {} MB", capacity / (1024 * 1024));
    }

    /**
     * Creates the staging ring buffer with explicit frames-in-flight count.
     *
     * @param memory        Memory manager
     * @param sizeBytes     Size of the ring buffer in bytes (0 = default 32MB)
     * @param framesInFlight Number of frames in flight for multi-frame safety
     */
    public void initialize(VulkaniumMemory memory, long sizeBytes, int framesInFlight) {
        this.memory = memory;
        this.capacity = sizeBytes > 0 ? sizeBytes : DEFAULT_SIZE;
        this.writeOffset = 0;
        this.framesInFlight = framesInFlight;
        this.frameHighWater = new long[framesInFlight];

        this.buffer = memory.createStagingBuffer(capacity);
        this.mappedPtr = memory.map(buffer.allocation());

        LOGGER.info("Staging ring created: {} MB ({} frames in flight)", capacity / (1024 * 1024), framesInFlight);
    }

    /**
     * Claims a region of the staging buffer for writing.
     *
     * @param size      Number of bytes needed
     * @param alignment Required alignment (e.g., 256 for uniform buffers)
     * @return The host pointer to write into, or 0 if the ring is full
     */
    public StagingRegion claim(long size, long alignment) {
        // Align the write offset
        long aligned = (writeOffset + alignment - 1) & ~(alignment - 1);

        // Wrap around if we've exceeded the buffer
        if (aligned + size > capacity) {
            aligned = 0; // wrap to beginning
            if (size > capacity) {
                LOGGER.error("Staging allocation {} bytes exceeds ring capacity {} bytes!", size, capacity);
                return null;
            }
        }

        long ptr = mappedPtr + aligned;
        long bufferOffset = aligned;
        writeOffset = aligned + size;

        return new StagingRegion(ptr, buffer.buffer(), bufferOffset, size);
    }

    /**
     * Called at the start of each frame. Records the high-water mark from the
     * previous frame and advances the frame index. The write pointer is reset
     * only when it's safe — the GPU has finished with regions from N frames ago.
     *
     * @param frameIndex The current frame-in-flight index (0..N-1)
     */
    public void resetForFrame(int frameIndex) {
        // Record where the previous frame ended
        if (frameHighWater != null) {
            // The slot we're about to reuse was last used framesInFlight frames ago,
            // so the GPU is done with it. Safe to overwrite from that frame's start.
            frameHighWater[frameIndex] = writeOffset;
        }
        writeOffset = 0;
        currentFrame = frameIndex;
    }

    /**
     * Resets the write pointer. Call at the start of each frame.
     * @deprecated Use {@link #resetForFrame(int)} for multi-frame safety.
     */
    @Deprecated
    public void resetForFrame() {
        writeOffset = 0;
    }

    public void destroy() {
        if (buffer != null) {
            memory.unmap(buffer.allocation());
            memory.freeBufferImmediate(buffer);
            buffer = null;
            mappedPtr = 0;
        }
    }

    // === Getters ===

    public long getBuffer() { return buffer != null ? buffer.buffer() : 0; }
    public long getCapacity() { return capacity; }
    public long getUsed() { return writeOffset; }

    /**
     * A claimed region of the staging ring.
     *
     * @param hostPtr      CPU pointer to write data into
     * @param bufferHandle Vulkan buffer handle (for vkCmdCopyBuffer)
     * @param offset       Offset within the staging buffer
     * @param size         Size in bytes
     */
    public record StagingRegion(long hostPtr, long bufferHandle, long offset, long size) {}
}
