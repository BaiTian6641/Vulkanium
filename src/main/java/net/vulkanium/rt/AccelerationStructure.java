package net.vulkanium.rt;


/**
 * Vulkan acceleration structure wrapper — the fundamental data structure for ray tracing.
 *
 * <p>An acceleration structure (AS) is a GPU-optimized spatial data structure (typically
 * a BVH — bounding volume hierarchy) that enables efficient ray-scene intersection queries.
 * Vulkan provides two levels:</p>
 *
 * <ul>
 *   <li><b>BLAS (Bottom-Level):</b> Contains geometry (triangles or AABBs) for a single
 *       object/chunk. Built once, reused many times per frame.</li>
 *   <li><b>TLAS (Top-Level):</b> Contains instances of BLASes with per-instance transforms.
 *       Rebuilt every frame from the set of visible BLASes.</li>
 * </ul>
 *
 * <h3>Minecraft Integration (Phase 10)</h3>
 * <pre>
 *   BLAS per chunk section (16×16×16 blocks)
 *     → Geometry from ChunkVertexFormat (same vertex buffer as rasterization!)
 *     → Built on chunk load/update, cached until section changes
 *
 *   TLAS per frame
 *     → One instance per visible chunk section BLAS
 *     → Transform = chunk-to-world translation matrix
 *     → Rebuilt every frame from visibility set
 * </pre>
 *
 * <h3>Required Extensions</h3>
 * <ul>
 *   <li>{@code VK_KHR_acceleration_structure}</li>
 *   <li>{@code VK_KHR_ray_tracing_pipeline} (for rt pipeline) OR
 *       {@code VK_KHR_ray_query} (for inline ray queries in compute/fragment)</li>
 *   <li>{@code VK_KHR_deferred_host_operations}</li>
 *   <li>{@code VK_KHR_buffer_device_address}</li>
 * </ul>
 *
 * <h3>Compatibility Tiers</h3>
 * <ul>
 *   <li><b>Tier 0 (None):</b> No RT extensions → RT disabled, rasterization only</li>
 *   <li><b>Tier 1 (Ray Query):</b> {@code VK_KHR_ray_query} → Hybrid RT (rasterize + ray query
 *       for shadows/AO in fragment shaders)</li>
 *   <li><b>Tier 2 (Full RT):</b> {@code VK_KHR_ray_tracing_pipeline} → Full path tracing
 *       (ray generation + closest hit + miss shaders)</li>
 * </ul>
 */
public class AccelerationStructure {
    /** Acceleration structure types */
    public enum Type {
        BOTTOM_LEVEL, // BLAS — per-chunk geometry
        TOP_LEVEL     // TLAS — per-frame instance set
    }

    /** Build quality hints */
    public enum BuildQuality {
        FAST_BUILD,   // Optimize for build speed (used for TLAS, rebuilt every frame)
        FAST_TRACE,   // Optimize for trace performance (used for BLAS, built once)
        BALANCED      // Balance between build and trace
    }

    // ── State ──
    private final Type type;
    private final BuildQuality quality;

    /** VkAccelerationStructureKHR handle */
    private long handle = 0;

    /** Backing VkBuffer for the acceleration structure */
    private long buffer = 0;
    private long bufferAllocation = 0;

    /** Device address of the acceleration structure */
    private long deviceAddress = 0;

    /** Size of the acceleration structure in bytes */
    private long size = 0;

    /** Whether this structure needs rebuilding */
    private boolean dirty = true;

    /** Geometry/instance count (for diagnostics) */
    private int primitiveCount = 0;

    /** Instance custom index used by hit shaders (material/entity class id). */
    private int instanceCustomIndex = 0;

    /** Instance SBT hit-group offset. */
    private int instanceSbtOffset = 0;

    /**
     * World-space origin of the section this BLAS represents.
     * Set to sectionX*16, sectionY*16, sectionZ*16.
     * Used as the TLAS instance translation relative to camera.
     */
    private float sectionOriginX = 0.0f;
    private float sectionOriginY = 0.0f;
    private float sectionOriginZ = 0.0f;

    public AccelerationStructure(Type type, BuildQuality quality) {
        this.type = type;
        this.quality = quality;
    }

    // ── Getters ──

    public Type getType() { return type; }
    public BuildQuality getQuality() { return quality; }
    public long getHandle() { return handle; }
    public long getBuffer() { return buffer; }
    public long getBufferAllocation() { return bufferAllocation; }
    public long getDeviceAddress() { return deviceAddress; }
    public long getSize() { return size; }
    public boolean isDirty() { return dirty; }
    public int getPrimitiveCount() { return primitiveCount; }
    public int getInstanceCustomIndex() { return instanceCustomIndex; }
    public int getInstanceSbtOffset() { return instanceSbtOffset; }
    public float getSectionOriginX() { return sectionOriginX; }
    public float getSectionOriginY() { return sectionOriginY; }
    public float getSectionOriginZ() { return sectionOriginZ; }

    // ── State management ──

    public void setHandle(long handle) { this.handle = handle; }
    public void setBuffer(long buffer, long allocation) {
        this.buffer = buffer;
        this.bufferAllocation = allocation;
    }
    public void setDeviceAddress(long deviceAddress) { this.deviceAddress = deviceAddress; }
    public void setSize(long size) { this.size = size; }
    public void markDirty() { this.dirty = true; }
    public void markClean() { this.dirty = false; }
    public void setSectionOrigin(float ox, float oy, float oz) {
        this.sectionOriginX = ox;
        this.sectionOriginY = oy;
        this.sectionOriginZ = oz;
    }
    public void setPrimitiveCount(int count) { this.primitiveCount = count; }
    public void setInstanceCustomIndex(int index) { this.instanceCustomIndex = index & 0x00FFFFFF; }
    public void setInstanceSbtOffset(int offset) { this.instanceSbtOffset = offset & 0x00FFFFFF; }

    /**
     * Returns whether this acceleration structure has been built.
     */
    public boolean isBuilt() {
        return handle != 0 && !dirty;
    }

    /**
     * Destroys the acceleration structure.
     * Note: Actual Vulkan cleanup requires VkDevice — delegated to BLASManager/TLASBuilder.
     */
    public void reset() {
        handle = 0;
        buffer = 0;
        bufferAllocation = 0;
        deviceAddress = 0;
        size = 0;
        dirty = true;
        primitiveCount = 0;
        instanceCustomIndex = 0;
        instanceSbtOffset = 0;
    }

    @Override
    public String toString() {
        return String.format("AccelerationStructure{type=%s, quality=%s, prims=%d, size=%dKB, built=%s}",
                type, quality, primitiveCount, size / 1024, isBuilt());
    }
}
