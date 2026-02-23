package net.vulkanium.rt;

import net.vulkanium.core.VulkaniumDevice;
import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.core.VulkaniumQueues;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level manager for the ray-tracing pipeline.
 *
 * <p>Orchestrates BLAS/TLAS construction, SBT management, RT pipeline creation,
 * and per-frame RT dispatch. This is the main entry point for all RT operations.</p>
 *
 * <h3>Per-Frame RT Flow</h3>
 * <ol>
 *   <li>Build/update dirty BLASes (from chunk mesh changes)</li>
 *   <li>Rebuild TLAS with current visible section instances</li>
 *   <li>Update SBT if materials changed</li>
 *   <li>Bind RT pipeline + descriptor sets</li>
 *   <li>Dispatch rays ({@code vkCmdTraceRaysKHR})</li>
 *   <li>Denoise output (SVGF compute passes)</li>
 *   <li>Composite into main render targets</li>
 * </ol>
 *
 * <h3>Integration with Raster Pipeline</h3>
 * <p>RT can replace specific render phases:</p>
 * <ul>
 *   <li>Shadow maps → RT shadow rays (Tier 1+)</li>
 *   <li>Screen-space reflections → RT reflections (Tier 2+)</li>
 *   <li>All lighting → Path-traced GI (Tier 3)</li>
 * </ul>
 *
 * <p>The raster pipeline remains active for passes that RT doesn't replace
 * (GUI, particles, etc.).</p>
 */
