package net.vulkanium.rt;

import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.core.VulkaniumQueues;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Collection;

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
    private final VulkaniumQueues queues;

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

    /** Number of instances written this frame */
    private int instanceCount = 0;

    /** Camera position (instances are relative to camera for precision) */
    private final Vector3f cameraPos = new Vector3f();

    /** Temp matrix for per-instance transform computation */
    private final Matrix4f tempTransform = new Matrix4f();

    /** Statistics */
    private long lastBuildTimeNs = 0;
    private int maxInstancesEverUsed = 0;

    public TLASBuilder(VulkaniumMemory memory, VulkaniumQueues queues) {
        this.memory = memory;
        this.queues = queues;
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

        // TODO Phase 10: Map the instance buffer
        // instanceBufferMapped = vmaMapMemory(allocator, instanceBufferAllocation)

        // Allocate scratch buffer (sized for max TLAS build)
        // Typical TLAS scratch is ~instance_count * 128 bytes
        long scratchSize = MAX_INSTANCES * 128L;
        try {
            long[] scratchResult = memory.allocateBuffer(scratchSize,
                    0x00000020 | 0x00020000, 0x00000002); // VMA_MEMORY_USAGE_GPU_ONLY
            scratchBuffer = scratchResult[0];
            scratchAllocation = scratchResult[1];
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to allocate TLAS scratch buffer ({} KB)", scratchSize / 1024);
            throw e;
        }

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

        // TODO Phase 10: Write each BLAS instance into the instance buffer
        // For each BLAS:
        //   1. Compute chunk-to-camera-relative transform
        //   2. Write VkAccelerationStructureInstanceKHR to instanceBufferMapped
        //   3. Increment instanceCount
        //
        // Example per-instance:
        //   float[12] transform = identity with translation = (chunkOrigin - cameraPos)
        //   customIndex = sectionKey & 0x00FFFFFF
        //   mask = 0xFF
        //   sbtOffset = 0 (use default hit group)
        //   flags = VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR
        //   reference = blas.getDeviceAddress()

        for (AccelerationStructure blas : builtBLASes) {
            if (instanceCount >= MAX_INSTANCES) {
                LOGGER.warn("TLAS instance limit reached ({} BLASes)", MAX_INSTANCES);
                break;
            }

            // Placeholder: count instances (real impl writes to mapped buffer)
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
    public void buildTLAS(long commandBuffer) {
        if (instanceCount == 0 || !tlas.isDirty()) return;

        long startNs = System.nanoTime();

        // TODO Phase 10: Record TLAS build
        // 1. Flush instance buffer writes (if not coherent memory)
        // 2. Fill VkAccelerationStructureBuildGeometryInfoKHR:
        //    .type = VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR
        //    .flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR
        //    .mode = VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR
        //    .dstAccelerationStructure = tlas.getHandle()
        //    .geometryCount = 1
        //    .pGeometries → VkAccelerationStructureGeometryKHR:
        //       .geometryType = VK_GEOMETRY_TYPE_INSTANCES_KHR
        //       .geometry.instances.data.deviceAddress = instanceBuffer device address
        //    .scratchData.deviceAddress = scratchBuffer device address
        // 3. VkAccelerationStructureBuildRangeInfoKHR:
        //    .primitiveCount = instanceCount
        // 4. vkCmdBuildAccelerationStructuresKHR(commandBuffer, 1, &buildInfo, &rangeInfo)

        tlas.markClean();
        lastBuildTimeNs = System.nanoTime() - startNs;
    }

    /**
     * Records a pipeline barrier: acceleration structure write → shader read.
     *
     * @param commandBuffer Active VkCommandBuffer
     */
    public void recordBuildBarrier(long commandBuffer) {
        // TODO Phase 10: VkMemoryBarrier2 with:
        //   srcStageMask = VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
        //   srcAccessMask = VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR
        //   dstStageMask = VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR
        //   dstAccessMask = VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR
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
        if (tlas != null) {
            // TODO Phase 10: vkDestroyAccelerationStructureKHR
            tlas.reset();
            tlas = null;
        }

        if (instanceBuffer != 0) {
            // TODO Phase 10: vmaUnmapMemory if mapped
            memory.freeBuffer(instanceBuffer, instanceBufferAllocation);
            instanceBuffer = 0;
        }

        if (scratchBuffer != 0) {
            memory.freeBuffer(scratchBuffer, scratchAllocation);
            scratchBuffer = 0;
        }

        LOGGER.info("TLAS builder destroyed (max instances used: {})", maxInstancesEverUsed);
    }
}
