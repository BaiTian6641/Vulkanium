package net.vulkanium.render.terrain.region;

import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.render.terrain.pass.TerrainPassType;
import net.vulkanium.render.terrain.section.RenderSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A render region groups 8×4×8 = 256 chunk sections for batched rendering.
 *
 * <p>Inspired by Sodium's {@code RenderRegion}, which groups sections so that an entire
 * region can be drawn in a single multi-draw-indirect (MDI) call. This dramatically
 * reduces draw call overhead — from thousands of per-section draw calls to ~20-50
 * per-region MDI calls for a 16-chunk render distance.</p>
 *
 * <h3>Buffer Layout</h3>
 * <p>Each region owns a contiguous GPU vertex buffer and index buffer. All sections
 * within the region write their mesh data into sub-allocations of these buffers.
 * The indirect draw buffer contains one {@code VkDrawIndexedIndirectCommand} per
 * visible section, enabling the GPU to skip invisible sections without CPU intervention.</p>
 *
 * <h3>Memory Strategy</h3>
 * <ul>
 *   <li>Vertex buffer: device-local, grows on demand (128 MB initial budget shared across all regions)</li>
 *   <li>Index buffer: device-local, similarly managed</li>
 *   <li>Indirect buffer: host-visible + device-local (small, rebuilt per frame)</li>
 * </ul>
 *
 * <h3>Region Coordinates</h3>
 * <p>A region at coordinate (rx, ry, rz) covers sections [rx*8, rx*8+7] × [ry*4, ry*4+3] × [rz*8, rz*8+7].
 * Region coordinate = section coordinate >> SIZE_SHIFT.</p>
 */
public class RenderRegion {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RenderRegion");

    /** Number of sections along X axis per region. */
    public static final int SIZE_X = 8;
    /** Number of sections along Y axis per region. */
    public static final int SIZE_Y = 4;
    /** Number of sections along Z axis per region. */
    public static final int SIZE_Z = 8;
    /** Total sections per region. */
    public static final int SECTION_COUNT = SIZE_X * SIZE_Y * SIZE_Z; // 256

    /** Bit shift for section→region coordinate conversion (log2 of SIZE_X/Z). */
    public static final int SIZE_SHIFT_X = 3; // log2(8)
    public static final int SIZE_SHIFT_Y = 2; // log2(4)
    public static final int SIZE_SHIFT_Z = 3; // log2(8)

    // Region coordinates (in region units)
    private final int regionX;
    private final int regionY;
    private final int regionZ;
    private final long packedKey;

    // Section storage — array indexed by local section position
    private final RenderSection[] sections;
    private int activeSectionCount = 0;

    // Per-pass GPU buffers (allocated lazily when first section has geometry)
    private final RegionGPUBuffers[] passBuffers;

    // State flags
    private boolean dirty = false;
    private boolean disposed = false;

    public RenderRegion(int regionX, int regionY, int regionZ) {
        this.regionX = regionX;
        this.regionY = regionY;
        this.regionZ = regionZ;
        this.packedKey = packRegionKey(regionX, regionY, regionZ);
        this.sections = new RenderSection[SECTION_COUNT];
        this.passBuffers = new RegionGPUBuffers[TerrainPassType.count()];
    }

    /**
     * Packs region coordinates into a single long key.
     */
    public static long packRegionKey(int rx, int ry, int rz) {
        return ((long) (rx & 0xFFFFF)) |
               ((long) (rz & 0xFFFFF) << 20) |
               ((long) (ry & 0xFFF) << 40);
    }

    /**
     * Converts section coordinates to region coordinates.
     */
    public static int sectionToRegionX(int sectionX) { return sectionX >> SIZE_SHIFT_X; }
    public static int sectionToRegionY(int sectionY) { return sectionY >> SIZE_SHIFT_Y; }
    public static int sectionToRegionZ(int sectionZ) { return sectionZ >> SIZE_SHIFT_Z; }

    /**
     * Computes the local index of a section within this region.
     */
    public static int localIndex(int sectionX, int sectionY, int sectionZ) {
        int lx = sectionX & (SIZE_X - 1);
        int ly = sectionY & (SIZE_Y - 1);
        int lz = sectionZ & (SIZE_Z - 1);
        return (ly * SIZE_X * SIZE_Z) + (lz * SIZE_X) + lx;
    }

    // ============== Section Management ==============