public class RTModuleManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RTManager");

    private final VulkaniumDevice vulkaniumDevice;
    private final VulkaniumMemory memory;

    // Sub-managers
    private final BLASManager blasManager;
    private final TLASBuilder tlasBuilder;
    private final ShaderBindingTable sbt;
    private final VulkaniumRayTracingPipeline rtPipeline;
    private final RTCapabilities capabilities;

    // Optional modules
    private SVGFDenoiser denoiser;
    private MaterialTable materialTable;

    // State
    private boolean enabled = false;
    private RTCapabilities.Tier activeTier = RTCapabilities.Tier.NONE;
    private int maxBounces = 1;
    private boolean denoiserEnabled = false;

    // Per-frame stats
    private int blasBuildsThisFrame;
    private long lastTraceTimeNs;

    public RTModuleManager(VulkaniumDevice device, VulkaniumMemory memory,
                            VulkaniumQueues queues, RTCapabilities capabilities) {
        this.vulkaniumDevice = device;
        this.memory = memory;
        this.capabilities = capabilities;

        this.blasManager = new BLASManager(memory, queues);
        this.tlasBuilder = new TLASBuilder(memory, queues);
        this.sbt = new ShaderBindingTable(memory);
        this.rtPipeline = new VulkaniumRayTracingPipeline(device);

        this.activeTier = capabilities.getTier();
        this.maxBounces = capabilities.getRecommendedMaxBounces();
    }

    /**
     * Initializes the RT pipeline if capabilities allow.
     */
    public void initialize() {
        if (!capabilities.isRTAvailable()) {
            LOGGER.info("Ray tracing not available on this GPU");
            return;
        }

        materialTable = new MaterialTable();
        materialTable.buildDefaultTable();
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getResourceManager() != null) {
                materialTable.applyLabPbrOverrides(mc.getResourceManager());
            }
        } catch (Throwable t) {
            LOGGER.debug("LabPBR material override pass skipped: {}", t.getMessage());
        }

        // Create RT pipeline and SBT
        rtPipeline.initialize(capabilities.hasRayTracingPipeline(),
                capabilities.getMaxRayRecursionDepth(),
                capabilities.getShaderGroupHandleSize(),
                capabilities.getShaderGroupHandleAlignment(),
                capabilities.getShaderGroupBaseAlignment());
        sbt.configure(capabilities.getShaderGroupHandleSize(),
                capabilities.getShaderGroupHandleAlignment(),
                capabilities.getShaderGroupBaseAlignment());
        blasManager.initialize(capabilities.hasAccelerationStructure());
        tlasBuilder.initialize();

        if (capabilities.shouldEnableDenoiser()) {
            denoiser = new SVGFDenoiser(vulkaniumDevice, memory);
            denoiser.initialize();
            denoiserEnabled = true;
        }

        enabled = true;
        LOGGER.info("RT pipeline initialized: tier={}, maxBounces={}, denoiser={}",
                activeTier.getName(), maxBounces, denoiserEnabled);
    }

    /**
     * Per-frame RT execution.
     *
     * @param commandBuffer Active command buffer
     * @param frameIndex Current frame index
     */
    public void executeFrame(VkCommandBuffer commandBuffer, int frameIndex) {
        if (!enabled) return;

        long startNs = System.nanoTime();
        blasBuildsThisFrame = 0;

        // 1. Build dirty BLASes
        int blasBuilds = blasManager.buildDirtyBLASes();
        blasBuildsThisFrame = blasBuilds;

        // 2. Rebuild TLAS
        tlasBuilder.collectInstances(blasManager.getBuiltBLASes(), 0, 0, 0);
        tlasBuilder.buildTLAS(commandBuffer.address());

        // 3. Barrier: AS build → ray trace
        insertASBarrier(commandBuffer);

        // 4. Dispatch rays
        dispatchRays(commandBuffer);

        // 5. Denoise (if enabled)
        if (denoiserEnabled && denoiser != null) {
            denoiser.execute(commandBuffer, frameIndex);
        }

        lastTraceTimeNs = System.nanoTime() - startNs;
    }

    private void dispatchRays(VkCommandBuffer commandBuffer) {
        // Bind RT pipeline
        rtPipeline.bind(commandBuffer);

        // Bind descriptor sets (TLAS, output image, camera data, materials)
        rtPipeline.bindDescriptors(commandBuffer);

        // Push constants: bounce count, frame index
        rtPipeline.pushConstants(commandBuffer, maxBounces);

        // Trace rays — full screen dispatch
        sbt.cmdTraceRays(commandBuffer, rtPipeline.getWidth(), rtPipeline.getHeight());
    }

    private void insertASBarrier(VkCommandBuffer commandBuffer) {
        // Memory barrier: acceleration structure build → ray trace read
        // srcStage: VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
        // dstStage: VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
        // srcAccess: VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR
        // dstAccess: VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                    .dstAccessMask(org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);

            org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    org.lwjgl.vulkan.KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    0,
                    barrier,
                    null,
                    null
            );
        }
        LOGGER.debug("Inserted AS->RT pipeline barrier for frame dispatch");
    }

    // ── Configuration ──

    public void setMaxBounces(int bounces) {
        this.maxBounces = Math.min(bounces, capabilities.getMaxRayRecursionDepth());
    }

    public void setDenoiserEnabled(boolean enabled) {
        this.denoiserEnabled = enabled && denoiser != null;
    }

    public void setActiveTier(RTCapabilities.Tier tier) {
        if (tier.getLevel() <= capabilities.getTier().getLevel()) {
            this.activeTier = tier;
            this.maxBounces = switch (tier) {
                case NONE -> 0;
                case BASIC -> 1;
                case STANDARD -> 2;
                case FULL -> capabilities.getRecommendedMaxBounces();
            };
        }
    }

    /**
     * Called when a chunk's mesh changes — marks its BLAS dirty.
     */
    public void onChunkMeshChanged(int sectionX, int sectionY, int sectionZ,
                                     long vertexBuffer, long indexBuffer,
                                     int vertexCount, int indexCount) {
        if (!enabled) return;
        long sectionKey = packSectionKey(sectionX, sectionY, sectionZ);
        blasManager.markDirty(sectionKey);
    }

    /**
     * Called when a chunk is unloaded.
     */
    public void onChunkUnloaded(int sectionX, int sectionY, int sectionZ) {
        if (!enabled) return;
        long sectionKey = packSectionKey(sectionX, sectionY, sectionZ);
        blasManager.removeBLAS(sectionKey);
    }

    public void resize(int width, int height) {
        if (!enabled) return;
        rtPipeline.resize(width, height);
        if (denoiser != null) denoiser.resize(width, height);
    }

    public void destroy() {
        if (denoiser != null) denoiser.destroy();
        sbt.destroy();
        rtPipeline.destroy();
        tlasBuilder.destroy();
        blasManager.destroy();
        LOGGER.info("RT pipeline destroyed");
    }

    // ── Internal ──

    private static long packSectionKey(int x, int y, int z) {
        return ((long)(x & 0x3FFFFF)) | (((long)(y & 0xFFFFF)) << 22) | (((long)(z & 0x3FFFFF)) << 42);
    }

    // ── Accessors ──

    public boolean isEnabled() { return enabled; }
    public RTCapabilities getCapabilities() { return capabilities; }
    public RTCapabilities.Tier getActiveTier() { return activeTier; }
    public int getBLASBuildsThisFrame() { return blasBuildsThisFrame; }
    public long getLastTraceTimeNs() { return lastTraceTimeNs; }
}
