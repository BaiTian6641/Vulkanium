package net.vulkanium.rt;

import net.vulkanium.core.VulkaniumDevice;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Probes and exposes hardware ray-tracing capabilities.
 *
 * <p>Not all GPUs support ray tracing, and those that do vary in performance
 * characteristics. This class uses the RT extension/property data already
 * queried by {@link VulkaniumDevice} during device initialization, then
 * classifies into capability tiers.</p>
 *
 * <h3>Required Extensions (enabled by VulkaniumDevice)</h3>
 * <ul>
 *   <li>{@code VK_KHR_acceleration_structure} — BLAS/TLAS management</li>
 *   <li>{@code VK_KHR_ray_tracing_pipeline} — RT shader stages</li>
 *   <li>{@code VK_KHR_spirv_1_4} — Extended SPIR-V for RT shaders</li>
 *   <li>{@code VK_KHR_deferred_host_operations} — Async BLAS builds</li>
 *   <li>{@code VK_KHR_buffer_device_address} — GPU pointers for AS</li>
 * </ul>
 *
 * <h3>Capability Tiers</h3>
 * <table>
 *   <tr><th>Tier</th><th>Hardware</th><th>Features</th></tr>
 *   <tr><td>0</td><td>No RT extensions</td><td>Rasterization only</td></tr>
 *   <tr><td>1</td><td>RT + 4GB VRAM</td><td>RT shadows only (1 bounce)</td></tr>
 *   <tr><td>2</td><td>RT + 8GB VRAM</td><td>RT shadows + reflections (2 bounces)</td></tr>
 *   <tr><td>3</td><td>RT + 12GB+ VRAM</td><td>Full path tracing (4+ bounces) + SVGF</td></tr>
 * </table>
 *
 * <h3>Minimum Hardware</h3>
 * <p>NVIDIA RTX 2060 / AMD RX 6600 / Intel Arc A750</p>
 */
public class RTCapabilities {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RTCaps");

    /**
     * RT capability tiers.
     */
    public enum Tier {
        /** No RT support — rasterization only */
        NONE(0, "No RT", "Standard rasterization pipeline"),
        /** Basic RT — shadows only, 1 bounce */
        BASIC(1, "RT Shadows", "Hardware RT shadows (1 bounce)"),
        /** Standard RT — shadows + reflections, 2 bounces */
        STANDARD(2, "RT Reflections", "RT shadows + reflections (2 bounces)"),
        /** Full RT — path tracing, 4+ bounces + SVGF denoising */
        FULL(3, "Path Tracing", "Full path tracing (4+ bounces) + SVGF");

        private final int level;
        private final String name;
        private final String description;

        Tier(int level, String name, String description) {
            this.level = level;
            this.name = name;
            this.description = description;
        }

        public int getLevel() { return level; }
        public String getName() { return name; }
        public String getDescription() { return description; }
    }

    // ── Extension support ──
    private final boolean hasAccelerationStructure;
    private final boolean hasRayTracingPipeline;
    private final boolean hasRayQuery;
    private final boolean hasSPIRV14;
    private final boolean hasDeferredHostOps;

    // ── RT pipeline properties ──
    private final int shaderGroupHandleSize;
    private final int shaderGroupHandleAlignment;
    private final int shaderGroupBaseAlignment;
    private final int maxRayRecursionDepth;
    private final int maxRayDispatchInvocationCount;

    // ── Acceleration structure properties ──
    private final long maxInstanceCount;
    private final long maxPrimitiveCount;
    private final long maxGeometryCount;
    private final boolean supportsUpdate; // AS updates (vs full rebuild)

    // ── Memory ──
    private final long deviceMemoryBytes;

    // ── Tier ──
    private final Tier tier;

    private RTCapabilities(boolean hasAS, boolean hasRTPipeline, boolean hasRQ,
                            boolean hasSPIRV, boolean hasDeferredOps,
                            int handleSize, int handleAlign, int baseAlign,
                            int maxRecursion, int maxDispatch,
                            long maxInstances, long maxPrimitives, long maxGeometries,
                            boolean supportsUpdate, long deviceMemory) {
        this.hasAccelerationStructure = hasAS;
        this.hasRayTracingPipeline = hasRTPipeline;
        this.hasRayQuery = hasRQ;
        this.hasSPIRV14 = hasSPIRV;
        this.hasDeferredHostOps = hasDeferredOps;

        this.shaderGroupHandleSize = handleSize;
        this.shaderGroupHandleAlignment = handleAlign;
        this.shaderGroupBaseAlignment = baseAlign;
        this.maxRayRecursionDepth = maxRecursion;
        this.maxRayDispatchInvocationCount = maxDispatch;

        this.maxInstanceCount = maxInstances;
        this.maxPrimitiveCount = maxPrimitives;
        this.maxGeometryCount = maxGeometries;
        this.supportsUpdate = supportsUpdate;
        this.deviceMemoryBytes = deviceMemory;

        this.tier = determineTier();
    }

