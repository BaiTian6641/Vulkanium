package net.vulkanium.shaderpack;

/**
 * Defines the rendering phases for a shaderpack pipeline.
 *
 * <p>These match the conventional phases used by OptiFine/Iris shaderpacks.
 * Each phase corresponds to a pair of vertex + fragment shaders that the
 * shaderpack provides (e.g., {@code gbuffers_terrain.vsh/.fsh}).</p>
 */
public enum ShaderPhase {
    // ─── Geometry buffer passes (gbuffers) ─────────────────────────────
    /** Fallback for all geometry that doesn't have a specific program */
    GBUFFERS_BASIC("gbuffers_basic"),
    /** Terrain (solid + cutout blocks) */
    GBUFFERS_TERRAIN("gbuffers_terrain"),
    /** Terrain with cutout alpha (leaves, tall grass, etc.) */
    GBUFFERS_TERRAIN_CUTOUT("gbuffers_terrain_cutout"),
    /** Translucent terrain (water, stained glass, etc.) */
    GBUFFERS_WATER("gbuffers_water"),
    /** Solid entities (players, mobs, items) */
    GBUFFERS_ENTITIES("gbuffers_entities"),
    /** Translucent entities */
    GBUFFERS_ENTITIES_TRANSLUCENT("gbuffers_entities_translucent"),
    /** Block entities (chests, signs, etc.) */
    GBUFFERS_BLOCK("gbuffers_block"),
    /** Sky dome and celestial bodies */
    GBUFFERS_SKYBASIC("gbuffers_skybasic"),
    /** Sky with textures (sun, moon) */
    GBUFFERS_SKYTEXTURED("gbuffers_skytextured"),
    /** Clouds */
    GBUFFERS_CLOUDS("gbuffers_clouds"),
    /** Weather particles (rain, snow) */
    GBUFFERS_WEATHER("gbuffers_weather"),
    /** Hand/held items */
    GBUFFERS_HAND("gbuffers_hand"),
    /** Hand with water overlay effect */
    GBUFFERS_HAND_WATER("gbuffers_hand_water"),
    /** Text rendering */
    GBUFFERS_TEXTURED("gbuffers_textured"),
    /** Armor glint overlay */
    GBUFFERS_ARMOR_GLINT("gbuffers_armor_glint"),

    // ─── Shadow pass ───────────────────────────────────────────────────
    /** Shadow map rendering from the sun's perspective */
    SHADOW("shadow"),
    /** Solid shadow geometry */
    SHADOW_SOLID("shadow_solid"),
    /** Cutout shadow geometry */
    SHADOW_CUTOUT("shadow_cutout"),

    // ─── Deferred passes ───────────────────────────────────────────────
    /** Deferred shading pass (lighting from G-buffer data) */
    DEFERRED("deferred"),
    /** Additional deferred passes (deferred1 through deferred15) */
    DEFERRED_EXTRA("deferred_extra"),

    // ─── Composite passes ──────────────────────────────────────────────
    /** First composite pass (post-processing) */
    COMPOSITE("composite"),
    /** Additional composite passes (composite1 through composite15) */
    COMPOSITE_EXTRA("composite_extra"),

    // ─── Final pass ────────────────────────────────────────────────────
    /** Final full-screen pass before output to screen */
    FINAL("final");

    private final String programName;

    ShaderPhase(String programName) {
        this.programName = programName;
    }

    /** Shader program name (used to locate shader files, e.g., "gbuffers_terrain") */
    public String getProgramName() { return programName; }
}
