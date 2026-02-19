package net.vulkanium.vulkan.memory;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory allocator and tracker for Vulkanium.
 *
 * <p>Wraps VMA (Vulkan Memory Allocator) and provides:</p>
 * <ul>
 *   <li>Budget tracking per memory heap</li>
 *   <li>Allocation statistics (count, total bytes, peak)</li>
 *   <li>Leak detection (debug mode)</li>
 *   <li>Defragmentation scheduling</li>
 * </ul>
 *
 * <p>Corresponds to VulkanMod's MemoryManager and Sodium's buffer management.</p>
 */
public class MemoryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Memory");

    private static MemoryManager instance;

    /** VMA allocator handle */
    private long vmaAllocator;

    /** Track all live allocations for leak detection */
    private final Long2ObjectOpenHashMap<AllocationInfo> allocations = new Long2ObjectOpenHashMap<>();

    /** Per-heap statistics */
    private final List<HeapStats> heapStats = new ArrayList<>();

    /** Total allocated bytes (across all heaps) */
    private long totalAllocated;
    private long peakAllocated;
    private int allocationCount;

    /** Whether to track individual allocations (debug mode) */
    private boolean trackingEnabled;

    private MemoryManager() {}

    public static MemoryManager getInstance() {
        if (instance == null) {
            instance = new MemoryManager();
        }
        return instance;
    }

    /**
     * Initialize VMA.
     *
     * @param vkInstance     VkInstance handle
     * @param physicalDevice VkPhysicalDevice handle
     * @param device         VkDevice handle
     * @param vulkanApiVersion Vulkan API version (VK_API_VERSION_1_2)
     */
    public void init(long vkInstance, long physicalDevice, long device, int vulkanApiVersion) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var inst = new org.lwjgl.vulkan.VkInstance(vkInstance, null);
            var physDev = new org.lwjgl.vulkan.VkPhysicalDevice(physicalDevice, inst);
            var dev = new org.lwjgl.vulkan.VkDevice(device, physDev, null, vulkanApiVersion);

            var vmaFunctions = org.lwjgl.util.vma.VmaVulkanFunctions.calloc(stack)
                    .set(inst, dev);

            var createInfo = org.lwjgl.util.vma.VmaAllocatorCreateInfo.calloc(stack)
                    .instance(inst)
                    .physicalDevice(physDev)
                    .device(dev)
                    .pVulkanFunctions(vmaFunctions)
                    .vulkanApiVersion(vulkanApiVersion);

            var pAllocator = stack.mallocPointer(1);
            int result = org.lwjgl.util.vma.Vma.vmaCreateAllocator(createInfo, pAllocator);
            if (result != 0) {
                throw new RuntimeException("Failed to create VMA allocator: VkResult " + result);
            }
            this.vmaAllocator = pAllocator.get(0);

            // Initialize VulkanBuffer shared state
            VulkanBuffer.initShared(vmaAllocator, device);
        }

        LOGGER.info("VMA allocator initialized (standalone MemoryManager)");
    }

    /**
     * Track an allocation. Called by VulkanBuffer.create().
     */
    public void trackAllocation(long allocationHandle, long size, String debugName) {
        totalAllocated += size;
        allocationCount++;
        peakAllocated = Math.max(peakAllocated, totalAllocated);

        if (trackingEnabled) {
            allocations.put(allocationHandle, new AllocationInfo(size, debugName,
                Thread.currentThread().getStackTrace()));
        }
    }

    /**
     * Untrack an allocation. Called by VulkanBuffer.destroy().
     */
    public void untrackAllocation(long allocationHandle, long size) {
        totalAllocated -= size;
        allocationCount--;
        if (trackingEnabled) {
            allocations.remove(allocationHandle);
        }
    }

    /**
     * Check for memory leaks. Call at shutdown.
     */
    public void checkLeaks() {
        if (!trackingEnabled) return;
        if (!allocations.isEmpty()) {
            LOGGER.error("MEMORY LEAK: {} allocations still alive!", allocations.size());
            for (var entry : allocations.long2ObjectEntrySet()) {
                AllocationInfo info = entry.getValue();
                LOGGER.error("  Leaked: '{}' ({} bytes)", info.debugName, info.size);
                if (info.stackTrace != null) {
                    for (int i = 2; i < Math.min(8, info.stackTrace.length); i++) {
                        LOGGER.error("    at {}", info.stackTrace[i]);
                    }
                }
            }
        } else {
            LOGGER.info("No memory leaks detected");
        }
    }

    /**
     * Get VMA allocation statistics summary.
     */
    public MemoryStats getStats() {
        return new MemoryStats(totalAllocated, peakAllocated, allocationCount);
    }

    /**
     * Get budget information for a specific memory heap.
     */
    public HeapBudget getHeapBudget(int heapIndex) {
        if (vmaAllocator == 0) return new HeapBudget(0, 0);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            // VMA reports budgets for all heaps; we query the max heap count (16)
            var budgets = org.lwjgl.util.vma.VmaBudget.calloc(16, stack);
            org.lwjgl.util.vma.Vma.vmaGetHeapBudgets(vmaAllocator, budgets);
            if (heapIndex < 16) {
                var b = budgets.get(heapIndex);
                return new HeapBudget(b.usage(), b.budget());
            }
        }
        return new HeapBudget(0, 0);
    }

    /**
     * Request VMA defragmentation pass.
     */
    public void defragment() {
        if (vmaAllocator == 0) return;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var defragInfo = org.lwjgl.util.vma.VmaDefragmentationInfo.calloc(stack);
            var pCtx = stack.mallocPointer(1);
            org.lwjgl.util.vma.Vma.vmaBeginDefragmentation(vmaAllocator, defragInfo, pCtx);
            long ctx = pCtx.get(0);
            // Process defragmentation passes
            var passInfo = org.lwjgl.util.vma.VmaDefragmentationPassMoveInfo.calloc(stack);
            while (org.lwjgl.util.vma.Vma.vmaBeginDefragmentationPass(vmaAllocator, ctx, passInfo)
                    == org.lwjgl.vulkan.VK10.VK_INCOMPLETE) {
                org.lwjgl.util.vma.Vma.vmaEndDefragmentationPass(vmaAllocator, ctx, passInfo);
            }
            var stats = org.lwjgl.util.vma.VmaDefragmentationStats.calloc(stack);
            org.lwjgl.util.vma.Vma.vmaEndDefragmentation(vmaAllocator, ctx, stats);
            LOGGER.info("Defragmentation: moved {} allocs, freed {} bytes",
                    stats.allocationsMoved(), stats.bytesFreed());
        }
    }

    public void destroy() {
        checkLeaks();
        if (vmaAllocator != 0) {
            org.lwjgl.util.vma.Vma.vmaDestroyAllocator(vmaAllocator);
            vmaAllocator = 0;
        }
        LOGGER.info("VMA allocator destroyed");
    }

    public long getVmaAllocator() { return vmaAllocator; }
    public long getTotalAllocated() { return totalAllocated; }
    public long getPeakAllocated() { return peakAllocated; }
    public int getAllocationCount() { return allocationCount; }
    public void setTrackingEnabled(boolean enabled) { this.trackingEnabled = enabled; }

    public static void shutdown() {
        if (instance != null) {
            instance.destroy();
            instance = null;
        }
    }

    // ─── Inner types ───────────────────────────────────────────────────

    public record MemoryStats(long totalBytes, long peakBytes, int count) {}
    public record HeapBudget(long usage, long budget) {
        public float utilization() {
            return budget > 0 ? (float) usage / budget : 0;
        }
    }

    private record AllocationInfo(long size, String debugName, StackTraceElement[] stackTrace) {}

    private static class HeapStats {
        final long heapSize;
        final int flags; // VkMemoryHeapFlags
        long used;

        HeapStats(long heapSize, int flags) {
            this.heapSize = heapSize;
            this.flags = flags;
        }
    }
}