    /**
     * Builds RT capabilities from a VulkaniumDevice that has already probed
     * RT extensions and queried properties during device initialization.
     *
     * @param device The initialized VulkaniumDevice with RT extension info
     */
    public static RTCapabilities probe(VulkaniumDevice device) {
        boolean hasAS = device.hasAccelerationStructure();
        boolean hasRTPipeline = device.hasRayTracingPipeline();
        boolean rtEnabled = device.isRTExtensionsEnabled();

        if (!rtEnabled || !hasAS || !hasRTPipeline) {
            LOGGER.info("RT extensions not enabled — Tier 0 (rasterization only)");
            return noRT();
        }

        // Use real properties queried from the physical device
        int handleSize = device.getRTShaderGroupHandleSize();
        int handleAlignment = device.getRTShaderGroupHandleAlignment();
        int baseAlignment = device.getRTShaderGroupBaseAlignment();
        int maxRecursion = device.getRTMaxRayRecursionDepth();
        int maxDispatch = device.getRTMaxRayDispatchInvocationCount();

        long maxInstances = device.getRTMaxInstanceCount();
        long maxPrimitives = device.getRTMaxPrimitiveCount();
        long maxGeometries = device.getRTMaxGeometryCount();
        boolean supportsUpdate = true; // All current RT GPUs support AS updates

        // Query device memory from existing device info
        long totalDeviceMemory = device.getDeviceInfo().vramMB() * 1024L * 1024L;

        RTCapabilities caps = new RTCapabilities(
                hasAS, hasRTPipeline, device.hasRayQuery(),
                device.hasSPIRV14(), device.hasDeferredHostOps(),
                handleSize, handleAlignment, baseAlignment, maxRecursion, maxDispatch,
                maxInstances, maxPrimitives, maxGeometries, supportsUpdate,
                totalDeviceMemory
        );

        LOGGER.info("RT capabilities: tier={}, VRAM={}GB, maxRecursion={}, handleSize={}",
                caps.tier.name, totalDeviceMemory / (1024L * 1024 * 1024),
                maxRecursion, handleSize);

        return caps;
    }

    /**
     * Legacy probe method — delegates to VulkaniumDevice-based probe.
     * @deprecated Use {@link #probe(VulkaniumDevice)} instead.
     */
    @Deprecated
    public static RTCapabilities probe(VkPhysicalDevice physicalDevice,
                                        boolean hasASExtension,
                                        boolean hasRTPipelineExtension,
                                        boolean hasRayQueryExtension,
                                        boolean hasSPIRV14Extension,
                                        boolean hasDeferredOpsExtension) {
        if (!hasASExtension || !hasRTPipelineExtension) {
            LOGGER.info("RT extensions not available — Tier 0 (rasterization only)");
            return noRT();
        }

        // Fallback: use reasonable defaults (real path goes through VulkaniumDevice)
        int handleSize = 32;
        int handleAlignment = 32;
        int baseAlignment = 64;
        int maxRecursion = 31;
        int maxDispatch = 1 << 30;

        long maxInstances = 1 << 24;
        long maxPrimitives = 1L << 29;
        long maxGeometries = 1 << 24;

        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc();
        VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);
        long totalDeviceMemory = 0;
        for (int i = 0; i < memProps.memoryHeapCount(); i++) {
            if ((memProps.memoryHeaps(i).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                totalDeviceMemory += memProps.memoryHeaps(i).size();
            }
        }
        memProps.free();

        return new RTCapabilities(
                hasASExtension, hasRTPipelineExtension, hasRayQueryExtension,
                hasSPIRV14Extension, hasDeferredOpsExtension,
                handleSize, handleAlignment, baseAlignment, maxRecursion, maxDispatch,
                maxInstances, maxPrimitives, maxGeometries, true,
                totalDeviceMemory
        );
    }

    private static RTCapabilities noRT() {
        return new RTCapabilities(
                false, false, false, false, false,
                0, 0, 0, 0, 0,
                0, 0, 0, false, 0
        );
    }

    private Tier determineTier() {
        if (!hasAccelerationStructure || !hasRayTracingPipeline) {
            return Tier.NONE;
        }

        long vramGB = deviceMemoryBytes / (1024L * 1024 * 1024);

        if (vramGB >= 12) return Tier.FULL;
        if (vramGB >= 8) return Tier.STANDARD;
        if (vramGB >= 4) return Tier.BASIC;

        return Tier.BASIC; // Has extensions but low VRAM
    }

    // ── Accessors ──

    public Tier getTier() { return tier; }
    public boolean isRTAvailable() { return tier != Tier.NONE; }
    public boolean hasAccelerationStructure() { return hasAccelerationStructure; }
    public boolean hasRayTracingPipeline() { return hasRayTracingPipeline; }
    public boolean hasRayQuery() { return hasRayQuery; }

    public int getShaderGroupHandleSize() { return shaderGroupHandleSize; }
    public int getShaderGroupHandleAlignment() { return shaderGroupHandleAlignment; }
    public int getShaderGroupBaseAlignment() { return shaderGroupBaseAlignment; }
    public int getMaxRayRecursionDepth() { return maxRayRecursionDepth; }

    public long getMaxInstanceCount() { return maxInstanceCount; }
    public long getMaxPrimitiveCount() { return maxPrimitiveCount; }
    public boolean supportsAccelerationStructureUpdate() { return supportsUpdate; }

    public long getDeviceMemoryBytes() { return deviceMemoryBytes; }

    /**
     * Returns the recommended max bounce count for this tier.
     */
    public int getRecommendedMaxBounces() {
        return switch (tier) {
            case NONE -> 0;
            case BASIC -> 1;
            case STANDARD -> 2;
            case FULL -> 4;
        };
    }

    /**
     * Returns whether SVGF denoising is recommended (Tier 3 only).
     */
    public boolean shouldEnableDenoiser() {
        return tier == Tier.FULL;
    }
}
