package net.vulkanium.render.program;

import java.util.Optional;

/**
 * Shader program identifiers matching OptiFine/Iris convention.
 *
 * <p>Each ProgramId corresponds to a shader source file pair (e.g., {@code gbuffers_terrain.vsh/fsh}).
 * Programs form a fallback hierarchy — when a shader pack doesn't provide a specific program,
 * the engine falls back to a parent program.</p>
 *
 * <h3>Fallback Tree</h3>
 * <pre>
 * Basic (root)
 * ├── Line
 * ├── Textured
 * │   ├── TexturedLit
 * │   │   ├── Terrain
 * │   │   │   ├── TerrainSolid
 * │   │   │   ├── TerrainCutout
 * │   │   │   ├── DamagedBlock
 * │   │   │   ├── Block
 * │   │   │   │   └── BlockTrans
 * │   │   │   └── Water
 * │   │   ├── Entities
 * │   │   │   ├── EntitiesTrans
 * │   │   │   ├── Lightning
 * │   │   │   └── EntitiesGlowing
 * │   │   ├── Particles
 * │   │   │   └── ParticlesTrans
 * │   │   ├── Item
 * │   │   ├── Hand
 * │   │   │   └── HandWater
 * │   │   └── Weather
 * │   ├── SkyTextured
 * │   ├── Clouds
 * │   ├── BeaconBeam
 * │   ├── ArmorGlint
 * │   └── SpiderEyes
 * └── SkyBasic
 *
 * Shadow (root)
 * ├── ShadowSolid
 * └── ShadowCutout
 *
 * DhTerrain (root)
 * ├── DhGeneric
 * └── DhWater
 *
 * DhShadow (root)
 * Final (root)
 * </pre>
 */
public enum ProgramId {
    // Shadow programs
    Shadow(ProgramGroup.SHADOW, "shadow", null, BlendOverride.OFF),
    ShadowSolid(ProgramGroup.SHADOW, "shadow_solid", Shadow, BlendOverride.OFF),
    ShadowCutout(ProgramGroup.SHADOW, "shadow_cutout", Shadow, BlendOverride.OFF),

    // Base gbuffers
    Basic(ProgramGroup.GBUFFERS, "gbuffers_basic", null, null),
    Line(ProgramGroup.GBUFFERS, "gbuffers_line", Basic, null),
    Textured(ProgramGroup.GBUFFERS, "gbuffers_textured", Basic, null),
    TexturedLit(ProgramGroup.GBUFFERS, "gbuffers_textured_lit", Textured, null),

    // Sky
    SkyBasic(ProgramGroup.GBUFFERS, "gbuffers_skybasic", Basic, null),
    SkyTextured(ProgramGroup.GBUFFERS, "gbuffers_skytextured", Textured, null),

    // Terrain
    Clouds(ProgramGroup.GBUFFERS, "gbuffers_clouds", Textured, null),
    Terrain(ProgramGroup.GBUFFERS, "gbuffers_terrain", TexturedLit, null),
    TerrainSolid(ProgramGroup.GBUFFERS, "gbuffers_terrain_solid", Terrain, null),
    TerrainCutout(ProgramGroup.GBUFFERS, "gbuffers_terrain_cutout", Terrain, null),
    DamagedBlock(ProgramGroup.GBUFFERS, "gbuffers_damagedblock", Terrain, null),
    Block(ProgramGroup.GBUFFERS, "gbuffers_block", Terrain, null),
    BlockTrans(ProgramGroup.GBUFFERS, "gbuffers_block_translucent", Block, null),
    BeaconBeam(ProgramGroup.GBUFFERS, "gbuffers_beaconbeam", Textured, null),
    Water(ProgramGroup.GBUFFERS, "gbuffers_water", Terrain, null),

    // Entities
    Item(ProgramGroup.GBUFFERS, "gbuffers_item", TexturedLit, null),
    Entities(ProgramGroup.GBUFFERS, "gbuffers_entities", TexturedLit, null),
    EntitiesTrans(ProgramGroup.GBUFFERS, "gbuffers_entities_translucent", Entities, null),
    Lightning(ProgramGroup.GBUFFERS, "gbuffers_lightning", Entities, null),
    Particles(ProgramGroup.GBUFFERS, "gbuffers_particles", TexturedLit, null),
    ParticlesTrans(ProgramGroup.GBUFFERS, "gbuffers_particles_translucent", Particles, null),
    EntitiesGlowing(ProgramGroup.GBUFFERS, "gbuffers_entities_glowing", Entities, null),
    ArmorGlint(ProgramGroup.GBUFFERS, "gbuffers_armor_glint", Textured, null),
    SpiderEyes(ProgramGroup.GBUFFERS, "gbuffers_spidereyes", Textured,
            new BlendOverride(770, 1, 0, 1)), // SRC_ALPHA, ONE, ZERO, ONE

    // Hand
    Hand(ProgramGroup.GBUFFERS, "gbuffers_hand", TexturedLit, null),
    HandWater(ProgramGroup.GBUFFERS, "gbuffers_hand_water", Hand, null),

    // Weather
    Weather(ProgramGroup.GBUFFERS, "gbuffers_weather", TexturedLit, null),

    // Distant Horizons
    DhTerrain(ProgramGroup.DH, "dh_terrain", null, null),
    DhGeneric(ProgramGroup.DH, "dh_generic", DhTerrain, null),
    DhWater(ProgramGroup.DH, "dh_water", DhTerrain, null),
    DhShadow(ProgramGroup.DH, "dh_shadow", null, null),

    // Final
    Final(ProgramGroup.FINAL, "final", null, null);

    private final ProgramGroup group;
    private final String sourceName;
    private final ProgramId fallback;
    private final BlendOverride defaultBlendOverride;

    ProgramId(ProgramGroup group, String sourceName, ProgramId fallback,
              BlendOverride defaultBlendOverride) {
        this.group = group;
        this.sourceName = sourceName;
        this.fallback = fallback;
        this.defaultBlendOverride = defaultBlendOverride;
    }

    public ProgramGroup getGroup() { return group; }
    public String getSourceName() { return sourceName; }
    public Optional<ProgramId> getFallback() { return Optional.ofNullable(fallback); }
    public BlendOverride getDefaultBlendOverride() { return defaultBlendOverride; }

    /**
     * Walks the fallback chain to find the first available program.
     */
    public ProgramId resolve(java.util.function.Predicate<ProgramId> available) {
        ProgramId current = this;
        while (current != null) {
            if (available.test(current)) return current;
            current = current.fallback;
        }
        return null; // No program available
    }

    // ── Inner types ──

    public enum ProgramGroup {
        SHADOW("shadow"),
        GBUFFERS("gbuffers"),
        DH("dh"),
        FINAL("final");

        private final String baseName;

        ProgramGroup(String baseName) { this.baseName = baseName; }
        public String getBaseName() { return baseName; }
    }

    /**
     * GL blend mode override for specific programs.
     * Values are GL constants (e.g., 770 = GL_SRC_ALPHA, 771 = GL_ONE_MINUS_SRC_ALPHA).
     */
    public record BlendOverride(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        public static final BlendOverride OFF = null;
    }
}
