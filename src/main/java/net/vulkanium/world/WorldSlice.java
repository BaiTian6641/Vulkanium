package net.vulkanium.world;

/**
 * Immutable snapshot of a world slice for off-thread chunk building.
 *
 * <p>Contains block states, biome data, and light data for a chunk section
 * plus a margin of neighbor blocks (typically 2 blocks on each side).
 * This allows the chunk builder to sample AO, smooth lighting, and
 * face-culling data without accessing the live world.</p>
 *
 * <p>Modeled after Sodium's {@code WorldSlice}. Object pooling should be
 * used to avoid frequent large allocations (each slice is ~200KB).</p>
 */
public class WorldSlice {

    /** Neighbor radius in blocks */
    private static final int NEIGHBOR_RADIUS = 2;

    /** Section array length (3 = 1 center + 2 neighbors on each side) */
    private static final int SECTION_LENGTH = 3;

    /** Total sections in the 3×3×3 neighborhood */
    private static final int SECTION_COUNT = SECTION_LENGTH * SECTION_LENGTH * SECTION_LENGTH;

    /** Block states for the 3×3×3 section neighborhood */
    private final ClonedChunkSection[] sections = new ClonedChunkSection[SECTION_COUNT];

    /** Biome color cache for this slice */
    private final BiomeColorCache biomeColors;

    /** Origin section world coordinates */
    private int originX, originY, originZ;

    /** World min/max Y in section coords */
    private int minSectionY, maxSectionY;

    public WorldSlice() {
        this.biomeColors = new BiomeColorCache();
    }

    /**
     * Copy chunk data from the client world into this slice.
     * Must be called on the main thread.
     *
     * @param sectionX origin section X
     * @param sectionY origin section Y
     * @param sectionZ origin section Z
     */
    public void init(int sectionX, int sectionY, int sectionZ,
                     ClonedChunkSectionCache cache, int blendRadius) {
        this.originX = sectionX;
        this.originY = sectionY;
        this.originZ = sectionZ;

        // Copy 3×3×3 neighborhood from cache
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int idx = (dy + 1) * 9 + (dz + 1) * 3 + (dx + 1);
                    sections[idx] = cache.get(sectionX + dx, sectionY + dy, sectionZ + dz);
                }
            }
        }

        biomeColors.setBlendRadius(blendRadius);
        biomeColors.invalidate();
    }

    /**
     * Get block state at world coordinates relative to the origin section.
     *
     * @param x local X (-16 to 31)
     * @param y local Y (-16 to 31)
     * @param z local Z (-16 to 31)
     * @return block state ID
     */
    public int getBlockState(int x, int y, int z) {
        // Determine section offset
        int dx = (x < 0 ? -1 : (x >= 16 ? 1 : 0));
        int dy = (y < 0 ? -1 : (y >= 16 ? 1 : 0));
        int dz = (z < 0 ? -1 : (z >= 16 ? 1 : 0));

        int idx = (dy + 1) * 9 + (dz + 1) * 3 + (dx + 1);
        ClonedChunkSection section = sections[idx];
        if (section == null || section.isEmpty()) return 0;

        return section.getBlockState((x + 16) & 0xF, (y + 16) & 0xF, (z + 16) & 0xF);
    }

    /**
     * Get packed light (block << 4 | sky) at local coordinates.
     */
    public int getPackedLight(int x, int y, int z) {
        int dx = (x < 0 ? -1 : (x >= 16 ? 1 : 0));
        int dy = (y < 0 ? -1 : (y >= 16 ? 1 : 0));
        int dz = (z < 0 ? -1 : (z >= 16 ? 1 : 0));

        int idx = (dy + 1) * 9 + (dz + 1) * 3 + (dx + 1);
        ClonedChunkSection section = sections[idx];
        if (section == null) return (15 << 4); // full sky light

        int lx = (x + 16) & 0xF;
        int ly = (y + 16) & 0xF;
        int lz = (z + 16) & 0xF;
        return (section.getBlockLight(lx, ly, lz) << 4) | section.getSkyLight(lx, ly, lz);
    }

    /**
     * Get the biome color at local coordinates.
     */
    public int getBiomeColor(BiomeColorSource source, int x, int y, int z) {
        return biomeColors.getColor(source, x & 0xF, y & 0xF, z & 0xF);
    }

    /**
     * Check if a block position is opaque (for face culling).
     */
    public boolean isOpaqueFullCube(int x, int y, int z) {
        int state = getBlockState(x, y, z);
        // In real implementation: look up block state properties
        // For now, non-zero = potentially opaque (simplified)
        return state != 0;
    }

    /**
     * Reset this slice for reuse (object pool pattern).
     */
    public void reset() {
        java.util.Arrays.fill(sections, null);
        biomeColors.invalidate();
    }

    public int getOriginX() { return originX; }
    public int getOriginY() { return originY; }
    public int getOriginZ() { return originZ; }
    public BiomeColorCache getBiomeColors() { return biomeColors; }
}
