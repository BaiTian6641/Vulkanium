package net.vulkanium.rt;

import net.vulkanium.Vulkanium;
import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.core.VulkaniumQueues;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bottom-Level Acceleration Structure (BLAS) manager for ray tracing.
 *
 * <p>Manages one BLAS per chunk section (16×16×16 blocks). Each BLAS contains
 * the triangle geometry from the chunk's terrain mesh, reusing the same vertex/index
 * buffers used for rasterization (zero-copy geometry sharing).</p>
 *
 * <h3>BLAS Lifecycle</h3>
 * <pre>
 *   Chunk loaded → BLAS created (NEEDS_BUILD)
 *     → Chunk mesh built → BLAS geometry set (NEEDS_BUILD)
 *       → Next RT frame → BLAS built on GPU (READY)
 *         → Block update → BLAS marked dirty → rebuild
 *           → Chunk unloaded → BLAS destroyed
 * </pre>
 *
 * <h3>Build Strategy (from MCVR)</h3>
 * <p>Inspired by MCVR's {@code BLASBatchBuilder}, BLASes are built in batches
 * using a dedicated build command buffer. The batch builder:</p>
 * <ol>
 *   <li>Collects all dirty BLASes into a build queue</li>
 *   <li>Sorts by priority (distance to camera, staleness)</li>
 *   <li>Allocates scratch memory for the batch</li>
 *   <li>Records {@code vkCmdBuildAccelerationStructuresKHR} for each BLAS</li>
 *   <li>Submits to compute queue with barrier before TLAS build</li>
 * </ol>
 *
 * <h3>Memory Layout</h3>
 * <p>Each BLAS's backing buffer is allocated from VMA with
 * {@code VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR}. A shared scratch
 * buffer is used for all builds (reused between frames, sized to max required).</p>
 *
 * <h3>Geometry Format</h3>
 * <p>BLAS geometry references the same vertex/index buffers as rasterization:</p>
 * <ul>
 *   <li>Vertex buffer: Vulkanium's 32-byte terrain format (position at offset 0, stride 32)</li>
 *   <li>Index buffer: uint16 or uint32 indices</li>
 *   <li>Transform: 3×4 affine matrix (chunk-to-world translation)</li>
 * </ul>
 *
 * <p><b>Note:</b> This is a Phase 10 stub. Full implementation requires
 * {@code VK_KHR_acceleration_structure} extension functions via LWJGL.</p>
 */
