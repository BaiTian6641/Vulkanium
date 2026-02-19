package net.vulkanium.shaderpack;

import java.util.*;

/**
 * Defines all shaderpack program IDs with Iris-compatible fallback chains.
 *
 * <p>When a shaderpack doesn't provide a specific program (e.g. {@code gbuffers_terrain_cutout}),
 * Iris falls back to a more general program (e.g. {@code gbuffers_terrain}), continuing
 * up the chain until a match is found or the base program is reached.</p>
 *
 * <h3>Fallback Chain Examples</h3>
 * <pre>
 *   gbuffers_terrain_cutout → gbuffers_terrain → gbuffers_textured_lit → gbuffers_textured → gbuffers_basic
 *   gbuffers_water → gbuffers_textured_lit → gbuffers_textured → gbuffers_basic
 *   gbuffers_entities → gbuffers_textured_lit → gbuffers_textured → gbuffers_basic
 *   gbuffers_hand_water → gbuffers_hand → gbuffers_textured_lit → gbuffers_textured → gbuffers_basic
 * </pre>
 */
public enum ProgramId {
    // ─── Geometry buffer programs (gbuffers) ───────────────────────────
    GBUFFERS_BASIC("gbuffers_basic", null),
    GBUFFERS_TEXTURED("gbuffers_textured", GBUFFERS_BASIC),
    GBUFFERS_TEXTURED_LIT("gbuffers_textured_lit", GBUFFERS_TEXTURED),
    GBUFFERS_TERRAIN("gbuffers_terrain", GBUFFERS_TEXTURED_LIT),
    GBUFFERS_TERRAIN_SOLID("gbuffers_terrain_solid", GBUFFERS_TERRAIN),
    GBUFFERS_TERRAIN_CUTOUT("gbuffers_terrain_cutout", GBUFFERS_TERRAIN),
    GBUFFERS_TERRAIN_CUTOUT_MIPPED("gbuffers_terrain_cutout_mipped", GBUFFERS_TERRAIN),
    GBUFFERS_WATER("gbuffers_water", GBUFFERS_TEXTURED_LIT),
    GBUFFERS_ENTITIES("gbuffers_entities", GBUFFERS_TEXTURED_LIT),
    GBUFFERS_ENTITIES_TRANSLUCENT("gbuffers_entities_translucent", GBUFFERS_ENTITIES),
    GBUFFERS_ENTITIES_GLOWING("gbuffers_entities_glowing", GBUFFERS_ENTITIES),
    GBUFFERS_BLOCK("gbuffers_block", GBUFFERS_TERRAIN),
    GBUFFERS_BEACON_BEAM("gbuffers_beaconbeam", GBUFFERS_TEXTURED),
    GBUFFERS_SKYBASIC("gbuffers_skybasic", GBUFFERS_BASIC),
    GBUFFERS_SKYTEXTURED("gbuffers_skytextured", GBUFFERS_TEXTURED),
    GBUFFERS_CLOUDS("gbuffers_clouds", GBUFFERS_TEXTURED),
    GBUFFERS_WEATHER("gbuffers_weather", GBUFFERS_TEXTURED_LIT),
    GBUFFERS_HAND("gbuffers_hand", GBUFFERS_TEXTURED_LIT),
    GBUFFERS_HAND_WATER("gbuffers_hand_water", GBUFFERS_HAND),
    GBUFFERS_ARMOR_GLINT("gbuffers_armor_glint", GBUFFERS_TEXTURED),
    GBUFFERS_SPIDEREYES("gbuffers_spidereyes", GBUFFERS_TEXTURED),
    GBUFFERS_DAMAGED_BLOCK("gbuffers_damagedblock", GBUFFERS_TERRAIN),
    GBUFFERS_LINE("gbuffers_line", GBUFFERS_BASIC),

    // ─── Shadow programs ───────────────────────────────────────────────
    SHADOW("shadow", null),
    SHADOW_SOLID("shadow_solid", SHADOW),
    SHADOW_CUTOUT("shadow_cutout", SHADOW),

