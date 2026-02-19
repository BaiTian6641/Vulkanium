package net.vulkanium.world;

/**
 * Biome color source enumeration.
 *
 * <p>Minecraft uses different color resolvers for different biome-tinted blocks
 * (grass, foliage, water). This enum identifies which color source to use.</p>
 */
public enum BiomeColorSource {
    /** Grass block, tall grass, fern etc. */
    GRASS,
    /** Leaves, vines */
    FOLIAGE,
    /** Water */
    WATER
}
