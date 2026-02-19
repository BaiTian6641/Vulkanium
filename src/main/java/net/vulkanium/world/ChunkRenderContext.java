package net.vulkanium.world;

/**
 * Contains all the data needed to build a chunk section's render data.
 *
 * <p>Passed from the main thread to chunk builder threads. Includes the origin
 * section and its neighbors (for face culling at section boundaries, AO sampling,
 * and biome blending).</p>
 *
 * <p>Modeled after Sodium's {@code ChunkRenderContext}.</p>
 */
public class ChunkRenderContext {

    /** The origin section being built */
    private final ClonedChunkSection origin;

    /** Array of 27 neighbor sections (3x3x3 cube around origin) */
    private final ClonedChunkSection[] neighbors;

    /** Section coordinates (world coords / 16) */
    private final int sectionX, sectionY, sectionZ;

    /** The world's minimum section Y coordinate */
    private final int minSectionY;

    /** Whether to rebuild (dirty geometry) or re-light only */
    private final boolean needsRebuild;

    public ChunkRenderContext(int sectionX, int sectionY, int sectionZ,
                              int minSectionY,
                              ClonedChunkSection origin,
                              ClonedChunkSection[] neighbors,
                              boolean needsRebuild) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.minSectionY = minSectionY;
        this.origin = origin;
        this.neighbors = neighbors;
        this.needsRebuild = needsRebuild;
    }

    /**
     * Get the origin section's cloned data.
     */
    public ClonedChunkSection getOrigin() {
        return origin;
    }

    /**
     * Get a neighbor section by its offset from the origin.
     * Offset range: -1 to 1 for each axis. (0,0,0) returns origin.
     */
    public ClonedChunkSection getNeighbor(int dx, int dy, int dz) {
        int index = (dy + 1) * 9 + (dz + 1) * 3 + (dx + 1);
        if (index < 0 || index >= 27) return null;
        return neighbors[index];
    }

    /**
     * Get block state at a position that may cross section boundaries.
     * Coordinates are relative to the origin section (0-15 for within, -2..17 for neighbors).
     */
    public int getBlockState(int localX, int localY, int localZ) {
        // Determine which neighbor section and local coord
        int dx = localX < 0 ? -1 : (localX >= 16 ? 1 : 0);
        int dy = localY < 0 ? -1 : (localY >= 16 ? 1 : 0);
        int dz = localZ < 0 ? -1 : (localZ >= 16 ? 1 : 0);

        ClonedChunkSection section = getNeighbor(dx, dy, dz);
        if (section == null) return 0;

        int nx = (localX + 16) & 0xF;
        int ny = (localY + 16) & 0xF;
        int nz = (localZ + 16) & 0xF;
        return section.getBlockState(nx, ny, nz);
    }

    /**
     * Get light level at a position (may cross boundaries).
     */
    public int getBlockLight(int localX, int localY, int localZ) {
        int dx = localX < 0 ? -1 : (localX >= 16 ? 1 : 0);
        int dy = localY < 0 ? -1 : (localY >= 16 ? 1 : 0);
        int dz = localZ < 0 ? -1 : (localZ >= 16 ? 1 : 0);

        ClonedChunkSection section = getNeighbor(dx, dy, dz);
        if (section == null) return 0;

        return section.getBlockLight((localX + 16) & 0xF, (localY + 16) & 0xF, (localZ + 16) & 0xF);
    }

    public int getSkyLight(int localX, int localY, int localZ) {
        int dx = localX < 0 ? -1 : (localX >= 16 ? 1 : 0);
        int dy = localY < 0 ? -1 : (localY >= 16 ? 1 : 0);
        int dz = localZ < 0 ? -1 : (localZ >= 16 ? 1 : 0);

        ClonedChunkSection section = getNeighbor(dx, dy, dz);
        if (section == null) return 15;

        return section.getSkyLight((localX + 16) & 0xF, (localY + 16) & 0xF, (localZ + 16) & 0xF);
    }

    public int getSectionX() { return sectionX; }
    public int getSectionY() { return sectionY; }
    public int getSectionZ() { return sectionZ; }
    public int getMinSectionY() { return minSectionY; }
    public boolean needsRebuild() { return needsRebuild; }

    /** World-space origin of this section */
    public int getOriginX() { return sectionX << 4; }
    public int getOriginY() { return sectionY << 4; }
    public int getOriginZ() { return sectionZ << 4; }
}
