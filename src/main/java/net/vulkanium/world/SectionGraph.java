package net.vulkanium.world;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages the section visibility graph using BFS traversal from the camera position.
 *
 * <p>Determines which chunk sections are visible by performing a graph search
 * starting from the camera's section. Uses face connectivity (which faces of a
 * section connect to which neighboring sections) and frustum culling to prune
 * non-visible sections.</p>
 *
 * <p>Inspired by Sodium's occlusion system and VulkanMod's SectionGraph.</p>
 */
public class SectionGraph {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/SectionGraph");

    /** Maximum render distance supported */
    private static final int MAX_RENDER_DISTANCE = 32;

    /** Directions for BFS: -X, +X, -Y, +Y, -Z, +Z */
    private static final int[][] DIRECTIONS = {
        {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };

    /** Bitmask for face directions */
    public static final int FACE_NEG_X = 0x01;
    public static final int FACE_POS_X = 0x02;
    public static final int FACE_NEG_Y = 0x04;
    public static final int FACE_POS_Y = 0x08;
    public static final int FACE_NEG_Z = 0x10;
    public static final int FACE_POS_Z = 0x20;
    public static final int ALL_FACES = 0x3F;

    /** Render distance in sections */
    private int renderDistance;

    /** Camera section position */
    private int cameraSectionX, cameraSectionY, cameraSectionZ;

    /** Min/max section Y coordinates for the world */
    private int minSectionY = -4; // 1.18+ default (y=-64)
    private int maxSectionY = 19; // 1.18+ default (y=319)

    /** Result: ordered list of visible sections (front-to-back for opaque) */
    private final List<VisibleSection> visibleSections = new ArrayList<>(4096);

    /** BFS visited set */
    private final Set<Long> visited = new HashSet<>(4096);

    /** BFS queue */
    private final ArrayDeque<TraversalNode> queue = new ArrayDeque<>(4096);

    /** Sections pending async build */
    private final ConcurrentLinkedQueue<long[]> pendingBuilds = new ConcurrentLinkedQueue<>();

    /** Per-section visibility masks computed during chunk building.
     *  Key = packed section coords, Value = face connectivity bitmask.
     *  A section with mask ALL_FACES is fully transparent (all face pairs connect).
     *  A section with mask 0 is fully opaque (no face pairs connect). */
    private final Map<Long, Integer> visibilityData = new ConcurrentHashMap<>(4096);

    /**
     * Perform the visibility graph traversal.
     *
     * @param camSX    camera section X
     * @param camSY    camera section Y
     * @param camSZ    camera section Z
     * @param distance render distance in chunks
     * @param frustum  frustum planes (6 × 4 floats), or null to skip frustum culling
     */
    public void update(int camSX, int camSY, int camSZ, int distance, float[] frustum) {
        this.cameraSectionX = camSX;
        this.cameraSectionY = camSY;
        this.cameraSectionZ = camSZ;
        this.renderDistance = Math.min(distance, MAX_RENDER_DISTANCE);

        visibleSections.clear();
        visited.clear();
        queue.clear();

        // Start BFS from camera section
        long startKey = packKey(camSX, camSY, camSZ);
        visited.add(startKey);
        queue.add(new TraversalNode(camSX, camSY, camSZ, ALL_FACES, 0));

        while (!queue.isEmpty()) {
            TraversalNode node = queue.poll();

            // Frustum cull this section's AABB
            if (frustum != null && !frustumTest(frustum, node.x, node.y, node.z)) {
                continue;
            }

            // Add to visible list
            float distance2 = distanceSquared(node.x, node.y, node.z);
            visibleSections.add(new VisibleSection(node.x, node.y, node.z, distance2));

            // Traverse to neighbors
            for (int d = 0; d < 6; d++) {
                int nx = node.x + DIRECTIONS[d][0];
                int ny = node.y + DIRECTIONS[d][1];
                int nz = node.z + DIRECTIONS[d][2];

                // Bounds check
                if (ny < minSectionY || ny > maxSectionY) continue;
                if (Math.abs(nx - cameraSectionX) > renderDistance) continue;
                if (Math.abs(nz - cameraSectionZ) > renderDistance) continue;

                long key = packKey(nx, ny, nz);
                if (visited.contains(key)) continue;

                // Check face connectivity
                int exitFace = 1 << d;
                if ((node.allowedFaces & exitFace) == 0) continue;

                // Compute allowed faces for the neighbor
                // (a section can see through to all faces except the entry face)
                int entryFace = 1 << (d ^ 1); // opposite direction
                int neighborAllowed = getVisibilityMask(nx, ny, nz) & ~entryFace;

                visited.add(key);
                queue.add(new TraversalNode(nx, ny, nz, neighborAllowed, node.depth + 1));
            }
        }

        // Sort visible sections front-to-back (for depth pre-pass efficiency)
        visibleSections.sort(Comparator.comparingDouble(s -> s.distanceSquared));
    }

    /**
     * Get the face visibility mask for a section.
     * Describes which face pairs can see through the section.
     *
     * <p>Computed during chunk building via flood-fill: for each face of a section,
     * a flood fill from that face determines which other faces are reachable
     * through the section's block geometry. This enables occlusion culling
     * in the BFS traversal.</p>
     *
     * <p>If no visibility data exists (section not yet built), defaults to
     * ALL_FACES (optimistic — assumes the section is transparent).</p>
     *
     * @return bitmask of passable faces
     */
    private int getVisibilityMask(int sectionX, int sectionY, int sectionZ) {
        long key = packKey(sectionX, sectionY, sectionZ);
        return visibilityData.getOrDefault(key, ALL_FACES);
    }

    /**
     * Update the visibility data for a section after chunk building.
     *
     * <p>Called from the chunk build worker when meshing completes.
     * The visibility mask is computed by flood-filling from each face
     * of the 16×16×16 block section to determine face connectivity.</p>
     *
     * @param sectionX section coordinate X
     * @param sectionY section coordinate Y
     * @param sectionZ section coordinate Z
     * @param mask     face connectivity bitmask (combination of FACE_* constants)
     */
    public void updateVisibility(int sectionX, int sectionY, int sectionZ, int mask) {
        long key = packKey(sectionX, sectionY, sectionZ);
        visibilityData.put(key, mask);
    }

    /**
     * Remove visibility data for a section (e.g., when a chunk is unloaded).
     */
    public void removeVisibility(int sectionX, int sectionY, int sectionZ) {
        long key = packKey(sectionX, sectionY, sectionZ);
        visibilityData.remove(key);
    }

    /**
     * Clear all visibility data (e.g., on world unload).
     */
    public void clearVisibility() {
        visibilityData.clear();
    }

    /**
     * Compute the visibility mask for a section by flood-filling from each face.
     *
     * <p>This is a utility method meant to be called during chunk building.
     * For each of the 6 faces, start a flood fill from all blocks on that face.
     * If the flood fill reaches any block on another face, that face pair
     * is considered connected.</p>
     *
     * @param blockStates 16×16×16 array of block state IDs (0 = air/transparent)
     * @return face connectivity bitmask
     */
    public static int computeVisibilityMask(int[] blockStates) {
        if (blockStates == null || blockStates.length != 4096) return ALL_FACES;

        // Track which faces are reachable from each starting face
        int result = 0;

        for (int startFace = 0; startFace < 6; startFace++) {
            // Get the set of block positions on this face
            boolean[] visited = new boolean[4096];
            ArrayDeque<Integer> floodQueue = new ArrayDeque<>(256);

            // Seed the flood fill with all transparent blocks on the start face
            for (int a = 0; a < 16; a++) {
                for (int b = 0; b < 16; b++) {
                    int idx = getFaceBlockIndex(startFace, a, b);
                    if (blockStates[idx] == 0) { // transparent/air
                        if (!visited[idx]) {
                            visited[idx] = true;
                            floodQueue.add(idx);
                        }
                    }
                }
            }

            // Flood fill through transparent blocks
            while (!floodQueue.isEmpty()) {
                int idx = floodQueue.poll();
                int bx = idx & 0xF;
                int by = (idx >> 4) & 0xF;
                int bz = (idx >> 8) & 0xF;

                // Check all 6 neighbors
                int[][] offsets = {{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
                for (int[] off : offsets) {
                    int nx = bx + off[0];
                    int ny = by + off[1];
                    int nz = bz + off[2];
                    if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) continue;

                    int nIdx = nx | (ny << 4) | (nz << 8);
                    if (!visited[nIdx] && blockStates[nIdx] == 0) {
                        visited[nIdx] = true;
                        floodQueue.add(nIdx);
                    }
                }
            }

            // Check which other faces were reached
            for (int targetFace = 0; targetFace < 6; targetFace++) {
                if (targetFace == startFace) {
                    result |= (1 << startFace); // A face can always "see" itself
                    continue;
                }
                boolean reached = false;
                for (int a = 0; a < 16 && !reached; a++) {
                    for (int b = 0; b < 16 && !reached; b++) {
                        int idx = getFaceBlockIndex(targetFace, a, b);
                        if (visited[idx]) {
                            reached = true;
                        }
                    }
                }
                if (reached) {
                    result |= (1 << startFace) | (1 << targetFace);
                }
            }
        }

        return result;
    }

    /**
     * Get the block index in a 16×16×16 array for a position on a face.
     *
     * @param face face direction (0=NEG_X, 1=POS_X, 2=NEG_Y, 3=POS_Y, 4=NEG_Z, 5=POS_Z)
     * @param a first coordinate along face (0-15)
     * @param b second coordinate along face (0-15)
     * @return packed index (x | y<<4 | z<<8)
     */
    private static int getFaceBlockIndex(int face, int a, int b) {
        return switch (face) {
            case 0 -> 0 | (a << 4) | (b << 8);        // NEG_X: x=0
            case 1 -> 15 | (a << 4) | (b << 8);       // POS_X: x=15
            case 2 -> a | (0 << 4) | (b << 8);        // NEG_Y: y=0
            case 3 -> a | (15 << 4) | (b << 8);       // POS_Y: y=15
            case 4 -> a | (b << 4) | (0 << 8);        // NEG_Z: z=0
            case 5 -> a | (b << 4) | (15 << 8);       // POS_Z: z=15
            default -> 0;
        };
    }

    /**
     * Frustum test for a section AABB.
     */
    private boolean frustumTest(float[] planes, int sectionX, int sectionY, int sectionZ) {
        float minX = sectionX * 16.0f;
        float minY = sectionY * 16.0f;
        float minZ = sectionZ * 16.0f;
        float maxX = minX + 16.0f;
        float maxY = minY + 16.0f;
        float maxZ = minZ + 16.0f;

        // Test against 6 frustum planes
        for (int i = 0; i < 6; i++) {
            float a = planes[i * 4];
            float b = planes[i * 4 + 1];
            float c = planes[i * 4 + 2];
            float d = planes[i * 4 + 3];

            // Find the most-positive vertex relative to the plane
            float px = a > 0 ? maxX : minX;
            float py = b > 0 ? maxY : minY;
            float pz = c > 0 ? maxZ : minZ;

            if (a * px + b * py + c * pz + d < 0) {
                return false; // Entirely outside this plane
            }
        }
        return true;
    }

    private float distanceSquared(int sectionX, int sectionY, int sectionZ) {
        float dx = (sectionX + 0.5f) - (cameraSectionX + 0.5f);
        float dy = (sectionY + 0.5f) - (cameraSectionY + 0.5f);
        float dz = (sectionZ + 0.5f) - (cameraSectionZ + 0.5f);
        return dx * dx + dy * dy + dz * dz;
    }

    private static long packKey(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
    }

    // ─── Results ───────────────────────────────────────────────────────

    public List<VisibleSection> getVisibleSections() {
        return Collections.unmodifiableList(visibleSections);
    }

    public int getVisibleCount() {
        return visibleSections.size();
    }

    public void setWorldHeight(int minY, int maxY) {
        this.minSectionY = minY >> 4;
        this.maxSectionY = (maxY >> 4) - 1;
    }

    // ─── Inner types ───────────────────────────────────────────────────

    public record VisibleSection(int sectionX, int sectionY, int sectionZ,
                                 float distanceSquared) {
        public int getWorldX() { return sectionX << 4; }
        public int getWorldY() { return sectionY << 4; }
        public int getWorldZ() { return sectionZ << 4; }
    }

    private record TraversalNode(int x, int y, int z, int allowedFaces, int depth) {}
}
