package net.vulkanium.world;

/**
 * Cached biome color data for chunk sections, supporting smooth biome blending.
 *
 * <p>Pre-computes blended biome colors (grass, foliage, water) for a section
 * and its neighbors. This avoids redundant per-vertex color lookups during
 * chunk meshing.</p>
 *
 * <p>Modeled after Sodium's {@code BiomeColorCache}. The blend radius is
 * configurable (Minecraft's "Biome Blend" setting).</p>
 */
public class BiomeColorCache {

    /** The blend radius in blocks (from MC settings: 0=off, 1–7) */
    private int blendRadius;

    /** The blend diameter (2 * radius + 1) */
    private int blendDiameter;

    /** Cached grass colors (16x16 for each Y in the section) */
    private final int[][] grassColors;

    /** Cached foliage colors */
    private final int[][] foliageColors;

    /** Cached water colors */
    private final int[][] waterColors;

    /** Whether each Y-layer has been computed */
    private final boolean[] grassComputed;
    private final boolean[] foliageComputed;
    private final boolean[] waterComputed;

    public BiomeColorCache() {
        this.grassColors = new int[16][256];   // 16 Y-layers, 16x16 XZ each
        this.foliageColors = new int[16][256];
        this.waterColors = new int[16][256];
        this.grassComputed = new boolean[16];
        this.foliageComputed = new boolean[16];
        this.waterComputed = new boolean[16];
    }

    /**
     * Set the blend radius. Must be called before getColor().
     */
    public void setBlendRadius(int radius) {
        this.blendRadius = radius;
        this.blendDiameter = 2 * radius + 1;
    }

    /**
     * Get the blended biome color at a position.
     * Lazy-computes the color layer on first access.
     *
     * @param source GRASS, FOLIAGE, or WATER
     * @param localX 0-15
     * @param localY 0-15
     * @param localZ 0-15
     * @return packed ARGB color (0xAARRGGBB)
     */
    public int getColor(BiomeColorSource source, int localX, int localY, int localZ) {
        int[][] colors;
        boolean[] computed;

        switch (source) {
            case GRASS -> { colors = grassColors; computed = grassComputed; }
            case FOLIAGE -> { colors = foliageColors; computed = foliageComputed; }
            case WATER -> { colors = waterColors; computed = waterComputed; }
            default -> { return 0xFFFFFFFF; }
        }

        if (!computed[localY]) {
            computeLayer(source, localY, colors[localY]);
            computed[localY] = true;
        }

        return colors[localY][(localZ << 4) | localX];
    }

