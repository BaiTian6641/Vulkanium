package net.vulkanium.compute;

import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Queries and exposes GPU compute capabilities.
 *
 * <p>Different GPUs have wildly different compute characteristics.
 * This class probes the physical device at startup and provides structured
 * access so that the compute scheduler and built-in modules can adapt their
 * dispatch parameters accordingly.</p>
 *
 * <h3>Queried Properties</h3>
 * <ul>
 *   <li>Max workgroup size (x/y/z) and max invocations per workgroup</li>
 *   <li>Max workgroup count (x/y/z) — limits dispatch dimensions</li>
 *   <li>Max compute shared memory size</li>
 *   <li>Subgroup size and supported operations</li>
 *   <li>Dedicated compute queue availability</li>
 *   <li>Cooperative matrix support (for ML workloads)</li>
 *   <li>Int64/Float64 atomic support</li>
 * </ul>
 */
public class ComputeCapabilities {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ComputeCaps");

    // ── Workgroup limits ──
    private final int maxWorkGroupSizeX;
    private final int maxWorkGroupSizeY;
    private final int maxWorkGroupSizeZ;
    private final int maxWorkGroupInvocations;
    private final int maxWorkGroupCountX;
    private final int maxWorkGroupCountY;
    private final int maxWorkGroupCountZ;
    private final int maxComputeSharedMemorySize;

    // ── Subgroup ──
    private final int subgroupSize;
    private final int subgroupSupportedStages;
    private final int subgroupSupportedOperations;

    // ── Queue info ──
    private final boolean hasDedicatedComputeQueue;
    private final int computeQueueFamilyIndex;
    private final int computeQueueCount;

    // ── Feature flags ──
    private final boolean supportsInt64Atomics;
    private final boolean supportsFloat64;
    private final boolean supportsInt16;
    private final boolean supportsFloat16;
    private final boolean supportsStorageBuffer16Bit;
    private final boolean supportsCooperativeMatrix;

    // ── Tier ──
    private final ComputeTier tier;

    /**
     * Compute capability tier — determines which built-in modules are available.
     */
    public enum ComputeTier {
        /** No dedicated compute queue, basic compute only */
        BASIC("Basic", "Compute shaders on graphics queue"),
        /** Dedicated compute queue, full async compute */
        STANDARD("Standard", "Dedicated compute queue + async dispatch"),
        /** Large shared memory, subgroup ops, advanced features */
        ADVANCED("Advanced", "Subgroup ops + large shared memory + 16-bit types");

        private final String name;
        private final String description;

        ComputeTier(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
    }

    private ComputeCapabilities(VkPhysicalDevice physicalDevice,
                                 VkPhysicalDeviceProperties2 properties2,
                                 VkPhysicalDeviceFeatures2 features2,
                                 VkPhysicalDeviceSubgroupProperties subgroupProps,
                                 int computeQueueFamily, int computeQueueCount,
                                 boolean dedicatedCompute) {
        VkPhysicalDeviceLimits limits = properties2.properties().limits();

        this.maxWorkGroupSizeX = limits.maxComputeWorkGroupSize(0);
        this.maxWorkGroupSizeY = limits.maxComputeWorkGroupSize(1);
        this.maxWorkGroupSizeZ = limits.maxComputeWorkGroupSize(2);
        this.maxWorkGroupInvocations = limits.maxComputeWorkGroupInvocations();
        this.maxWorkGroupCountX = limits.maxComputeWorkGroupCount(0);
        this.maxWorkGroupCountY = limits.maxComputeWorkGroupCount(1);
        this.maxWorkGroupCountZ = limits.maxComputeWorkGroupCount(2);
        this.maxComputeSharedMemorySize = limits.maxComputeSharedMemorySize();

        this.subgroupSize = subgroupProps.subgroupSize();
        this.subgroupSupportedStages = subgroupProps.supportedStages();
        this.subgroupSupportedOperations = subgroupProps.supportedOperations();

        this.hasDedicatedComputeQueue = dedicatedCompute;
        this.computeQueueFamilyIndex = computeQueueFamily;
        this.computeQueueCount = computeQueueCount;

        // Feature queries (from VkPhysicalDeviceFeatures2 chain)
        VkPhysicalDeviceFeatures features = features2.features();
        this.supportsInt64Atomics = features.shaderInt64();
        this.supportsFloat64 = features.shaderFloat64();
        this.supportsInt16 = features.shaderInt16();
        this.supportsFloat16 = false; // Requires VkPhysicalDeviceFloat16Int8FeaturesKHR
        this.supportsStorageBuffer16Bit = false; // Requires VkPhysicalDevice16BitStorageFeatures
        this.supportsCooperativeMatrix = false; // Requires VK_KHR_cooperative_matrix

        this.tier = determineTier();
        logCapabilities();
    }