public class BLASManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/BLASManager");

    /** Maximum BLASes to build per frame (budget ~2ms GPU time) */
    private static final int MAX_BUILDS_PER_FRAME = 32;

    /** Scratch buffer size (grown as needed) */
    private static final long INITIAL_SCRATCH_SIZE = 64 * 1024 * 1024; // 64 MB

    /** Per-BLAS geometry parameters needed for vkCmdBuildAccelerationStructuresKHR. */
    record GeometryInfo(long vertexBuffer, long vertexOffset, int vertexCount, int vertexStride,
                        long indexBuffer, long indexOffset, int indexCount, boolean useUint32) {}

    // ── State ──
    private final VulkaniumMemory memory;

    /** Section key → BLAS mapping */
    private final Map<Long, AccelerationStructure> blasMap = new ConcurrentHashMap<>();

    /** Section key → geometry info */
    private final Map<Long, GeometryInfo> geometryInfoMap = new ConcurrentHashMap<>();

    /** Shared scratch buffer for BLAS builds */
    private long scratchBuffer = 0;
    private long scratchAllocation = 0;
    private long scratchSize = 0;
    private long scratchDeviceAddress = 0;

    /** Statistics */
    private int totalBLASCount = 0;
    private int dirtyBLASCount = 0;
    private int buildsThisFrame = 0;
    private long totalBLASMemory = 0;

    /** Whether RT is available (checked at initialization) */
    private boolean rtAvailable = false;

    public BLASManager(VulkaniumMemory memory, VulkaniumQueues queues) {
        this.memory = memory;
    }

    /**
     * Initializes the BLAS manager. Checks for RT extension support.
     *
     * @param hasAccelerationStructure Whether VK_KHR_acceleration_structure is available
     */
    public void initialize(boolean hasAccelerationStructure) {
        this.rtAvailable = hasAccelerationStructure;

        if (!rtAvailable) {
            LOGGER.info("BLAS manager: RT extensions not available, BLAS management disabled");
            return;
        }

        // Allocate initial scratch buffer
        allocateScratch(INITIAL_SCRATCH_SIZE);

        LOGGER.info("BLAS manager initialized (scratch: {} MB)", scratchSize / (1024 * 1024));
    }

    /**
     * Creates a BLAS for a chunk section.
     *
     * @param sectionKey Packed section coordinates (from RenderSection.packSectionKey)
     */
    public void createBLAS(long sectionKey) {
        if (!rtAvailable) return;

        AccelerationStructure blas = new AccelerationStructure(
                AccelerationStructure.Type.BOTTOM_LEVEL,
                AccelerationStructure.BuildQuality.FAST_TRACE);
        blasMap.put(sectionKey, blas);
        totalBLASCount++;
        dirtyBLASCount++;
    }

    /**
     * Marks a BLAS as needing rebuild (e.g., after block update).
     */
    public void markDirty(long sectionKey) {
        AccelerationStructure blas = blasMap.get(sectionKey);
        if (blas != null && !blas.isDirty()) {
            blas.markDirty();
            dirtyBLASCount++;
        }
    }

    /**
     * Sets the geometry data for a BLAS.
     *
     * @param sectionKey     Packed section coordinates
     * @param vertexBuffer   VkBuffer containing vertex data
     * @param vertexOffset   Byte offset into vertex buffer
     * @param vertexCount    Number of vertices
     * @param vertexStride   Bytes per vertex (32 for terrain format)
     * @param indexBuffer    VkBuffer containing index data
     * @param indexOffset    Byte offset into index buffer
     * @param indexCount     Number of indices
     * @param useUint32      true for uint32 indices, false for uint16
     */
    public void setGeometry(long sectionKey, long vertexBuffer, long vertexOffset,
                            int vertexCount, int vertexStride,
                            long indexBuffer, long indexOffset, int indexCount,
                            boolean useUint32) {
        if (!rtAvailable) return;

        AccelerationStructure blas = blasMap.get(sectionKey);
        if (blas == null) {
            createBLAS(sectionKey);
            blas = blasMap.get(sectionKey);
            if (blas == null) return;
        }

        blas.setPrimitiveCount(indexCount / 3);
        geometryInfoMap.put(sectionKey, new GeometryInfo(
                vertexBuffer, vertexOffset, vertexCount, vertexStride,
                indexBuffer, indexOffset, indexCount, useUint32));
        if (!blas.isDirty()) {
            blas.markDirty();
            dirtyBLASCount++;
        }

        LOGGER.debug("BLAS geometry updated: sectionKey={} vertices={} indices={} stride={} indexType={}",
                sectionKey, vertexCount, indexCount, vertexStride, useUint32 ? "u32" : "u16");
    }

    /**
     * Sets the per-instance material/custom index consumed by RT hit shaders.
     */
    public void setInstanceMaterialId(long sectionKey, int materialId) {
        AccelerationStructure blas = blasMap.get(sectionKey);
        if (blas != null) {
            blas.setInstanceCustomIndex(materialId);
        }
    }

    /**
     * Sets the per-instance SBT hit-group offset.
     */
    public void setInstanceSbtOffset(long sectionKey, int sbtOffset) {
        AccelerationStructure blas = blasMap.get(sectionKey);
        if (blas != null) {
            blas.setInstanceSbtOffset(sbtOffset);
        }
    }

    /**
     * Records the section world-space origin for this BLAS.
     * Must be set so TLASBuilder can compute proper camera-relative instance transforms.
     *
     * @param sectionKey packed section key
     * @param ox         sectionX * 16 (world block coordinate of section origin X)
     * @param oy         sectionY * 16
     * @param oz         sectionZ * 16
     */
    public void setInstanceWorldOrigin(long sectionKey, float ox, float oy, float oz) {
        AccelerationStructure blas = blasMap.get(sectionKey);
        if (blas != null) {
            blas.setSectionOrigin(ox, oy, oz);
        }
    }

    /**
     * Builds dirty BLASes up to the per-frame budget.
     * Records vkCmdBuildAccelerationStructuresKHR for each into the given command buffer.
     *
     * @param commandBuffer active VkCommandBuffer (must be recording, outside any render pass)
     * @return Number of BLASes built this frame
     */
    public int buildDirtyBLASes(VkCommandBuffer commandBuffer) {
        if (!rtAvailable || dirtyBLASCount == 0 || commandBuffer == null) return 0;
        VkDevice device = Vulkanium.getVulkanDevice().getLogicalDevice();

        buildsThisFrame = 0;

        for (Map.Entry<Long, AccelerationStructure> entry : blasMap.entrySet()) {
            AccelerationStructure blas = entry.getValue();
            if (!blas.isDirty()) continue;
            if (buildsThisFrame >= MAX_BUILDS_PER_FRAME) break;

            long sectionKey = entry.getKey();
            GeometryInfo geo = geometryInfoMap.get(sectionKey);
            if (geo == null || blas.getPrimitiveCount() <= 0 || geo.vertexCount() <= 0) {
                blas.markClean();
                dirtyBLASCount--;
                continue;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Get vertex/index buffer device addresses
                long vertexDA = getBufferDeviceAddress(geo.vertexBuffer());
                long indexDA = geo.indexBuffer() != 0 ? getBufferDeviceAddress(geo.indexBuffer()) : 0;
                if (vertexDA == 0) {
                    blas.markClean();
                    dirtyBLASCount--;
                    continue;
                }

                // Geometry description
                var triangles = VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack)
                        .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                        .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                        .maxVertex(geo.vertexCount() - 1)
                        .vertexStride(geo.vertexStride());
                triangles.vertexData().deviceAddress(vertexDA + geo.vertexOffset());

                if (indexDA != 0 && geo.indexCount() > 0) {
                    triangles.indexType(geo.useUint32() ? VK10.VK_INDEX_TYPE_UINT32 : VK10.VK_INDEX_TYPE_UINT16);
                    triangles.indexData().deviceAddress(indexDA + geo.indexOffset());
                } else {
                    triangles.indexType(KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR);
                }

                var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
                        .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                        .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                        .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);
                geometry.get(0).geometry().triangles(triangles);

                int primitiveCount = blas.getPrimitiveCount();

                // Query build sizes (use buffer of 1 for vkCmdBuildAccelerationStructuresKHR)
                var buildInfoBuf = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
                var buildInfo = buildInfoBuf.get(0)
                        .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                        .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                        .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                        .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .pGeometries(geometry);

                var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                        .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);
                KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                        device,
                        KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                        buildInfo,
                        stack.ints(primitiveCount),
                        sizes);

                long requiredSize = sizes.accelerationStructureSize();
                long scratchRequired = sizes.buildScratchSize();

                // Ensure scratch is big enough
                if (scratchRequired > scratchSize) {
                    allocateScratch(Math.max(scratchRequired, scratchSize * 2));
                }

                // (Re)allocate AS storage buffer if needed
                if (blas.getBuffer() == 0 || blas.getSize() < requiredSize) {
                    if (blas.getHandle() != 0) {
                        KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, blas.getHandle(), null);
                        blas.setHandle(0);
                    }
                    if (blas.getBuffer() != 0) {
                        memory.freeBuffer(blas.getBuffer(), blas.getBufferAllocation());
                        totalBLASMemory -= blas.getSize();
                    }
                    // VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    long[] alloc = memory.allocateBuffer(requiredSize, 0x00200000 | 0x00020000, 0x00000002);
                    blas.setBuffer(alloc[0], alloc[1]);
                    blas.setSize(requiredSize);
                    totalBLASMemory += requiredSize;
                }

                // Create VkAccelerationStructureKHR if we don't have one
                if (blas.getHandle() == 0) {
                    var createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                            .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                            .buffer(blas.getBuffer())
                            .offset(0)
                            .size(requiredSize)
                            .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                    var pAS = stack.mallocLong(1);
                    int res = KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device, createInfo, null, pAS);
                    if (res != VK10.VK_SUCCESS) {
                        LOGGER.warn("vkCreateAccelerationStructureKHR failed for BLAS {}: {}", sectionKey, res);
                        blas.markClean();
                        dirtyBLASCount--;
                        continue;
                    }
                    blas.setHandle(pAS.get(0));
                    // Query device address of the AS
                    var addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                            .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                            .accelerationStructure(pAS.get(0));
                    long asAddr = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(device, addrInfo);
                    blas.setDeviceAddress(asAddr);
                }

                // Build the BLAS
                buildInfo.dstAccelerationStructure(blas.getHandle())
                         .scratchData().deviceAddress(scratchDeviceAddress);

                var rangeInfo = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
                        .primitiveCount(primitiveCount)
                        .primitiveOffset(0)
                        .firstVertex(0)
                        .transformOffset(0);

                KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(
                        commandBuffer,
                        buildInfoBuf,
                        stack.pointers(rangeInfo));

            } catch (Exception e) {
                LOGGER.warn("Failed to build BLAS for section {}: {}", sectionKey, e.getMessage());
            }

            blas.markClean();
            buildsThisFrame++;
            dirtyBLASCount--;
        }

        if (buildsThisFrame > 0) {
            LOGGER.debug("BLAS build pass complete: built={} dirtyRemaining={} total={} memMB={}",
                    buildsThisFrame,
                    dirtyBLASCount,
                    totalBLASCount,
                    totalBLASMemory / (1024 * 1024));
        }

        return buildsThisFrame;
    }

    /**
     * Removes and destroys a BLAS for an unloaded chunk section.
     */
    public void removeBLAS(long sectionKey) {
        AccelerationStructure blas = blasMap.remove(sectionKey);
        if (blas != null) {
            if (blas.isDirty()) dirtyBLASCount--;
            if (blas.getBuffer() != 0) {
                memory.freeBuffer(blas.getBuffer(), blas.getBufferAllocation());
                totalBLASMemory -= blas.getSize();
            }
            totalBLASCount--;
            blas.reset();
        }
    }

    /**
     * Gets the BLAS for a section.
     */
    public AccelerationStructure getBLAS(long sectionKey) {
        return blasMap.get(sectionKey);
    }

    /**
     * Returns all built (non-dirty) BLASes for TLAS construction.
     */
    public java.util.Collection<AccelerationStructure> getBuiltBLASes() {
        return blasMap.values().stream()
                .filter(AccelerationStructure::isBuilt)
                .toList();
    }

    // ── Statistics ──

    public int getTotalBLASCount() { return totalBLASCount; }
    public int getDirtyBLASCount() { return dirtyBLASCount; }
    public int getBuildsThisFrame() { return buildsThisFrame; }
    public long getTotalBLASMemory() { return totalBLASMemory; }
    public boolean isRTAvailable() { return rtAvailable; }

    // ── Internal ──

    private void allocateScratch(long size) {
        if (scratchBuffer != 0) {
            memory.freeBuffer(scratchBuffer, scratchAllocation);
        }
        try {
            // VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            long[] result = memory.allocateBuffer(size, 0x00000020 | 0x00020000, 0x00000002);
            scratchBuffer = result[0];
            scratchAllocation = result[1];
            scratchSize = size;
            scratchDeviceAddress = getBufferDeviceAddress(scratchBuffer);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to allocate scratch buffer ({} MB) — RT extensions may not be enabled at device level",
                    size / (1024 * 1024));
            scratchBuffer = 0;
            scratchAllocation = 0;
            scratchSize = 0;
            scratchDeviceAddress = 0;
            rtAvailable = false;
        }
    }

    // ── Lifecycle ──

    public void destroy() {
        VkDevice device = Vulkanium.getVulkanDevice() != null ? Vulkanium.getVulkanDevice().getLogicalDevice() : null;
        for (AccelerationStructure blas : blasMap.values()) {
            if (blas.getHandle() != 0 && device != null) {
                KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, blas.getHandle(), null);
            }
            if (blas.getBuffer() != 0) {
                memory.freeBuffer(blas.getBuffer(), blas.getBufferAllocation());
            }
            blas.reset();
        }
        blasMap.clear();
        geometryInfoMap.clear();

        if (scratchBuffer != 0) {
            memory.freeBuffer(scratchBuffer, scratchAllocation);
            scratchBuffer = 0;
        }

        totalBLASCount = 0;
        dirtyBLASCount = 0;
        totalBLASMemory = 0;

        LOGGER.info("BLAS manager destroyed");
    }

    private long estimateBLASSizeBytes(int primitiveCount) {
        return Math.max(64 * 1024L, primitiveCount * 96L);
    }

    private void allocateOrResizeBLASStorage(AccelerationStructure blas, long requiredSize) {
        if (blas.getBuffer() != 0) {
            memory.freeBuffer(blas.getBuffer(), blas.getBufferAllocation());
            totalBLASMemory -= blas.getSize();
            blas.setBuffer(0, 0);
            blas.setHandle(0);
            blas.setDeviceAddress(0);
            blas.setSize(0);
        }

        // VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
        long[] alloc = memory.allocateBuffer(requiredSize, 0x00200000 | 0x00020000, 0x00000002);
        blas.setBuffer(alloc[0], alloc[1]);
        blas.setSize(requiredSize);
        totalBLASMemory += requiredSize;
    }

    private long getBufferDeviceAddress(long buffer) {
        if (buffer == 0 || Vulkanium.getVulkanDevice() == null) return 0;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferDeviceAddressInfo info = org.lwjgl.vulkan.VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(buffer);
            return org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress(Vulkanium.getVulkanDevice().getLogicalDevice(), info);
        } catch (Throwable t) {
            LOGGER.debug("BLAS device address query failed: {}", t.getMessage());
            return 0;
        }
    }
}
