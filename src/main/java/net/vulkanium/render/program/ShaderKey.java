package net.vulkanium.render.program;

/**
 * Maps Minecraft render states to shader programs, vertex formats, and rendering properties.
 *
 * <p>Each ShaderKey represents a unique combination of shader program, alpha test mode,
 * vertex format, fog mode, and lighting model. When Minecraft issues a render call,
 * the corresponding ShaderKey determines which compiled Vulkan pipeline to use.</p>
 *
 * <h3>Key Selection Priority (from MixinGameRenderer)</h3>
 * <ol>
 *   <li>Sky phase → SKY_* keys</li>
 *   <li>Shadow active → SHADOW_* keys</li>
 *   <li>Hand active → HAND_* keys</li>
 *   <li>Block entity phase → BLOCK_ENTITY* / BE_* keys</li>
 *   <li>Entity phase with terrain shader → MOVING_BLOCK</li>
 *   <li>Default → standard key</li>
 * </ol>
 *
 * <p>65 total entries: 50 non-shadow + 15 shadow variants.</p>
 */
public enum ShaderKey {

    // ── Basic / Textured (6) ──
    BASIC(ProgramId.Basic, AlphaTest.OFF, VertexFormatId.POSITION, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    BASIC_COLOR(ProgramId.Basic, AlphaTest.NON_ZERO_ALPHA, VertexFormatId.POSITION_COLOR, FogMode.OFF, LightingModel.LIGHTMAP),
    TEXTURED(ProgramId.Textured, AlphaTest.NON_ZERO_ALPHA, VertexFormatId.POSITION_TEX, FogMode.OFF, LightingModel.LIGHTMAP),
    TEXTURED_COLOR(ProgramId.Textured, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.POSITION_TEX_COLOR, FogMode.OFF, LightingModel.LIGHTMAP),
    LEASH(ProgramId.Basic, AlphaTest.OFF, VertexFormatId.POSITION_COLOR_LIGHTMAP, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    LINES(ProgramId.Line, AlphaTest.OFF, VertexFormatId.POSITION_COLOR_NORMAL, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),

    // ── Sky (4) ──
    SKY_BASIC(ProgramId.SkyBasic, AlphaTest.OFF, VertexFormatId.POSITION, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    SKY_BASIC_COLOR(ProgramId.SkyBasic, AlphaTest.NON_ZERO_ALPHA, VertexFormatId.POSITION_COLOR, FogMode.OFF, LightingModel.LIGHTMAP),
    SKY_TEXTURED(ProgramId.SkyTextured, AlphaTest.OFF, VertexFormatId.POSITION_TEX, FogMode.OFF, LightingModel.LIGHTMAP),
    SKY_TEXTURED_COLOR(ProgramId.SkyTextured, AlphaTest.OFF, VertexFormatId.POSITION_TEX_COLOR, FogMode.OFF, LightingModel.LIGHTMAP),

    // ── Clouds (2) ──
    CLOUDS(ProgramId.Clouds, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.POSITION_TEX_COLOR_NORMAL, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    CLOUDS_SODIUM(ProgramId.Clouds, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.CLOUDS, FogMode.PER_FRAGMENT, LightingModel.LIGHTMAP),

    // ── Terrain (4) ──
    TERRAIN_SOLID(ProgramId.TerrainSolid, AlphaTest.OFF, VertexFormatId.TERRAIN, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    TERRAIN_CUTOUT(ProgramId.TerrainCutout, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.TERRAIN, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    TERRAIN_TRANSLUCENT(ProgramId.Water, AlphaTest.OFF, VertexFormatId.TERRAIN, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    MOVING_BLOCK(ProgramId.Block, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.TERRAIN, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),

    // ── Entities (10) ──
    ENTITIES_ALPHA(ProgramId.Entities, AlphaTest.VERTEX_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    ENTITIES_SOLID(ProgramId.Entities, AlphaTest.OFF, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    ENTITIES_SOLID_DIFFUSE(ProgramId.Entities, AlphaTest.OFF, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
    ENTITIES_SOLID_BRIGHT(ProgramId.Entities, AlphaTest.OFF, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.FULLBRIGHT),
    ENTITIES_CUTOUT(ProgramId.Entities, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    ENTITIES_CUTOUT_DIFFUSE(ProgramId.Entities, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
    ENTITIES_TRANSLUCENT(ProgramId.EntitiesTrans, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
    ENTITIES_EYES(ProgramId.SpiderEyes, AlphaTest.NON_ZERO_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.FULLBRIGHT),
    ENTITIES_EYES_TRANS(ProgramId.SpiderEyes, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.FULLBRIGHT),
    LIGHTNING(ProgramId.Lightning, AlphaTest.OFF, VertexFormatId.POSITION_COLOR, FogMode.PER_VERTEX, LightingModel.FULLBRIGHT),

    // ── Hand (9) ──
    HAND_CUTOUT(ProgramId.Hand, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    HAND_CUTOUT_BRIGHT(ProgramId.Hand, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.FULLBRIGHT),
    HAND_CUTOUT_DIFFUSE(ProgramId.Hand, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
    HAND_TEXT(ProgramId.Hand, AlphaTest.NON_ZERO_ALPHA, VertexFormatId.GLYPH, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    HAND_TEXT_INTENSITY(ProgramId.Hand, AlphaTest.NON_ZERO_ALPHA, VertexFormatId.GLYPH, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    HAND_TRANSLUCENT(ProgramId.HandWater, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    HAND_WATER_BRIGHT(ProgramId.HandWater, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.FULLBRIGHT),
    HAND_WATER_DIFFUSE(ProgramId.HandWater, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),

    // ── Block Entities (5) ──
    BLOCK_ENTITY(ProgramId.Block, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    BLOCK_ENTITY_BRIGHT(ProgramId.Block, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.FULLBRIGHT),
    BLOCK_ENTITY_DIFFUSE(ProgramId.Block, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
    BE_TRANSLUCENT(ProgramId.BlockTrans, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
    BEACON(ProgramId.BeaconBeam, AlphaTest.OFF, VertexFormatId.BLOCK, FogMode.PER_FRAGMENT, LightingModel.FULLBRIGHT),

    // ── Particles / Weather (3) ──
    PARTICLES(ProgramId.Particles, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.PARTICLE, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    PARTICLES_TRANS(ProgramId.ParticlesTrans, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.PARTICLE, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    WEATHER(ProgramId.Weather, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.PARTICLE, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),

    // ── Text (5) ──
    TEXT(ProgramId.EntitiesTrans, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.GLYPH, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    TEXT_INTENSITY(ProgramId.EntitiesTrans, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.GLYPH, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    TEXT_BE(ProgramId.BlockTrans, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.GLYPH, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    TEXT_INTENSITY_BE(ProgramId.BlockTrans, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.GLYPH, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
    TEXT_BG(ProgramId.EntitiesTrans, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.POSITION_COLOR_LIGHTMAP, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),

    // ── Other (2) ──
    CRUMBLING(ProgramId.DamagedBlock, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.TERRAIN, FogMode.OFF, LightingModel.FULLBRIGHT),
    GLINT(ProgramId.ArmorGlint, AlphaTest.NON_ZERO_ALPHA, VertexFormatId.POSITION_TEX, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),

    // ── Shadow Variants (15) ──
    SHADOW_TERRAIN_CUTOUT(ProgramId.ShadowCutout, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.TERRAIN, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_ENTITIES_CUTOUT(ProgramId.ShadowCutout, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.ENTITY, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_BEACON_BEAM(ProgramId.Shadow, AlphaTest.OFF, VertexFormatId.BLOCK, FogMode.OFF, LightingModel.FULLBRIGHT),
    SHADOW_BASIC(ProgramId.Shadow, AlphaTest.OFF, VertexFormatId.POSITION, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_BASIC_COLOR(ProgramId.Shadow, AlphaTest.NON_ZERO_ALPHA, VertexFormatId.POSITION_COLOR, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_TEX(ProgramId.Shadow, AlphaTest.NON_ZERO_ALPHA, VertexFormatId.POSITION_TEX, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_TEX_COLOR(ProgramId.Shadow, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.POSITION_TEX_COLOR, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_CLOUDS(ProgramId.Shadow, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.POSITION_TEX_COLOR_NORMAL, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_LINES(ProgramId.Shadow, AlphaTest.OFF, VertexFormatId.POSITION_COLOR_NORMAL, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_LEASH(ProgramId.Shadow, AlphaTest.OFF, VertexFormatId.POSITION_COLOR_LIGHTMAP, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_LIGHTNING(ProgramId.Shadow, AlphaTest.OFF, VertexFormatId.POSITION_COLOR, FogMode.OFF, LightingModel.FULLBRIGHT),
    SHADOW_PARTICLES(ProgramId.Shadow, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.PARTICLE, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_TEXT(ProgramId.Shadow, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.GLYPH, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_TEXT_BG(ProgramId.Shadow, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.POSITION_COLOR_LIGHTMAP, FogMode.OFF, LightingModel.LIGHTMAP),
    SHADOW_TEXT_INTENSITY(ProgramId.Shadow, AlphaTest.ONE_TENTH_ALPHA, VertexFormatId.GLYPH, FogMode.OFF, LightingModel.LIGHTMAP);

    private final ProgramId program;
    private final AlphaTest alphaTest;
    private final VertexFormatId vertexFormat;
    private final FogMode fogMode;
    private final LightingModel lightingModel;

    ShaderKey(ProgramId program, AlphaTest alphaTest, VertexFormatId vertexFormat,
              FogMode fogMode, LightingModel lightingModel) {
        this.program = program;
        this.alphaTest = alphaTest;
        this.vertexFormat = vertexFormat;
        this.fogMode = fogMode;
        this.lightingModel = lightingModel;
    }

    // ── Getters ──

    public ProgramId getProgram() { return program; }
    public AlphaTest getAlphaTest() { return alphaTest; }
    public VertexFormatId getVertexFormat() { return vertexFormat; }
    public FogMode getFogMode() { return fogMode; }
    public LightingModel getLightingModel() { return lightingModel; }

    public String getName() { return toString().toLowerCase(); }
    public boolean isShadow() { return program == ProgramId.Shadow || program == ProgramId.ShadowSolid || program == ProgramId.ShadowCutout; }
    public boolean hasDiffuseLighting() { return lightingModel == LightingModel.DIFFUSE || lightingModel == LightingModel.DIFFUSE_LM; }
    public boolean shouldIgnoreLightmap() { return lightingModel == LightingModel.FULLBRIGHT || lightingModel == LightingModel.DIFFUSE; }
    public boolean isGlint() { return this == GLINT; }
    public boolean isText() { return name().contains("TEXT"); }
    public boolean isIntensity() { return this == TEXT_INTENSITY || this == TEXT_INTENSITY_BE || this == HAND_TEXT_INTENSITY || this == SHADOW_TEXT_INTENSITY; }

    // ── Inner enums ──

    public enum AlphaTest {
        /** No alpha testing */
        OFF(0.0f),
        /** Discard if alpha == 0 */
        NON_ZERO_ALPHA(0.003921569f), // 1/255
        /** Discard if alpha < 0.1 (standard cutout) */
        ONE_TENTH_ALPHA(0.1f),
        /** Discard based on vertex alpha attribute */
        VERTEX_ALPHA(0.0f);

        private final float threshold;

        AlphaTest(float threshold) { this.threshold = threshold; }
        public float getThreshold() { return threshold; }
        public boolean isEnabled() { return this != OFF; }
    }

    public enum FogMode {
        /** No fog */
        OFF,
        /** Fog computed per vertex (linear/exp/exp2) */
        PER_VERTEX,
        /** Fog computed per fragment (higher quality) */
        PER_FRAGMENT
    }

    public enum LightingModel {
        /** No lighting — full brightness (spider eyes, beacons, etc.) */
        FULLBRIGHT,
        /** Standard block+sky lightmap lookup */
        LIGHTMAP,
        /** Diffuse shading only, no lightmap */
        DIFFUSE,
        /** Diffuse shading + lightmap (most entity rendering) */
        DIFFUSE_LM
    }

    /**
     * Vertex format identifiers for pipeline input state.
     * Maps to the Vulkan VkVertexInputAttributeDescription arrays.
     */
    public enum VertexFormatId {
        POSITION,                     // vec3 pos
        POSITION_COLOR,               // vec3 pos, vec4 color
        POSITION_TEX,                 // vec3 pos, vec2 uv
        POSITION_TEX_COLOR,           // vec3 pos, vec2 uv, vec4 color
        POSITION_COLOR_LIGHTMAP,      // vec3 pos, vec4 color, ivec2 light
        POSITION_COLOR_NORMAL,        // vec3 pos, vec4 color, vec3 normal
        POSITION_TEX_COLOR_NORMAL,    // vec3 pos, vec2 uv, vec4 color, vec3 normal
        TERRAIN,                      // Vulkanium 32-byte format (pos+color+uv+normal+tangent+light+entityId)
        ENTITY,                       // MC entity format (pos+color+uv+overlay+light+normal)
        PARTICLE,                     // MC particle format (pos+uv+color+light)
        GLYPH,                        // MC text format (pos+color+uv+light)
        BLOCK,                        // MC block format (pos+color+uv+uv2+normal)
        CLOUDS                        // Sodium clouds format
    }
}
