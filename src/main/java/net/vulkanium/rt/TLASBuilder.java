package net.vulkanium.rt;

import net.vulkanium.Vulkanium;
import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.core.VulkaniumQueues;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Collection;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Top-Level Acceleration Structure (TLAS) builder for ray tracing.
 *
 * <p>The TLAS is rebuilt every frame from all visible and built BLASes. Each BLAS
 * instance in the TLAS carries a per-instance transform (chunk-to-world translation)
 * and a custom instance index used to look up material data.</p>
 *
 * <h3>Per-Frame TLAS Build</h3>
 * <pre>
 *  Frame start
 *    → BLASManager builds dirty BLASes
 *    → TLASBuilder.collectInstances(visibleBLASes, cameraPos)
 *    → TLASBuilder.build(commandBuffer) — records vkCmdBuildAccelerationStructuresKHR
 *    → Pipeline barrier (acceleration structure → ray tracing shader reads)
 *    → Ray tracing dispatch reads TLAS
 * </pre>
 *
 * <h3>Instance Data</h3>
 * <p>Each {@code VkAccelerationStructureInstanceKHR} is 64 bytes:</p>
 * <pre>
 *   float[12] transform      — 3×4 row-major affine transform (chunk origin relative to camera)
 *   uint24    instanceCustomIndex — section key (lower 24 bits for material lookup)
 *   uint8     mask                — visibility mask (0xFF = all ray types)
 *   uint24    instanceShaderBindingTableRecordOffset — SBT hit group index
 *   uint8     flags               — VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR
 *   uint64    accelerationStructureReference — BLAS device address
 * </pre>
 *
 * <h3>Build Quality</h3>
 * <p>TLAS uses {@code VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR}
 * since it's rebuilt every frame. This trades trace quality for faster build times.</p>
 *
 * <p><b>Note:</b> Phase 10 stub. Real implementation requires VK_KHR_acceleration_structure.</p>
 */
