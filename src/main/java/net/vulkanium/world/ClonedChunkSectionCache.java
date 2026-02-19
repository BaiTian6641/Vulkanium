package net.vulkanium.world;

/**
 * A 3x3x3 cache of cloned chunk sections, centered on a target section.
 *
 * <p>Provides the neighborhood data needed for chunk meshing operations
 * (ambient occlusion, smooth lighting, face culling at section boundaries).</p>
 *
 * <p>Sections are cached from the client world on the main thread,
 * then passed to builder threads via {@link ChunkRenderContext}.</p>
 */
public class ClonedChunkSectionCache {

    /** Maximum number of cached section snapshots */
    private static final int MAX_CACHE_SIZE = 1024;

    /** Ring buffer of cached sections */
    private final ClonedChunkSection[] cache = new ClonedChunkSection[MAX_CACHE_SIZE];

    /** Position keys for cache entries */
    private final long[] keys = new long[MAX_CACHE_SIZE];

    /** Write cursor */
    private int cursor;

    public ClonedChunkSectionCache() {
        java.util.Arrays.fill(keys, Long.MIN_VALUE);
    }

    /**
     * Get a cached section, or null if not present.
     */
    public ClonedChunkSection get(int sectionX, int sectionY, int sectionZ) {
        long key = ClonedChunkSection.packPos(sectionX, sectionY, sectionZ);
        for (int i = 0; i < MAX_CACHE_SIZE; i++) {
            if (keys[i] == key) {
                return cache[i];
            }
        }
        return null;
    }

    /**
     * Add a section to the cache.
     */
    public void put(int sectionX, int sectionY, int sectionZ, ClonedChunkSection section) {
        long key = ClonedChunkSection.packPos(sectionX, sectionY, sectionZ);
        // Check if already present
        for (int i = 0; i < MAX_CACHE_SIZE; i++) {
            if (keys[i] == key) {
                cache[i] = section;
                return;
            }
        }
        // Insert at cursor (ring buffer eviction)
        cache[cursor] = section;
        keys[cursor] = key;
        cursor = (cursor + 1) % MAX_CACHE_SIZE;
    }

    /**
     * Invalidate all cached sections for a given chunk column.
     */
    public void invalidateColumn(int chunkX, int chunkZ) {
        for (int i = 0; i < MAX_CACHE_SIZE; i++) {
            if (keys[i] != Long.MIN_VALUE) {
                int sx = ClonedChunkSection.unpackX(keys[i]);
                int sz = ClonedChunkSection.unpackZ(keys[i]);
                if (sx == chunkX && sz == chunkZ) {
                    keys[i] = Long.MIN_VALUE;
                    cache[i] = null;
                }
            }
        }
    }

    /**
     * Clear the entire cache.
     */
    public void clear() {
        java.util.Arrays.fill(cache, null);
        java.util.Arrays.fill(keys, Long.MIN_VALUE);
        cursor = 0;
    }
}