    /**
     * Probes compute capabilities from a physical device.
     */
    public static ComputeCapabilities probe(VkPhysicalDevice physicalDevice,
                                             int computeQueueFamily,
                                             int computeQueueCount,
                                             boolean dedicatedCompute) {
        VkPhysicalDeviceSubgroupProperties subgroupProps =
                VkPhysicalDeviceSubgroupProperties.calloc()
                        .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES);

        VkPhysicalDeviceProperties2 properties2 =
                VkPhysicalDeviceProperties2.calloc()
                        .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
                        .pNext(subgroupProps);

        VK11.vkGetPhysicalDeviceProperties2(physicalDevice, properties2);

        VkPhysicalDeviceFeatures2 features2 =
                VkPhysicalDeviceFeatures2.calloc()
                        .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2);

        VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, features2);

        ComputeCapabilities caps = new ComputeCapabilities(
                physicalDevice, properties2, features2, subgroupProps,
                computeQueueFamily, computeQueueCount, dedicatedCompute
        );

        properties2.free();
        features2.free();
        subgroupProps.free();

        return caps;
    }

    private ComputeTier determineTier() {
        if (hasDedicatedComputeQueue && subgroupSize >= 32
                && maxComputeSharedMemorySize >= 49152) {
            return ComputeTier.ADVANCED;
        } else if (hasDedicatedComputeQueue) {
            return ComputeTier.STANDARD;
        }
        return ComputeTier.BASIC;
    }

    private void logCapabilities() {
        LOGGER.info("Compute capabilities: tier={}, workgroup=[{},{},{}] max={}, shared={}KB, subgroup={}",
                tier.name,
                maxWorkGroupSizeX, maxWorkGroupSizeY, maxWorkGroupSizeZ,
                maxWorkGroupInvocations,
                maxComputeSharedMemorySize / 1024, subgroupSize);
        if (hasDedicatedComputeQueue) {
            LOGGER.info("  Dedicated compute queue: family={} count={}", computeQueueFamilyIndex, computeQueueCount);
        }
    }

    // ── Accessors ──

    public ComputeTier getTier() { return tier; }
    public int getMaxWorkGroupSizeX() { return maxWorkGroupSizeX; }
    public int getMaxWorkGroupSizeY() { return maxWorkGroupSizeY; }
    public int getMaxWorkGroupSizeZ() { return maxWorkGroupSizeZ; }
    public int getMaxWorkGroupInvocations() { return maxWorkGroupInvocations; }
    public int getMaxWorkGroupCountX() { return maxWorkGroupCountX; }
    public int getMaxWorkGroupCountY() { return maxWorkGroupCountY; }
    public int getMaxWorkGroupCountZ() { return maxWorkGroupCountZ; }
    public int getMaxComputeSharedMemorySize() { return maxComputeSharedMemorySize; }
    public int getSubgroupSize() { return subgroupSize; }
    public boolean hasDedicatedComputeQueue() { return hasDedicatedComputeQueue; }
    public int getComputeQueueFamilyIndex() { return computeQueueFamilyIndex; }
    public boolean supportsInt64Atomics() { return supportsInt64Atomics; }
    public boolean supportsFloat16() { return supportsFloat16; }
    public boolean supportsCooperativeMatrix() { return supportsCooperativeMatrix; }

    /**
     * Returns the optimal workgroup size for 1D dispatches on this GPU.
     * Typically matches subgroup size or a multiple of it.
     */
    public int getOptimal1DWorkGroupSize() {
        return Math.min(256, maxWorkGroupInvocations);
    }

    /**
     * Computes the number of workgroups needed for a 1D dispatch.
     */
    public int getDispatchCount1D(int totalInvocations, int workGroupSize) {
        return (totalInvocations + workGroupSize - 1) / workGroupSize;
    }
}