public class TLASBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/TLASBuilder");

    /** Size of VkAccelerationStructureInstanceKHR */
    private static final int INSTANCE_SIZE = 64;

    /** Maximum instances in the TLAS (practical limit for Minecraft render distance) */
    private static final int MAX_INSTANCES = 16384;

    // ── State ──
    private final VulkaniumMemory memory;

    /** The TLAS acceleration structure */
    private AccelerationStructure tlas;

    /** Instance buffer (device-local, host-visible for CPU writes) */
    private long instanceBuffer = 0;
    private long instanceBufferAllocation = 0;
    private long instanceBufferSize = 0;

    /** Mapped pointer to instance buffer data */
    private ByteBuffer instanceBufferMapped = null;

    /** Scratch buffer for TLAS builds */
    private long scratchBuffer = 0;
    private long scratchAllocation = 0;
    private long scratchSize = 0;
    private long scratchDeviceAddress = 0;
    private long instanceBufferDeviceAddress = 0;

    /** Real VkAccelerationStructureKHR handle (0 if not yet created) */
    private long tlasHandle = 0;

    /** Number of instances written this frame */
    private int instanceCount = 0;

    /** Camera position (instances are relative to camera for precision) */
    private final Vector3f cameraPos = new Vector3f();

    /** Statistics */
    private long lastBuildTimeNs = 0;
    private int maxInstancesEverUsed = 0;

    public TLASBuilder(VulkaniumMemory memory, VulkaniumQueues queues) {
        this.memory = memory;
    }

    /**
     * Initializes the TLAS builder. Allocates instance and scratch buffers.
     *
     * @throws RuntimeException if buffer allocation fails (e.g., RT extensions not enabled)
     */
    public void initialize() {
        // Allocate TLAS wrapper
        tlas = new AccelerationStructure(
                AccelerationStructure.Type.TOP_LEVEL,
                AccelerationStructure.BuildQuality.FAST_BUILD);

        // Allocate instance buffer (host-visible for CPU writes each frame)
        instanceBufferSize = (long) MAX_INSTANCES * INSTANCE_SIZE;
        // VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR (0x00080000)
        // | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT (0x00020000)
        try {
            long[] result = memory.allocateBuffer(instanceBufferSize,
                    0x00080000 | 0x00020000, 0x00000003); // VMA_MEMORY_USAGE_CPU_TO_GPU
            instanceBuffer = result[0];
            instanceBufferAllocation = result[1];
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to allocate TLAS instance buffer ({} KB) — RT AS extensions may not be active",
                    instanceBufferSize / 1024);
            throw e; // Let RTModuleManager's caller handle this
        }

        instanceBufferMapped = memory.mapBuffer(instanceBufferAllocation);
        if (instanceBufferMapped == null) {
            throw new RuntimeException("Failed to map TLAS instance buffer");
        }

        // Allocate scratch buffer (sized for max TLAS build)
        // Typical TLAS scratch is ~instance_count * 128 bytes
        long scratchSize = MAX_INSTANCES * 128L;
        try {
            long[] scratchResult = memory.allocateBuffer(scratchSize,
                    0x00000020 | 0x00020000, 0x00000002); // VMA_MEMORY_USAGE_GPU_ONLY
            scratchBuffer = scratchResult[0];
            scratchAllocation = scratchResult[1];
            this.scratchSize = scratchSize;
            this.scratchDeviceAddress = getBufferDeviceAddress(scratchBuffer);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to allocate TLAS scratch buffer ({} KB)", scratchSize / 1024);
            throw e;
        }

        instanceBufferDeviceAddress = getBufferDeviceAddress(instanceBuffer);

        LOGGER.info("TLAS builder initialized (max instances: {}, instance buffer: {} KB, scratch: {} KB)",
                MAX_INSTANCES, instanceBufferSize / 1024, scratchSize / 1024);
    }

    /**
     * Collects visible BLAS instances for the current frame's TLAS.
     *
     * @param builtBLASes Collection of BLASes that are built and ready
     * @param cameraX     Camera world position X
     * @param cameraY     Camera world position Y
     * @param cameraZ     Camera world position Z
     */
    public void collectInstances(Collection<AccelerationStructure> builtBLASes,
                                  double cameraX, double cameraY, double cameraZ) {
        cameraPos.set((float) cameraX, (float) cameraY, (float) cameraZ);
        instanceCount = 0;

        if (instanceBufferMapped != null) {
            instanceBufferMapped.clear();
        }

        for (AccelerationStructure blas : builtBLASes) {
            if (instanceCount >= MAX_INSTANCES) {
                LOGGER.warn("TLAS instance limit reached ({} BLASes)", MAX_INSTANCES);
                break;
            }

            if (blas.getDeviceAddress() == 0 || instanceBufferMapped == null) {
                continue;
            }

            int base = instanceCount * INSTANCE_SIZE;

            // transform[3x4] row-major: identity + camera-relative translation
            float tx = -cameraPos.x;
            float ty = -cameraPos.y;
            float tz = -cameraPos.z;

            instanceBufferMapped.putFloat(base, 1.0f);
            instanceBufferMapped.putFloat(base + 4, 0.0f);
            instanceBufferMapped.putFloat(base + 8, 0.0f);
            instanceBufferMapped.putFloat(base + 12, tx);

            instanceBufferMapped.putFloat(base + 16, 0.0f);
            instanceBufferMapped.putFloat(base + 20, 1.0f);
            instanceBufferMapped.putFloat(base + 24, 0.0f);
            instanceBufferMapped.putFloat(base + 28, ty);

            instanceBufferMapped.putFloat(base + 32, 0.0f);
            instanceBufferMapped.putFloat(base + 36, 0.0f);
            instanceBufferMapped.putFloat(base + 40, 1.0f);
            instanceBufferMapped.putFloat(base + 44, tz);

                int customIndex = blas.getInstanceCustomIndex() & 0x00FFFFFF;
                int sbtOffset = blas.getInstanceSbtOffset() & 0x00FFFFFF;
                int customIndexAndMask = customIndex | (0xFF << 24);
                int sbtOffsetAndFlags = sbtOffset
                    | ((VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR & 0xFF) << 24);
            instanceBufferMapped.putInt(base + 48, customIndexAndMask);
            instanceBufferMapped.putInt(base + 52, sbtOffsetAndFlags);
            instanceBufferMapped.putLong(base + 56, blas.getDeviceAddress());

            instanceCount++;
        }

        maxInstancesEverUsed = Math.max(maxInstancesEverUsed, instanceCount);
        tlas.setPrimitiveCount(instanceCount);
        tlas.markDirty();
    }

    /**
     * Records the TLAS build command into the given command buffer.
     *
     * <p>Must be called after {@link #collectInstances} and before any ray tracing
     * dispatches that read the TLAS. The caller is responsible for inserting a
     * pipeline barrier after this call.</p>
     *
     * @param commandBuffer Active VkCommandBuffer
     */
    public void buildTLAS(VkCommandBuffer commandBuffer) {
        if (instanceCount == 0 || !tlas.isDirty() || commandBuffer == null) return;

        long startNs = System.nanoTime();
        VkDevice device = Vulkanium.getVulkanDevice().getLogicalDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Geometry: instances
            var instanceData = VkAccelerationStructureGeometryInstancesDataKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
                    .arrayOfPointers(false);
            instanceData.data().deviceAddress(instanceBufferDeviceAddress);

            var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                    .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.get(0).geometry().instances(instanceData);

            // Query build sizes (use buffer of 1 for vkCmdBuildAccelerationStructuresKHR)
            var buildInfoBuf = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            var buildInfo = buildInfoBuf.get(0)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geometry);

            var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);
            vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo,
                    stack.ints(instanceCount),
                    sizes);

            long requiredSize = sizes.accelerationStructureSize();
            long scratchRequired = sizes.buildScratchSize();

            // Resize scratch if needed
            if (scratchRequired > scratchSize) {
                if (scratchBuffer != 0) {
                    memory.freeBuffer(scratchBuffer, scratchAllocation);
                }
                long newSize = Math.max(scratchRequired, scratchSize * 2);
                long[] r = memory.allocateBuffer(newSize, 0x00000020 | 0x00020000, 0x00000002);
                scratchBuffer = r[0];
                scratchAllocation = r[1];
                scratchSize = newSize;
                scratchDeviceAddress = getBufferDeviceAddress(scratchBuffer);
            }

            // (Re)allocate TLAS storage if needed
            if (tlas.getBuffer() == 0 || tlas.getSize() < requiredSize) {
                if (tlasHandle != 0) {
                    vkDestroyAccelerationStructureKHR(device, tlasHandle, null);
                    tlasHandle = 0;
                    tlas.setHandle(0);
                }
                if (tlas.getBuffer() != 0) {
                    memory.freeBuffer(tlas.getBuffer(), tlas.getBufferAllocation());
                }
                long[] alloc = memory.allocateBuffer(requiredSize,
                        0x00200000 | 0x00020000, 0x00000002); // AS_STORAGE | BDA | GPU_ONLY
                tlas.setBuffer(alloc[0], alloc[1]);
                tlas.setSize(requiredSize);
                LOGGER.debug("Allocated TLAS storage: {} KB", requiredSize / 1024);
            }

            // Create VkAccelerationStructureKHR if needed
            if (tlasHandle == 0) {
                var createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                        .buffer(tlas.getBuffer())
                        .offset(0)
                        .size(requiredSize)
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
                var pAS = stack.mallocLong(1);
                int res = vkCreateAccelerationStructureKHR(device, createInfo, null, pAS);
                if (res != VK_SUCCESS) {
                    LOGGER.error("vkCreateAccelerationStructureKHR (TLAS) failed: {}", res);
                    tlas.markClean();
                    lastBuildTimeNs = System.nanoTime() - startNs;
                    return;
                }
                tlasHandle = pAS.get(0);
                tlas.setHandle(tlasHandle);

                var addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                        .accelerationStructure(tlasHandle);
                long asAddr = vkGetAccelerationStructureDeviceAddressKHR(device, addrInfo);
                tlas.setDeviceAddress(asAddr);
            }

            // Build
            buildInfo.dstAccelerationStructure(tlasHandle)
                     .scratchData().deviceAddress(scratchDeviceAddress);

            var rangeInfo = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
                    .primitiveCount(instanceCount)
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);

            vkCmdBuildAccelerationStructuresKHR(
                    commandBuffer,
                    buildInfoBuf,
                    stack.pointers(rangeInfo));

        } catch (Exception e) {
            LOGGER.error("Failed to build TLAS: {}", e.getMessage(), e);
        }

        tlas.markClean();
        lastBuildTimeNs = System.nanoTime() - startNs;
        LOGGER.debug("TLAS build completed: instances={} buildTime={}µs",
                instanceCount, lastBuildTimeNs / 1000);
    }

    /**
     * Records a pipeline barrier: acceleration structure write → shader read.
     *
     * @param commandBuffer Active VkCommandBuffer
     */
    public void recordBuildBarrier(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null || Vulkanium.getVulkanDevice() == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                    .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);

            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    0,
                    barrier,
                    null,
                    null
            );
        }
    }

    // ── Getters ──

    /** Returns the TLAS handle for descriptor set binding. */
    public AccelerationStructure getTLAS() { return tlas; }

    /** Number of instances in the current frame's TLAS. */
    public int getInstanceCount() { return instanceCount; }

    /** Last TLAS build time in nanoseconds. */
    public long getLastBuildTimeNs() { return lastBuildTimeNs; }

    /** Maximum instances ever used across all frames. */
    public int getMaxInstancesEverUsed() { return maxInstancesEverUsed; }

    // ── Lifecycle ──

    public void destroy() {
        VkDevice device = Vulkanium.getVulkanDevice() != null ? Vulkanium.getVulkanDevice().getLogicalDevice() : null;
        if (tlasHandle != 0 && device != null) {
            vkDestroyAccelerationStructureKHR(device, tlasHandle, null);
            tlasHandle = 0;
        }
        if (tlas != null) {
            if (tlas.getBuffer() != 0) {
                memory.freeBuffer(tlas.getBuffer(), tlas.getBufferAllocation());
            }
            tlas.reset();
            tlas = null;
        }

        if (instanceBuffer != 0) {
            if (instanceBufferMapped != null) {
                memory.unmapBuffer(instanceBufferAllocation);
                instanceBufferMapped = null;
            }
            memory.freeBuffer(instanceBuffer, instanceBufferAllocation);
            instanceBuffer = 0;
        }

        if (scratchBuffer != 0) {
            memory.freeBuffer(scratchBuffer, scratchAllocation);
            scratchBuffer = 0;
        }

        LOGGER.info("TLAS builder destroyed (max instances used: {})", maxInstancesEverUsed);
    }

    private long getBufferDeviceAddress(long buffer) {
        if (buffer == 0 || Vulkanium.getVulkanDevice() == null) return 0;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferDeviceAddressInfo info = org.lwjgl.vulkan.VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(buffer);
            return org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress(Vulkanium.getVulkanDevice().getLogicalDevice(), info);
        } catch (Throwable t) {
            LOGGER.debug("TLAS device address query failed: {}", t.getMessage());
            return 0;
        }
    }
}
