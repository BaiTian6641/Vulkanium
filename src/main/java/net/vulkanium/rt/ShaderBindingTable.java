package net.vulkanium.rt;

import net.vulkanium.Vulkanium;
import net.vulkanium.core.VulkaniumMemory;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shader Binding Table (SBT) manager for ray tracing pipelines.
 *
 * <p>The SBT maps ray types and geometry instance indices to shader groups.
 * It's a GPU buffer organized into four regions:</p>
 *
 * <pre>
 *   ┌───────────────────────┐  ← rayGenRegion
 *   │  Ray Generation       │     1 record: main raygen shader
 *   ├───────────────────────┤  ← missRegion
 *   │  Miss [0]             │     Sky/environment miss for primary rays
 *   │  Miss [1]             │     Shadow miss (returns "not occluded")
 *   ├───────────────────────┤  ← hitRegion
 *   │  Closest Hit [0]      │     Opaque geometry (terrain, entities)
 *   │  Closest Hit [1]      │     Transparent geometry (water, glass)
 *   │  Closest Hit [2]      │     (reserved for future material types)
 *   ├───────────────────────┤  ← callableRegion
 *   │  Callable [0]         │     (reserved for shader pack extensions)
 *   └───────────────────────┘
 * </pre>
 *
 * <h3>Alignment Requirements</h3>
 * <ul>
 *   <li>Each region base address: aligned to {@code shaderGroupBaseAlignment}</li>
 *   <li>Each record stride: aligned to {@code shaderGroupHandleAlignment}</li>
 *   <li>Record size: {@code shaderGroupHandleSize} (typically 32 bytes)</li>
 * </ul>
 *
 * <h3>Shader Pack Integration (Future)</h3>
 * <p>Shader packs can define custom closest-hit and miss shaders via:</p>
 * <pre>
 *   shaders/raytrace/raygen.rgen
 *   shaders/raytrace/miss.rmiss
 *   shaders/raytrace/shadow.rmiss
 *   shaders/raytrace/closesthit.rchit
 *   shaders/raytrace/anyhit.rahit
 * </pre>
 *
 * <p><b>Note:</b> Phase 10 stub. Requires VK_KHR_ray_tracing_pipeline properties.</p>
 */
