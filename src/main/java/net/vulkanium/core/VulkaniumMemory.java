package net.vulkanium.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan Memory Allocator (VMA) wrapper and memory management system.
 *
 * <p>Improvements over VulkanMod's {@code MemoryManager}:</p>
 * <ul>
 *   <li>Clean VMA allocator lifecycle (create/destroy)</li>
 *   <li>Deferred destruction with per-frame free lists (N frames in flight)</li>
 *   <li>Typed allocation helpers: device-local, host-visible, staging</li>
 *   <li>Budget tracking with VMA heap statistics</li>
 *   <li>Image allocation factored out from scattered static methods</li>
 * </ul>
 */
public class VulkaniumMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Memory");
    private static final long BYTES_PER_MB = 1024 * 1024;

    /** Memory type constant for device-local (GPU-only) memory */
    public static final int DEVICE_LOCAL = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    /** Memory type constant for host-visible (CPU-accessible) memory */
    public static final int HOST_VISIBLE = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

    private long allocator;
    private VulkaniumDevice vulkaniumDevice;
    private int framesInFlight;
    private int currentFrame;

    // Per-frame deferred free lists
    private List<DeferredBuffer>[] pendingBufferFrees;
    private List<DeferredImage>[] pendingImageFrees;
    private List<Runnable>[] pendingFrameOps;

    // Tracking
    private long allocatedDeviceLocal;
    private long allocatedHostVisible;

    /**
     * Create the VMA allocator and set up per-frame free lists.
     */
    @SuppressWarnings("unchecked")
    public void initialize(VulkaniumInstance instance, VulkaniumDevice device, int framesInFlight) {
        this.vulkaniumDevice = device;
        this.framesInFlight = framesInFlight;
        this.currentFrame = 0;

        try (MemoryStack stack = stackPush()) {
            // Enable VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT if RT extensions are enabled
            // (required for any buffer with VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
            int vmaFlags = 0;
            if (device.isRTExtensionsEnabled()) {
                vmaFlags |= VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT;
                LOGGER.info("VMA: enabling BUFFER_DEVICE_ADDRESS support for RT");
            }

            VmaAllocatorCreateInfo createInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .instance(instance.getInstance())
                    .physicalDevice(device.getPhysicalDevice())
                    .device(device.getLogicalDevice())
                    .flags(vmaFlags)
                    .pVulkanFunctions(VmaVulkanFunctions.calloc(stack).set(instance.getInstance(), device.getLogicalDevice()));

            PointerBuffer pAllocator = stack.mallocPointer(1);
            int result = vmaCreateAllocator(createInfo, pAllocator);
            checkResult(result, "Failed to create VMA allocator");

            this.allocator = pAllocator.get(0);
        }

        // Initialize per-frame lists
        pendingBufferFrees = new List[framesInFlight];
        pendingImageFrees = new List[framesInFlight];
        pendingFrameOps = new List[framesInFlight];
        for (int i = 0; i < framesInFlight; i++) {
            pendingBufferFrees[i] = new ArrayList<>();
            pendingImageFrees[i] = new ArrayList<>();
            pendingFrameOps[i] = new ArrayList<>();
        }

        LOGGER.info("VMA allocator created (frames-in-flight: {})", framesInFlight);
        logMemoryBudget();
    }

    /**
     * Called at the start of each frame to process deferred frees.
     */
    public synchronized void beginFrame(int frameIndex) {
        this.currentFrame = frameIndex;
        processDeferredFrees(frameIndex);
        processFrameOps(frameIndex);
    }

    // ========== Buffer Allocation ==========

    /**
     * Allocates a Vulkan buffer via VMA.
     *
     * @param size       Buffer size in bytes
     * @param usage      VkBufferUsageFlags (e.g. VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
     * @param memFlags   VkMemoryPropertyFlags (e.g. VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
     * @return Allocation result containing buffer handle and VMA allocation
     */
    public BufferAllocation createBuffer(long size, int usage, int memFlags) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .requiredFlags(memFlags);

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);

            int result = vmaCreateBuffer(allocator, bufferInfo, allocInfo, pBuffer, pAllocation, null);
            checkResult(result, "Failed to create buffer (size: %d bytes)".formatted(size));

            long buffer = pBuffer.get(0);
            long allocation = pAllocation.get(0);

            // Track
            if ((memFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                allocatedDeviceLocal += size;
            } else {
                allocatedHostVisible += size;
            }

            return new BufferAllocation(buffer, allocation, size, memFlags);
        }
    }

    /**
     * Creates a device-local buffer (GPU VRAM). Not host-mappable.
     */
    public BufferAllocation createDeviceLocalBuffer(long size, int usage) {
        return createBuffer(size,
                usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    }

    /**
     * Creates a host-visible, host-coherent buffer (CPU-mappable, for uniforms/staging).
     */
    public BufferAllocation createHostVisibleBuffer(long size, int usage) {
        return createBuffer(size, usage,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }

    /**
     * Creates a staging buffer for CPU→GPU uploads.
     */
    public BufferAllocation createStagingBuffer(long size) {
        return createHostVisibleBuffer(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    }

    // ========== Image Allocation ==========

    /**
     * Allocates a Vulkan image via VMA.
     */
    public ImageAllocation createImage(int width, int height, int mipLevels, int format,
                                       int tiling, int usage, int memFlags) {
        return createImage(width, height, mipLevels, 1, format, tiling, usage, memFlags, VK_SAMPLE_COUNT_1_BIT);
    }

    /**
     * Allocates a Vulkan image with full parameters.
     */
    public ImageAllocation createImage(int width, int height, int mipLevels, int arrayLayers,
                                       int format, int tiling, int usage, int memFlags, int samples) {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(mipLevels)
                    .arrayLayers(arrayLayers)
                    .samples(samples)
                    .tiling(tiling)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            imageInfo.extent()
                    .width(width)
                    .height(height)
                    .depth(1);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .requiredFlags(memFlags);

            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);

            int result = vmaCreateImage(allocator, imageInfo, allocInfo, pImage, pAllocation, null);
            checkResult(result, "Failed to create image (%dx%d, format %d)".formatted(width, height, format));

            return new ImageAllocation(pImage.get(0), pAllocation.get(0), width, height, format, mipLevels);
        }
    }

    // ========== Memory Mapping ==========

    /**
     * Maps a VMA allocation and returns a pointer. Caller must call {@link #unmap(long)}.
     */
    public long map(long allocation) {
        PointerBuffer pData = MemoryUtil.memAllocPointer(1);
        int result = vmaMapMemory(allocator, allocation, pData);
        checkResult(result, "Failed to map memory");
        long ptr = pData.get(0);
        MemoryUtil.memFree(pData);
        return ptr;
    }

    /**
     * Unmaps a previously mapped VMA allocation.
     */
    public void unmap(long allocation) {
        vmaUnmapMemory(allocator, allocation);
    }

    /**
     * Maps, copies data, and unmaps in one operation.
     */
    public void mapAndCopy(long allocation, long srcPtr, long size) {
        long dstPtr = map(allocation);
        MemoryUtil.memCopy(srcPtr, dstPtr, size);
        unmap(allocation);
    }

    // ========== Deferred Destruction ==========

    /**
     * Schedules a buffer for destruction after all frames-in-flight have completed.
     */
    public synchronized void deferFreeBuffer(BufferAllocation buffer) {
        pendingBufferFrees[currentFrame].add(new DeferredBuffer(buffer.buffer(), buffer.allocation(), buffer.size(), buffer.memFlags()));
    }

    /**
     * Immediately destroys a buffer. Use when you know it's safe (e.g. after device idle).
     */
    public void freeBufferImmediate(BufferAllocation buffer) {
        freeBufferImmediate(buffer.buffer(), buffer.allocation(), buffer.size(), buffer.memFlags());
    }

    private void freeBufferImmediate(long buffer, long allocation, long size, int memFlags) {
        vmaDestroyBuffer(allocator, buffer, allocation);
        if ((memFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
            allocatedDeviceLocal -= size;
        } else {
            allocatedHostVisible -= size;
        }
    }

    /**
     * Schedules an image for destruction after all frames-in-flight have completed.
     */
    public synchronized void deferFreeImage(ImageAllocation image) {
        pendingImageFrees[currentFrame].add(new DeferredImage(image.image(), image.allocation()));
    }

    /**
     * Immediately destroys an image.
     */
    public void freeImageImmediate(ImageAllocation image) {
        vmaDestroyImage(allocator, image.image(), image.allocation());
    }

    /**
     * Schedules an arbitrary operation to run when this frame index comes around again.
     */
    public synchronized void deferFrameOp(Runnable op) {
        pendingFrameOps[currentFrame].add(op);
    }

    private void processDeferredFrees(int frame) {
        List<DeferredBuffer> buffers = pendingBufferFrees[frame];
        for (DeferredBuffer db : buffers) {
            freeBufferImmediate(db.buffer, db.allocation, db.size, db.memFlags);
        }
        buffers.clear();

        List<DeferredImage> images = pendingImageFrees[frame];
        for (DeferredImage di : images) {
            vmaDestroyImage(allocator, di.image, di.allocation);
        }
        images.clear();
    }

    private void processFrameOps(int frame) {
        for (Runnable op : pendingFrameOps[frame]) {
            op.run();
        }
        pendingFrameOps[frame].clear();
    }

    // ========== Statistics ==========

    public long getAllocatedDeviceLocalMB() { return allocatedDeviceLocal / BYTES_PER_MB; }
    public long getAllocatedHostVisibleMB() { return allocatedHostVisible / BYTES_PER_MB; }

    public void logMemoryBudget() {
        try (MemoryStack stack = stackPush()) {
            int heapCount = vulkaniumDevice.getMemoryProperties().memoryHeapCount();
            VmaBudget.Buffer budgets = VmaBudget.calloc(heapCount, stack);
            vmaGetHeapBudgets(allocator, budgets);

            for (int i = 0; i < heapCount; i++) {
                VmaBudget budget = budgets.get(i);
                if (budget.budget() > 0) {
                    LOGGER.info("  Heap {}: usage {}/{} MB",
                            i,
                            budget.usage() / BYTES_PER_MB,
                            budget.budget() / BYTES_PER_MB);
                }
            }
        }
    }

    /**
     * Returns formatted heap stats string for debug display.
     */
    public String getHeapStatsString() {
        try (MemoryStack stack = stackPush()) {
            int heapCount = vulkaniumDevice.getMemoryProperties().memoryHeapCount();
            VmaBudget.Buffer budgets = VmaBudget.calloc(heapCount, stack);
            vmaGetHeapBudgets(allocator, budgets);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < heapCount; i++) {
                VmaBudget budget = budgets.get(i);
                if (budget.budget() > 0) {
                    sb.append("Heap %d: %d/%d MB  ".formatted(
                            i, budget.usage() / BYTES_PER_MB, budget.budget() / BYTES_PER_MB));
                }
            }
            return sb.toString().trim();
        }
    }

    // ========== Convenience Methods (RT compatibility) ==========

    /** Tracked images for createImage2D/destroyImage convenience methods */
    private final Map<Long, ImageAllocation> imageTracker = new ConcurrentHashMap<>();

    /**
     * Allocates a buffer and returns [bufferHandle, allocationHandle].
     * Convenience wrapper for RT module compatibility.
     */
    public long[] allocateBuffer(long size, int usage, int memFlags) {
        BufferAllocation alloc = createBuffer(size, usage, memFlags);
        return new long[]{alloc.buffer(), alloc.allocation()};
    }

    /**
     * Frees a buffer by its handle and allocation.
     * Convenience wrapper for RT module compatibility.
     */
    public void freeBuffer(long buffer, long allocation) {
        vmaDestroyBuffer(allocator, buffer, allocation);
    }

    /**
     * Defers a buffer free to the end of the current frame-in-flight cycle.
     * Safe to call while the GPU may still be using the buffer.
     */
    public void deferBufferFree(long buffer, long allocation) {
        if (pendingBufferFrees != null && pendingBufferFrees[currentFrame] != null) {
            long size = 0; // size unknown from handles alone
            pendingBufferFrees[currentFrame].add(new DeferredBuffer(buffer, allocation, size, 0));
        } else {
            // Fallback: immediate free
            freeBuffer(buffer, allocation);
        }
    }

    /**
     * Maps a buffer allocation and returns a ByteBuffer wrapping the mapped pointer.
     * The caller must call {@link #unmapBuffer(long)} when done.
     *
     * @param allocation VMA allocation handle
     * @return A ByteBuffer wrapping the mapped memory, or null on failure
     */
    public java.nio.ByteBuffer mapBuffer(long allocation) {
        long ptr = map(allocation);
        if (ptr == 0) return null;
        // We don't know the exact size, so use a reasonable max (1 GB)
        // The caller should know the actual buffer size and limit reads/writes
        return org.lwjgl.system.MemoryUtil.memByteBuffer(ptr, (int) Math.min(getAllocationSize(allocation), Integer.MAX_VALUE));
    }

    /**
     * Unmaps a buffer allocation previously mapped with {@link #mapBuffer(long)}.
     */
    public void unmapBuffer(long allocation) {
        unmap(allocation);
    }

    /**
     * Returns the size of a VMA allocation, or a default if unavailable.
     */
    private long getAllocationSize(long allocation) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.util.vma.VmaAllocationInfo info = org.lwjgl.util.vma.VmaAllocationInfo.calloc(stack);
            org.lwjgl.util.vma.Vma.vmaGetAllocationInfo(allocator, allocation, info);
            return info.size();
        }
    }

    /**
     * Creates a 2D device-local image and tracks it for later destruction.
     * Convenience wrapper for RT module compatibility.
     *
     * @return The VkImage handle
     */
    public long createImage2D(int width, int height, int format, int usage) {
        ImageAllocation alloc = createImage(width, height, 1, format,
                VK_IMAGE_TILING_OPTIMAL, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        imageTracker.put(alloc.image(), alloc);
        return alloc.image();
    }

    /**
     * Destroys an image previously created via {@link #createImage2D}.
     */
    public void destroyImage(long imageHandle) {
        ImageAllocation alloc = imageTracker.remove(imageHandle);
        if (alloc != null) {
            freeImageImmediate(alloc);
        }
    }

    // ========== Cleanup ==========

    /**
     * Flushes all deferred frees and destroys the VMA allocator.
     */
    public void destroy() {
        if (allocator == 0) return;

        // Flush all pending frees
        for (int i = 0; i < framesInFlight; i++) {
            processDeferredFrees(i);
            processFrameOps(i);
        }

        LOGGER.info("Destroying VMA allocator (device-local: {} MB, host-visible: {} MB)",
                allocatedDeviceLocal / BYTES_PER_MB, allocatedHostVisible / BYTES_PER_MB);

        vmaDestroyAllocator(allocator);
        allocator = 0;
    }

    // ========== Getters ==========

    public long getAllocator() { return allocator; }

    // ========== Records ==========

    public record BufferAllocation(long buffer, long allocation, long size, int memFlags) {}

    public record ImageAllocation(long image, long allocation, int width, int height, int format, int mipLevels) {}

    private record DeferredBuffer(long buffer, long allocation, long size, int memFlags) {}
    private record DeferredImage(long image, long allocation) {}

    // ========== MemoryLocation Enum + Convenience Methods for ComputeAllocator ==========

    /**
     * Memory location hint for buffer allocation.
     */
    public enum MemoryLocation {
        GPU_ONLY(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT),
        CPU_TO_GPU(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT),
        GPU_TO_CPU(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);

        public final int memFlags;
        MemoryLocation(int memFlags) { this.memFlags = memFlags; }
    }

    /** Tracked compute buffer allocations: buffer handle → allocation handle */
    private final Map<Long, BufferAllocation> computeBufferTracker = new ConcurrentHashMap<>();

    /**
     * Creates a buffer using a MemoryLocation hint and returns the buffer handle.
     * Used by ComputeAllocator.
     */
    public long createBuffer(long size, int usage, MemoryLocation location) {
        BufferAllocation alloc = createBuffer(size, usage, location.memFlags);
        computeBufferTracker.put(alloc.buffer(), alloc);
        return alloc.buffer();
    }

    /** Maps a compute buffer by its handle. Returns raw pointer. */
    public long mapComputeBuffer(long bufferHandle) {
        BufferAllocation alloc = computeBufferTracker.get(bufferHandle);
        if (alloc == null) throw new IllegalStateException("Unknown buffer handle for mapping");
        return map(alloc.allocation());
    }

    /** Unmaps a compute buffer by its handle. */
    public void unmapComputeBuffer(long bufferHandle) {
        BufferAllocation alloc = computeBufferTracker.get(bufferHandle);
        if (alloc != null) unmap(alloc.allocation());
    }

    /** Destroys a buffer by its handle. */
    public void destroyBuffer(long bufferHandle) {
        BufferAllocation alloc = computeBufferTracker.remove(bufferHandle);
        if (alloc != null) {
            vmaDestroyBuffer(allocator, alloc.buffer(), alloc.allocation());
        }
    }

    /** Copies data from a ByteBuffer to a mapped pointer. */
    public void copyToMapped(long dstPtr, java.nio.ByteBuffer src) {
        org.lwjgl.system.MemoryUtil.memCopy(
                org.lwjgl.system.MemoryUtil.memAddress(src) + src.position(),
                dstPtr,
                src.remaining());
    }

    /** Copies data from a mapped pointer to a ByteBuffer. */
    public void copyFromMapped(long srcPtr, java.nio.ByteBuffer dst, long size) {
        int copyLen = (int) Math.min(size, dst.remaining());
        org.lwjgl.system.MemoryUtil.memCopy(srcPtr,
                org.lwjgl.system.MemoryUtil.memAddress(dst) + dst.position(),
                copyLen);
        dst.position(dst.position() + copyLen);
    }
}
