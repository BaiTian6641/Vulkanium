package net.vulkanium.render.terrain.section;

import net.vulkanium.render.terrain.pass.TerrainPassType;
import net.vulkanium.render.terrain.region.RenderRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a single 16×16×16 chunk section for rendering.
 *
 * <p>Each RenderSection corresponds to one Minecraft chunk section (a 16³ block volume).
 * Sections are grouped into {@link RenderRegion}s (8×4×8 = 256 sections per region)
 * for efficient batched rendering via multi-draw-indirect.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Created when a chunk section enters render distance</li>
 *   <li>Mesh built asynchronously by {@code ChunkBuildTask}</li>
 *   <li>Mesh uploaded to region vertex/index buffers via {@code ChunkUploadManager}</li>
 *   <li>Drawn as part of region's MDI command each frame</li>
 *   <li>Rebuilt when block state changes within the section</li>
 *   <li>Destroyed when the section leaves render distance</li>
 * </ol>
 *
 * <h3>Inspired by Sodium's {@code RenderSection}</h3>
 * <p>Sodium stores sections in a flat array indexed by position, with build results
 * written by worker threads and uploaded per-region. Vulkanium follows the same indexing
 * but maps to Vulkan buffers and MDI commands instead of GL multi-draw.</p>
 */
public class RenderSection {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RenderSection");

    // Section coordinates (in section units, not blocks)
    private final int sectionX;
    private final int sectionY;
    private final int sectionZ;

    // Compact key for HashMap lookup: encodes x,y,z into a single long
    private final long packedKey;

    // Visibility state
    private final SectionVisibility visibility;

    // Per-pass GPU buffer offsets within the owning region's concatenated buffers
    private final PassGPUSlot[] gpuSlots;

    // Region membership
    private RenderRegion region;
    private int indexInRegion = -1; // My slot within the region's section array

    // Build state
    private volatile SectionBuildState buildState = SectionBuildState.NEEDS_BUILD;
    private long lastBuildTime = 0;
    private int buildPriority = 0;

    // Flags
    private boolean isEmpty = true;
    private boolean hasTranslucents = false;

    public RenderSection(int sectionX, int sectionY, int sectionZ) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.packedKey = packSectionKey(sectionX, sectionY, sectionZ);
        this.visibility = new SectionVisibility();
        this.visibility.setAABB(sectionX, sectionY, sectionZ);
        this.gpuSlots = new PassGPUSlot[TerrainPassType.count()];
    }

    /**
     * Packs section coordinates into a single long key.
     * Supports coordinates from -2^20 to 2^20 in X/Z and -128 to 383 in Y.
     */
    public static long packSectionKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF)) |
               ((long) (z & 0x1FFFFF) << 21) |
               ((long) (y & 0x3FF) << 42);
    }

    // ============== Build State ==============

    public enum SectionBuildState {
        /** Needs initial mesh build or rebuild due to block changes. */
        NEEDS_BUILD,
        /** Build task has been submitted and is pending. */
        BUILD_PENDING,
        /** Build completed, data waiting for GPU upload. */
        UPLOAD_PENDING,
        /** Data uploaded and ready to render. */
        READY,
        /** Section is being discarded (out of render distance). */
        DISCARDED
    }

    /**
     * Marks this section as needing a rebuild (e.g., due to block updates).
     */
    public void markDirty() {
        if (buildState != SectionBuildState.DISCARDED) {
            buildState = SectionBuildState.NEEDS_BUILD;
        }
    }

    /**
     * Computes build priority based on distance and staleness.
     * Lower values = higher priority.
     *
     * @param cameraX Camera position
     * @param cameraY Camera position
     * @param cameraZ Camera position
     * @param currentTime Current time in millis
     */
    public void updateBuildPriority(double cameraX, double cameraY, double cameraZ, long currentTime) {
        visibility.updateDistance(cameraX, cameraY, cameraZ);
        float dist = visibility.getDistanceSq();

        // Staleness: sections waiting longer get higher priority
        long waitTime = currentTime - lastBuildTime;
        float staleness = Math.min(waitTime / 1000.0f, 10.0f); // Cap at 10s

        // Priority: closer = more urgent, staler = more urgent
        // Score is inverted so lower = higher priority for PriorityQueue
        buildPriority = (int) (dist - staleness * 100.0f);
    }

    /**
     * Applies build results from a completed ChunkBuildTask.
     * Called from the upload manager after mesh data is built.
     */
    public void applyBuildResults(SectionData data) {
        this.isEmpty = data.isEmpty();
        this.hasTranslucents = data.hasTranslucents();

        if (!data.isEmpty()) {
            buildState = SectionBuildState.UPLOAD_PENDING;
        } else {
            buildState = SectionBuildState.READY; // Empty sections are trivially "ready"
        }
        this.lastBuildTime = System.currentTimeMillis();
    }

    /**
     * Records GPU buffer offsets after upload to region buffers.
     */
    public void setGPUSlot(TerrainPassType pass, long vertexOffset, long indexOffset,
                           int vertexCount, int indexCount) {
        gpuSlots[pass.ordinal()] = new PassGPUSlot(vertexOffset, indexOffset, vertexCount, indexCount);
    }

    /**
     * Marks this section as fully uploaded and renderable.
     */
    public void markReady() {
        buildState = SectionBuildState.READY;
    }

    /**
     * Marks this section as discarded (leaving render distance or world unload).
     */
    public void discard() {
        buildState = SectionBuildState.DISCARDED;
        region = null;
        for (int i = 0; i < gpuSlots.length; i++) {
            gpuSlots[i] = null;
        }
    }

    // ============== Getters ==============

    public int getSectionX() { return sectionX; }
    public int getSectionY() { return sectionY; }
    public int getSectionZ() { return sectionZ; }
    public long getPackedKey() { return packedKey; }
    public SectionVisibility getVisibility() { return visibility; }
    public SectionBuildState getBuildState() { return buildState; }
    public void setBuildState(SectionBuildState state) { this.buildState = state; }
    public int getBuildPriority() { return buildPriority; }
    public boolean isEmpty() { return isEmpty; }
    public boolean hasTranslucents() { return hasTranslucents; }

    public RenderRegion getRegion() { return region; }
    public void setRegion(RenderRegion region) { this.region = region; }
    public int getIndexInRegion() { return indexInRegion; }
    public void setIndexInRegion(int index) { this.indexInRegion = index; }

    public PassGPUSlot getGPUSlot(TerrainPassType pass) {
        return gpuSlots[pass.ordinal()];
    }

    /** Block-space origin X of this section. */
    public int getBlockX() { return sectionX << 4; }
    /** Block-space origin Y of this section. */
    public int getBlockY() { return sectionY << 4; }
    /** Block-space origin Z of this section. */
    public int getBlockZ() { return sectionZ << 4; }

    /**
     * GPU buffer location for a single pass within a section.
     * Offsets are relative to the region's concatenated vertex/index buffers.
     */
    public record PassGPUSlot(long vertexOffset, long indexOffset, int vertexCount, int indexCount) {
        /** Returns true if this slot has geometry to draw. */
        public boolean hasGeometry() {
            return indexCount > 0;
        }
    }

    @Override
    public String toString() {
        return String.format("RenderSection[%d, %d, %d | %s | empty=%s]",
                sectionX, sectionY, sectionZ, buildState, isEmpty);
    }
}
