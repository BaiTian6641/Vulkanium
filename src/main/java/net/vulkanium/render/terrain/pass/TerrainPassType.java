package net.vulkanium.render.terrain.pass;

/**
 * Terrain render pass types, defining the draw layers for chunk rendering.
 *
 * <p>These correspond to Minecraft's render types and are drawn in order.
 * Each pass type may have a different pipeline state (depth write, alpha test,
 * blend mode, etc.).</p>
 *
 * <p>Shader packs map these to different gbuffers programs:</p>
 * <ul>
 *   <li>SOLID → gbuffers_terrain</li>
 *   <li>CUTOUT → gbuffers_terrain (with alpha test)</li>
 *   <li>CUTOUT_MIPPED → gbuffers_terrain (alpha test + mipmaps)</li>
 *   <li>TRANSLUCENT → gbuffers_water</li>
 *   <li>TRIPWIRE → gbuffers_water (special alpha handling)</li>
 * </ul>
 */
public enum TerrainPassType {
    /**
     * Opaque solid blocks (stone, dirt, wood, etc.).
     * No alpha testing, no blending. Fastest to render.
     * Depth write: on. Depth test: on.
     */
    SOLID(0, false, false, "gbuffers_terrain"),

    /**
     * Cutout blocks (flowers, grass, saplings, etc.).
     * Alpha test with threshold 0.1. No blending.
     * Depth write: on. Depth test: on.
     */
    CUTOUT(1, true, false, "gbuffers_terrain"),

    /**
     * Cutout-mipmapped blocks (leaves, etc.).
     * Alpha test with mipmap-aware threshold. No blending.
     * Depth write: on. Depth test: on.
     */
    CUTOUT_MIPPED(2, true, false, "gbuffers_terrain"),

    /**
     * Translucent blocks (water, ice, stained glass, etc.).
     * Alpha blending enabled. Must be rendered back-to-front.
     * Depth write: on (for depth buffer). Depth test: on. Blend: SRC_ALPHA, ONE_MINUS_SRC_ALPHA.
     */
    TRANSLUCENT(3, false, true, "gbuffers_water"),

    /**
     * Tripwire — special translucent pass (string, tripwire hooks).
     * Similar blend mode to TRANSLUCENT but separate for pack control.
     */
    TRIPWIRE(4, false, true, "gbuffers_water");

    /** Draw order index (lower = drawn first). */
    public final int order;

    /** Whether this pass uses alpha testing. */
    public final boolean alphaTest;

    /** Whether this pass uses alpha blending. */
    public final boolean blend;

    /** The shader pack program name that maps to this pass. */
    public final String shaderPackProgram;

    TerrainPassType(int order, boolean alphaTest, boolean blend, String shaderPackProgram) {
        this.order = order;
        this.alphaTest = alphaTest;
        this.blend = blend;
        this.shaderPackProgram = shaderPackProgram;
    }

    /** Returns the number of terrain pass types. */
    public static int count() {
        return values().length;
    }

    /**
     * Returns all pass types in draw order (opaque first, translucent last).
     * This is the order in which terrain passes should be rendered.
     */
    public static TerrainPassType[] drawOrder() {
        return new TerrainPassType[]{SOLID, CUTOUT, CUTOUT_MIPPED, TRANSLUCENT, TRIPWIRE};
    }

    /**
     * Returns only translucent pass types that require sorting.
     */
    public static TerrainPassType[] translucentPasses() {
        return new TerrainPassType[]{TRANSLUCENT, TRIPWIRE};
    }
}
