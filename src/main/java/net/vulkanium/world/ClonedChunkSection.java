package net.vulkanium.world;

/**
 * A snapshot of a chunk section's data, taken from the main thread
 * for safe use on chunk builder threads.
 *
 * <p>Clones block state palette, light data, and biome data from
 * a live chunk section. The snapshot is immutable after creation.</p>
 *
 * <p>Modeled after Sodium's {@code ClonedChunkSection}.</p>
 */
public class ClonedChunkSection {

    /** The section position (packed: x, y, z in section coords) */
    private final long packedPos;

    /** Block state IDs (4096 entries for a 16³ section) */
    private final int[] blockStates;

    /** Block light levels (4096 entries, 0-15) */
    private final byte[] blockLight;

    /** Sky light levels (4096 entries, 0-15) */
    private final byte[] skyLight;

    /** Whether this section is empty (all air) */
    private final boolean empty;

    /** Biome registry IDs (64 entries, 4³ biome grid) */
    private final int[] biomes;

    public ClonedChunkSection(long packedPos, int[] blockStates, byte[] blockLight,
                              byte[] skyLight, int[] biomes) {
        this.packedPos = packedPos;
        this.blockStates = blockStates;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
        this.biomes = biomes;
        this.empty = checkEmpty();
    }

    private boolean checkEmpty() {
        if (blockStates == null) return true;
        for (int state : blockStates) {
            if (state != 0) return false; // 0 = air
        }
        return true;
    }

    /**
     * Get block state ID at local coordinates.
     */
    public int getBlockState(int x, int y, int z) {
        if (blockStates == null) return 0;
        return blockStates[(y << 8) | (z << 4) | x];
    }

    /**
     * Get block light at local coordinates.
     */
    public int getBlockLight(int x, int y, int z) {
        if (blockLight == null) return 0;
        return blockLight[(y << 8) | (z << 4) | x] & 0xF;
    }

    /**
     * Get sky light at local coordinates.
     */
    public int getSkyLight(int x, int y, int z) {
        if (skyLight == null) return 15;
        return skyLight[(y << 8) | (z << 4) | x] & 0xF;
    }

    /**
     * Get biome ID at a biome grid position (0-3 for each axis).
     */
    public int getBiome(int x, int y, int z) {
        if (biomes == null) return 0;
        return biomes[(y << 4) | (z << 2) | x];
    }

    public long getPackedPos() { return packedPos; }
    public boolean isEmpty() { return empty; }

    // ─── Packed position helpers ──────────────────────────────────────

    public static long packPos(int sectionX, int sectionY, int sectionZ) {
        return ((long) sectionX & 0x3FFFFF) << 42
             | ((long) sectionY & 0xFFFFF) << 20
             | ((long) sectionZ & 0x3FFFFF);
    }

    public static int unpackX(long packed) { return (int) (packed >> 42); }
    public static int unpackY(long packed) { return (int) (packed << 22 >> 44); }
    public static int unpackZ(long packed) { return (int) (packed << 42 >> 42); }
}
