package net.vulkanium.render.terrain.region;

import net.vulkanium.render.terrain.section.RenderSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages the lifecycle of render regions across the entire render distance.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Creates regions on-demand as sections are added</li>
 *   <li>Maps section coordinates → region</li>
 *   <li>Removes empty regions to free GPU memory</li>
 *   <li>Provides iteration over all active regions for rendering</li>
 *   <li>Handles render distance changes by culling/extending regions</li>
 * </ul>
 *
 * <h3>Design: Flat HashMap</h3>
 * <p>Regions are stored in a HashMap keyed by packed region coordinates.
 * This is simpler than Sodium's sorted array approach but equally efficient
 * for the expected region count (~100-200 at 16 chunk render distance).</p>
 */
public class RenderRegionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RegionManager");

    /** All active regions, keyed by packed region coordinate. */
    private final Map<Long, RenderRegion> regions = new HashMap<>();

    /** Flat list of all non-empty regions (rebuilt when regions change). */
    private final List<RenderRegion> activeRegionList = new ArrayList<>();
    private boolean activeListDirty = true;

    /** All active sections, keyed by packed section coordinate. */
    private final Map<Long, RenderSection> sections = new HashMap<>();

    /** Current render distance in chunks. */
    private int renderDistance = 12;

    /** Camera position (section coordinates). */
    private int cameraSectionX, cameraSectionY, cameraSectionZ;

    // ============== Section Management ==============

    /**
     * Adds a section to the world. Creates its containing region if necessary.
     *
     * @return The newly created or existing RenderSection
     */
    public RenderSection addSection(int sectionX, int sectionY, int sectionZ) {
        long sectionKey = RenderSection.packSectionKey(sectionX, sectionY, sectionZ);

        // Check if already exists
        RenderSection existing = sections.get(sectionKey);
        if (existing != null) {
            return existing;
        }

        // Create section
        RenderSection section = new RenderSection(sectionX, sectionY, sectionZ);

        // Find or create region
        RenderRegion region = getOrCreateRegion(
                RenderRegion.sectionToRegionX(sectionX),
                RenderRegion.sectionToRegionY(sectionY),
                RenderRegion.sectionToRegionZ(sectionZ)
        );
        region.addSection(section);
        sections.put(sectionKey, section);

        return section;
    }

    /**
     * Removes a section from the world. Disposes its containing region if now empty.
     */
    public void removeSection(int sectionX, int sectionY, int sectionZ) {
        long sectionKey = RenderSection.packSectionKey(sectionX, sectionY, sectionZ);
        RenderSection section = sections.remove(sectionKey);
        if (section == null) return;

        RenderRegion region = section.getRegion();
        if (region != null) {
            region.removeSection(section);
            if (region.isEmpty()) {
                removeRegion(region);
            }
        }
        section.discard();
    }

    /**
     * Returns the section at the given coordinates, or null.
     */
    public RenderSection getSection(int sectionX, int sectionY, int sectionZ) {
        return sections.get(RenderSection.packSectionKey(sectionX, sectionY, sectionZ));
    }

    /**
     * Marks a section as needing rebuild due to block changes.
     */
    public void markSectionDirty(int sectionX, int sectionY, int sectionZ) {
        RenderSection section = getSection(sectionX, sectionY, sectionZ);
        if (section != null) {
            section.markDirty();
        }
    }

    // ============== Region Management ==============

    /**
     * Gets or creates a region at the given region coordinates.
     */
    public RenderRegion getOrCreateRegion(int regionX, int regionY, int regionZ) {
        long regionKey = RenderRegion.packRegionKey(regionX, regionY, regionZ);
        return regions.computeIfAbsent(regionKey, k -> {
            RenderRegion region = new RenderRegion(regionX, regionY, regionZ);
            activeListDirty = true;
            LOGGER.debug("Created region [{}, {}, {}]", regionX, regionY, regionZ);
            return region;
        });
    }

    private void removeRegion(RenderRegion region) {
        regions.remove(region.getPackedKey());
        region.dispose();
        activeListDirty = true;
        LOGGER.debug("Removed empty region [{}, {}, {}]",
                region.getRegionX(), region.getRegionY(), region.getRegionZ());
    }

    /**
     * Returns an unmodifiable view of all active regions.
     * The list is lazily rebuilt when regions change.
     */
    public List<RenderRegion> getActiveRegions() {
        if (activeListDirty) {
            activeRegionList.clear();
            for (RenderRegion region : regions.values()) {
                if (!region.isEmpty() && !region.isDisposed()) {
                    activeRegionList.add(region);
                }
            }
            activeListDirty = false;
        }
        return Collections.unmodifiableList(activeRegionList);
    }

    // ============== Frustum & Distance ==============

    /**
     * Updates the camera position for distance-based prioritization.
     */
    public void updateCameraPosition(double cameraX, double cameraY, double cameraZ) {
        this.cameraSectionX = (int) Math.floor(cameraX) >> 4;
        this.cameraSectionY = (int) Math.floor(cameraY) >> 4;
        this.cameraSectionZ = (int) Math.floor(cameraZ) >> 4;
    }

    /**
     * Sets the render distance and removes sections/regions that are now out of range.
     */
    public void setRenderDistance(int chunks) {
        if (this.renderDistance == chunks) return;
        this.renderDistance = chunks;
        LOGGER.info("Render distance changed to {} chunks", chunks);
        // Sections outside the new distance will be cleaned up by the next cull pass
    }

    /**
     * Collects all sections that need to be rebuilt, sorted by priority.
     *
     * @param maxSections Maximum number of sections to return (for rate limiting)
     * @return Priority-sorted list of sections needing rebuild
     */
    public List<RenderSection> collectBuildQueue(int maxSections) {
        long now = System.currentTimeMillis();
        List<RenderSection> buildQueue = new ArrayList<>();

        for (RenderSection section : sections.values()) {
            if (section.getBuildState() == RenderSection.SectionBuildState.NEEDS_BUILD) {
                section.updateBuildPriority(
                        cameraSectionX * 16.0 + 8.0,
                        cameraSectionY * 16.0 + 8.0,
                        cameraSectionZ * 16.0 + 8.0,
                        now
                );
                buildQueue.add(section);
            }
        }

        // Sort by priority (lower = higher priority)
        buildQueue.sort(Comparator.comparingInt(RenderSection::getBuildPriority));

        // Limit the number of sections to build per frame
        if (buildQueue.size() > maxSections) {
            return buildQueue.subList(0, maxSections);
        }
        return buildQueue;
    }

    /**
     * Performs distance culling — removes sections beyond the render distance.
     */
    public void cullDistantSections() {
        int maxDistSq = (renderDistance + 1) * (renderDistance + 1) * 256; // block distance squared

        Iterator<Map.Entry<Long, RenderSection>> it = sections.entrySet().iterator();
        while (it.hasNext()) {
            RenderSection section = it.next().getValue();
            int dx = section.getSectionX() - cameraSectionX;
            int dz = section.getSectionZ() - cameraSectionZ;
            int distSq = dx * dx + dz * dz;

            if (distSq > maxDistSq) {
                RenderRegion region = section.getRegion();
                if (region != null) {
                    region.removeSection(section);
                    if (region.isEmpty()) {
                        removeRegion(region);
                    }
                }
                section.discard();
                it.remove();
            }
        }
    }

    // ============== Cleanup ==============

    /**
     * Destroys all regions and sections.
     */
    public void destroy() {
        for (RenderRegion region : regions.values()) {
            region.dispose();
        }
        regions.clear();
        sections.clear();
        activeRegionList.clear();
        LOGGER.info("Region manager destroyed ({} sections freed)", sections.size());
    }

    // ============== Statistics ==============

    public int getRegionCount() { return regions.size(); }
    public int getSectionCount() { return sections.size(); }
    public int getRenderDistance() { return renderDistance; }
}
