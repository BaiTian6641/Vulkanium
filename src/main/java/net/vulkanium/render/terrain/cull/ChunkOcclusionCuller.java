package net.vulkanium.render.terrain.cull;

import net.vulkanium.render.terrain.region.RenderRegion;
import net.vulkanium.render.terrain.region.RenderRegionManager;
import net.vulkanium.render.terrain.section.RenderSection;
import net.vulkanium.render.terrain.section.SectionVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Graph-based occlusion culling for chunk sections.
 *
 * <p>Inspired by Sodium's BFS graph traversal from the camera section outward.
 * Sections that cannot be reached from the camera through connected visible
 * faces are marked as occluded. This efficiently culls sections hidden behind
 * walls, underground, or behind mountains.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Start BFS from the section containing the camera</li>
 *   <li>For each visited section, check which faces are "open" (can see through)</li>
 *   <li>Propagate visibility through open faces to neighboring sections</li>
 *   <li>Sections not reached by BFS are marked as occluded</li>
 * </ol>
 *
 * <h3>Face Connectivity</h3>
 * <p>A section face is "open" if the section has geometry that doesn't fully cover
 * the face. This is computed during mesh building (ChunkBuildTask) and stored as
 * a 6-bit mask per section (one bit per face: +X, -X, +Y, -Y, +Z, -Z).</p>
 *
 * <h3>Complementary to Frustum Culling</h3>
 * <p>Frustum culling removes what's outside the view. Occlusion culling removes
 * what's inside the view but hidden behind solid geometry. Together they eliminate
 * most invisible sections.</p>
 */
public class ChunkOcclusionCuller {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/OcclusionCull");

    /** Face direction indices. */
    public static final int FACE_POS_X = 0;
    public static final int FACE_NEG_X = 1;
    public static final int FACE_POS_Y = 2;
    public static final int FACE_NEG_Y = 3;
    public static final int FACE_POS_Z = 4;
    public static final int FACE_NEG_Z = 5;

    /** Direction offsets for BFS: [dx, dy, dz] for each face. */
    private static final int[][] DIRECTION_OFFSETS = {
            {1, 0, 0},   // +X
            {-1, 0, 0},  // -X
            {0, 1, 0},   // +Y
            {0, -1, 0},  // -Y
            {0, 0, 1},   // +Z
            {0, 0, -1}   // -Z
    };

    /** Opposite face index for each direction. */
    private static final int[] OPPOSITE_FACE = {1, 0, 3, 2, 5, 4};

    /** BFS queue for graph traversal. */
    private final Deque<RenderSection> bfsQueue = new ArrayDeque<>(1024);

    /** Set of visited section keys during BFS. */
    private final Set<Long> visited = new HashSet<>(4096);

    /** Per-section face openness masks (6-bit): 1 = face is open (can see through). */
    private final Map<Long, Integer> faceMasks = new HashMap<>(4096);

    /** Statistics. */
    private int sectionsTraversed = 0;
    private int sectionsOccluded = 0;

    /**
     * Registers the face openness mask for a section.
     * Called after mesh building completes.
     *
     * @param sectionKey Packed section key
     * @param mask       6-bit mask: bit i = face i is open
     */
    public void setFaceMask(long sectionKey, int mask) {
        faceMasks.put(sectionKey, mask);
    }

    /**
     * Removes the face mask for a discarded section.
     */
    public void removeFaceMask(long sectionKey) {
        faceMasks.remove(sectionKey);
    }

    /**
     * Performs graph-based occlusion culling using BFS from the camera position.
     *
     * <p>Sections reachable via open faces from the camera section are marked as
     * not-occluded. All others are marked occluded.</p>
     *
     * @param regionManager The region manager with all active sections
     * @param cameraSectionX Camera's section X coordinate
     * @param cameraSectionY Camera's section Y coordinate
     * @param cameraSectionZ Camera's section Z coordinate
     */
    public void performOcclusionCull(RenderRegionManager regionManager,
                                     int cameraSectionX, int cameraSectionY, int cameraSectionZ) {
        bfsQueue.clear();
        visited.clear();
        sectionsTraversed = 0;
        sectionsOccluded = 0;

        // Start from camera section
        RenderSection startSection = regionManager.getSection(cameraSectionX, cameraSectionY, cameraSectionZ);
        if (startSection == null) {
            // Camera is in an unloaded section — mark everything visible
            // (no occlusion culling possible)
            return;
        }

        long startKey = startSection.getPackedKey();
        bfsQueue.add(startSection);
        visited.add(startKey);

        // BFS traversal
        while (!bfsQueue.isEmpty()) {
            RenderSection current = bfsQueue.poll();
            sectionsTraversed++;

            // Mark as not occluded (reachable from camera)
            current.getVisibility().setOccluded(false);

            // Get face openness mask for this section
            int mask = faceMasks.getOrDefault(current.getPackedKey(), 0x3F); // Default: all open

            // Propagate to neighbors through open faces
            for (int face = 0; face < 6; face++) {
                if ((mask & (1 << face)) == 0) {
                    continue; // This face is closed — cannot see through
                }

                int nx = current.getSectionX() + DIRECTION_OFFSETS[face][0];
                int ny = current.getSectionY() + DIRECTION_OFFSETS[face][1];
                int nz = current.getSectionZ() + DIRECTION_OFFSETS[face][2];

                RenderSection neighbor = regionManager.getSection(nx, ny, nz);
                if (neighbor == null) continue;

                long neighborKey = neighbor.getPackedKey();
                if (visited.contains(neighborKey)) continue;

                // Only propagate if the neighbor's opposite face is also open
                int neighborMask = faceMasks.getOrDefault(neighborKey, 0x3F);
                int oppositeFace = OPPOSITE_FACE[face];
                if ((neighborMask & (1 << oppositeFace)) == 0) {
                    continue; // Neighbor's entry face is closed
                }

                // Only traverse if the neighbor passed frustum culling
                if (!neighbor.getVisibility().isFrustumVisible()) continue;

                visited.add(neighborKey);
                bfsQueue.add(neighbor);
            }
        }

        // Mark all non-visited (but frustum-visible) sections as occluded
        for (var region : regionManager.getActiveRegions()) {
            for (int i = 0; i < RenderRegion.SECTION_COUNT; i++) {
                RenderSection section = region.getSection(i);
                if (section == null) continue;

                SectionVisibility vis = section.getVisibility();
                if (vis.isFrustumVisible() && !visited.contains(section.getPackedKey())) {
                    vis.setOccluded(true);
                    sectionsOccluded++;
                }
            }
        }
    }

    /**
     * Clears all stored face masks (e.g., on world change).
     */
    public void clear() {
        faceMasks.clear();
        visited.clear();
        bfsQueue.clear();
    }

    // ============== Statistics ==============

    public int getSectionsTraversed() { return sectionsTraversed; }
    public int getSectionsOccluded() { return sectionsOccluded; }
}