    /**
     * Compute blended colors for an entire Y-layer.
     */
    private void computeLayer(BiomeColorSource source, int localY, int[] output) {
        if (blendRadius <= 0) {
            // No blending — use raw biome colors
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    output[(z << 4) | x] = getRawBiomeColor(source, x, localY, z);
                }
            }
        } else {
            // Biome blending: average colors in a (blendDiameter x blendDiameter) box
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int totalR = 0, totalG = 0, totalB = 0;
                    int count = 0;

                    for (int dz = -blendRadius; dz <= blendRadius; dz++) {
                        for (int dx = -blendRadius; dx <= blendRadius; dx++) {
                            int color = getRawBiomeColor(source, x + dx, localY, z + dz);
                            totalR += (color >> 16) & 0xFF;
                            totalG += (color >> 8) & 0xFF;
                            totalB += color & 0xFF;
                            count++;
                        }
                    }

                    int r = totalR / count;
                    int g = totalG / count;
                    int b = totalB / count;
                    output[(z << 4) | x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }
    }

    /** Reference to the world slice's sections for biome lookups */
    private ClonedChunkSection[] sections;

    /**
     * Set the sections for biome color lookup.
     * Called when WorldSlice is initialized with section data.
     */
    public void setSections(ClonedChunkSection[] sections) {
        this.sections = sections;
    }

    /**
     * Get the raw (unblended) biome color at a local position.
     *
     * <p>Queries the biome at the given position from the ClonedChunkSection's
     * biome grid (4×4×4 per section). The biome ID is then mapped to a color
     * using the appropriate color resolver.</p>
     *
     * <p>MC's biome color mapping:</p>
     * <ul>
     *     <li>GRASS: temperature/downfall → grass color map texture</li>
     *     <li>FOLIAGE: temperature/downfall → foliage color map texture</li>
     *     <li>WATER: biome-specific water color property</li>
     * </ul>
     *
     * <p>Since we don't have direct access to MC's color map textures or biome
     * registry in the render thread, we use the biome ID and a lookup table
     * of known biome colors. The full integration will use accessor mixins
     * to read colors from the live biome registry.</p>
     */
    private int getRawBiomeColor(BiomeColorSource source, int x, int y, int z) {
        // Determine which section contains this position
        // x, y, z may be outside [0,16) due to blending radius
        int dx = (x < 0 ? -1 : (x >= 16 ? 1 : 0));
        int dy = (y < 0 ? -1 : (y >= 16 ? 1 : 0));
        int dz = (z < 0 ? -1 : (z >= 16 ? 1 : 0));

        int idx = (dy + 1) * 9 + (dz + 1) * 3 + (dx + 1);

        if (sections != null && idx >= 0 && idx < sections.length && sections[idx] != null) {
            // Biome grid uses 4×4×4 resolution (each biome cell = 4×4×4 blocks)
            int bx = ((x + 16) & 0xF) >> 2; // 0-3
            int by = ((y + 16) & 0xF) >> 2;
            int bz = ((z + 16) & 0xF) >> 2;
            int biomeId = sections[idx].getBiome(bx, by, bz);

            // Map biome ID → color using known biome color table
            return getBiomeColorForId(source, biomeId);
        }

        // Fallback: default MC colors
        return getDefaultBiomeColor(source);
    }

    /**
     * Map a biome registry ID to a color for the given source.
     *
     * <p>This uses a simplified lookup of common vanilla biome colors.
     * In full integration, this would use BiomeColors.getXxxColor() via mixins
     * which internally reads from MC's grass_color_map.png / foliage_color_map.png.</p>
     *
     * <p>Common vanilla biome IDs (1.20.1 registry order varies, but these
     * are representative defaults):</p>
     */
    private int getBiomeColorForId(BiomeColorSource source, int biomeId) {
        // Without direct registry access, we use a temperature/downfall heuristic.
        // MC biome colors are determined by:
        //   grass: GrassColors.get(temperature, downfall) from grass_color_map.png
        //   foliage: FoliageColors.get(temperature, downfall) from foliage_color_map.png
        //   water: biome.getWaterColor() (direct property)
        //
        // For now, return biome-ID-based approximations.
        // The mixin integration layer will supply real colors from the registry.
        //
        // We use biomeId as a seed to produce slightly varying colors,
        // simulating different biomes until proper integration is in place.

        return switch (source) {
            case GRASS -> interpolateGrassColor(biomeId);
            case FOLIAGE -> interpolateFoliageColor(biomeId);
            case WATER -> interpolateWaterColor(biomeId);
        };
    }

    /**
     * Approximate grass color using biome-like temperature/downfall mapping.
     * Returns a green-ish color that varies by biome ID.
     */
    private int interpolateGrassColor(int biomeId) {
        // MC default grass: temperature-based gradient from brown-green to bright-green
        // Hash the biome ID to get pseudo-temperature
        float temp = clamp01((biomeId * 0.618f) % 1.0f);
        float downfall = clamp01(((biomeId * 7 + 3) * 0.618f) % 1.0f);

        // Approximate MC's grass_color_map.png lookup:
        // High temp + high downfall = bright green (jungle)
        // Low temp + low downfall = brown-green (plains at low temp)
        // Default: mid-green
        int r = (int) (80 + 50 * temp);
        int g = (int) (160 + 60 * downfall);
        int b = (int) (60 + 30 * temp);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Approximate foliage color using biome-like mapping.
     */
    private int interpolateFoliageColor(int biomeId) {
        float temp = clamp01((biomeId * 0.618f) % 1.0f);
        float downfall = clamp01(((biomeId * 7 + 3) * 0.618f) % 1.0f);
        int r = (int) (50 + 40 * temp);
        int g = (int) (140 + 50 * downfall);
        int b = (int) (20 + 20 * temp);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Approximate water color per biome.
     */
    private int interpolateWaterColor(int biomeId) {
        // MC water colors are biome-specific constants.
        // Default blue, swamp = darker, warm ocean = lighter
        float hue = clamp01((biomeId * 0.382f) % 1.0f);
        int r = (int) (50 + 20 * hue);
        int g = (int) (100 + 30 * hue);
        int b = (int) (200 + 30 * (1.0f - hue));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * Get the default biome color when no biome data is available.
     */
    private int getDefaultBiomeColor(BiomeColorSource source) {
        return switch (source) {
            case GRASS -> 0xFF7CBD6B;   // default grass green
            case FOLIAGE -> 0xFF48B518; // default foliage green
            case WATER -> 0xFF3F76E4;   // default water blue
        };
    }

    /**
     * Invalidate all cached colors (e.g., when biome data changes).
     */
    public void invalidate() {
        java.util.Arrays.fill(grassComputed, false);
        java.util.Arrays.fill(foliageComputed, false);
        java.util.Arrays.fill(waterComputed, false);
    }
}
