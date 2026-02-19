package net.vulkanium.vulkan.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ring-style staging buffer for streaming data from CPU to GPU.
 *
 * <p>A large persistently-mapped buffer that cycles through regions.
 * Each frame gets a fresh section of the ring, avoiding GPU-visible writes
 * to regions still in flight.</p>
 *
 * <p>This is an improved version of the Phase 0 StagingRing, now integrated
 * with the typed buffer hierarchy and supporting per-frame fencing.</p>
 *
 * <p>Usage pattern:</p>
 * <pre>
 *   beginFrame(frameIndex);
 *   long offset = alloc(dataSize);
 *   memcpy(mappedPointer + offset, src, dataSize);
 *   // Record vkCmdCopyBuffer from staging[offset] → target
 *   endFrame();
 * </pre>
 */
public class StagingBuffer extends VulkanBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Staging");

    /** Number of frames in flight (ring sections) */
    private final int framesInFlight;

    /** Size of one ring section */
    private final long sectionSize;

    /** Current write offset within the active section */
    private long writeOffset;

    /** Current frame's section base offset */
    private long sectionBase;

    /** Current frame index */
    private int currentFrame;

    /** Per-frame high-water marks for statistics */
    private final long[] frameUsage;

    /** Total bytes staged this frame */
    private long currentFrameBytes;

    public StagingBuffer(long sectionSize, int framesInFlight, String debugName) {
        super(sectionSize * framesInFlight,
              USAGE_TRANSFER_SRC,
              MemoryType.CPU_TO_GPU,
              debugName);
        this.framesInFlight = framesInFlight;
        this.sectionSize = sectionSize;
        this.frameUsage = new long[framesInFlight];
    }

    /**
     * Begin a new frame. Resets the write pointer to the frame's section.
     */
    public void beginFrame(int frameIndex) {
        this.currentFrame = frameIndex % framesInFlight;
        this.sectionBase = (long) currentFrame * sectionSize;
        this.writeOffset = 0;
        this.currentFrameBytes = 0;
    }

    /**
     * Allocate space from the staging ring for this frame.
     *
     * @param bytes     number of bytes needed
     * @param alignment required alignment (e.g., 4 for vertex data, texel size for images)
     * @return offset in the staging buffer, or -1 if section full
     */
    public long alloc(long bytes, long alignment) {
        // Align the write cursor
        long aligned = (writeOffset + alignment - 1) & ~(alignment - 1);
        if (aligned + bytes > sectionSize) {
            LOGGER.warn("Staging section overflow! Need {} bytes, {} remaining",
                       bytes, sectionSize - aligned);
            return -1;
        }

        long offset = sectionBase + aligned;
        writeOffset = aligned + bytes;
        currentFrameBytes += bytes;
        return offset;
    }

    /** Convenience: alloc with 4-byte alignment. */
    public long alloc(long bytes) {
        return alloc(bytes, 4);
    }

    /**
     * End the current frame. Records usage statistics.
     */
    public void endFrame() {
        frameUsage[currentFrame] = currentFrameBytes;
    }

    /**
     * Get the mapped pointer + an offset. For writing data.
     */
    public long getWritePointer(long stagingOffset) {
        return mappedPointer + stagingOffset;
    }

    /**
     * Get current utilization ratio (0.0 - 1.0).
     */
    public float getUtilization() {
        return (float) writeOffset / sectionSize;
    }

    /**
     * Get peak usage across recent frames.
     */
    public long getPeakUsage() {
        long peak = 0;
        for (long usage : frameUsage) {
            peak = Math.max(peak, usage);
        }
        return peak;
    }

    /**
     * Check if the staging buffer should be resized based on usage patterns.
     * Returns recommended new section size, or 0 if current size is fine.
     */
    public long recommendResize() {
        long peak = getPeakUsage();
        if (peak > sectionSize * 0.9) {
            // Running out, double it
            return sectionSize * 2;
        }
        if (peak < sectionSize * 0.25 && sectionSize > 16 * 1024 * 1024) {
            // Wasting memory, halve it
            return sectionSize / 2;
        }
        return 0;
    }

    public long getSectionSize() { return sectionSize; }
    public int getFramesInFlight() { return framesInFlight; }
    public long getCurrentFrameBytes() { return currentFrameBytes; }
    public long getRemainingBytes() { return sectionSize - writeOffset; }
}
