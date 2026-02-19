package net.vulkanium.rt;

import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.core.VulkaniumQueues;
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

    // ── State ──
    private final VulkaniumMemory memory;
    private final VulkaniumQueues queues;

    /** Section key → BLAS mapping */
    private final Map<Long, AccelerationStructure> blasMap = new ConcurrentHashMap<>();

    /** Shared scratch buffer for BLAS builds */
    private long scratchBuffer = 0;
    private long scratchAllocation = 0;
    private long scratchSize = 0;

    /** Statistics */
    private int totalBLASCount = 0;
    private int dirtyBLASCount = 0;
    private int buildsThisFrame = 0;
    private long totalBLASMemory = 0;

    /** Whether RT is available (checked at initialization) */
    private boolean rtAvailable = false;

    public BLASManager(VulkaniumMemory memory, VulkaniumQueues queues) {
        this.memory = memory;
        this.queues = queues;
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
        if (blas == null) return;

        blas.setPrimitiveCount(indexCount / 3);
        blas.markDirty();

        // TODO Phase 10: Store geometry references for vkCmdBuildAccelerationStructuresKHR
        // VkAccelerationStructureGeometryKHR with:
        //   geometryType = VK_GEOMETRY_TYPE_TRIANGLES_KHR
        //   geometry.triangles.vertexFormat = VK_FORMAT_R32G32B32_SFLOAT
        //   geometry.triangles.vertexData.deviceAddress = vertexBuffer device address + vertexOffset
        //   geometry.triangles.vertexStride = vertexStride
        //   geometry.triangles.maxVertex = vertexCount - 1
        //   geometry.triangles.indexType = useUint32 ? VK_INDEX_TYPE_UINT32 : VK_INDEX_TYPE_UINT16
        //   geometry.triangles.indexData.deviceAddress = indexBuffer device address + indexOffset
    }

    /**
     * Builds dirty BLASes up to the per-frame budget.
     *
     * @return Number of BLASes built this frame
     */
    public int buildDirtyBLASes() {
        if (!rtAvailable || dirtyBLASCount == 0) return 0;

        buildsThisFrame = 0;

        // TODO Phase 10: Implement actual BLAS building
        // 1. Collect dirty BLASes, sort by priority
        // 2. For each BLAS, query build sizes:
        //    vkGetAccelerationStructureBuildSizesKHR(...)
        // 3. Allocate BLAS backing buffers if needed
        // 4. Ensure scratch buffer is large enough
        // 5. Record vkCmdBuildAccelerationStructuresKHR commands
        // 6. Submit to compute queue

        for (AccelerationStructure blas : blasMap.values()) {
            if (!blas.isDirty()) continue;
            if (buildsThisFrame >= MAX_BUILDS_PER_FRAME) break;

            // Placeholder: mark as clean (real impl would build on GPU)
            blas.markClean();
            buildsThisFrame++;
            dirtyBLASCount--;
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
            totalBLASMemory -= blas.getSize();
            totalBLASCount--;
            // TODO Phase 10: vkDestroyAccelerationStructureKHR, free buffer
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
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to allocate scratch buffer ({} MB) — RT extensions may not be enabled at device level",
                    size / (1024 * 1024));
            scratchBuffer = 0;
            scratchAllocation = 0;
            scratchSize = 0;
            rtAvailable = false;
        }
    }

    // ── Lifecycle ──

    public void destroy() {
        for (AccelerationStructure blas : blasMap.values()) {
            // TODO Phase 10: vkDestroyAccelerationStructureKHR per BLAS
            blas.reset();
        }
        blasMap.clear();

        if (scratchBuffer != 0) {
            memory.freeBuffer(scratchBuffer, scratchAllocation);
            scratchBuffer = 0;
        }

        totalBLASCount = 0;
        dirtyBLASCount = 0;
        totalBLASMemory = 0;

        LOGGER.info("BLAS manager destroyed");
    }
}