    /**
     * Adds a section to this region at its appropriate local slot.
     *
     * @return The local index assigned to the section
     */
    public int addSection(RenderSection section) {
        int localIdx = localIndex(section.getSectionX(), section.getSectionY(), section.getSectionZ());
        if (sections[localIdx] != null) {
            LOGGER.warn("Replacing existing section at local index {} in region [{}, {}, {}]",
                    localIdx, regionX, regionY, regionZ);
        }
        sections[localIdx] = section;
        section.setRegion(this);
        section.setIndexInRegion(localIdx);
        activeSectionCount++;
        dirty = true;
        return localIdx;
    }

    /**
     * Removes a section from this region.
     */
    public void removeSection(RenderSection section) {
        int localIdx = section.getIndexInRegion();
        if (localIdx >= 0 && localIdx < SECTION_COUNT && sections[localIdx] == section) {
            sections[localIdx] = null;
            section.setRegion(null);
            section.setIndexInRegion(-1);
            activeSectionCount--;
            dirty = true;
        }
    }

    /**
     * Returns the section at the given local index, or null.
     */
    public RenderSection getSection(int localIndex) {
        return sections[localIndex];
    }

    /**
     * Collects all sections that need to be rebuilt (NEEDS_BUILD state).
     */
    public List<RenderSection> collectDirtySections() {
        List<RenderSection> dirty = new ArrayList<>();
        for (RenderSection section : sections) {
            if (section != null &&
                section.getBuildState() == RenderSection.SectionBuildState.NEEDS_BUILD) {
                dirty.add(section);
            }
        }
        return dirty;
    }

    /**
     * Collects all visible, ready-to-render sections for a specific pass type.
     */
    public List<RenderSection> collectVisibleSections(TerrainPassType pass) {
        List<RenderSection> visible = new ArrayList<>();
        for (RenderSection section : sections) {
            if (section != null &&
                section.getBuildState() == RenderSection.SectionBuildState.READY &&
                section.getVisibility().isVisible() &&
                !section.isEmpty()) {
                RenderSection.PassGPUSlot slot = section.getGPUSlot(pass);
                if (slot != null && slot.hasGeometry()) {
                    visible.add(section);
                }
            }
        }
        return visible;
    }

    // ============== GPU Buffer Management ==============

    /**
     * Returns the GPU buffers for a given pass, creating them lazily if needed.
     */
    public RegionGPUBuffers getOrCreatePassBuffers(TerrainPassType pass, VulkaniumMemory memory) {
        if (passBuffers[pass.ordinal()] == null) {
            passBuffers[pass.ordinal()] = new RegionGPUBuffers(memory, this, pass);
        }
        return passBuffers[pass.ordinal()];
    }

    /**
     * Returns existing GPU buffers for a pass, or null if none allocated.
     */
    public RegionGPUBuffers getPassBuffers(TerrainPassType pass) {
        return passBuffers[pass.ordinal()];
    }

    // ============== State ==============

    public boolean isEmpty() { return activeSectionCount == 0; }
    public boolean isDirty() { return dirty; }
    public void clearDirty() { dirty = false; }
    public boolean isDisposed() { return disposed; }

    /**
     * Releases all GPU resources for this region.
     */
    public void dispose() {
        disposed = true;
        for (int i = 0; i < passBuffers.length; i++) {
            if (passBuffers[i] != null) {
                passBuffers[i].destroy();
                passBuffers[i] = null;
            }
        }
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null) {
                sections[i].discard();
                sections[i] = null;
            }
        }
        activeSectionCount = 0;
    }

    // ============== Getters ==============

    public int getRegionX() { return regionX; }
    public int getRegionY() { return regionY; }
    public int getRegionZ() { return regionZ; }
    public long getPackedKey() { return packedKey; }
    public int getActiveSectionCount() { return activeSectionCount; }

    /** World-space origin X in blocks. */
    public int getBlockOriginX() { return regionX * SIZE_X * 16; }
    /** World-space origin Y in blocks. */
    public int getBlockOriginY() { return regionY * SIZE_Y * 16; }
    /** World-space origin Z in blocks. */
    public int getBlockOriginZ() { return regionZ * SIZE_Z * 16; }

    @Override
    public String toString() {
        return String.format("RenderRegion[%d, %d, %d | sections=%d | dirty=%s]",
                regionX, regionY, regionZ, activeSectionCount, dirty);
    }
}
