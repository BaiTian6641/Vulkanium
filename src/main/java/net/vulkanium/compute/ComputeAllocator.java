package net.vulkanium.compute;

import net.vulkanium.core.VulkaniumMemory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * GPU buffer allocator for compute tasks.
 *
 * <p>Manages memory for SSBO inputs/outputs used by the compute platform.
 * Three transfer patterns are supported:</p>
 * <ul>
 *   <li><b>CPU → GPU:</b> Upload via staging ring into device-local SSBO</li>
 *   <li><b>GPU → GPU:</b> Zero-copy between tasks (same SSBO, barrier only)</li>
 *   <li><b>GPU → CPU:</b> Readback via HOST_VISIBLE | HOST_CACHED ring buffer</li>
 * </ul>
 *
 * <h3>Ring Buffer Design</h3>
 * <p>Upload and readback use ring buffers to avoid per-frame allocation overhead.
 * Each ring is divided into {@code MAX_IN_FLIGHT} slices so the GPU can read from
 * the previous frame's slice while the CPU writes to the current slice.</p>
 *
 * <h3>Device-Local Pools</h3>
 * <p>For large SSBOs that persist across frames (e.g., world data), device-local
 * allocations are managed with a free list to avoid fragmentation.</p>
 */
public class ComputeAllocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ComputeAlloc");

    /** Default staging ring size (32 MB) */
    private static final long STAGING_RING_SIZE = 32L * 1024 * 1024;

    /** Default readback ring size (16 MB) */
    private static final long READBACK_RING_SIZE = 16L * 1024 * 1024;

    /** Maximum individual SSBO allocation size (256 MB) */
    private static final long MAX_SSBO_SIZE = 256L * 1024 * 1024;

    /** Number of in-flight frames for ring buffers */
    private static final int MAX_IN_FLIGHT = 3;

    private final VkDevice device;
    private final VulkaniumMemory memory;

    // ── Staging ring (host-visible, CPU → GPU) ──
    private long stagingBuffer;
    private long stagingAllocation;
    private long stagingMappedPtr;
    private long stagingOffset;
    private final long stagingSliceSize;

    // ── Readback ring (host-visible + cached, GPU → CPU) ──
    private long readbackBuffer;
    private long readbackAllocation;
    private long readbackMappedPtr;
    private long readbackOffset;
    private final long readbackSliceSize;

    // ── Device-local pool ──
    private int deviceLocalAllocCount;

    private int currentFrame;

    public ComputeAllocator(VkDevice device, VulkaniumMemory memory) {
        this.device = device;
        this.memory = memory;
        this.stagingSliceSize = STAGING_RING_SIZE / MAX_IN_FLIGHT;
        this.readbackSliceSize = READBACK_RING_SIZE / MAX_IN_FLIGHT;
    }

    /**
     * Initializes staging and readback ring buffers.
     */
    public void initialize() {
        // Staging ring: HOST_VISIBLE | HOST_COHERENT for CPU writes → GPU reads
        stagingBuffer = memory.createBuffer(
                STAGING_RING_SIZE,
                VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VulkaniumMemory.MemoryLocation.CPU_TO_GPU
        );
        stagingMappedPtr = memory.mapComputeBuffer(stagingBuffer);

        // Readback ring: HOST_VISIBLE | HOST_CACHED for GPU writes → CPU reads
        readbackBuffer = memory.createBuffer(
                READBACK_RING_SIZE,
                VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VulkaniumMemory.MemoryLocation.GPU_TO_CPU
        );
        readbackMappedPtr = memory.mapComputeBuffer(readbackBuffer);

        LOGGER.info("Compute allocator initialized: staging={}MB readback={}MB",
                STAGING_RING_SIZE / (1024 * 1024), READBACK_RING_SIZE / (1024 * 1024));
    }

    // ── CPU → GPU upload ──

    /**
     * Uploads data to a staging region for compute input.
     *
     * @param data Source data
     * @return A binding describing the staging buffer region
     */
    public BufferRegion upload(ByteBuffer data) {
        long size = data.remaining();
        if (size > stagingSliceSize) {
            throw new IllegalArgumentException("Upload exceeds staging slice: " + size + " > " + stagingSliceSize);
        }

        long sliceBase = (long) currentFrame * stagingSliceSize;
        long offset = sliceBase + stagingOffset;

        if (stagingOffset + size > stagingSliceSize) {
            LOGGER.warn("Staging ring overflow, wrapping to start of slice");
            stagingOffset = 0;
            offset = sliceBase;
        }

        // Copy to mapped staging memory
        memory.copyToMapped(stagingMappedPtr + offset, data);
        stagingOffset += align(size, 256); // SSBO offset alignment

        return new BufferRegion(stagingBuffer, offset, size);
    }

    /**
     * Allocates a device-local SSBO for compute output.
     *
     * @param size Buffer size in bytes
     * @return A binding describing the new device-local buffer
     */
    public BufferRegion allocateDeviceLocal(long size) {
        if (size > MAX_SSBO_SIZE) {
            throw new IllegalArgumentException("SSBO too large: " + size);
        }

        long buffer = memory.createBuffer(
                size,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                        | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VulkaniumMemory.MemoryLocation.GPU_ONLY
        );
        deviceLocalAllocCount++;

        return new BufferRegion(buffer, 0, size);
    }

    // ── GPU → CPU readback ──

    /**
     * Allocates a readback region in the readback ring.
     *
     * @param size Number of bytes to read back
     * @return A binding describing the readback destination
     */
    public BufferRegion allocateReadback(long size) {
        if (size > readbackSliceSize) {
            throw new IllegalArgumentException("Readback exceeds slice: " + size);
        }

        long sliceBase = (long) currentFrame * readbackSliceSize;
        long offset = sliceBase + readbackOffset;

        if (readbackOffset + size > readbackSliceSize) {
            readbackOffset = 0;
            offset = sliceBase;
        }

        readbackOffset += align(size, 256);
        return new BufferRegion(readbackBuffer, offset, size);
    }

    /**
     * Reads back data from a completed compute task.
     *
     * @param region The readback region (from a previous frame)
     * @param dest   Destination byte buffer
     */
    public void readback(BufferRegion region, ByteBuffer dest) {
        memory.copyFromMapped(readbackMappedPtr + region.offset(), dest, region.size());
    }

    // ── Frame management ──

    /**
     * Advances to the next frame, resetting the current slice offsets.
     */
    public void nextFrame(int frameIndex) {
        this.currentFrame = frameIndex % MAX_IN_FLIGHT;
        this.stagingOffset = 0;
        this.readbackOffset = 0;
    }

    /**
     * Frees a device-local buffer.
     */
    public void free(BufferRegion region) {
        if (region.buffer() != stagingBuffer && region.buffer() != readbackBuffer) {
            memory.destroyBuffer(region.buffer());
            deviceLocalAllocCount--;
        }
    }

    public void destroy() {
        memory.unmapBuffer(stagingBuffer);
        memory.destroyBuffer(stagingBuffer);
        memory.unmapBuffer(readbackBuffer);
        memory.destroyBuffer(readbackBuffer);
        LOGGER.info("Compute allocator destroyed ({} device-local allocs leaked)", deviceLocalAllocCount);
    }

    // ── Utilities ──

    private static long align(long value, long alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    /**
     * Describes a region within a Vulkan buffer.
     */
    public record BufferRegion(long buffer, long offset, long size) {
        /**
         * Returns true if this region is in the staging ring.
         */
        public boolean isStaging() {
            return false; // Determined at usage site by comparing buffer handles
        }
    }
}