public class ShaderBindingTable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/SBT");

    /** Default maximum shader groups per SBT region */
    private static final int MAX_MISS_SHADERS = 4;
    private static final int MAX_HIT_GROUPS = 8;
    private static final int MAX_CALLABLE_SHADERS = 4;

    // ── RT Pipeline Properties (queried from device) ──
    private int shaderGroupHandleSize = 32;       // Typical value
    private int shaderGroupHandleAlignment = 32;
    private int shaderGroupBaseAlignment = 64;
    private int maxShaderGroupStride = 4096;

    // ── Buffer State ──
    private final VulkaniumMemory memory;

    /** SBT backing buffer */
    private long sbtBuffer = 0;
    private long sbtBufferAllocation = 0;
    private long sbtBufferSize = 0;
    private long sbtBufferDeviceAddress = 0;

    /** Region offsets within the SBT buffer */
    private long rayGenOffset = 0;
    private long missOffset = 0;
    private long hitOffset = 0;
    private long callableOffset = 0;

    /** Record stride (per region — all records same stride within a region) */
    private int recordStride = 0;

    /** Number of shader groups per region */
    private int missCount = 0;
    private int hitGroupCount = 0;
    private int callableCount = 0;

    /** Whether the SBT has been built and is ready for dispatch */
    private boolean ready = false;

    public ShaderBindingTable(VulkaniumMemory memory) {
        this.memory = memory;
    }

    /**
     * Configures the SBT with device ray tracing pipeline properties.
     *
     * @param handleSize      shaderGroupHandleSize from device properties
     * @param handleAlignment shaderGroupHandleAlignment from device properties
     * @param baseAlignment   shaderGroupBaseAlignment from device properties
     */
    public void configure(int handleSize, int handleAlignment, int baseAlignment) {
        this.shaderGroupHandleSize = handleSize;
        this.shaderGroupHandleAlignment = handleAlignment;
        this.shaderGroupBaseAlignment = baseAlignment;
        this.recordStride = alignUp(handleSize, handleAlignment);

        LOGGER.info("SBT configured: handleSize={}, handleAlign={}, baseAlign={}, stride={}",
                handleSize, handleAlignment, baseAlignment, recordStride);
    }

    /**
     * Builds the SBT from a ray tracing pipeline's shader group handles.
     *
     * @param rtPipeline      VkPipeline handle for the ray tracing pipeline
     * @param missShaderCount Number of miss shader groups
     * @param hitGroupCount   Number of hit shader groups
     * @param callableCount   Number of callable shader groups
     */
    public void build(long rtPipeline, int missShaderCount, int hitGroupCount, int callableCount) {
        this.missCount = missShaderCount;
        this.hitGroupCount = hitGroupCount;
        this.callableCount = callableCount;

        // Calculate region sizes and offsets
        long rayGenSize = alignUp(recordStride, shaderGroupBaseAlignment);
        long missSize = alignUp((long) missShaderCount * recordStride, shaderGroupBaseAlignment);
        long hitSize = alignUp((long) hitGroupCount * recordStride, shaderGroupBaseAlignment);
        long callableSize = callableCount > 0
                ? alignUp((long) callableCount * recordStride, shaderGroupBaseAlignment)
                : 0;

        rayGenOffset = 0;
        missOffset = rayGenSize;
        hitOffset = missOffset + missSize;
        callableOffset = hitOffset + hitSize;
        sbtBufferSize = callableOffset + callableSize;

        // (Re-)allocate buffer
        if (sbtBuffer != 0) {
            memory.freeBuffer(sbtBuffer, sbtBufferAllocation);
        }

        // VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR (0x00000400)
        // | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT (0x00020000)
        // | VK_BUFFER_USAGE_TRANSFER_DST_BIT (0x00000002) for handle upload
        long[] result = memory.allocateBuffer(sbtBufferSize,
                0x00000400 | 0x00020000 | 0x00000002, 0x00000003); // CPU_TO_GPU
        sbtBuffer = result[0];
        sbtBufferAllocation = result[1];

        sbtBufferDeviceAddress = queryBufferDeviceAddress(sbtBuffer);
        if (sbtBufferDeviceAddress == 0) {
            LOGGER.warn("SBT buffer device address unavailable; ray tracing dispatch will be skipped");
            ready = false;
            return;
        }

        int totalGroups = 1 + missShaderCount + hitGroupCount + callableCount;
        java.nio.ByteBuffer handles = org.lwjgl.BufferUtils.createByteBuffer(totalGroups * shaderGroupHandleSize);

        int res = org.lwjgl.vulkan.KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(
                Vulkanium.getVulkanDevice().getLogicalDevice(),
                rtPipeline,
                0,
                totalGroups,
                handles
        );
        if (res != org.lwjgl.vulkan.VK10.VK_SUCCESS) {
            LOGGER.warn("Failed to fetch RT shader group handles (VkResult {})", res);
            ready = false;
            return;
        }

        java.nio.ByteBuffer mapped = memory.mapBuffer(sbtBufferAllocation);
        if (mapped == null) {
            LOGGER.warn("Failed to map SBT buffer for handle upload");
            ready = false;
            return;
        }

        long mappedAddr = org.lwjgl.system.MemoryUtil.memAddress(mapped);
        long handlesAddr = org.lwjgl.system.MemoryUtil.memAddress(handles);

        // Raygen group at index 0
        org.lwjgl.system.MemoryUtil.memCopy(handlesAddr, mappedAddr + rayGenOffset, shaderGroupHandleSize);

        int groupIndex = 1;
        for (int i = 0; i < missShaderCount; i++, groupIndex++) {
            long dst = mappedAddr + missOffset + (long) i * recordStride;
            long src = handlesAddr + (long) groupIndex * shaderGroupHandleSize;
            org.lwjgl.system.MemoryUtil.memCopy(src, dst, shaderGroupHandleSize);
        }

        for (int i = 0; i < hitGroupCount; i++, groupIndex++) {
            long dst = mappedAddr + hitOffset + (long) i * recordStride;
            long src = handlesAddr + (long) groupIndex * shaderGroupHandleSize;
            org.lwjgl.system.MemoryUtil.memCopy(src, dst, shaderGroupHandleSize);
        }

        for (int i = 0; i < callableCount; i++, groupIndex++) {
            long dst = mappedAddr + callableOffset + (long) i * recordStride;
            long src = handlesAddr + (long) groupIndex * shaderGroupHandleSize;
            org.lwjgl.system.MemoryUtil.memCopy(src, dst, shaderGroupHandleSize);
        }

        memory.unmapBuffer(sbtBufferAllocation);

        ready = true;
        LOGGER.info("SBT built: {} bytes (raygen: 1, miss: {}, hit: {}, callable: {})",
                sbtBufferSize, missShaderCount, hitGroupCount, callableCount);
    }

    // ── VkStridedDeviceAddressRegionKHR accessors for vkCmdTraceRaysKHR ──

    /** Ray generation region: address, stride, size */
    public long getRayGenAddress() { return sbtBufferDeviceAddress + rayGenOffset; }
    public int  getRayGenStride()  { return recordStride; }
    public long getRayGenSize()    { return alignUp(recordStride, shaderGroupBaseAlignment); }

    /** Miss region: address, stride, size */
    public long getMissAddress() { return sbtBufferDeviceAddress + missOffset; }
    public int  getMissStride()  { return recordStride; }
    public long getMissSize()    { return alignUp((long) missCount * recordStride, shaderGroupBaseAlignment); }

    /** Hit group region: address, stride, size */
    public long getHitAddress() { return sbtBufferDeviceAddress + hitOffset; }
    public int  getHitStride()  { return recordStride; }
    public long getHitSize()    { return alignUp((long) hitGroupCount * recordStride, shaderGroupBaseAlignment); }

    /** Callable region: address, stride, size */
    public long getCallableAddress() {
        return callableCount > 0 ? sbtBufferDeviceAddress + callableOffset : 0;
    }
    public int  getCallableStride() { return callableCount > 0 ? recordStride : 0; }
    public long getCallableSize()   {
        return callableCount > 0
                ? alignUp((long) callableCount * recordStride, shaderGroupBaseAlignment)
                : 0;
    }

    /** Whether the SBT is ready for ray tracing dispatch. */
    public boolean isReady() { return ready; }

    /**
     * Records a vkCmdTraceRaysKHR command. Stub for Phase 10.
     */
    public void cmdTraceRays(VkCommandBuffer commandBuffer, int width, int height) {
        if (!ready) return;
        if (sbtBufferDeviceAddress == 0) {
            LOGGER.warn("SBT trace skipped: device address is not initialized yet");
            return;
        }
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var raygen = org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(getRayGenAddress())
                .stride(getRayGenStride())
                .size(getRayGenSize());
            var miss = org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(getMissAddress())
                .stride(getMissStride())
                .size(getMissSize());
            var hit = org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(getHitAddress())
                .stride(getHitStride())
                .size(getHitSize());
            var callable = org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(getCallableAddress())
                .stride(getCallableStride())
                .size(getCallableSize());

            org.lwjgl.vulkan.KHRRayTracingPipeline.vkCmdTraceRaysKHR(
                commandBuffer,
                raygen,
                miss,
                hit,
                callable,
                width,
                height,
                1
            );
        }
        LOGGER.debug("SBT vkCmdTraceRaysKHR dispatched at {}x{}", width, height);
    }

    // ── Utility ──

    private static long alignUp(long value, int alignment) {
        return (value + alignment - 1) & ~((long) alignment - 1);
    }

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    // ── Lifecycle ──

    public void destroy() {
        if (sbtBuffer != 0) {
            memory.freeBuffer(sbtBuffer, sbtBufferAllocation);
            sbtBuffer = 0;
            sbtBufferAllocation = 0;
            sbtBufferDeviceAddress = 0;
        }
        ready = false;
        LOGGER.info("SBT destroyed");
    }

    private long queryBufferDeviceAddress(long buffer) {
        if (buffer == 0 || Vulkanium.getVulkanDevice() == null) {
            return 0;
        }
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var addrInfo = org.lwjgl.vulkan.VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(buffer);
            return org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress(Vulkanium.getVulkanDevice().getLogicalDevice(), addrInfo);
        } catch (Throwable t) {
            LOGGER.debug("SBT device address query failed: {}", t.getMessage());
            return 0;
        }
    }
}