    // ─── Deferred programs (0–15) ──────────────────────────────────────
    DEFERRED("deferred", null),
    DEFERRED1("deferred1", null),
    DEFERRED2("deferred2", null),
    DEFERRED3("deferred3", null),
    DEFERRED4("deferred4", null),
    DEFERRED5("deferred5", null),
    DEFERRED6("deferred6", null),
    DEFERRED7("deferred7", null),
    DEFERRED8("deferred8", null),
    DEFERRED9("deferred9", null),
    DEFERRED10("deferred10", null),
    DEFERRED11("deferred11", null),
    DEFERRED12("deferred12", null),
    DEFERRED13("deferred13", null),
    DEFERRED14("deferred14", null),
    DEFERRED15("deferred15", null),

    // ─── Composite programs (0–15) ─────────────────────────────────────
    COMPOSITE("composite", null),
    COMPOSITE1("composite1", null),
    COMPOSITE2("composite2", null),
    COMPOSITE3("composite3", null),
    COMPOSITE4("composite4", null),
    COMPOSITE5("composite5", null),
    COMPOSITE6("composite6", null),
    COMPOSITE7("composite7", null),
    COMPOSITE8("composite8", null),
    COMPOSITE9("composite9", null),
    COMPOSITE10("composite10", null),
    COMPOSITE11("composite11", null),
    COMPOSITE12("composite12", null),
    COMPOSITE13("composite13", null),
    COMPOSITE14("composite14", null),
    COMPOSITE15("composite15", null),

    // ─── Final program ─────────────────────────────────────────────────
    FINAL("final", null),

    // ─── Prepare programs (0–15, OptiFine extension) ───────────────────
    PREPARE("prepare", null),
    PREPARE1("prepare1", null),
    PREPARE2("prepare2", null),
    PREPARE3("prepare3", null),
    PREPARE4("prepare4", null),
    PREPARE5("prepare5", null),
    PREPARE6("prepare6", null),
    PREPARE7("prepare7", null);

    private final String sourceName;
    private final ProgramId fallback;

    ProgramId(String sourceName, ProgramId fallback) {
        this.sourceName = sourceName;
        this.fallback = fallback;
    }

    /** Shader source base name (e.g. "gbuffers_terrain") — used to find .vsh/.fsh files */
    public String getSourceName() { return sourceName; }

    /** Next program in the fallback chain, or null if this is the root */
    public ProgramId getFallback() { return fallback; }

    /**
     * Returns the full fallback chain starting from this program.
     * Example: GBUFFERS_WATER → [GBUFFERS_WATER, GBUFFERS_TEXTURED_LIT, GBUFFERS_TEXTURED, GBUFFERS_BASIC]
     */
    public List<ProgramId> getFallbackChain() {
        List<ProgramId> chain = new ArrayList<>();
        ProgramId current = this;
        while (current != null) {
            chain.add(current);
            current = current.fallback;
        }
        return chain;
    }

    /** Whether this is a gbuffers (geometry) program */
    public boolean isGbuffers() { return sourceName.startsWith("gbuffers_"); }

    /** Whether this is a shadow program */
    public boolean isShadow() { return sourceName.startsWith("shadow"); }

    /** Whether this is a composite program */
    public boolean isComposite() { return sourceName.startsWith("composite"); }

    /** Whether this is a deferred program */
    public boolean isDeferred() { return sourceName.startsWith("deferred"); }

    /** Whether this is a fullscreen pass (composite/deferred/final/prepare) */
    public boolean isFullscreenPass() { return isComposite() || isDeferred() || this == FINAL || sourceName.startsWith("prepare"); }

    // ── Lookup by name ──

    private static final Map<String, ProgramId> BY_NAME = new HashMap<>();
    static {
        for (ProgramId id : values()) {
            BY_NAME.put(id.sourceName, id);
        }
    }

    /**
     * Looks up a ProgramId by source name.
     * @param name Source name like "gbuffers_terrain"
     * @return ProgramId or null if not found
     */
    public static ProgramId byName(String name) {
        return BY_NAME.get(name);
    }
}
